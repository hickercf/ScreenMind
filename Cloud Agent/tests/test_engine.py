import asyncio
import json
import time
import httpx
import pytest
from fastapi.testclient import TestClient
from screenmind.api import create_app, ProcessLock
from screenmind.config import Settings
from screenmind.information_labels import read_excerpt_labels
from screenmind.models import ScreenInput
from screenmind.providers import CompatibleProvider, MockProvider, ProviderError
from screenmind.store import DomainError, Store


def screen(event_id="screen-1", text="截止时间：周五 18:00", block_id="deadline"):
    return {"event_id": event_id, "session_id": "session-1", "app_package": "com.example",
            "app_name": "示例", "page_title": "报名通知", "captured_at": "2026-11-07T10:00:00+08:00",
            "blocks": [{"id": block_id, "text": text, "kind": "key_value"}]}


@pytest.fixture
def settings(tmp_path):
    return Settings(db_path=tmp_path / "test.sqlite3", mock_delay=0.01,
                    allowed_hosts=("testserver", "localhost", "127.0.0.1"))


@pytest.fixture
def client(settings):
    with TestClient(create_app(settings)) as value:
        yield value


def upload(client, event_id="screen-1", text="截止时间：周五 18:00", block_id="deadline"):
    response = client.post("/api/screens", json=screen(event_id, text, block_id))
    assert response.status_code == 201, response.text
    return response.json()["screen"]["id"]


def create(client, ids=None, event_id="task-1"):
    ids = ids or [upload(client)]
    response = client.post("/api/tasks", json={"event_id": event_id, "instruction": "整理报名要求", "screen_ids": ids})
    assert response.status_code == 202, response.text
    return response.json()["task"]


def wait_task(client, tid, status="waiting_input", timeout=5):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        task = client.get("/api/tasks/" + tid).json()["task"]
        if task["status"] == status:
            return task
        if task["status"] == "failed" and status != "failed":
            pytest.fail(task["error"])
        time.sleep(0.02)
    pytest.fail(f"Timed out: expected {status}, got {task['status']}")


def action(client, task, kind, **kwargs):
    return client.post(f"/api/tasks/{task['id']}/actions", json={
        "event_id": kwargs.pop("event_id", f"action-{kind}-{task['revision']}"),
        "expected_revision": task["revision"], "kind": kind, **kwargs,
    })


def test_full_edit_continue_confirm_roundtrip(client):
    ids = [upload(client), upload(client, "screen-2", "材料：项目计划书")]
    task = wait_task(client, create(client, ids)["id"])
    assert len(task["card"]["fields"]) == 2
    field = task["card"]["fields"][0]
    result = action(client, task, "revise", card_version=1, edits={field["id"]: "周四 17:00"}, message="保留我的更正")
    assert result.status_code == 202
    task = wait_task(client, task["id"])
    edited = next(f for f in task["card"]["fields"] if f["id"] == field["id"])
    assert edited["value"] == "周四 17:00" and edited["origin"] == "user"
    assert task["card_version"] == 2 and task["revision"] == 2
    result = action(client, task, "confirm", card_version=2)
    assert result.json()["task"]["status"] == "completed"
    events = client.get("/api/events", params={"task_id": task["id"]}).json()["events"]
    assert any(e["kind"] == "tool.started" and e["payload"]["tool"] == "read_screens" for e in events)
    assert sum(e["kind"] == "card.published" for e in events) == 2


