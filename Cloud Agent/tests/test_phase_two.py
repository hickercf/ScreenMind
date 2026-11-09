import asyncio
from dataclasses import replace
from datetime import datetime, timezone, timedelta
import json
from pathlib import Path
import time
import uuid

import httpx
import pytest
from fastapi.testclient import TestClient

from screenmind.actions import ActionService, CalDAVCalendar, CalendarRequest, ExportCalendar, calendar_bytes
from screenmind.api import create_app
from screenmind.config import Settings
from screenmind.delegation import DelegationService
from screenmind.memory_service import MemoryService
from screenmind.models import ActionInput, ScreenInput, TaskInput
from screenmind.retrieval import HashDemoEmbedding, LanceIndex
from screenmind.simplemem_adapter import RawExtractor, SimpleMemExtractor
from screenmind.store import Store, DomainError, encode
from screenmind.agents.base import ToolError
from test_engine import settings, client, screen, wait_task
from test_phase_one import uid, make_work, make_task, response, respond


def schedule_work(client):
    return client.post("/api/works", json={"event_id": uid(), "title": "答辩安排", "subagent_id": "schedule"}).json()["work"]


def schedule_task(client, wid, changes, timestamp="2026-11-08T10:00:00+08:00"):
    body = screen(uid(), json.dumps({"schedule_changes": changes}, ensure_ascii=False))
    body["blocks"][0]["message_at"] = timestamp
    sid = client.post("/api/screens", json=body).json()["screen"]["id"]
    result = client.post("/api/tasks", json={"event_id": uid(), "instruction": "更新日程", "screen_ids": [sid],
        "work_id": wid, "interaction_protocol": "cloud-v1"})
    assert result.status_code == 202, result.text
    return result.json()["task"], sid


def confirm_schedule(client, task):
    out = task["interaction"]
    return respond(client, task, response(out, "accept_state", proposal_id=out["proposal_id"]))


def initial_schedule(client):
    w = schedule_work(client)
    task, sid = schedule_task(client, w["id"], [
        {"id": "material", "title": "提交材料", "at": "2026-12-10T17:00:00+08:00", "before": ["defense"]},
        {"id": "defense", "title": "答辩", "at": "2026-12-10T18:00:00+08:00"}])
    task = wait_task(client, task["id"])
    assert confirm_schedule(client, task).status_code == 202
    return w, sid


def test_schedule_change_clarification_confirmation_and_feedback(client):
    w, _ = initial_schedule(client)
    task, _ = schedule_task(client, w["id"], [{"id": "defense", "at": "2026-12-10T16:00:00+08:00"}], "2026-11-09T10:00:00+08:00")
    task = wait_task(client, task["id"])
    out = task["interaction"]
    assert out["kind"] == "clarification" and "冲突" in out["question"]
    assert client.get(f"/api/works/{w['id']}").json()["work"]["state"]["items"]["material"]["at"].endswith("17:00:00+08:00")
    body = response(out, "answer", question_id=out["question_id"], answer=json.dumps({"schedule_changes": [
        {"id": "material", "at": "2026-12-10T15:00:00+08:00"}]}))
    assert respond(client, task, body).status_code == 202
    proposal = wait_task(client, task["id"])
    assert proposal["interaction"]["kind"] == "state_proposal"
    assert proposal["revision"] == 2
    assert confirm_schedule(client, proposal).status_code == 202
    final = client.get(f"/api/works/{w['id']}").json()["work"]
    assert final["memory_version"] == 2
    assert final["state"]["items"]["defense"]["at"].endswith("16:00:00+08:00")
    assert final["state"]["items"]["material"]["status"] == "pending"
    # Screen alone cannot mark a task complete; authenticated user feedback is required.
    done, _ = schedule_task(client, w["id"], [{"id": "material", "status": "completed"}], (datetime.now(timezone.utc) + timedelta(seconds=1)).isoformat())
    done = wait_task(client, done["id"])
    out = done["interaction"]
    assert out["kind"] == "clarification"
    respond(client, done, response(out, "answer", question_id=out["question_id"], answer=json.dumps({"schedule_changes": [
        {"id": "material", "status": "completed"}]})))
    done = wait_task(client, done["id"])
    assert confirm_schedule(client, done).status_code == 202
    assert client.get(f"/api/works/{w['id']}").json()["work"]["state"]["items"]["material"]["status"] == "completed"


