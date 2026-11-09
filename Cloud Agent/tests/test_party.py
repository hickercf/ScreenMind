import sqlite3
import uuid
from fastapi.testclient import TestClient
from screenmind.api import create_app
from screenmind.agents import Registry
from screenmind.agents.information import InformationAgent
from screenmind.store import Store
from test_engine import settings, client, screen, wait_task, action, upload


def uid():
    return uuid.uuid4().hex


def work(client, title="生日聚会", agent="party"):
    response = client.post("/api/works", json={"event_id": uid(), "title": title, "subagent_id": agent})
    assert response.status_code == 201, response.text
    return response.json()["work"]


def evidence(client, texts=None, speaker=None):
    texts = texts or ["参加成员：小林、小王、小李、小陈、小张、小周、小吴、小赵", "套餐总价：688元", "人均预算：150元", "蛋糕负责人：小林"]
    body = screen(uid())
    body["blocks"] = [{"id": "b" + str(i), "kind": "chat", "text": text, "speaker": speaker} for i, text in enumerate(texts)]
    response = client.post("/api/screens", json=body)
    assert response.status_code == 201, response.text
    return response.json()["screen"]["id"]


def task(client, wid, sid, agent=None):
    body = {"event_id": uid(), "instruction": "更新事项计划", "screen_ids": [sid], "work_id": wid}
    if agent:
        body["subagent_id"] = agent
    response = client.post("/api/tasks", json=body)
    assert response.status_code == 202, response.text
    return wait_task(client, response.json()["task"]["id"])


def confirm(client, task, **kwargs):
    return action(client, task, "confirm", card_version=task["card_version"], **kwargs)


def memory(client, wid):
    return client.get("/api/works/" + wid).json()["work"]


def value(task, key):
    return next(f["value"] for f in task["card"]["fields"] if f["id"] == key)


def initialized(client):
    w = work(client)
    first = task(client, w["id"], evidence(client))
    assert memory(client, w["id"])["state"] == {}
    assert "86.00" in value(first, "cost")
    assert confirm(client, first).status_code == 202
    return w


def test_party_departure_confirm_and_reassignment(client):
    w = initialized(client)
    change = task(client, w["id"], evidence(client, ["我今晚不去了"], "小林"))
    assert change["subagent_id"] == "party" and change["memory_base"] == 1
    assert "确定参加 7 人" in value(change, "attendance")
    assert "98.29" in value(change, "cost")
    assert "待重新分配" in value(change, "responsibility_0")
    assert memory(client, w["id"])["state"]["plan"]["participants"][0]["status"] == "attending"
    forbidden = action(client, change, "revise", card_version=1, edits={"attendance": "参加 99 人"})
    assert forbidden.status_code == 422
    # Same task can carry a user-approved change to the proposed responsibility.
    result = action(client, change, "revise", message="蛋糕负责人：小王")
    assert result.status_code == 202
    change = wait_task(client, change["id"])
    assert value(change, "responsibility_0") == "小王"
    assert any(r.startswith("input:") for r in change["card"]["fields"][0]["source_refs"])
    event = uid()
    assert confirm(client, change, event_id=event).status_code == 202
    assert confirm(client, change, event_id=event).json()["replayed"]
    saved = memory(client, w["id"])
    assert saved["memory_version"] == 2
    assert saved["state"]["plan"]["participants"][0]["status"] == "declined"
    assert len(client.get("/api/works/" + w["id"]).json()["history"]) == 2


def test_tentative_is_not_declined_and_cancel_does_not_commit(client):
    w = initialized(client)
    change = task(client, w["id"], evidence(client, ["我可能来不了"], "小林"))
    assert "待确认 1 人；退出 0 人" in value(change, "attendance")
    assert "备用负责人" in value(change, "responsibility_0")
    assert "86.00" in value(change, "cost") and "98.29" in value(change, "cost")
    assert action(client, change, "cancel").status_code == 202
    assert memory(client, w["id"])["memory_version"] == 1
    assert confirm(client, change).status_code == 409


def test_memory_conflict_requires_regeneration(client):
    w = initialized(client)
    one = task(client, w["id"], evidence(client, ["我今晚不去了"], "小林"))
    two = task(client, w["id"], evidence(client, ["我今晚不去了"], "小王"))
    assert confirm(client, one).status_code == 202
    response = confirm(client, two)
    assert response.status_code == 409 and "记忆" in response.text
    assert action(client, two, "revise", message="小王：退出").status_code == 202
    two = wait_task(client, two["id"])
    assert two["memory_base"] == 2 and "确定参加 6 人" in value(two, "attendance")
    assert confirm(client, two).status_code == 202
    assert memory(client, w["id"])["memory_version"] == 3