@pytest.mark.parametrize("archived", [False, True])
def test_excerpt_headings_survive_reopen_and_keep_the_author_and_text(client, monkeypatch, archived):
    data = screen()
    data["blocks"] += [
        {"id": "note", "kind": "paragraph", "text": "保留原文里的 paragraph 这个词"},
        {"id": "chat", "kind": "chat", "text": "图书馆门口集合"},
        {"id": "card", "kind": "card", "text": "带上借书证"},
        {"id": "speaker", "kind": "chat", "speaker": "chat", "text": "这位读者的名字就是 chat"},
    ]
    sid = client.post("/api/screens", json=data).json()["screen"]["id"]
    wid = client.post("/api/works", json={"event_id": "reading", "title": "周五读书会",
                                          "subagent_id": "information"}).json()["work"]["id"]
    with monkeypatch.context() as legacy:
        if archived:
            legacy.setattr("screenmind.agents.information.MOCK_EXCERPT_TITLE", "屏幕信息整理 · 模拟结果")
            legacy.setattr("screenmind.agents.information.mock_excerpt_summary", lambda count, more:
                           f"模拟模型按原文整理，不执行语义分析。当前收录 {count} 份屏幕；用户修订由服务端保留。")
            legacy.setattr("screenmind.agents.information.excerpt_label",
                           lambda app, block: app + " · " + (block.get("speaker") or block["kind"]))
            legacy.setattr("screenmind.store.read_excerpt_labels", lambda cards, get_screen: None)
        response = client.post("/api/tasks", json={"event_id": "meeting", "instruction": "整理读书会",
            "work_id": wid, "screen_ids": [sid], "interaction_protocol": "cloud-v1"})
        tid = response.json()["task"]["id"]
        task = wait_task(client, tid)
        original = task["card"]["fields"]
        if archived:
            assert original[0]["label"] == "示例 · key_value"
        output = task["interaction"]
        accepted = client.post(f"/api/tasks/{tid}/responses", json={"event_id": "accept-meeting",
            "kind": "accept_state", "expected_revision": task["revision"], "output_version": output["output_version"],
            "proposal_id": output["proposal_id"]})
        assert accepted.status_code == 202, accepted.text
    expected = ["示例 · 截止时间", "示例 · 正文摘录", "示例 · 聊天消息", "示例 · 卡片内容", "示例 · chat"]
    get_fields = lambda card: [f["label"] for f in card["fields"]]
    task = client.get("/api/tasks/" + tid).json()["task"]
    assert get_fields(task["card"]) == expected
    assert get_fields(task["previous_card"]) == expected
    assert get_fields(task["interaction"]["card"]) == expected
    assert get_fields(task["interaction"]["candidate_state"]["card"]) == expected
    persisted = client.get("/api/works/" + wid).json()
    assert get_fields(persisted["work"]["state"]["card"]) == expected
    assert get_fields(persisted["history"][0]["state"]["card"]) == expected
    for card in (task["card"], task["previous_card"], task["interaction"]["card"],
                 persisted["work"]["state"]["card"], persisted["history"][0]["state"]["card"]):
        assert card["title"] == "线索摘录"
        assert card["summary"] == "摘录自 1 份线索。"
    assert [c["title"] for c in client.get(f"/api/works/{wid}/plan").json()["confirmed"]] == expected
    assert [{k: v for k, v in f.items() if k != "label"} for f in original] == [
        {k: v for k, v in f.items() if k != "label"} for f in task["card"]["fields"]]
    assert client.delete("/api/screens/" + sid).status_code == 200
    withdrawn = client.get("/api/tasks/" + tid).json()["task"]["card"]
    assert not any(f["label"].endswith("key_value") for f in withdrawn["fields"])


def test_phone_metadata_in_a_model_field_preserves_custom_headings_and_user_corrections():
    text = ("用户确认的来源渠道：manual；语义类型：note；时区：Asia/Shanghai；事项发生时间：未知。"
            "此时间不是消息发送时间，采集时间也不是消息发送时间。")
    source = {"event_id":"mobile:test","app_name":"微光 · 用户确认线索","app_package":"cn.weiguang.app",
              "blocks":[{"id":"metadata","kind":"key_value","text":text}]}
    model = {"id":"model_heading","value":text,"label":"这条笔记的来源","origin":"source","source_refs":["scr_test:metadata"]}
    edited = {**model,"id":"my_correction","origin":"user"}
    fields = [model.copy(), edited.copy()]
    read_excerpt_labels([{"type":"information","fields":fields}], lambda sid:source)
    assert fields[0] == {**model,"value":"你手动记下了这条备忘。"}
    assert fields[1] == edited
    original = model.copy()
    foreign = {**source,"app_package":"org.example.notes"}
    read_excerpt_labels([{"type":"information","fields":[original]}], lambda sid:foreign)
    assert original == model