def test_schedule_stale_duplicate_and_unknown_time(client):
    w, _ = initial_schedule(client)
    old, _ = schedule_task(client, w["id"], [{"id": "defense", "at": "2026-12-10T16:00:00+08:00"}], "2026-11-07T10:00:00+08:00")
    old = wait_task(client, old["id"], "completed")
    assert old["interaction"]["kind"] == "information"
    duplicate, _ = schedule_task(client, w["id"], [{"id": "defense", "at": "2026-12-10T18:00:00+08:00"}])
    assert wait_task(client, duplicate["id"], "completed")["memory_proposal"] is None
    vague, _ = schedule_task(client, w["id"], [{"id": "defense", "at": None}], "2026-11-09T10:00:00+08:00")
    assert wait_task(client, vague["id"])["interaction"]["kind"] == "clarification"
    unknown, _ = schedule_task(client, w["id"], [{"id": "defense", "at": "2026-12-10T19:00:00+08:00"}], None)
    assert wait_task(client, unknown["id"])["interaction"]["kind"] == "clarification"
    assert client.get(f"/api/works/{w['id']}").json()["work"]["memory_version"] == 1


def test_calendar_export_authorization_receipt_and_idempotency(settings, tmp_path):
    with TestClient(create_app(replace(settings, action_path=tmp_path / "exports"))) as client:
        w, _ = initial_schedule(client)
        body = {"event_id": uid(), "expected_state_version": 1, "item_id": "defense", "end": "2026-12-10T18:30:00+08:00", "tool": "calendar.export"}
        endpoint = f"/api/works/{w['id']}/action-proposals"
        r = client.post(endpoint, json=body)
        assert r.status_code == 201, r.text
        task, out = r.json()["task"], r.json()["task"]["interaction"]
        aid = out["action_id"]
        assert client.post(endpoint, json=body).json()["replayed"] is True
        assert client.get(f"/api/actions/{aid}").json()["action"]["status"] == "proposed"
        assert client.get(f"/api/actions/{aid}/artifact").status_code == 404
        authorization = response(out, "authorize_action", action_id=aid)
        assert respond(client, task, {**authorization, "parameters": {"title": "tamper"}}).status_code == 422
        assert respond(client, task, authorization).status_code == 202
        assert respond(client, task, authorization).json()["replayed"] is True
        wait_task(client, task["id"], "completed")
        action = client.get(f"/api/actions/{aid}").json()["action"]
        assert action["status"] == "succeeded" and action["receipt"]["calendar_imported"] is False
        data = client.get(f"/api/actions/{aid}/artifact")
        assert data.status_code == 200 and "BEGIN:VEVENT" in data.text and "SUMMARY:答辩" in data.text
        assert len(list((tmp_path / "exports").glob("*.ics"))) == 1


def action_fixture(store, settings, tools=None):
    service = ActionService(store, settings, tools)
    w = store.create_work({"event_id": uid(), "title": "时间", "subagent_id": "schedule"})[0]
    state = {"items": {"x": {"id": "x", "title": "演示", "at": "2026-12-10T16:00:00+08:00", "status": "pending", "before": []}}}
    store.db.execute("UPDATE works SET state=?,memory_version=1 WHERE id=?", (encode(state), w["id"]))
    body = CalendarRequest(event_id=uid(), expected_state_version=1, item_id="x", end="2026-12-10T16:30:00+08:00").model_dump(mode="json")
    task = service.propose(w["id"], body)[0]
    out = task["interaction"]
    store.respond(task["id"], response(out, "authorize_action", action_id=out["action_id"]))
    return service, w, task, out["action_id"], body


def test_action_state_change_before_dispatch_and_cancel(tmp_path):
    store = Store(tmp_path / "actions.db", "owner")
    try:
        settings = Settings(action_path=tmp_path / "exports")
        service, w, task, aid, body = action_fixture(store, settings)
        store.db.execute("UPDATE works SET memory_version=2 WHERE id=?", (w["id"],))
        asyncio.run(service.tick())
        assert service.get(aid)["status"] == "stale"
        assert not list((tmp_path / "exports").glob("*.ics"))
        body.update(event_id=uid(), expected_state_version=2)
        t = service.propose(w["id"], body)[0]
        store.action(t["id"], ActionInput(event_id=uid(), expected_revision=1, kind="cancel").model_dump())
        assert asyncio.run(service.tick()) is False
    finally:
        store.close()


