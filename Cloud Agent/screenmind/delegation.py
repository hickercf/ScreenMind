"""Single-layer auxiliary tasks: limited evidence, bounded execution, read-only results."""
import asyncio
import hashlib
import json
import uuid

from pydantic import AwareDatetime, Field, model_validator
from .models import StrictModel, Identifier
from .agents.base import ToolError
from .memory import ContextBuilder
from .store import encode, now


class Interval(StrictModel):
    id: Identifier
    start: AwareDatetime
    end: AwareDatetime
    source_refs: list[str] = Field(min_length=1, max_length=20)

    @model_validator(mode="after")
    def ordered(self):
        if self.start >= self.end:
            raise ValueError("Interval end must be after start")
        return self


class TimeCheckInput(StrictModel):
    candidate: Interval
    busy: list[Interval] = Field(max_length=30)


TIME_CHECK_TOOL = {"type": "function", "function": {"name": "delegate_time_check",
    "description": "委派只读时间冲突检查，仅检查传入且带授权来源的占用区间；不代表读取了全部日历。",
    "parameters": TimeCheckInput.model_json_schema()}}


class TimeCheckAgent:
    async def run(self, payload):
        candidate = payload.candidate
        conflicts = [{"id": b.id, "start": max(candidate.start, b.start).isoformat(),
                      "end": min(candidate.end, b.end).isoformat(), "source_refs": b.source_refs}
                     for b in payload.busy if candidate.start < b.end and b.start < candidate.end]
        return {"conflicts": conflicts, "checked_intervals": len(payload.busy),
                "scope": "provided_intervals_only", "candidate_source_refs": candidate.source_refs}


class DelegationService:
    def __init__(self, timeout=5, helper=None, reviewer=None):
        self.timeout, self.helper = timeout, helper or TimeCheckAgent()
        self.reviewer = reviewer

    async def run(self, store, task, arguments, helper_name='time_check'):
        if helper_name not in getattr(store.registry.get(task["subagent_id"]), "auxiliary_tools", ()):
            raise ToolError("该主能力未注册此辅助能力")
        if helper_name == 'evidence_review' and self.reviewer:
            from .evidence_review import ReviewInput, review_packet
            payload = ReviewInput.model_validate(arguments)
            refs = set(payload.source_refs)
            helper, helper_input, timeout = self.reviewer, review_packet(task['memory_context'], payload), 65
        elif helper_name == 'time_check':
            payload = TimeCheckInput.model_validate(arguments)
            refs = set(payload.candidate.source_refs)
            refs.update(r for interval in payload.busy for r in interval.source_refs)
            helper, helper_input, timeout = self.helper, payload, self.timeout
        else:
            raise ToolError('辅助能力未启用')
        if not refs.issubset(ContextBuilder.source_refs(task["memory_context"])):
            raise ToolError("委派包含未授权来源")
        def current():
            return (store.current(task["id"], task["revision"]) and task["work_id"]
                    and store.work(task["work_id"])["memory_version"] == task["memory_base"] and store.sources_live(task))
        if not current():
            raise ToolError("父任务或正式状态已变化，不能继续委派")
        body = payload.model_dump(mode="json")
        digest = hashlib.sha256(encode({'helper': helper_name, 'input': body}).encode()).hexdigest()
        row = store.db.execute("SELECT * FROM delegations WHERE parent_task_id=? AND revision=? AND digest=?",
                               (task["id"], task["revision"], digest)).fetchone()
        if row and row["status"] == "succeeded":
            return {"delegation_id": row["id"], **json.loads(row["result"])}
        if helper_name == 'evidence_review' and not row and store.db.execute(
                "SELECT count(*) FROM delegations WHERE parent_task_id=? AND revision=? AND helper=?",
                (task['id'], task['revision'], helper_name)).fetchone()[0] >= 2:
            raise ToolError('本轮辅助审阅最多两个不同委派')
        did = row["id"] if row else "delegation_" + uuid.uuid4().hex[:16]
        store.db.execute("""INSERT OR REPLACE INTO delegations
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""", (did, store.owner, task["id"], task["work_id"], task["revision"],
            task["memory_base"], digest, "running", encode(body), None, helper_name, now()))
        store.event(task["id"], "delegation.started", {"delegation_id": did, "helper": helper_name})
        try:
            async with asyncio.timeout(timeout):
                result = await helper.run(helper_input)
            if not current():
                store.db.execute("UPDATE delegations SET status='discarded' WHERE id=?", (did,))
                raise ToolError("委派结果已过期，必须重新运行父任务")
            store.db.execute("UPDATE delegations SET status='succeeded',result=? WHERE id=?", (encode(result), did))
            store.event(task["id"], "delegation.finished", {"delegation_id": did})
            return {"delegation_id": did, **result}
        except asyncio.CancelledError:
            store.db.execute("UPDATE delegations SET status='cancelled' WHERE id=?", (did,))
            raise
        except TimeoutError:
            store.db.execute("UPDATE delegations SET status='failed' WHERE id=?", (did,))
            store.event(task['id'], 'delegation.failed', {'delegation_id': did, 'reason': 'TimeoutError'})
            raise ToolError("辅助任务超时") from None
        except ToolError:
            raise
        except Exception as exc:
            store.db.execute("UPDATE delegations SET status='failed' WHERE id=?", (did,))
            store.event(task['id'], 'delegation.failed', {'delegation_id': did, 'reason': type(exc).__name__})
            raise ToolError("辅助任务失败") from None