def test_only_the_complete_old_mock_card_gets_the_new_intro():
    title = "屏幕信息整理 · 模拟结果"
    summary = "模拟模型按原文整理，不执行语义分析。当前收录 3 份屏幕；用户修订由服务端保留。 本轮已收到补充要求；真实语义处理需配置模型。"
    generated = {"type":"information", "title":title, "summary":summary}
    previous = {"type":"information", "title":"线索摘录",
                "summary":"你选了 2 份记录。演示模式按原文摘录，每条都保留了出处。"}
    custom = [{**generated, "title":"借书清单"}, {**generated, "summary":"这是我整理的三条摘录。"},
              {**generated, "summary":summary + " 周五要带过去。"}]
    originals = [card.copy() for card in custom]
    read_excerpt_labels([generated, previous, *custom], lambda _:None)
    assert generated["title"] == "线索摘录" and generated["summary"].endswith("已记下这次补充。")
    assert previous["summary"] == "摘录自 2 份线索。"
    assert custom == originals


def test_screen_upload_never_creates_task(client):
    upload(client)
    assert client.get("/api/tasks").json()["tasks"] == []


def test_idempotency_conflicts_and_action_replay(client):
    sid = upload(client)
    assert upload(client) == sid
    assert client.post("/api/screens", json=screen(text="changed")).status_code == 409
    task = create(client, [sid])
    assert create(client, [sid])["id"] == task["id"]
    task = wait_task(client, task["id"])
    body = {"event_id": "unique-edit", "expected_revision": task["revision"], "kind": "revise", "message": "请继续"}
    first = client.post(f"/api/tasks/{task['id']}/actions", json=body)
    again = client.post(f"/api/tasks/{task['id']}/actions", json=body)
    assert first.status_code == again.status_code == 202
    assert again.json()["replayed"] and again.json()["task"]["revision"] == 2
    body["message"] = "不同内容"
    assert client.post(f"/api/tasks/{task['id']}/actions", json=body).status_code == 409


def test_stale_card_and_revision_rejected(client):
    task = wait_task(client, create(client)["id"])
    assert action(client, task, "confirm", card_version=99).status_code == 409
    assert action(client, task, "revise", message="补充要求").status_code == 202
    assert action(client, task, "confirm", card_version=1).status_code == 409


def test_cancel_and_followup_interrupt_old_work(settings):
    with TestClient(create_app(settings, MockProvider(0.15))) as client:
        task = create(client)
        task = wait_task(client, task["id"], "running")
        changed = action(client, task, "revise", message="更正要求")
        assert changed.status_code == 202
        task = wait_task(client, task["id"])
        assert task["card_version"] == 1 and task["card_revision"] == 2
        other = create(client, [upload(client)], "task-2")
        other = wait_task(client, other["id"], "running")
        assert action(client, other, "cancel").status_code == 202
        time.sleep(0.4)
        other = client.get("/api/tasks/" + other["id"]).json()["task"]
        assert other["status"] == "cancelled" and other["card"] is None


def test_restart_recovers_running_and_keeps_completed(settings):
    with TestClient(create_app(settings, MockProvider(5))) as client:
        task = create(client)
        wait_task(client, task["id"], "running")
    with TestClient(create_app(settings)) as client:
        recovered = wait_task(client, task["id"])
        assert recovered["revision"] == 2
        events = client.get("/api/events").json()["events"]
        assert any(e["kind"] == "task.recovered" for e in events)
        assert action(client, recovered, "confirm", card_version=1).status_code == 202
    with TestClient(create_app(settings)) as client:
        assert client.get("/api/tasks/" + task["id"]).json()["task"]["status"] == "completed"


class CountProvider(MockProvider):
    def __init__(self):
        super().__init__(0.1)
        self.active = self.peak = 0

    async def complete(self, messages, tools):
        self.active += 1
        self.peak = max(self.peak, self.active)
        try:
            return await super().complete(messages, tools)
        finally:
            self.active -= 1


def test_bounded_concurrency_and_task_context_isolation(settings):
    provider = CountProvider()
    with TestClient(create_app(settings, provider)) as client:
        tasks = [create(client, [upload(client, f"screen-{i}", f"专属内容 {i}")], f"task-{i}") for i in range(4)]
        results = [wait_task(client, t["id"]) for t in tasks]
        assert provider.peak == settings.concurrency
        for i, result in enumerate(results):
            assert [f["value"] for f in result["card"]["fields"]] == [f"专属内容 {i}"]


class BadEvidenceProvider(MockProvider):
    async def complete(self, messages, tools):
        result = await super().complete(messages, tools)
        call = result["tool_calls"][0]
        if call["function"]["name"] == "present_card":
            args = json.loads(call["function"]["arguments"])
            args["fields"][0]["source_refs"] = ["another-task:secret"]
            call["function"]["arguments"] = json.dumps(args)
        return result


