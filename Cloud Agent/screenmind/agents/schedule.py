"""Schedule changes are patches with source times, never an unguarded state replacement."""
from copy import deepcopy
from datetime import datetime, timezone
import json
from typing import Literal

from pydantic import AwareDatetime, Field, model_validator
from ..models import StrictModel, Identifier
from .base import Proposal, ToolError, tool_call


class ScheduleChange(StrictModel):
    id: Identifier
    title: str | None = Field(default=None, min_length=1, max_length=100)
    at: AwareDatetime | None = None
    status: Literal["pending", "completed"] | None = None
    before: list[Identifier] | None = Field(default=None, max_length=20)
    source_refs: list[str] = Field(min_length=1, max_length=20)


class ScheduleDraft(StrictModel):
    changes: list[ScheduleChange] = Field(min_length=1, max_length=20)

    @model_validator(mode="after")
    def unique_ids(self):
        if len({c.id for c in self.changes}) != len(self.changes):
            raise ValueError("Duplicate schedule item")
        return self


def source_times(context):
    result = {}
    # Only current evidence and authenticated followups may change the current plan.
    for screen in context.task["memory_context"]["screens"]:
        for block in screen["blocks"]:
            # Capture time is NOT notification time; unknown message times stay unknown.
            result[block["source_ref"]] = block.get("message_at")
    for i in context.task["memory_context"]["authenticated_user_inputs"]:
        result[i["source_ref"]] = context.task["updated_at"]
    return result


