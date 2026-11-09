import json
import uuid

import pytest
from fastapi.testclient import TestClient
from screenmind.api import create_app
from screenmind.config import Settings
from screenmind.interactions import interaction_adapter
from screenmind.memory import BasicMemoryProvider, ContextBuilder
from screenmind.models import TaskInput, WorkInput, ActionInput
from screenmind.providers import MockProvider
from screenmind.store import Store, DomainError
from screenmind.worker import Worker
from test_engine import settings, client, screen, wait_task


def uid():
    return uuid.uuid4().hex


def make_work(store):
    return store.create_work(WorkInput(event_id=uid(), title="活动", subagent_id="information").model_dump())[0]


def make_task(store, wid=None, text="通知", protocol="legacy"):
    source = store.save_screen(screen(uid(), text))[0]
    task = store.create_task(TaskInput(event_id=uid(), instruction="整理材料", screen_ids=[source["id"]],
        work_id=wid, interaction_protocol=protocol).model_dump())[0]
    return task, source


def card(ref):
    return {"title": "安排", "summary": "依据材料整理", "fields": [
        {"id": "time", "label": "时间", "value": "周五", "source_refs": [ref]}]}


def response(output, kind, **extra):
    return {"contract_version": "cloud-v1", "event_id": uid(), "expected_revision": output["revision"],
            "output_version": output["output_version"], "kind": kind, **extra}


def start(client, wid=None, protocol="cloud-v1", status=None):
    sid = client.post("/api/screens", json=screen(uid())).json()["screen"]["id"]
    r = client.post("/api/tasks", json={"event_id": uid(), "instruction": "整理通知", "screen_ids": [sid],
        "work_id": wid, "interaction_protocol": protocol})
    assert r.status_code == 202, r.text
    return wait_task(client, r.json()["task"]["id"], status or ("completed" if wid is None and protocol == "cloud-v1" else "waiting_input"))


def respond(client, task, body):
    return client.post(f"/api/tasks/{task['id']}/responses", json=body)


def test_basic_memory_snapshot_history_isolation_and_provenance(tmp_path):
    with_store = Store(tmp_path / "memory.db", "owner")
    try:
        store = with_store
        first_work, other_work = make_work(store), make_work(store)
        first, old = make_task(store, first_work["id"], "旧场地甲因容量不足未采用")
        unrelated, other = make_task(store, other_work["id"], "另一个活动的秘密")
        claimed = store.claim(first["id"])
        ref = old["id"] + ":deadline"
        store.publish(first["id"], 1, card(ref), "mock", {"venue": "丙"})
        store.action(first["id"], ActionInput(event_id=uid(), expected_revision=1, kind="confirm", card_version=1).model_dump())
        current, new = make_task(store, first_work["id"], "再看看旧场地")
        bundle = store.claim(current["id"])["memory_context"]
        assert bundle["memory"]["state"] == {"venue": "丙"}
        assert bundle["memory"]["memory_version"] == 1
        assert [s["id"] for s in bundle["screens"]] == [new["id"]]
        assert bundle["history"][0]["screen"]["id"] == old["id"]
        assert bundle["history"][0]["acceptance"] == "evidence_only"
        refs = ContextBuilder.source_refs(bundle)
        assert ref in refs and other["id"] + ":deadline" not in refs
        # Later evidence cannot leak into a running round's saved snapshot.
        later, source = make_task(store, first_work["id"], "之后收到的变更")
        assert store.task(current["id"])["memory_context"] == bundle
        worker = Worker(store, None, Settings())
        worker.validate_card(card(ref), store.task(current["id"]))
        from screenmind.agents.base import ToolError
        with pytest.raises(ToolError):
            worker.validate_card(card(other["id"] + ":deadline"), store.task(current["id"]))
        assert store.work(first_work["id"])["state"] == {"venue": "丙"}
    finally:
        with_store.close()


def test_unconfirmed_evidence_and_historical_user_inputs(tmp_path):
    store = Store(tmp_path / "inputs.db", "owner")
    try:
        work = make_work(store)
        old, source = make_task(store, work["id"])
        store.action(old["id"], ActionInput(event_id=uid(), expected_revision=1, kind="revise", message="先别订场地").model_dump())
        store.action(old["id"], ActionInput(event_id=uid(), expected_revision=2, kind="cancel").model_dump())
        new, _ = make_task(store, work["id"])
        bundle = store.claim(new["id"])["memory_context"]
        assert bundle["memory"]["state"] == {} and bundle["memory"]["memory_version"] == 0
        history_input = next(h for h in bundle["history"] if h["kind"] == "user_input")
        assert history_input["source_ref"] == f"input:{old['id']}:2"
        assert history_input["acceptance"] == "historical_input_not_current_instruction"
        assert bundle["authenticated_user_inputs"] == []
    finally:
        store.close()


