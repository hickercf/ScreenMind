"""Authenticated mobile API -> real shared Agent runtime, synthetic input, no model bills."""
import json
import asyncio
import os
from pathlib import Path
import sqlite3
import tempfile
import threading
import time
import unittest
from contextlib import closing
from unittest.mock import patch
from uuid import uuid4

from fastapi.testclient import TestClient
from backend import main


class AgentBridgeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.env = patch.dict(os.environ, {'WEIGUANG_DB': self.temp.name + '/mobile.db',
            'WEIGUANG_AGENT_ROOT': self.temp.name + '/agents', 'SCREENMIND_PROVIDER': 'mock',
            'SCREENMIND_MEMORY_MODE': 'basic', 'SCREENMIND_MEMORY_EXTRACTOR': 'raw'})
        self.env.start()
        main.ATTEMPTS.clear()
        self.client = TestClient(main.app).__enter__()
        self.a = self.register('alice')
        self.b = self.register('bob')

    def tearDown(self):
        self.client.__exit__(None, None, None)
        self.env.stop()
        self.temp.cleanup()

    def register(self, name):
        r = self.client.post('/v1/auth/register', json={'username': name, 'password': 'synthetic-password'})
        self.assertEqual(r.status_code, 201)
        return r.json()

    def req(self, method, path, body=None, user=None):
        return self.client.request(method, '/v1/' + path, json=body,
            headers={'Authorization': 'Bearer ' + (user or self.a)['access_token']})

    def upload(self, text, **fields):
        event = {'event_id': str(uuid4()), 'revision': 1, 'event_type': 'message', 'source_kind': 'screenshot_ocr',
            'captured_at': '2026-11-09T12:00:00+08:00', 'payload': {'text': text}, **fields}
        r = self.req('POST', 'events', event)
        self.assertEqual(r.status_code, 200, r.text)
        return event

    def work(self, plugin='information'):
        r = self.req('POST', 'agent/works', {'event_id': str(uuid4()), 'title': '合成事项', 'subagent_id': plugin})
        self.assertEqual(r.status_code, 201, r.text)
        return r.json()['work']

    def test_ninth_workspace_can_open_after_idle_and_old_account_keeps_its_private_work(self):
        # Advance the gateway's inactivity clock without delaying its actual worker or HTTP timers.
        stamp = [1000.]
        with patch('backend.agent_bridge.monotonic', side_effect=lambda: stamp[0], create=True):
            original = self.work()
            others = [self.b]
            with closing(sqlite3.connect(main.database_path())) as conn, conn:
                password = main.PASSWORDS.hash('synthetic-password')
                for i in range(7):
                    uid, name = str(uuid4()), f'workspace{i}'
                    conn.execute('INSERT INTO users VALUES (?,?,?,?)', (uid, name, password, main.now()))
                    others.append(main.issue_session(conn, uid, name))
            for user in others[:-1]:
                self.assertEqual(self.req('GET', 'agent/works', user=user).status_code, 200)
            ninth = others[-1]
            self.assertEqual(self.req('GET', 'agent/works', user=ninth).status_code, 503)
            stamp[0] += 120
            response = self.req('GET', 'agent/works', user=ninth)
            self.assertEqual(response.status_code, 200, response.text)
            self.assertEqual(response.json()['works'], [])
            self.assertEqual(self.req('GET', 'agent/works/' + original['id'], user=ninth).status_code, 404)
            restored = self.req('GET', 'agent/works')
            self.assertEqual(restored.status_code, 200, restored.text)
            self.assertEqual([w['id'] for w in restored.json()['works']], [original['id']])
            self.assertLessEqual(len(main.app.state.agent_hub.entries), 8)

    def test_active_request_is_never_evicted_even_after_idle_window(self):
        hub = main.app.state.agent_hub
        hub.max_open, hub.idle_seconds = 1, 0
        work = self.work()
        entered, release = threading.Event(), threading.Event()

        async def hold_request():
            async with hub.lock(self.a['user']['id']):
                entered.set()
                await asyncio.to_thread(release.wait, 10)

        pending = self.client.portal.start_task_soon(hold_request)
        try:
            self.assertTrue(entered.wait(5))
            response = self.req('GET', 'agent/works', user=self.b)
            self.assertEqual(response.status_code, 503)
            self.assertEqual(response.headers['Retry-After'], '5')
        finally:
            release.set()
            pending.result(5)
        self.assertEqual(self.req('GET', 'agent/works', user=self.b).status_code, 200)
        self.assertEqual(self.req('GET', 'agent/works/' + work['id']).status_code, 200)

    def test_queued_and_running_tasks_finish_before_the_workspace_can_close(self):
        hub = main.app.state.agent_hub
        hub.max_open, hub.idle_seconds = 1, 0
        work = self.work()
        event = self.upload('周末把讨论稿带到组会')
        child = hub.entries[self.a['user']['id']].app
        provider = child.state.runtime.provider
        complete = provider.complete
        entered, release = threading.Event(), threading.Event()

        async def complete_when_ready(*args, **kwargs):
            entered.set()
            await asyncio.to_thread(release.wait, 10)
            return await complete(*args, **kwargs)

        with patch.object(provider, 'complete', complete_when_ready):
            with patch.object(child.state.store, 'queued', return_value=[]):
                task, _ = self.start(work, [event])
                self.assertEqual(self.req('GET', 'agent/works', user=self.b).status_code, 503)
            try:
                self.assertTrue(entered.wait(5))
                self.assertEqual(self.req('GET', 'agent/works', user=self.b).status_code, 503)
            finally:
                release.set()
            final = self.wait(task['id'])
        self.assertIn(final['status'], ('completed', 'waiting_input'))
        self.assertEqual(self.req('GET', 'agent/works', user=self.b).status_code, 200)
        self.assertEqual(self.req('GET', 'agent/tasks/' + task['id']).json()['task']['status'], final['status'])

    def start(self, work, events):
        body = {'request_id': str(uuid4()), 'work_id': work['id'], 'instruction': '整理这些线索，缺少事实先澄清，候选确认后才生效',
            'events': [{'event_id': e['event_id'], 'revision': e['revision']} for e in events]}
        r = self.req('POST', 'agent/tasks', body)
        self.assertEqual(r.status_code, 202, r.text)
        return r.json()['task'], body

    def wait(self, tid):
        until = time.monotonic() + 10
        while time.monotonic() < until:
            r = self.req('GET', 'agent/tasks/' + tid)
            self.assertEqual(r.status_code, 200, r.text)
            task = r.json()['task']
            if task['status'] not in ('queued', 'running'):
                return task
            time.sleep(.05)
        self.fail('Task did not settle')

    def response(self, task, kind, **fields):
        return {'event_id': str(uuid4()), 'expected_revision': task['revision'],
            'output_version': task['interaction']['output_version'], 'kind': kind, **fields}

    def schedule(self):
        work = self.work('schedule')
        event = self.upload(json.dumps({'schedule_changes': [{'id': 'meeting', 'title': '组会', 'at': '2026-12-10T18:00:00+08:00'}]}))
        task, _ = self.start(work, [event])
        return work, event, self.wait(task['id'])

    def test_phone_schedule_accepts_a_dated_answer_without_exposing_internal_instructions(self):
        work = self.work('schedule')
        event = self.upload('图书馆读书会，等大家定下见面时间')
        task, _ = self.start(work, [event])
        asked = self.wait(task['id'])
        output = asked['interaction']
        self.assertEqual(output['kind'], 'clarification')
        self.assertNotRegex(output['question'], r'schedule_changes|JSON')
        data = self.response(asked, 'answer', question_id=output['question_id'], answer='一起还书，2027年1月10日 下午6点半（北京时间）')
        result = self.req('POST', 'agent/tasks/'+task['id']+'/responses', data)
        self.assertEqual(result.status_code, 202, result.text)
        proposed = self.wait(task['id'])
        self.assertEqual(proposed['interaction']['kind'], 'state_proposal')
        item, = proposed['memory_proposal']['items'].values()
        self.assertEqual((item['title'], item['at']), ('一起还书', '2027-01-10T18:30:00+08:00'))
        self.assertEqual(self.req('GET', 'agent/works/'+work['id']).json()['work']['memory_version'], 0)
        self.assertEqual(self.req('GET', 'agent/tasks/'+task['id'], user=self.b).status_code, 404)

    def test_memory_and_delegation_endpoints_preserve_account_isolation(self):
        w = self.work()
        path = f"agent/works/{w['id']}"
        self.assertEqual(self.req('GET', path+'/memory-index').status_code, 200)
        self.assertEqual(self.req('GET', path+'/memories').json()['enabled'], False)
        self.assertEqual(self.req('GET', path+'/memories', user=self.b).status_code, 404)
        self.assertEqual(self.req('POST', path+'/memory-index/rebuild', {}, user=self.b).status_code, 404)
        event = self.upload('合成记忆隔离测试')
        task, _ = self.start(w, [event])
        self.wait(task['id'])
        self.assertEqual(self.req('GET', f"agent/tasks/{task['id']}/delegations").status_code, 200)
        self.assertEqual(self.req('GET', f"agent/tasks/{task['id']}/delegations", user=self.b).status_code, 404)

    def test_plan_is_account_scoped_and_hides_superseded_candidates(self):
        work, _, first = self.schedule()
        url = f"agent/works/{work['id']}/plan"
        self.assertEqual(self.req("GET", url, user=self.b).status_code, 404)
        self.assertEqual(self.client.get("/v1/" + url).status_code, 401)
        result = self.req("GET", url)
        self.assertEqual(result.headers["cache-control"], "no-store")
        plan = result.json()
        self.assertEqual(plan["version"], 0)
        self.assertEqual(plan["confirmed"], [])
        self.assertEqual(plan["candidate"]["task_id"], first["id"])
        self.assertEqual(plan["candidate"]["cards"][0]["value"], "组会")
        second, _ = self.start(work, [self.upload(json.dumps({
            'schedule_changes': [{'id':'other', 'title':'讨论', 'at':'2026-12-10T19:00:00+08:00'}]
        }))])
        second = self.wait(second["id"])
        self.assertEqual(self.req("GET", url).json()["candidate"]["task_id"], second["id"])
        body = self.response(first, "accept_state", proposal_id=first["interaction"]["proposal_id"])
        self.assertEqual(self.req("POST", f"agent/tasks/{first['id']}/responses", body).status_code, 202)
        plan = self.req("GET", url).json()
        self.assertEqual(plan["version"], 1)
        self.assertIsNone(plan["candidate"])
        self.assertEqual(plan["confirmed"][0]["value"], "组会")

    def test_card_opinion_uses_current_revision_without_confirming_state(self):
        work, _, first = self.schedule()
        url = f"agent/works/{work['id']}/plan"
        card = self.req("GET", url).json()["candidate"]["cards"][0]
        body = {"event_id": str(uuid4()), "card_id": card["id"], "version": 0,
                "text": "请把组会改晚一小时", "mode": "candidate", "task_id": first["id"],
                "revision": first["revision"], "card_version": first["card_version"]}
        self.assertEqual(self.req("POST", url+"/opinions", body, user=self.b).status_code, 404)
        self.assertEqual(self.req("POST", url+"/opinions", {**body, "revision": 90}).status_code, 409)
        changed = self.req("POST", url+"/opinions", body)
        self.assertEqual(changed.status_code, 202, changed.text)
        self.assertEqual(changed.json()["task"]["id"], first["id"])
        self.assertTrue(self.req("POST", url+"/opinions", body).json()["replayed"])
        self.assertEqual(self.req("GET", url).json()["version"],0)
        self.assertEqual(self.req("POST", url+"/opinions", {**body,"event_id":str(uuid4())}).status_code,409)

    def structured_event(self):
        text = '周五在A302讨论，记得带电脑'
        screen = {'event_id': 'evt-capture', 'session_id': 'sess-capture', 'app_package': 'com.tencent.mm',
            'app_name': '微信', 'page_title': '项目组', 'captured_at': '2026-11-13T12:00:00+08:00',
            'blocks': [{'id': 'bubble-1', 'kind': 'chat', 'speaker': '小林', 'text': text,
                        'message_at': '2026-11-13T11:58:00+08:00', 'bbox': [10, 20, 800, 70]}]}
        return {'event_id': str(uuid4()), 'revision': 1, 'event_type': 'message', 'source_kind': 'screenshot_ocr',
                'captured_at': screen['captured_at'], 'payload': {'text': text, 'screen': screen}}

    def test_structured_capture_preserves_messages_confirmation_and_isolation(self):
        event = self.structured_event()
        self.assertEqual(self.req('POST', 'events', event).status_code, 200)
        self.assertEqual(self.req('POST', 'events', event).status_code, 200)
        work = self.work()
        task, request = self.start(work, [event])
        self.assertEqual(self.req('POST', 'agent/tasks', request).json()['task']['id'], task['id'])
        settled = self.wait(task['id'])
        screen = settled['memory_context']['screens'][0]
        self.assertEqual(screen['app_package'], 'com.tencent.mm')
        self.assertEqual(screen['page_title'], '项目组')
        self.assertEqual(screen['blocks'][0]['speaker'], '小林')
        self.assertEqual(screen['blocks'][0]['bbox'], [10,20,800,70])
        self.assertIn('11:58:00', screen['blocks'][0]['message_at'])
        self.assertEqual(self.req('GET', 'agent/works/'+work['id']).json()['work']['memory_version'], 0)
        self.assertEqual(self.req('POST', 'agent/tasks', request, user=self.b).status_code, 404)
        output = settled['interaction']
        response = self.response(settled, 'accept_state', proposal_id=output['proposal_id'])
        self.assertEqual(self.req('POST', f"agent/tasks/{task['id']}/responses", response).status_code, 202)
        self.assertEqual(self.req('GET', 'agent/works/'+work['id']).json()['work']['memory_version'], 1)

    def test_structured_capture_rejects_drift_invalid_coordinates_and_fake_owner(self):
        for mutate in (
            lambda e: e['payload'].update(text='另一个版本的文字'),
            lambda e: e['payload']['screen']['blocks'][0].update(bbox=[0,0,1001,1]),
            lambda e: e['payload']['screen'].update(owner='another-user'),
            lambda e: e.update(source_kind='manual'),
            lambda e: e.update(captured_at='2026-11-12T12:00:00+08:00'),
        ):
            event = self.structured_event(); mutate(event)
            self.assertEqual(self.req('POST', 'events', event).status_code, 422)

    def test_structured_capture_retains_user_occurrence_time_without_replacing_message_time(self):
        event = self.structured_event()
        event['occurred_at'] = '2026-12-10T18:00:00+08:00'
        self.assertEqual(self.req('POST', 'events', event).status_code, 200)
        task,_ = self.start(self.work(),[event])
        settled = self.wait(task['id'])
        blocks = settled['memory_context']['screens'][0]['blocks']
        self.assertIn('11:58:00', blocks[0]['message_at'])
        self.assertTrue(any('2026-12-10T18:00:00+08:00' in b['text'] and '不是消息发送时间' in b['text'] for b in blocks))
        occurrence = next(field for field in settled['card']['fields'] if field['label'] == '已确认的发生时间')
        self.assertIn('2026年12月10日 18:00（UTC+08:00）', occurrence['value'])

    def test_async_flow_scope_and_retry_identity(self):
        w = self.work()
        e = self.upload('截图里的材料截止时间：2026年12月10日17:00。')
        task, body = self.start(w, [e])
        replay = self.req('POST', 'agent/tasks', body)
        self.assertEqual(replay.json()['task']['id'], task['id'])
        final = self.wait(task['id'])
        self.assertEqual(final['status'], 'waiting_input')
        self.assertEqual(final['interaction']['kind'], 'state_proposal')
        self.assertEqual(final['mobile_evidence'][0]['event_id'], e['event_id'])
        for path in ('agent/tasks/' + task['id'], 'agent/works/' + w['id']):
            self.assertEqual(self.req('GET', path, user=self.b).status_code, 404)
        self.assertEqual(self.req('GET', 'agent/tasks', user=self.b).json()['tasks'], [])
        self.assertEqual(self.req('POST', 'agent/tasks', body, user=self.b).status_code, 404)
        self.assertEqual(self.client.get('/v1/agent/tasks').status_code, 401)
        self.assertEqual(self.req('POST', 'agent/tasks', {**body, 'owner': self.b['user']['id']}).status_code, 422)

    def test_schedule_state_and_separate_calendar_authorization(self):
        hub = main.app.state.agent_hub
        hub.max_open, hub.idle_seconds = 1, 0
        w, event, task = self.schedule()
        self.assertEqual(task['interaction']['kind'], 'state_proposal')
        response = self.response(task, 'accept_state', proposal_id=task['interaction']['proposal_id'])
        self.assertEqual(self.req('POST', f"agent/tasks/{task['id']}/responses", response).status_code, 202)
        body = {'event_id': str(uuid4()), 'expected_state_version': 1, 'item_id': 'meeting', 'end': '2026-12-10T18:30:00+08:00'}
        preview = self.req('POST', f"agent/works/{w['id']}/action-proposals", body)
        self.assertEqual(preview.status_code, 201, preview.text)
        task = preview.json()['task']; aid = task['interaction']['action_id']
        self.assertEqual(self.req('GET', f'agent/actions/{aid}/calendar').status_code, 404)
        auth = self.response(task, 'authorize_action', action_id=aid)
        tool = hub.entries[self.a['user']['id']].app.state.actions.tools['calendar.export']
        export = tool.execute
        entered, release = threading.Event(), threading.Event()

        async def hold_export(action):
            entered.set()
            await asyncio.to_thread(release.wait, 10)
            return await export(action)

        with patch.object(tool, 'execute', hold_export):
            try:
                self.assertEqual(self.req('POST', f"agent/tasks/{task['id']}/responses", auth).status_code, 202)
                self.assertEqual(self.req('POST', f"agent/tasks/{task['id']}/responses", auth).status_code, 202)
                self.assertTrue(entered.wait(5))
                self.assertEqual(self.req('GET', 'agent/works', user=self.b).status_code, 503)
            finally:
                release.set()
        until = time.monotonic() + 5
        while time.monotonic() < until:
            result = self.req('GET', f'agent/actions/{aid}').json()['action']
            if result['status'] == 'succeeded': break
            time.sleep(.05)
        self.assertEqual(result['status'], 'succeeded')
        calendar = self.req('GET', f'agent/actions/{aid}/calendar').json()
        self.assertIn('SUMMARY:组会', calendar['content'])
        self.assertFalse(calendar['calendar_imported'])
        self.assertEqual(self.req('GET', f'agent/actions/{aid}/artifact', user=self.b).status_code, 404)

    def test_revision_update_invalidates_old_candidate_and_rejects_stale_input(self):
        w, event, task = self.schedule()
        event['revision'] = 2
        event['payload']['text'] = '时间有变化，待确认。'
        self.assertEqual(self.req('POST', 'events', event).status_code, 200)
        old = self.req('GET', 'agent/tasks/' + task['id']).json()['task']
        self.assertEqual(old['status'], 'failed')
        retry = {'event_id': str(uuid4()), 'expected_revision': old['revision'], 'kind': 'retry'}
        self.assertEqual(self.req('POST', f"agent/tasks/{old['id']}/actions", retry).status_code, 409)
        self.assertEqual(self.req('GET', 'agent/health').json()['status'], 'ok')
        response = self.response(task, 'accept_state', proposal_id=task['interaction']['proposal_id'])
        self.assertEqual(self.req('POST', f"agent/tasks/{task['id']}/responses", response).status_code, 409)
        body = {'request_id': str(uuid4()), 'work_id': w['id'], 'instruction': '整理', 'events': [{'event_id': event['event_id'], 'revision': 1}]}
        self.assertEqual(self.req('POST', 'agent/tasks', body).status_code, 409)
        self.assertEqual(self.req('GET', 'agent/works/' + w['id']).json()['work']['memory_version'], 0)
        # A health response alone cannot prove that the scheduler survived.
        other = self.work()
        next_task, _ = self.start(other, [event])
        self.assertEqual(self.wait(next_task['id'])['status'], 'waiting_input')

    def test_delete_persists_and_invalidates_after_restart(self):
        w, event, task = self.schedule()
        self.client.__exit__(None, None, None)
        self.client = TestClient(main.app).__enter__()
        self.assertEqual(self.req('DELETE', 'events/' + event['event_id']).status_code, 200)
        # Child engine is still unopened, durable invalidation must survive until its first request.
        old = self.req('GET', 'agent/tasks/' + task['id']).json()['task']
        self.assertEqual(old['status'], 'failed')
        with closing(sqlite3.connect(main.database_path())) as conn:
            self.assertEqual(conn.execute('SELECT count(*) FROM agent_invalidations').fetchone()[0], 0)

    def test_evidence_withdrawn_while_workspace_is_closed_invalidates_before_next_read(self):
        hub = main.app.state.agent_hub
        hub.max_open, hub.idle_seconds = 1, 0
        _, event, task = self.schedule()
        self.assertEqual(self.req('GET', 'agent/works', user=self.b).status_code, 200)
        self.assertNotIn(self.a['user']['id'], hub.entries)
        self.assertEqual(self.req('DELETE', 'events/' + event['event_id']).status_code, 200)
        self.assertNotIn(self.a['user']['id'], hub.entries)
        recovered = self.req('GET', 'agent/tasks/' + task['id'])
        self.assertEqual(recovered.status_code, 200)
        self.assertEqual(recovered.json()['task']['status'], 'failed')

    def test_clarification_answer_resumes_same_task(self):
        w = self.work('schedule')
        e = self.upload(json.dumps({'schedule_changes': [{'id': 'x', 'title': '组会'}]}))
        task, _ = self.start(w, [e]); task = self.wait(task['id'])
        self.assertEqual(task['interaction']['kind'], 'clarification')
        answer = self.response(task, 'answer', question_id=task['interaction']['question_id'],
            answer=json.dumps({'schedule_changes': [{'id': 'x', 'at': '2026-12-10T18:00:00+08:00'}]}))
        r = self.req('POST', f"agent/tasks/{task['id']}/responses", answer)
        self.assertEqual(r.status_code, 202, r.text)
        final = self.wait(task['id'])
        self.assertEqual(final['revision'], 2)
        self.assertEqual(final['interaction']['kind'], 'state_proposal')

    def test_text_chunks_and_occurrence_time_not_message_time(self):
        w = self.work()
        event = self.upload('甲' * 10000, occurred_at='2026-12-10T18:00:00+08:00', source_kind='calendar', event_type='schedule')
        task, _ = self.start(w, [event]); task = self.wait(task['id'])
        screens = task['memory_context']['screens']
        self.assertEqual(len(screens[0]['blocks']), 4)
        self.assertTrue(all(b['message_at'] is None for b in screens[0]['blocks']))
        self.assertEqual(sum(len(b['text']) for b in screens[0]['blocks'] if b['id'].startswith('text_')), 10000)

    def test_archived_phone_note_keeps_authored_words_and_displays_record_details(self):
        original = '周五还书，借书清单上写着 manual 和 note'
        event = self.upload(original, source_kind='manual', event_type='note')
        work = self.work()
        with patch('screenmind.agents.information.phone_excerpt', side_effect=lambda app,bid,text,label:(label,text)), \
                patch('screenmind.store.read_excerpt_labels', return_value=None):
            task, _ = self.start(work, [event])
            old = self.wait(task['id'])
            self.assertIn('来源渠道：manual', old['card']['fields'][-1]['value'])
        current = self.req('GET', 'agent/tasks/' + task['id']).json()['task']
        rows = current['card']['fields']
        self.assertEqual([(f['label'], f['value']) for f in rows],
                         [('ScreenMind · 正文摘录', original), ('记录信息', '你手动记下了这条备忘。')])
        self.assertEqual([f['source_refs'] for f in rows], [f['source_refs'] for f in old['card']['fields']])
        self.assertIn('来源渠道：manual', current['memory_context']['screens'][0]['blocks'][-1]['text'])
        self.assertEqual(self.req('GET', 'agent/tasks/'+task['id'], user=self.b).status_code, 404)

    def test_calendar_card_displays_confirmed_time_offset_without_changing_source_time(self):
        event = self.upload('去图书馆还书', source_kind='calendar', event_type='schedule',
                            occurred_at='2026-12-10T18:30:12+05:45', timezone='Asia/Kathmandu')
        task, _ = self.start(self.work(), [event])
        result = self.wait(task['id'])
        details = result['card']['fields'][-1]
        self.assertEqual(details['label'], '记录信息')
        self.assertIn('2026年12月10日 18:30:12（UTC+05:45）', details['value'])
        self.assertIn('系统日历', details['value'])
        self.assertIn('Asia/Kathmandu', result['memory_context']['screens'][0]['blocks'][-1]['text'])


if __name__ == '__main__':
    unittest.main()