def test_fabricated_provenance_fails_with_step_limit(settings):
    with TestClient(create_app(settings, BadEvidenceProvider(0))) as client:
        task = wait_task(client, create(client)["id"], "failed")
        assert "步数上限" in task["error"] and task["card"] is None
        events = client.get("/api/events").json()["events"]
        assert any(e["kind"] == "tool.rejected" for e in events)


class FlakyProvider(MockProvider):
    def __init__(self):
        super().__init__(0)
        self.failed = False

    async def complete(self, messages, tools):
        if not self.failed:
            self.failed = True
            raise ProviderError("模拟可恢复故障")
        return await super().complete(messages, tools)


def test_failed_task_can_retry(settings):
    with TestClient(create_app(settings, FlakyProvider())) as client:
        task = wait_task(client, create(client)["id"], "failed")
        assert action(client, task, "retry").status_code == 202
        task = wait_task(client, task["id"])
        assert task["revision"] == 2 and task["error"] is None


def test_body_auth_origin_and_validation_boundaries(settings, client):
    assert client.post("/api/screens", json=screen(), headers={"Origin": "https://evil.example"}).status_code == 403
    assert client.post("/api/screens", content='{}').status_code == 415
    assert client.post("/api/screens", content='x'*512001, headers={"Content-Type": "application/json"}).status_code == 413
    payload = screen();payload["captured_at"] = "2026-11-07T10:00:00"
    assert client.post("/api/screens", json=payload).status_code == 422
    assert client.get("/api/tasks/missing").status_code == 404
    assert client.post("/api/tasks", json={"event_id":"missing", "instruction":"整理", "screen_ids":["missing"]}).status_code == 404
    assert client.get("/api/health", headers={"Host":"evil.example"}).status_code == 400


def test_api_key_required(tmp_path):
    config = Settings(db_path=tmp_path/'key.sqlite3', api_key='test-key', allowed_hosts=('testserver',))
    with TestClient(create_app(config)) as client:
        assert client.get('/api/health').status_code == 401
        assert client.get('/api/health', headers={'Authorization':'Bearer test-key'}).status_code == 200
        assert client.get('/').status_code == 200


def test_workbench_prefix_keeps_the_connection_key_required(tmp_path, monkeypatch):
    monkeypatch.setenv("SCREENMIND_ROOT_PATH", "/workbench")
    config = Settings(db_path=tmp_path/"workbench.sqlite3", api_key="test-workbench", allowed_hosts=("testserver",))
    with TestClient(create_app(config)) as client:
        for base in ("", "/workbench"):
            assert client.get(base+"/").status_code == 200
            assert client.get(base+"/api/health").status_code == 401
            assert client.get(base+"/api/screens").status_code == 401
            valid = {"Authorization":"Bearer test-workbench"}
            assert client.get(base+"/api/health", headers=valid).status_code == 200
            assert client.get(base+"/api/screens", headers={**valid, "Origin":"https://unrelated.example"}).status_code == 403
        assert "/workbench/openapi.json" in client.get("/workbench/docs").text


def test_store_workspace_isolation(tmp_path):
    store = Store(tmp_path/'isolation.sqlite3', 'alice')
    body = ScreenInput.model_validate(screen()).model_dump(mode='json')
    item, _ = store.save_screen(body)
    store.close()
    other = Store(tmp_path/'isolation.sqlite3', 'bob')
    assert other.list_screens() == []
    with pytest.raises(DomainError):
        other.get_screen(item['id'])
    with pytest.raises(DomainError):
        other.screen_page(2, before=item['id'])
    other.close()


def test_older_screens_survive_timestamp_ties_insertions_and_withdrawals(client, monkeypatch):
    monkeypatch.setattr("screenmind.store.now", lambda: "2026-11-21T03:00:00+00:00")
    ids = sorted((upload(client, f"book-{n}") for n in range(5)), reverse=True)
    first = client.get("/api/screens", params={"limit": 2}).json()
    assert [s["id"] for s in first["screens"]] == ids[:2]
    assert first["total"] == 5 and first["next_before"] == ids[1]
    assert client.delete("/api/screens/"+ids[1]).status_code == 200
    assert client.get("/api/screens/"+ids[1]).status_code == 404
    monkeypatch.setattr("screenmind.store.now", lambda: "2026-11-21T04:00:00+00:00")
    upload(client, "newer-book")
    rest = client.get("/api/screens", params={"limit": 3, "before": first["next_before"]}).json()
    assert [s["id"] for s in rest["screens"]] == ids[2:]
    assert rest["total"] == 5 and rest["next_before"] is None
    assert client.get("/api/screens/"+ids[-1]).json()["screen"]["id"] == ids[-1]
    assert set(client.get("/api/screens").json()) == {"screens"}