def test_unknown_action_reconciles_without_reexecuting(tmp_path):
    class LostReceipt(ExportCalendar):
        calls = 0
        async def execute(self, action):
            self.calls += 1
            await super().execute(action)
            raise TimeoutError("lost acknowledgement")
    tool = LostReceipt(tmp_path / "exports")
    store = Store(tmp_path / "unknown.db", "owner")
    try:
        service, w, task, aid, body = action_fixture(store, Settings(), [tool])
        asyncio.run(service.tick())
        assert service.get(aid)["status"] == "unknown"
        with pytest.raises(DomainError):
            service.propose(w["id"], {**body, "event_id": uid()})
        with pytest.raises(DomainError):
            store.action(task["id"], ActionInput(event_id=uid(), expected_revision=2, kind="retry").model_dump())
        assert asyncio.run(service.reconcile(aid))["status"] == "succeeded"
        assert tool.calls == 1
    finally:
        store.close()


def test_caldav_conditional_put_and_reconcile():
    saved, calls = {}, []
    def handler(request):
        calls.append(request.method)
        if request.method == "PUT":
            assert request.headers["if-none-match"] == "*"
            if saved:
                return httpx.Response(412)
            saved["body"] = request.content
            return httpx.Response(201, headers={"etag": '"one"'})
        return httpx.Response(200, content=saved["body"])
    cfg = Settings(caldav_url="https://calendar.example/user/events", caldav_username="demo", caldav_password="test-only")
    tool = CalDAVCalendar(cfg, httpx.MockTransport(handler))
    action = {"id": "action_test", "created_at": "2026-11-09T10:00:00+00:00", "parameters": {
        "title": "测试\n不能注入事件", "start": "2026-12-10T16:00:00+08:00", "end": "2026-12-10T16:30:00+08:00", "calendar_target": tool.url}}
    assert asyncio.run(tool.execute(action))["status"] == "succeeded"
    assert asyncio.run(tool.execute(action))["receipt"]["reconciled"] is True
    assert calls == ["PUT", "PUT", "GET"]
    changed = {**action, "parameters": {**action["parameters"], "calendar_target": "https://different.example/"}}
    assert asyncio.run(tool.execute(changed))["status"] == "failed"


def delegation_fixture(store):
    w = store.create_work({"event_id": uid(), "title": "聚会", "subagent_id": "party"})[0]
    task, source = make_task(store, w["id"])
    task = store.claim(task["id"])
    interval = {"id": "candidate", "start": "2026-12-10T16:00:00+08:00", "end": "2026-12-10T17:00:00+08:00",
                "source_refs": [source["id"] + ":deadline"]}
    return task, {"candidate": interval, "busy": [{**interval, "id": "busy"}]}


def test_delegation_is_read_only_scoped_and_idempotent(tmp_path):
    store = Store(tmp_path / "delegation.db", "owner")
    try:
        task, args = delegation_fixture(store)
        service = DelegationService()
        first = asyncio.run(service.run(store, task, args))
        assert first["conflicts"] and first["scope"] == "provided_intervals_only"
        assert asyncio.run(service.run(store, task, args))["delegation_id"] == first["delegation_id"]
        assert store.work(task["work_id"])["memory_version"] == 0
        args["candidate"]["source_refs"] = ["scr_other:secret"]
        with pytest.raises(ToolError):
            asyncio.run(service.run(store, task, args))
    finally:
        store.close()


def test_delegation_cancellation_timeout_and_stale_state(tmp_path):
    store = Store(tmp_path / "delegation-failure.db", "owner")
    try:
        task, args = delegation_fixture(store)
        class Stale:
            async def run(self, payload):
                store.db.execute("UPDATE works SET memory_version=1 WHERE id=?", (task["work_id"],))
                return {"conflicts": []}
        with pytest.raises(ToolError):
            asyncio.run(DelegationService(helper=Stale()).run(store, task, args))
        assert store.db.execute("SELECT status FROM delegations").fetchone()[0] == "discarded"
        task, args = delegation_fixture(store)
        class Slow:
            async def run(self, payload):
                await asyncio.sleep(5)
        with pytest.raises(ToolError):
            asyncio.run(DelegationService(timeout=.01, helper=Slow()).run(store, task, args))
        async def cancelled():
            handle = asyncio.create_task(DelegationService(helper=Slow()).run(store, task, args))
            await asyncio.sleep(.01)
            handle.cancel()
            with pytest.raises(asyncio.CancelledError):
                await handle
        asyncio.run(cancelled())
        assert store.db.execute("SELECT status FROM delegations WHERE parent_task_id=?", (task["id"],)).fetchone()[0] == "cancelled"
    finally:
        store.close()