class ScheduleAgent:
    id = "schedule"
    name = "日程安排"
    description = "维护明确时间、截止条件、依赖与用户完成反馈；变更需确认"
    keywords = ("schedule", "日程", "答辩", "截止")
    requires_work = True
    auxiliary_tools = ("time_check",)
    output_tool = "present_schedule"
    output_schema = ScheduleDraft.model_json_schema()
    system_prompt = """你是日程 SubAgent。read_screens 后通过 present_schedule 提交变化项。
保留 state.items 的稳定 id，只提交明确变化。at 必须是带时区的绝对时间；时间模糊就 ask_clarification。
before 表示该项必须早于哪些项，仅在原文明确依赖时设置；不能猜测提交必须在答辩前。
未知字段不填。完成状态只能依据 authenticated_user_inputs 的明确完成反馈，不因时间已过自动完成。
source_refs 只能用当前屏幕块或本任务用户补充。历史材料仅用来理解上下文，不据此覆盖当前安排。
必要时可 delegate_time_check 检查有明确授权来源的时间区间。不得写入正式状态或执行日历动作。"""

    def propose(self, arguments, context):
        draft = ScheduleDraft.model_validate(arguments)
        state = deepcopy((context.memory or {}).get("state") or {"items": {}})
        items = state.setdefault("items", {})
        times = source_times(context)
        all_refs, ignored = set(), []
        def question(text, refs):
            return Proposal(question={"question": text, "source_refs": sorted(refs)[:20]})
        for change in draft.changes:
            refs = set(change.source_refs)
            if not refs.issubset(times):
                raise ToolError("日程变更必须引用本轮屏幕或本任务用户补充，不能仅用旧记忆")
            all_refs.update(refs)
            old = items.get(change.id)
            if "at" in change.model_fields_set and change.at is None:
                return question("时间仍不明确，请提供带日期和时区的具体时间。", refs)
            stamp = max((times[r] for r in refs if times[r]), key=datetime.fromisoformat, default=None)
            stamp_dt = datetime.fromisoformat(stamp) if stamp else None
            user_override = any(r.startswith("input:") for r in refs)
            if old and stamp_dt and old.get("source_time") and not user_override:
                if stamp_dt < datetime.fromisoformat(old["source_time"]):
                    ignored.append(change.id)
                    continue
            updated = deepcopy(old or {"id": change.id, "title": change.title or "待确认", "at": None,
                                       "status": "pending", "before": [], "source_time": None})
            for field in ("title", "at", "status", "before"):
                value = getattr(change, field)
                if value is not None:
                    updated[field] = value.isoformat() if field == "at" else value
            if not updated["at"] or updated["title"] == "待确认":
                return question("请补充该日程的标题和带日期、时区的具体时间。", refs)
            changed = old and (any(updated[k] != old[k] for k in ("title", "status", "before"))
                               or datetime.fromisoformat(updated["at"]) != datetime.fromisoformat(old["at"]))
            if changed and not stamp:
                return question("这条通知没有可靠的消息时间，是否确认以它修改当前日程？请补充明确的新安排。", refs)
            if changed and not user_override and old.get("source_time") and stamp_dt == datetime.fromisoformat(old["source_time"]):
                return question("同一时间的通知内容存在冲突，请确认应采用的安排。", refs)
            if change.status == "completed" and not any(r.startswith("input:") for r in refs):
                return question("请由用户明确反馈这项工作是否已经完成。", refs)
            if not old or changed:
                updated["source_time"] = stamp
                updated["source_refs"] = sorted(refs)
                items[change.id] = updated
        if len(items) > 20:
            raise ToolError("第一版日程最多20项")
        for item in items.values():
            for following in item["before"]:
                if following not in items:
                    return question("日程依赖指向缺少的事项，请补充相应安排。", all_refs)
                if datetime.fromisoformat(item["at"]) >= datetime.fromisoformat(items[following]["at"]):
                    return question(f"{item['title']}须早于{items[following]['title']}，但当前时间冲突，请确认新的时间。", all_refs)
        previous = (context.memory or {}).get("state") or {"items": {}}
        fields = []
        for key, item in items.items():
            refs = set(item.get("source_refs", [])) & context.source_refs
            if key in previous.get("items", {}):
                # Unchanged fields are supported by the confirmed snapshot, not a new notice.
                if item == previous["items"][key]:
                    refs = set()
                refs.add(context.memory["source_ref"])
            dependency = " · 须早于：" + "、".join(items[i]["title"] for i in item["before"]) if item["before"] else " · 无先后约束"
            fields.append({"id": key, "label": item["title"], "value": item["at"] + " · " + item["status"] + dependency,
                           "source_refs": sorted(refs)[:20], "editable": False})
        if not fields:
            return question("请提供至少一项明确日程。", all_refs)
        card = {"title": "日程安排", "summary": ("忽略较早的通知；" if ignored else "") +
                ("当前安排没有变化" if state == previous else "核对时间与依赖后确认更新"), "fields": fields}
        return Proposal(card, state if state != previous else None)

    @staticmethod
    def calendar_item(state, item_id):
        return state.get("items", {}).get(item_id)

    def mock_reply(self, messages):
        # Reproducible mock protocol, not a natural-language date parser.
        evidence = next((json.loads(m["content"]) for m in messages if m.get("name") == "read_screens"), None)
        if evidence is None:
            return tool_call("read_screens", {})
        changes = {}
        for screen in evidence["screens"]:
            for block in screen["blocks"]:
                try:
                    payload = json.loads(block["text"])
                except ValueError:
                    continue
                for c in payload.get("schedule_changes", []) if isinstance(payload, dict) else []:
                    changes[c["id"]] = {**changes.get(c["id"], {}), **c, "source_refs": [block["source_ref"]]}
        for i in evidence["authenticated_user_inputs"]:
            try:
                payload = json.loads(i["message"])
            except ValueError:
                continue
            for c in payload.get("schedule_changes", []) if isinstance(payload, dict) else []:
                changes[c["id"]] = {**changes.get(c["id"], {}), **c, "source_refs": [i["source_ref"]]}
        if not changes:
            ref = evidence["screens"][0]["blocks"][0]["source_ref"]
            return tool_call("ask_clarification", {"question": "模拟模式请提供 schedule_changes JSON；真实模型可处理自然语言。", "source_refs": [ref]})
        return tool_call(self.output_tool, {"changes": list(changes.values())})