def test_memory_budget_and_one_off_never_search_other_tasks(tmp_path):
    store = Store(tmp_path / "budget.db", "owner", memory_provider=BasicMemoryProvider(history_char_budget=0))
    try:
        work = make_work(store)
        make_task(store, work["id"])
        current, _ = make_task(store, work["id"])
        bundle = store.claim(current["id"])["memory_context"]
        assert bundle["history"] == [] and bundle["history_omitted"] is True
        assert bundle["history_chars_used"] == 0
        oneoff, _ = make_task(store)
        bundle = store.claim(oneoff["id"])["memory_context"]
        assert bundle["memory"] is None and bundle["history"] == []
    finally:
        store.close()


def test_work_resolution_is_read_only_and_owner_scoped(tmp_path):
    store = Store(tmp_path / "resolve.db", "a")
    other = Store(tmp_path / "resolve.db", "b")
    try:
        w1, w2 = make_work(store), make_work(store)
        t1, s1 = make_task(store, w1["id"])
        t2, s2 = make_task(store, w2["id"])
        result = store.work_resolver.inspect([s1["id"], s2["id"]])
        assert result["status"] == "needs_selection"
        assert set(result["candidate_work_ids"]) == {w1["id"], w2["id"]}
        with pytest.raises(DomainError):
            store.work_resolver.resolve([s1["id"]])
        with pytest.raises(DomainError):
            store.work_resolver.resolve([s1["id"]], w2["id"])
        with pytest.raises(DomainError):
            other.work_resolver.inspect([s1["id"]], w1["id"])
        unbound = store.save_screen(screen(uid()))[0]
        assert store.work_resolver.inspect([unbound["id"]], w1["id"])["status"] == "selected"
        assert store.db.execute("SELECT 1 FROM work_screens WHERE screen_id=?", (unbound["id"],)).fetchone() is None
    finally:
        store.close()
        other.close()


def test_cloud_information_completes_and_ack_is_idempotent(client):
    task = start(client)
    assert task["status"] == "completed"
    output = task["interaction"]
    assert interaction_adapter.validate_python(output).kind == "information"
    body = response(output, "acknowledge")
    assert respond(client, task, body).status_code == 202
    assert respond(client, task, body).json()["replayed"] is True
    wrong = {**body, "kind": "authorize_action", "action_id": "unregistered"}
    assert respond(client, task, wrong).status_code == 409
    wrong["event_id"] = uid()
    assert respond(client, task, wrong).status_code == 422
    assert client.get("/api/works").json()["works"] == []
    assert "memory_context" not in client.get("/api/tasks").json()["tasks"][0]


def test_state_response_uses_saved_candidate_and_rejects_stale_confirm(client):
    wid = client.post("/api/works", json={"event_id": uid(), "title": "通知", "subagent_id": "information"}).json()["work"]["id"]
    first, second = start(client, wid), start(client, wid)
    output = first["interaction"]
    assert output["kind"] == "state_proposal" and output["base_state_version"] == 0
    body = response(output, "accept_state", proposal_id=output["proposal_id"])
    assert respond(client, first, {**body, "proposal_id": second["interaction"]["proposal_id"]}).status_code == 422
    assert respond(client, first, {**body, "candidate_state": {"injected": True}}).status_code == 422
    assert client.post(f"/api/tasks/{first['id']}/actions", json={"event_id": uid(), "expected_revision": 1,
        "kind": "confirm", "card_version": 1}).status_code == 422
    assert respond(client, first, body).json()["task"]["status"] == "completed"
    assert respond(client, first, body).json()["replayed"] is True
    assert client.get(f"/api/works/{wid}").json()["work"]["state"] == output["candidate_state"]
    stale = second["interaction"]
    assert respond(client, second, response(stale, "accept_state", proposal_id=stale["proposal_id"])).status_code == 409
    assert respond(client, second, response(stale, "reject_state", proposal_id=stale["proposal_id"])).json()["task"]["status"] == "cancelled"
    assert client.get(f"/api/works/{wid}").json()["work"]["memory_version"] == 1


class ClarifyingProvider(MockProvider):
    async def complete(self, messages, tools):
        context = json.loads(messages[1]["content"])
        if not context["followups"]:
            evidence = next((json.loads(m["content"]) for m in messages if m.get("name") == "read_screens"), None)
            if evidence is None:
                return self.call("read_screens", {})
            return self.call("ask_clarification", {"question": "截止时间是否调整？",
                "source_refs": [evidence["screens"][0]["blocks"][0]["source_ref"]]})
        return await super().complete(messages, tools)