def memory_fixture(tmp_path, extractor=None):
    pytest.importorskip("lancedb")
    store = Store(tmp_path / "memory.db", "owner")
    encoder = HashDemoEmbedding()
    service = MemoryService(store, encoder, LanceIndex(tmp_path / "lance", encoder.name), extractor or RawExtractor())
    return store, service


def test_real_lancedb_long_history_retrieval_and_delete_rebuild(tmp_path):
    store, service = memory_fixture(tmp_path)
    try:
        w, other = make_work(store), make_work(store)
        _, old = make_task(store, w["id"], "甲场地只有六个座位，因此没有采用甲场地。")
        for i in range(14):
            make_task(store, w["id"], f"准备事项第{i}条：带纸巾")
        _, secret = make_task(store, other["id"], "甲场地秘密跨事项资料")
        async def index_all():
            while await service.index_one():
                pass
        asyncio.run(index_all())
        current, source = make_task(store, w["id"], "为什么没有采用甲场地？")
        claimed = store.claim(current["id"])
        assert all(i.get("screen", {}).get("id") != old["id"] for i in claimed["memory_context"]["history"])
        enriched = asyncio.run(service.enrich(claimed))
        history = encode(enriched["memory_context"]["history"])
        assert old["id"] in history and "六个座位" in history and secret["id"] not in history
        assert enriched["memory_context"]["mode"] == "hybrid"
        affected = store.delete_screen(old["id"])
        assert current["id"] in affected
        assert store.task(current["id"])["status"] == "failed"
        store.rebuild_memory(w["id"])
        asyncio.run(index_all())
        assert store.db.execute("SELECT count(*) FROM memory_entries WHERE source_ref=?", (old["id"] + ":deadline",)).fetchone()[0] == 0
        assert all(r != old["id"] + ":deadline" for r in service.index.search(store.owner, w["id"], service.encoder.encode(["甲场地"])[0]))
    finally:
        store.close()


def test_index_failure_unindexed_raw_supplement_and_vector_failure(tmp_path):
    class BrokenExtractor:
        name = "broken-test"
        def extract(self, *args):
            raise RuntimeError("unavailable")
    store, service = memory_fixture(tmp_path, BrokenExtractor())
    try:
        w = make_work(store)
        _, old = make_task(store, w["id"], "乙场地至少八人套餐")
        asyncio.run(service.index_one())
        assert store.memory_status(w["id"])["pending"] == 1
        current, _ = make_task(store, w["id"], "乙场地为什么不能选择")
        task = store.claim(current["id"])
        def broken(*args):
            raise RuntimeError("vector service down")
        service.index.search = broken
        result = asyncio.run(service.enrich(task))
        assert result["memory_context"]["mode"] == "hybrid-degraded"
        assert old["id"] in encode(result["memory_context"]["history"])
    finally:
        store.close()


def test_simplemem_real_upstream_builder_isolates_buffers():
    pytest.importorskip("simplemem")
    class FakeLLM:
        prompts = []
        def chat_completion(self, messages, **kwargs):
            self.prompts.append(messages[-1]["content"])
            return json.dumps([{"lossless_restatement": "场地甲因容量不足未选", "keywords": ["场地甲"], "persons": [], "entities": []}])
        @staticmethod
        def extract_json(value):
            return json.loads(value)
    llm = FakeLLM()
    extractor = SimpleMemExtractor(Settings(), llm)
    assert "场地甲" in extractor.extract("场地甲只有六个座位")["text"]
    extractor.extract("另一个事项代号UNIQUE_SECOND")
    assert "场地甲只有六个座位" not in llm.prompts[1]
    assert len(llm.prompts) == 2


