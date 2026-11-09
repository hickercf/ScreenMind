"""Isolated mobile gateway smoke test. --real --run permits synthetic model calls."""
import argparse
from dataclasses import replace
import json
import os
from pathlib import Path
import sys
import time
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi.testclient import TestClient
from screenmind.config import Settings


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--real', action='store_true')
    parser.add_argument('--run', action='store_true')
    parser.add_argument('--env-file')
    args = parser.parse_args()
    if args.real and not args.run:
        parser.error('--real requires --run to permit model calls')
    cfg = Settings.from_env(args.env_file) if args.real else Settings()
    if args.real and cfg.provider != 'openai-compatible':
        parser.error('Configure a real model')
    root = Path('artifacts') / ('gateway-live-' + uuid4().hex)
    root.mkdir(parents=True)
    os.environ['WEIGUANG_DB'] = str(root / 'mobile.sqlite3')
    os.environ['WEIGUANG_AGENT_ROOT'] = str(root / 'agents')
    from backend import main as backend
    report = {'passed': False, 'synthetic': True, 'provider': cfg.provider, 'model': cfg.model, 'stages': []}
    with TestClient(backend.app) as client:
        client.app.state.agent_hub.settings = replace(cfg, memory_mode='basic', memory_extractor='raw', caldav_url='')
        user = client.post('/v1/auth/register', json={'username': 'smoke_' + uuid4().hex[:12], 'password': uuid4().hex}).json()
        headers = {'Authorization': 'Bearer ' + user['access_token']}
        def request(method, path, body=None):
            r = client.request(method, '/v1/' + path, json=body, headers=headers)
            if r.status_code >= 400:
                raise RuntimeError(f'{path}: HTTP {r.status_code}')
            return r.json()
        def wait(tid):
            start = time.monotonic()
            while time.monotonic()-start < cfg.task_timeout + 35:
                task = request('GET', 'agent/tasks/' + tid)['task']
                if task['status'] not in ('queued', 'running'):
                    report['stages'].append({'status': task['status'], 'kind': (task.get('interaction') or {}).get('kind'), 'seconds': round(time.monotonic()-start,2)})
                    return task
                time.sleep(.2)
            raise TimeoutError('Agent smoke timed out')
        work = request('POST', 'agent/works', {'event_id': str(uuid4()), 'title': '手机网关合成验证', 'subagent_id': 'schedule'})['work']
        text = '组会安排在2026年12月10日18:00，时区UTC+8。' if args.real else json.dumps({'schedule_changes': [{'id': 'meeting', 'title': '组会', 'at': '2026-12-10T18:00:00+08:00'}]})
        event = {'event_id': str(uuid4()), 'revision': 1, 'event_type': 'message', 'source_kind': 'screenshot_ocr',
            'captured_at': '2026-11-09T12:00:00+08:00', 'payload': {'text': text}}
        request('POST', 'events', event)
        task = request('POST', 'agent/tasks', {'request_id': str(uuid4()), 'work_id': work['id'],
            'instruction': '将这项明确日程整理为候选，等待我确认。', 'events': [{'event_id': event['event_id'], 'revision': 1}]})['task']
        task = wait(task['id'])
        assert task['interaction']['kind'] == 'state_proposal'
        def respond(task, kind, **target):
            return request('POST', f"agent/tasks/{task['id']}/responses", {'event_id': str(uuid4()), 'expected_revision': task['revision'],
                'output_version': task['interaction']['output_version'], 'kind': kind, **target})
        respond(task, 'accept_state', proposal_id=task['interaction']['proposal_id'])
        work = request('GET', 'agent/works/' + work['id'])['work']
        item = next(iter(work['state']['items']))
        assert work['memory_version'] == 1
        preview = request('POST', f"agent/works/{work['id']}/action-proposals", {'event_id': str(uuid4()), 'expected_state_version': 1,
            'item_id': item, 'end': '2026-12-10T18:30:00+08:00'})['task']
        aid = preview['interaction']['action_id']
        respond(preview, 'authorize_action', action_id=aid)
        until = time.monotonic()+10
        while time.monotonic()<until:
            receipt = request('GET', 'agent/actions/' + aid)['action']
            if receipt['status'] == 'succeeded': break
            time.sleep(.1)
        assert receipt['status'] == 'succeeded'
        file = request('GET', f'agent/actions/{aid}/calendar')
        assert not file['calendar_imported'] and 'BEGIN:VEVENT' in file['content']
        report.update(passed=True, work_id=work['id'], state_version=1, action_status=receipt['status'], calendar_imported=False)
        (root / 'calendar.ics').write_text(file['content'], encoding='utf-8')
    Path('artifacts/agent-integration-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
