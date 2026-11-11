"""Authenticated mobile API -> real shared Agent runtime, synthetic input, no model bills."""
import json
import os
from pathlib import Path
import sqlite3
import tempfile
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
        self.assertEqual(self.req('POST', f"agent/tasks/{task['id']}/responses", auth).status_code, 202)
        self.assertEqual(self.req('POST', f"agent/tasks/{task['id']}/responses", auth).status_code, 202)
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


if __name__ == '__main__':
    unittest.main()