def test_restart_keeps_unknown_action_without_resending(tmp_path):
    path = tmp_path / "restart.db"
    cfg = Settings(action_path=tmp_path / "exports")
    store = Store(path, "owner")
    service, w, task, aid, _ = action_fixture(store, cfg)
    # Simulate crash after external file write, before receipt persistence.
    asyncio.run(service.tools["calendar.export"].execute(service.get(aid)))
    store.db.execute("UPDATE external_actions SET status='executing' WHERE id=?", (aid,))
    store.close()
    store = Store(path, "owner")
    try:
        service = ActionService(store, cfg)
        store.recover()
        assert service.get(aid)["status"] == "unknown"
        assert store.task(task["id"])["status"] == "failed"
        assert asyncio.run(service.tick()) is False
        assert asyncio.run(service.reconcile(aid))["status"] == "succeeded"
        assert len(list(cfg.action_path.glob("*.ics"))) == 1
    finally:
        store.close()


def test_delete_during_memory_extraction_cannot_resurrect_source(tmp_path):
    import threading
    started, release = threading.Event(), threading.Event()
    class PausedExtractor(RawExtractor):
        def extract(self, *args):
            started.set()
            assert release.wait(5)
            return super().extract(*args)
    store, service = memory_fixture(tmp_path, PausedExtractor())
    try:
        w = make_work(store)
        _, source = make_task(store, w["id"], "应当删除的材料")
        async def race():
            indexing = asyncio.create_task(service.index_one())
            assert await asyncio.to_thread(started.wait, 5)
            store.delete_screen(source["id"])
            release.set()
            await indexing
            assert store.db.execute("SELECT count(*) FROM memory_entries").fetchone()[0] == 0
            await service.index_one()
            assert store.memory_status(w["id"]) == {"deleted": 1}
        asyncio.run(race())
    finally:
        release.set()
        store.close()


def test_worker_delegates_then_publishes_without_committing(settings):
    from screenmind.providers import MockProvider
    from screenmind.agents.base import tool_call
    class DelegatingModel(MockProvider):
        async def complete(self, messages, tools):
            read = next((m for m in messages if m.get("name") == "read_screens"), None)
            delegated = any(m.get("name") == "delegate_time_check" for m in messages)
            if read and not delegated:
                assert any(t["function"]["name"] == "delegate_time_check" for t in tools)
                ref = json.loads(read["content"])["screens"][0]["blocks"][0]["source_ref"]
                return tool_call("delegate_time_check", {"candidate": {"id": "demo", "start": "2026-12-10T17:00:00+08:00",
                    "end": "2026-12-10T18:00:00+08:00", "source_refs": [ref]}, "busy": []})
            return await super().complete(messages, tools)
    with TestClient(create_app(settings, provider=DelegatingModel(.001))) as client:
        w = schedule_work(client)
        task, _ = schedule_task(client, w["id"], [{"id": "demo", "title": "演示", "at": "2026-12-10T17:00:00+08:00"}])
        task = wait_task(client, task["id"])
        assert task["interaction"]["kind"] == "state_proposal"
        delegations = client.get(f"/api/tasks/{task['id']}/delegations").json()["delegations"]
        assert len(delegations) == 1 and delegations[0]["status"] == "succeeded"
        assert client.get(f"/api/works/{w['id']}").json()["work"]["memory_version"] == 0


def test_missing_qwen_weights_degrades_to_basic(settings):
    cfg = replace(settings, memory_mode="hybrid", embedding="qwen", embedding_model_path="missing-model-for-test")
    with TestClient(create_app(cfg)) as client:
        health = client.get("/api/health").json()
        assert health["memory_mode"] == "basic" and health["memory_error"] == "ValueError"
        initial_schedule(client)


def test_competing_action_previews_cannot_both_authorize(tmp_path):
    store = Store(tmp_path / "competing-actions.db", "owner")
    try:
        service, w, task, aid, body = action_fixture(store, Settings(action_path=tmp_path / "exports"))
        store.db.execute("UPDATE external_actions SET status='proposed' WHERE id=?", (aid,))
        second = service.propose(w["id"], {**body, "event_id": uid()})[0]
        first_output = task["interaction"]
        store.respond(task["id"], response(first_output, "authorize_action", action_id=aid))
        out = second["interaction"]
        with pytest.raises(DomainError):
            store.respond(second["id"], response(out, "authorize_action", action_id=out["action_id"]))
        asyncio.run(service.tick())
        with pytest.raises(DomainError):
            service.propose(w["id"], {**body, "event_id": uid()})
    finally:
        store.close()
