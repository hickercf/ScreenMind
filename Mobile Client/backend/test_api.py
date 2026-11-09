import os
import sqlite3
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from datetime import datetime, timezone
from uuid import uuid4

from fastapi.testclient import TestClient
from backend import main


class ApiTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.previous_db = os.environ.get('WEIGUANG_DB')
        os.environ['WEIGUANG_DB'] = self.temp.name + '/test.sqlite3'
        main.ATTEMPTS.clear()
        self.client = TestClient(main.app)
        self.client.__enter__()
        self.a = self.register('alice')
        self.b = self.register('bob')

    def tearDown(self):
        self.client.__exit__(None, None, None)
        if self.previous_db is None:
            os.environ.pop('WEIGUANG_DB', None)
        else:
            os.environ['WEIGUANG_DB'] = self.previous_db
        self.temp.cleanup()

    def register(self, username):
        response = self.client.post('/v1/auth/register', json={'username': username, 'password': 'test-password-123'})
        self.assertEqual(response.status_code, 201, response.text)
        return response.json()

    def headers(self, user):
        return {'Authorization': 'Bearer ' + user['access_token']}

    def request(self, user, method, path, data=None):
        return self.client.request(method, path, json=data, headers=self.headers(user))

    def event(self, text='明天开会'):
        return {'event_id': str(uuid4()), 'revision': 1, 'event_type': 'message',
                'source_kind': 'screenshot_ocr', 'captured_at': datetime.now(timezone.utc).isoformat(),
                'payload': {'text': text}}

    def group(self):
        return self.request(self.a, 'POST', '/v1/groups', {'name': '研发组'}).json()['id']

    def join(self, group):
        invitation = self.request(self.a, 'POST', f'/v1/groups/{group}/invites').json()
        response = self.request(self.b, 'POST', '/v1/groups/join', {'code': invitation['code']})
        self.assertEqual(response.status_code, 200)
        return invitation

    def generate(self, event):
        return self.request(self.a, 'POST', '/v1/decisions/generate', {'request_id': str(uuid4()), 'event_ids': [event['event_id']]})

    def test_real_password_and_case_insensitive_account(self):
        invalid = self.client.post('/v1/auth/login', json={'username': 'alice', 'password': 'wrong-password'})
        self.assertEqual(invalid.status_code, 401)
        valid = self.client.post('/v1/auth/login', json={'username': 'ALICE', 'password': 'test-password-123'})
        self.assertEqual(valid.status_code, 200)
        self.assertEqual(valid.json()['user']['id'], self.a['user']['id'])
        with closing(sqlite3.connect(main.database_path())) as conn, conn:
            password = conn.execute('SELECT password_hash FROM users WHERE username="alice"').fetchone()[0]
        self.assertTrue(password.startswith('$argon2id$'))

    def test_duplicate_registration_and_validation(self):
        duplicate = self.client.post('/v1/auth/register', json={'username': 'ALICE', 'password': '12345678'})
        self.assertEqual(duplicate.status_code, 409)
        weak = self.client.post('/v1/auth/register', json={'username': 'c', 'password': '123'})
        self.assertEqual(weak.status_code, 422)
        self.assertEqual(self.request(self.a, 'POST', '/v1/groups', {'name': '  '}).status_code, 422)

    def test_refresh_rotates_and_logout_revokes(self):
        old_refresh = self.a['refresh_token']
        rotated = self.client.post('/v1/auth/refresh', json={'refresh_token': old_refresh})
        self.assertEqual(rotated.status_code, 200)
        self.assertEqual(self.request(self.a, 'GET', '/v1/me').status_code, 401)
        self.assertEqual(self.client.post('/v1/auth/refresh', json={'refresh_token': old_refresh}).status_code, 401)
        self.a = rotated.json()
        self.assertEqual(self.request(self.a, 'POST', '/v1/auth/logout').status_code, 200)
        self.assertEqual(self.request(self.a, 'GET', '/v1/me').status_code, 401)
        self.assertEqual(self.client.post('/v1/auth/refresh', json={'refresh_token': self.a['refresh_token']}).status_code, 401)

    def test_same_group_does_not_share_private_events(self):
        group = self.group()
        self.join(group)
        event = self.event('仅 Alice 可见的消息')
        self.assertEqual(self.request(self.a, 'POST', '/v1/events', event).status_code, 200)
        self.assertEqual(self.request(self.b, 'GET', '/v1/events').json()['items'], [])
        self.assertEqual(self.request(self.b, 'GET', f'/v1/groups/{group}').json()['shared'], [])
        stolen = self.request(self.b, 'POST', '/v1/decisions/generate', {'request_id': str(uuid4()), 'event_ids': [event['event_id']]})
        self.assertEqual(stolen.status_code, 404)

    def test_explicit_share_and_revoke(self):
        group = self.group()
        self.join(group)
        event = self.event('本次明确共享的内容')
        self.request(self.a, 'POST', '/v1/events', event)
        self.assertEqual(self.request(self.b, 'POST', f'/v1/groups/{group}/shares', {'event_id': event['event_id']}).status_code, 404)
        self.assertEqual(self.request(self.a, 'POST', f'/v1/groups/{group}/shares', {'event_id': event['event_id']}).status_code, 200)
        items = self.request(self.b, 'GET', f'/v1/groups/{group}').json()['shared']
        self.assertEqual(items[0]['text'], event['payload']['text'])
        self.request(self.a, 'DELETE', f'/v1/groups/{group}/shares/{event["event_id"]}')
        self.assertEqual(self.request(self.b, 'GET', f'/v1/groups/{group}').json()['shared'], [])

    def test_owner_permission_and_member_removal(self):
        group = self.group()
        self.assertEqual(self.request(self.b, 'GET', f'/v1/groups/{group}').status_code, 403)
        self.join(group)
        self.assertEqual(self.request(self.b, 'POST', f'/v1/groups/{group}/invites').status_code, 403)
        self.assertEqual(self.request(self.b, 'DELETE', f'/v1/groups/{group}/members/{self.a["user"]["id"]}').status_code, 403)
        self.request(self.a, 'DELETE', f'/v1/groups/{group}/members/{self.b["user"]["id"]}')
        self.assertEqual(self.request(self.b, 'GET', f'/v1/groups/{group}').status_code, 403)

    def test_expired_invite(self):
        group = self.group()
        invite = self.request(self.a, 'POST', f'/v1/groups/{group}/invites').json()
        with closing(sqlite3.connect(main.database_path())) as conn, conn:
            conn.execute('UPDATE invites SET expires_at=0')
        self.assertEqual(self.request(self.b, 'POST', '/v1/groups/join', {'code': invite['code']}).status_code, 400)

    def test_concurrent_join_consumes_invite_once(self):
        group = self.group()
        invite = self.request(self.a, 'POST', f'/v1/groups/{group}/invites').json()
        def join_once(_):
            return self.request(self.b, 'POST', '/v1/groups/join', {'code': invite['code']}).status_code
        with ThreadPoolExecutor(max_workers=4) as pool:
            codes = list(pool.map(join_once, range(4)))
        self.assertEqual(codes, [200] * 4)
        with closing(sqlite3.connect(main.database_path())) as conn, conn:
            remaining = conn.execute('SELECT uses_left FROM invites').fetchone()[0]
        self.assertEqual(remaining, 4)

    def test_event_idempotency_version_and_stale_decision(self):
        event = self.event()
        self.assertEqual(self.request(self.a, 'POST', '/v1/events', event).status_code, 200)
        self.assertEqual(self.request(self.a, 'POST', '/v1/events', event).status_code, 200)
        self.assertEqual(len(self.request(self.a, 'GET', '/v1/events').json()['items']), 1)
        decision = self.generate(event).json()
        event['payload']['text'] = '改为后天'
        self.assertEqual(self.request(self.a, 'POST', '/v1/events', event).status_code, 409)
        event['revision'] = 2
        self.assertEqual(self.request(self.a, 'POST', '/v1/events', event).status_code, 200)
        self.assertEqual(self.request(self.a, 'GET', '/v1/decisions').json()['items'][0]['status'], 'stale')
        self.assertEqual(self.request(self.a, 'PUT', f'/v1/decisions/{decision["decision_id"]}/feedback', {'action': 'accepted'}).status_code, 409)

    def test_generation_retry_and_feedback_isolation(self):
        event = self.event('已确认的材料')
        self.request(self.a, 'POST', '/v1/events', event)
        request = {'request_id': str(uuid4()), 'event_ids': [event['event_id']]}
        first = self.request(self.a, 'POST', '/v1/decisions/generate', request).json()
        second = self.request(self.a, 'POST', '/v1/decisions/generate', request).json()
        self.assertEqual(first['decision_id'], second['decision_id'])
        self.assertEqual(first['provider'], 'rule_demo')
        path = f'/v1/decisions/{first["decision_id"]}/feedback'
        self.assertEqual(self.request(self.b, 'PUT', path, {'action': 'accepted'}).status_code, 404)
        self.assertEqual(self.request(self.a, 'PUT', path, {'action': 'accepted'}).status_code, 200)
        self.assertEqual(self.request(self.a, 'PUT', path, {'action': 'accepted'}).status_code, 200)
        self.assertEqual(self.request(self.a, 'GET', '/v1/decisions').json()['items'][0]['feedback'], 'accepted')

    def test_delete_removes_shares_and_invalidates_evidence(self):
        group = self.group()
        self.join(group)
        event = self.event()
        self.request(self.a, 'POST', '/v1/events', event)
        self.generate(event)
        self.request(self.a, 'POST', f'/v1/groups/{group}/shares', {'event_id': event['event_id']})
        self.request(self.a, 'DELETE', f'/v1/events/{event["event_id"]}')
        self.assertEqual(self.request(self.b, 'GET', f'/v1/groups/{group}').json()['shared'], [])
        self.assertEqual(self.request(self.a, 'GET', '/v1/decisions').json()['items'][0]['status'], 'stale')

    def test_rejects_naive_time_and_claimed_owner(self):
        event = self.event()
        event['captured_at'] = '2026-11-08T09:00:00'
        self.assertEqual(self.request(self.a, 'POST', '/v1/events', event).status_code, 422)
        event = self.event()
        event['user_id'] = self.b['user']['id']
        self.assertEqual(self.request(self.a, 'POST', '/v1/events', event).status_code, 422)

    def test_missing_credentials_and_rate_limit(self):
        self.assertEqual(self.client.get('/v1/events').status_code, 401)
        for _ in range(12):
            self.client.post('/v1/auth/login', json={'username': 'alice', 'password': 'wrong-password'})
        last = self.client.post('/v1/auth/login', json={'username': 'alice', 'password': 'wrong-password'})
        self.assertEqual(last.status_code, 429)


if __name__ == '__main__':
    unittest.main()
