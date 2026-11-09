"""Task-scoped tools and a bounded, cancellable model/tool loop."""
import asyncio
import json
import logging
import time
from pydantic import ValidationError
from .models import Card
from .providers import ProviderError
from .store import encode

logger = logging.getLogger(__name__)

from .agents.base import AgentContext, READ_TOOL, ToolError


def input_sources(task):
    return [{"source_ref": f"input:{task['id']}:{i['expected_revision'] + 1}",
             "message": i.get("message", ""), "edits": i.get("edits", {})}
            for i in task["inputs"] if i.get("kind") == "revise"]


class Worker:
    def __init__(self, store, provider, settings):
        self.store, self.provider, self.settings = store, provider, settings

    async def run(self, task):
        try:
            await self.execute(task)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            logger.error("SubAgent initialization failed (%s), task=%s", type(exc).__name__, task["id"])
            self.store.fail(task["id"], task["revision"], "SubAgent 初始化失败，请检查能力注册与事项配置")

    async def execute(self, task):
        tid, revision = task["id"], task["revision"]
        agent = self.store.registry.get(task["subagent_id"])
        memory = task["memory_snapshot"]
        allowed = {s + ":" + b["id"] for s in task["screen_ids"] for b in self.store.get_screen(s)["blocks"]}
        if memory:
            allowed.add(memory["source_ref"])
        allowed.update(i["source_ref"] for i in input_sources(task))
        agent_context = AgentContext(task, memory, frozenset(allowed))
        tools = [READ_TOOL, {"type": "function", "function": {
            "name": agent.output_tool, "description": agent.description, "parameters": agent.output_schema}}]
        previous = task["previous_card"]
        context = {
            "subagent_id": agent.id,
            "work_id": task["work_id"],
            "trusted_instruction": task["instruction"],
            "authenticated_user_followups": [i for i in task["inputs"] if i.get("kind") == "revise"],
            "followups": [i.get("message", "") for i in task["inputs"] if i.get("kind") == "revise"],
            "previous_card": previous,
            "screen_count": len(task["screen_ids"]),
        }
        messages = [{"role": "system", "content": agent.system_prompt}, {"role": "user", "content": encode(context)}]
        evidence_read = False
        started = time.monotonic()
        try:
            async with asyncio.timeout(self.settings.task_timeout):
                for step in range(self.settings.max_steps):
                    if not self.store.current(tid, revision):
                        return
                    self.store.event(tid, "model.requested", {"revision": revision, "step": step + 1, "mode": self.provider.mode})
                    message = await self.provider.complete(messages, tools)
                    if not self.store.current(tid, revision):
                        return
                    if not isinstance(message, dict):
                        raise ProviderError("模型返回了无效消息")
                    calls = message.get("tool_calls") or []
                    if not isinstance(calls, list) or len(calls) > 8:
                        raise ProviderError("模型返回了过多或格式错误的工具调用")
                    if not calls:
                        messages.append({"role": "assistant", "content": str(message.get("content") or "")[:4000]})
                        messages.append({"role": "user", "content": "请先读取上下文，再使用提供的输出工具提交方案。"})
                        continue
                    # Validate the protocol envelope before appending it to history.
                    for call in calls:
                        if not isinstance(call, dict) or not isinstance(call.get("id"), str) or not isinstance(call.get("function"), dict):
                            raise ProviderError("工具调用协议不完整")
                    messages.append(message)
                    for call in calls:
                        name = call["function"].get("name")
                        self.store.event(tid, "tool.started", {"revision": revision, "tool": str(name)[:80]})
                        try:
                            raw = call["function"].get("arguments", "{}")
                            if not isinstance(raw, str) or len(raw) > 100000:
                                raise ToolError("工具参数格式或长度无效")
                            arguments = json.loads(raw)
                            if not isinstance(arguments, dict):
                                raise ToolError("工具参数必须是对象")
                            if name == "read_screens":
                                if arguments:
                                    raise ToolError("read_screens 不接受参数")
                                screens = [self.store.get_screen(s) for s in task["screen_ids"]]
                                for screen in screens:
                                    for block in screen["blocks"]:
                                        block["source_ref"] = screen["id"] + ":" + block["id"]
                                result = {"untrusted_evidence": True, "screens": screens, "memory": memory,
                                          "authenticated_user_inputs": input_sources(task)}
                                evidence_read = True
                            elif name == agent.output_tool:
                                if not evidence_read:
                                    raise ToolError("先调用 read_screens，不能在读取证据前提交卡片")
                                proposal = agent.propose(arguments, agent_context)
                                card = self.validate_card(proposal.card, task)
                                proposed_memory = proposal.memory
                                self.store.event(tid, "tool.finished", {"revision": revision, "tool": name})
                                if self.store.publish(tid, revision, card, self.provider.mode, proposed_memory):
                                    self.store.event(tid, "worker.finished", {"revision": revision, "elapsed_ms": round((time.monotonic() - started) * 1000)})
                                return
                            else:
                                raise ToolError("未知工具；只允许当前 SubAgent 注册的工具")
                            self.store.event(tid, "tool.finished", {"revision": revision, "tool": name})
                        except (ToolError, ValidationError, json.JSONDecodeError) as exc:
                            # Validation errors deliberately omit the input payload and any secrets inside it.
                            reason = str(exc) if isinstance(exc, ToolError) else "工具参数不符合 Schema，请检查字段、长度、必填项与引用"
                            result = {"error": reason}
                            self.store.event(tid, "tool.rejected", {"revision": revision, "tool": str(name)[:80], "reason": reason})
                        messages.append({"role": "tool", "tool_call_id": call["id"], "name": str(name), "content": encode(result)})
                raise ProviderError("已达到模型执行步数上限，尚未生成有效卡片")
        except asyncio.CancelledError:
            # Revision fencing prevents a cancelled/obsolete run from publishing.
            raise
        except TimeoutError:
            self.store.fail(tid, revision, "任务执行超时，可稍后重试")
        except ProviderError as exc:
            self.store.fail(tid, revision, str(exc))
        except Exception as exc:
            logger.error("Worker failed (%s), task=%s", type(exc).__name__, tid)
            self.store.fail(tid, revision, "执行器内部错误，请查看服务端诊断或重试")

    def validate_card(self, arguments, task):
        card = Card.model_validate(arguments).model_dump()
        allowed = {s + ":" + b["id"] for s in task["screen_ids"] for b in self.store.get_screen(s)["blocks"]}
        if task.get("memory_snapshot"):
            allowed.add(task["memory_snapshot"]["source_ref"])
        allowed.update(i["source_ref"] for i in input_sources(task))
        for field in card["fields"]:
            if not set(field["source_refs"]).issubset(allowed):
                raise ToolError("卡片引用了当前任务以外或不存在的证据")
            # Only authenticated UI actions may mark values as user-authored.
            field["origin"] = "source"
        pinned = {f["id"]: f for f in (task["previous_card"] or {}).get("fields", []) if f["origin"] == "user"}
        by_id = {f["id"]: f for f in card["fields"]}
        by_id.update(pinned)
        card["fields"] = list(by_id.values())
        # Prevent a model from silently discarding an edited field to satisfy the limit.
        return Card.model_validate(card).model_dump()