def test_work_isolation_and_router_binding(client):
    a, b = work(client, "聚会 A"), work(client, "聚会 B")
    sa, sb = evidence(client), evidence(client, ["参加成员：阿甲、阿乙", "套餐总价：100元"])
    ta, tb = task(client, a["id"], sa), task(client, b["id"], sb)
    assert confirm(client, ta).status_code == 202
    assert confirm(client, tb).status_code == 202
    assert len(memory(client, a["id"])["state"]["plan"]["participants"]) == 8
    assert len(memory(client, b["id"])["state"]["plan"]["participants"]) == 2
    for wid, agent, sid in [(b["id"], "party", sa), (a["id"], "information", sa), (None, "information", sa)]:
        r = client.post("/api/tasks", json={"event_id": uid(), "instruction": "整理", "screen_ids": [sid], "work_id": wid, "subagent_id": agent})
        assert r.status_code == 422
    route = client.post("/api/tasks", json={"event_id": uid(), "instruction": "规划聚会", "screen_ids": [evidence(client)]})
    assert route.status_code == 422 and "work_id" in route.text


def test_restart_keeps_memory(settings):
    with TestClient(create_app(settings)) as client:
        w = initialized(client)
    with TestClient(create_app(settings)) as client:
        assert memory(client, w["id"])["memory_version"] == 1
        change = task(client, w["id"], evidence(client, ["我今晚不去了"], "小林"))
        assert "确定参加 7 人" in value(change, "attendance")


def test_extension_registered_without_runtime_changes(settings):
    class NotesAgent(InformationAgent):
        id = "notes"
        name = "会议记录示例扩展"
        requires_work = True
    registry = Registry([InformationAgent(), NotesAgent()])
    with TestClient(create_app(settings, registry=registry)) as client:
        w = work(client, "会议", "notes")
        result = task(client, w["id"], upload(client))
        assert result["subagent_id"] == "notes"
        assert confirm(client, result).status_code == 202
        assert memory(client, w["id"])["state"]["card"]["fields"]


def test_old_schema_migrates_without_data_loss(tmp_path):
    path = tmp_path / "legacy.sqlite3"
    db = sqlite3.connect(path)
    db.execute("""CREATE TABLE tasks(id TEXT PRIMARY KEY,owner TEXT NOT NULL,instruction TEXT NOT NULL,
        screen_ids TEXT NOT NULL,status TEXT NOT NULL,revision INTEGER NOT NULL,card_version INTEGER NOT NULL DEFAULT 0,
        previous_card TEXT,error TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL)""")
    db.execute("INSERT INTO tasks VALUES('old','owner','old instruction','[]','completed',2,0,NULL,NULL,'t','t')")
    db.commit(); db.close()
    store = Store(path, "owner")
    assert store.task("old")["subagent_id"] == "information"
    assert store.task("old")["instruction"] == "old instruction"
    assert store.task("old")["work_id"] is None
    store.close()


def test_unknown_speaker_does_not_change_memory(client):
    w = initialized(client)
    r = client.post("/api/tasks", json={"event_id": uid(), "instruction": "更新", "screen_ids": [evidence(client, ["我不来了"], "陌生人")], "work_id": w["id"]})
    failed = wait_task(client, r.json()["task"]["id"], "failed")
    assert "成员" in failed["error"]
    assert memory(client, w["id"])["memory_version"] == 1


def test_all_departed_no_division_by_zero(client):
    w = work(client)
    result = task(client, w["id"], evidence(client, ["参加成员：小林", "套餐总价：688元", "小林：退出"]))
    assert "不能计算" in value(result, "cost")
    assert "聚会是否继续" in value(result, "next_steps")


def test_legacy_request_receipt_accepts_new_nullable_fields(tmp_path):
    store = Store(tmp_path / "receipt.sqlite3", "owner")
    sid = store.save_screen(screen())[0]["id"]
    body = {"event_id": "old-request", "instruction": "整理", "screen_ids": [sid]}
    original, _ = store.create_task(body)
    replayed, is_replay = store.create_task({**body, "work_id": None, "subagent_id": None})
    assert is_replay and replayed["id"] == original["id"]
    # Binding an already-used legacy screen must not break the queued task's scope.
    wid = store.create_work({"event_id": "work", "title": "Party", "subagent_id": "party"})[0]["id"]
    from screenmind.store import DomainError
    import pytest
    with pytest.raises(DomainError):
        store.create_task({**body, "event_id": "new", "work_id": wid})
    assert store.claim(original["id"])["status"] == "running"
    store.close()


def test_party_rejects_foreign_memory_reference(settings):
    from screenmind.providers import MockProvider
    import json
    class ForgedMemoryProvider(MockProvider):
        async def complete(self, messages, tools):
            result = await super().complete(messages, tools)
            call = result["tool_calls"][0]["function"]
            if call["name"] == "present_party":
                draft = json.loads(call["arguments"])
                draft["source_refs"].append("memory:another-party:1")
                call["arguments"] = json.dumps(draft)
            return result
    with TestClient(create_app(settings, provider=ForgedMemoryProvider(0))) as client:
        w = work(client)
        response = client.post("/api/tasks", json={"event_id": uid(), "instruction": "整理", "screen_ids": [evidence(client)], "work_id": w["id"]})
        failed = wait_task(client, response.json()["task"]["id"], "failed")
        assert failed["card"] is None
        assert memory(client, w["id"])["memory_version"] == 0