def test_clarification_recovers_same_task_after_restart(settings):
    app = create_app(settings, ClarifyingProvider(0))
    with TestClient(app) as client:
        task = start(client, status="waiting_input")
        output = task["interaction"]
        assert output["kind"] == "clarification" and task["card"] is None
    with TestClient(create_app(settings, ClarifyingProvider(0))) as client:
        body = response(output, "answer", question_id=output["question_id"], answer="截止改为周六")
        assert client.post(f"/api/tasks/{task['id']}/actions", json={"event_id": uid(), "expected_revision": 1,
            "kind": "revise", "message": "绕过问题直接回复"}).status_code == 422
        assert respond(client, task, {**body, "output_version": 99}).status_code == 409
        assert respond(client, task, {**body, "question_id": "wrong"}).status_code == 422
        r = respond(client, task, body)
        assert r.status_code == 202, r.text
        assert r.json()["task"]["revision"] == 2
        assert respond(client, task, body).json()["replayed"] is True
        final = wait_task(client, task["id"], "completed")
        assert final["id"] == task["id"] and final["status"] == "completed"
        assert final["interaction"]["output_version"] == 2 and final["interaction"]["revision"] == 2
        assert final["memory_context"]["authenticated_user_inputs"][0]["message"] == "截止改为周六"
        assert respond(client, task, {**body, "event_id": uid()}).status_code == 409


def test_cancelled_question_cannot_resume_and_contract_discovery(settings):
    with TestClient(create_app(settings, ClarifyingProvider(0))) as client:
        task = start(client, status="waiting_input")
        output = task["interaction"]
        client.post(f"/api/tasks/{task['id']}/actions", json={"event_id": uid(), "expected_revision": 1, "kind": "cancel"})
        assert respond(client, task, response(output, "answer", question_id=output["question_id"], answer="周六")).status_code == 409
        contract = client.get("/api/contracts").json()
        assert contract["external_actions_enabled"] is True
        assert contract["supported_outputs"] == ["information", "clarification", "state_proposal", "action_proposal"]
        assert "ActionProposal" in contract["output_schema"]["$defs"]
        assert "/api/tasks/{tid}/responses" in client.get("/openapi.json").json()["paths"]


def test_work_resolution_endpoint_does_not_dispatch(client):
    sid = client.post("/api/screens", json=screen(uid())).json()["screen"]["id"]
    result = client.post("/api/works/resolve", json={"screen_ids": [sid]})
    assert result.json()["status"] == "unassigned"
    assert client.get("/api/tasks").json()["tasks"] == []


def test_party_cloud_protocol_keeps_confirmed_state_until_accept(client):
    from test_party import work, evidence
    w = work(client)
    def party_task(sid):
        created = client.post("/api/tasks", json={"event_id": uid(), "instruction": "更新聚会", "screen_ids": [sid],
            "work_id": w["id"], "interaction_protocol": "cloud-v1"}).json()["task"]
        return wait_task(client, created["id"])
    initial = party_task(evidence(client))
    out = initial["interaction"]
    assert out["kind"] == "state_proposal"
    assert client.get(f"/api/works/{w['id']}").json()["work"]["memory_version"] == 0
    assert respond(client, initial, response(out, "accept_state", proposal_id=out["proposal_id"])).status_code == 202
    change = party_task(evidence(client, ["我今晚不去了"], "小林"))
    assert change["memory_context"]["history"]
    assert "98.29" in next(f["value"] for f in change["interaction"]["card"]["fields"] if f["id"] == "cost")
    assert client.get(f"/api/works/{w['id']}").json()["work"]["memory_version"] == 1
    out = change["interaction"]
    assert respond(client, change, response(out, "accept_state", proposal_id=out["proposal_id"])).status_code == 202
    assert client.get(f"/api/works/{w['id']}").json()["work"]["memory_version"] == 2


def test_contract_examples_validate():
    from pathlib import Path
    from pydantic import TypeAdapter
    from screenmind.interactions import InteractionResponse
    fixtures = json.loads((Path(__file__).parents[1] / "examples/phase-one-contracts.json").read_text(encoding="utf-8"))
    assert len(fixtures["flows"]) == 4
    for case in fixtures["flows"]:
        output = interaction_adapter.validate_python(case["output"])
        answer = TypeAdapter(InteractionResponse).validate_python(case["response"])
        assert output.revision == answer.expected_revision
        assert output.output_version == answer.output_version
        if output.kind == "action_proposal":
            assert output.execution_enabled is False and case["runtime_supported"] is False


def test_legacy_revision_retry_receipt_survives_new_clarification(tmp_path):
    store = Store(tmp_path / "replay.db", "owner")
    try:
        w = make_work(store)
        task, source = make_task(store, w["id"], protocol="cloud-v1")
        store.claim(task["id"])
        ref = source["id"] + ":deadline"
        store.publish(task["id"], 1, card(ref), "mock", {"time": "周五"})
        edit = ActionInput(event_id=uid(), expected_revision=1, kind="revise", message="再检查时间").model_dump()
        store.action(task["id"], edit)
        store.claim(task["id"])
        store.clarify(task["id"], 2, {"question": "需要改到周六吗？", "source_refs": [ref]})
        assert store.action(task["id"], edit)[1] is True
        assert store.task(task["id"])["revision"] == 2
    finally:
        store.close()