class Runtime:
    """One process, bounded worker slots, durable queue; model sessions are per task."""
    def __init__(self, store, provider, settings):
        self.store, self.provider, self.settings = store, provider, settings
        self.worker = Worker(store, provider, settings)
        self.running = {}
        self.scheduler = None

    async def start(self):
        self.store.recover()
        self.scheduler = asyncio.create_task(self.schedule(), name="screenmind-scheduler")

    async def schedule(self):
        while True:
            for tid, handle in list(self.running.items()):
                if handle.done():
                    if not handle.cancelled():
                        handle.exception()  # Retrieve any exception to avoid orphan warnings.
                    del self.running[tid]
            for tid in self.store.queued():
                if len(self.running) >= self.settings.concurrency:
                    break
                if tid not in self.running:
                    task = self.store.claim(tid)
                    if task:
                        self.running[tid] = asyncio.create_task(self.worker.run(task), name=tid)
            await asyncio.sleep(0.05)

    def interrupt(self, tid):
        if handle := self.running.get(tid):
            handle.cancel()

    async def close(self):
        if self.scheduler:
            self.scheduler.cancel()
            await asyncio.gather(self.scheduler, return_exceptions=True)
        for handle in self.running.values():
            handle.cancel()
        await asyncio.gather(*self.running.values(), return_exceptions=True)
        await self.provider.close()