def test_process_lock(tmp_path):
    lock = ProcessLock(tmp_path/'lock.sqlite3')
    with pytest.raises(RuntimeError):
        ProcessLock(tmp_path/'lock.sqlite3')
    lock.close()
    ProcessLock(tmp_path/'lock.sqlite3').close()


def test_real_provider_protocol_and_secret_redaction(settings):
    calls = []
    async def run():
        config = Settings(provider='openai-compatible', base_url='https://model.example/v1', model='test-model', model_key='secret-for-test')
        def handler(request):
            calls.append(request)
            return httpx.Response(200, json={'choices':[{'message':{'role':'assistant','content':None,'tool_calls':[{'id':'x','type':'function','function':{'name':'read_screens','arguments':'{}'}}]}}]})
        provider = CompatibleProvider(config, httpx.MockTransport(handler))
        result = await provider.complete([{'role':'user','content':'hello'}], [])
        assert result['tool_calls'][0]['function']['name'] == 'read_screens'
        assert calls[0].url.path == '/v1/chat/completions'
        assert calls[0].headers['authorization'] == 'Bearer secret-for-test'
        await provider.close()
        bad = CompatibleProvider(config, httpx.MockTransport(lambda r: httpx.Response(401, text='leaked secret-for-test')))
        with pytest.raises(ProviderError) as exc:
            await bad.complete([], [])
        assert '401' in str(exc.value) and 'secret-for-test' not in str(exc.value)
        await bad.close()
    asyncio.run(run())


def test_real_adapter_full_tool_roundtrip(settings):
    requests = []
    demo = MockProvider(0)

    async def handler(request):
        payload = json.loads(request.content)
        requests.append(payload)
        reply = await demo.complete(payload['messages'], payload['tools'])
        return httpx.Response(200, json={'choices':[{'message': reply}]})

    config = Settings(provider='openai-compatible', base_url='https://model.example/v1', model='test', model_key='secret')
    adapter = CompatibleProvider(config, httpx.MockTransport(handler))
    with TestClient(create_app(settings, adapter)) as client:
        task = wait_task(client, create(client)['id'])
        assert task['card'] and len(requests) == 2
        assert requests[1]['messages'][-1]['role'] == 'tool'
        assert requests[1]['messages'][-1]['name'] == 'read_screens'
        assert {t['function']['name'] for t in requests[0]['tools']} == {'read_screens','present_card'}


def test_task_timeout(tmp_path):
    config = Settings(db_path=tmp_path/'timeout.sqlite3', task_timeout=0.08, allowed_hosts=('testserver',))
    with TestClient(create_app(config, MockProvider(1))) as client:
        task = wait_task(client, create(client)['id'], 'failed')
        assert '超时' in task['error'] and task['card'] is None


def test_maximum_block_identifier_and_additional_screen(client):
    original = upload(client, block_id='x'*100)
    task = wait_task(client, create(client,[original])['id'])
    extra = upload(client,'extra','新补充的证据')
    response = action(client, task, 'revise', screen_ids=[extra])
    assert response.status_code == 202
    task = wait_task(client, task['id'])
    assert len(task['card']['fields']) == 2
    assert any(f['value'] == '新补充的证据' for f in task['card']['fields'])


def test_transport_retry_is_bounded():
    async def run():
        config = Settings(provider='openai-compatible', base_url='https://model.example/v1', model='test', model_key='secret')
        count = 0
        def handler(request):
            nonlocal count
            count += 1
            return httpx.Response(503, text='internal details that must not escape')
        provider = CompatibleProvider(config, httpx.MockTransport(handler))
        with pytest.raises(ProviderError) as exc:
            await provider.complete([], [])
        assert count == 3 and 'internal details' not in str(exc.value)
        await provider.close()
    asyncio.run(run())
