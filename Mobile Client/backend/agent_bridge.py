"""Authenticated gateway to isolated instances of the shared Cloud Agent engine.

No client-supplied owner, no model calls inside the mobile database transaction.
The legacy /v1/decisions rule provider remains an explicitly labeled compatibility path.
"""
import asyncio
from contextlib import AsyncExitStack, asynccontextmanager, closing
from dataclasses import dataclass, field, replace
import hashlib
import json
import os
from pathlib import Path
import secrets
import sqlite3
from time import monotonic
from uuid import UUID

import httpx
from fastapi import APIRouter, Depends, FastAPI, Header, HTTPException, Request, Response, Query
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import Field
from screenmind.api import create_app
from screenmind.config import Settings
from screenmind.models import StrictModel, Identifier, Text, WorkInput, ActionInput
from screenmind.interactions import InteractionResponse
from screenmind.actions import CalendarRequest
from screenmind.plan_view import PlanOpinion


class EventRef(StrictModel):
    event_id: UUID
    revision: int = Field(ge=1)


class TaskRequest(StrictModel):
    request_id: UUID
    work_id: Identifier
    instruction: Text
    events: list[EventRef] = Field(min_length=1, max_length=10)


@dataclass
class Workspace:
    app: FastAPI
    client: httpx.AsyncClient
    lifetime: AsyncExitStack
    last_used: float


@dataclass
class AccountRequests:
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    count: int = 0


class AgentHub:
    max_open = 8
    idle_seconds = 60

    def __init__(self, database_path, settings=None):
        self.database_path = database_path
        self.settings = settings or Settings.from_env()
        self.root = Path(os.getenv('WEIGUANG_AGENT_ROOT', str(database_path().parent / 'agents'))).resolve()
        self.entries = {}
        self.locks = {}
        self.open_lock = asyncio.Lock()

    @asynccontextmanager
    async def lock(self, uid):
        requests = self.locks.setdefault(uid, AccountRequests())
        requests.count += 1
        try:
            async with requests.lock:
                yield
        finally:
            requests.count -= 1
            if not requests.count:
                del self.locks[uid]
            if workspace := self.entries.get(uid):
                workspace.last_used = monotonic()

    def connect(self):
        conn = sqlite3.connect(self.database_path(), timeout=15)
        conn.row_factory = sqlite3.Row
        return conn

    async def restore_existing(self, uid):
        path = self.root / hashlib.sha256(uid.encode()).hexdigest() / 'state.sqlite3'
        if uid in self.entries or path.is_file():
            await self.ready(uid)

    async def entry(self, uid):
        if workspace := self.entries.get(uid):
            return workspace.app, workspace.client
        async with self.open_lock:
            if workspace := self.entries.get(uid):
                return workspace.app, workspace.client
            if len(self.entries) >= self.max_open:
                await self.release_idle()
            root = self.root / hashlib.sha256(uid.encode()).hexdigest()
            key = secrets.token_urlsafe(32)
            cfg = replace(self.settings, owner=uid, db_path=root / 'state.sqlite3',
                index_path=root / 'index', action_path=root / 'exports', api_key=key,
                allowed_hosts=('agent.internal',), caldav_url='', caldav_username='', caldav_password='')
            child = create_app(cfg)
            async with AsyncExitStack() as starting:
                await starting.enter_async_context(child.router.lifespan_context(child))
                client = await starting.enter_async_context(httpx.AsyncClient(
                    transport=httpx.ASGITransport(app=child), base_url='http://agent.internal',
                    headers={'Authorization': 'Bearer ' + key}, timeout=40))
                child.state.store.db.execute('''CREATE TABLE IF NOT EXISTS mobile_evidence(
                    event_id TEXT NOT NULL, revision INTEGER NOT NULL, work_id TEXT NOT NULL,
                    screen_id TEXT NOT NULL, PRIMARY KEY(event_id,revision,work_id))''')
                self.entries[uid] = Workspace(child, client, starting.pop_all(), monotonic())
            return child, client

    async def release_idle(self):
        cutoff = monotonic() - self.idle_seconds
        for uid, workspace in sorted(self.entries.items(), key=lambda item: item[1].last_used):
            if uid in self.locks or workspace.last_used > cutoff:
                continue
            store = workspace.app.state.store
            if any(not task.done() for task in workspace.app.state.runtime.running.values()):
                continue
            if store.db.execute("""SELECT 1 FROM tasks WHERE owner=? AND status IN ('queued','running')
                UNION ALL SELECT 1 FROM external_actions WHERE owner=? AND status IN ('authorized','executing','reconciling')
                LIMIT 1""", (uid, uid)).fetchone():
                continue
            if store.memory_service and (store.memory_service.lock.locked() or store.db.execute(
                """SELECT 1 FROM memory_jobs WHERE owner=? AND
                (status='running' OR (status IN ('pending','delete_pending') AND attempts<3)) LIMIT 1""", (uid,)
            ).fetchone()):
                continue
            # Remove before yielding. A concurrent request for this account will join open_lock.
            del self.entries[uid]
            closing_task = asyncio.create_task(workspace.lifetime.aclose())
            try:
                await asyncio.shield(closing_task)
            except asyncio.CancelledError:
                await closing_task
                raise
            return
        raise HTTPException(503, '云端正在处理其他请求，请稍后再试。', headers={'Retry-After': '5'})

    async def close(self):
        async with self.open_lock, AsyncExitStack() as closing_workspaces:
            for workspace in self.entries.values():
                closing_workspaces.push_async_callback(workspace.lifetime.aclose)
            self.entries.clear()

    async def call(self, uid, method, path, body=None):
        _, client = await self.entry(uid)
        response = await client.request(method, '/api/' + path, json=body)
        if response.status_code >= 400:
            raise HTTPException(response.status_code, response.json().get('detail', 'Agent 请求未完成'))
        return response

    async def invalidate(self, uid):
        # Durable outbox is created in the SAME transaction as the event edit/delete.
        if uid not in self.entries:
            return
        child = self.entries[uid].app
        store = child.state.store
        with closing(self.connect()) as conn:
            jobs = conn.execute('SELECT seq,event_id,revision FROM agent_invalidations WHERE user_id=? ORDER BY seq', (uid,)).fetchall()
        for job in jobs:
            rows = store.db.execute('SELECT screen_id FROM mobile_evidence WHERE event_id=? AND (revision<>? OR ? IS NULL)',
                (job['event_id'], job['revision'], job['revision'])).fetchall()
            for row in rows:
                try:
                    affected = store.delete_screen(row['screen_id'])
                except Exception as exc:
                    from screenmind.store import DomainError
                    if not isinstance(exc, DomainError) or exc.status != 404:
                        raise
                    affected = []
                for tid in affected:
                    child.state.runtime.interrupt(tid)
            with closing(self.connect()) as conn, conn:
                conn.execute('DELETE FROM agent_invalidations WHERE seq=? AND user_id=?', (job['seq'], uid))

    async def ready(self, uid):
        await self.entry(uid)
        await self.invalidate(uid)

    async def create_task(self, uid, data):
        child, _ = await self.entry(uid)
        store = child.state.store
        # Validate owner/work before importing any source; the work is bound to its plugin.
        await self.call(uid, 'GET', 'works/' + data.work_id)
        refs = [e.model_dump(mode='json') for e in data.events]
        if len({r['event_id'] for r in refs}) != len(refs):
            raise HTTPException(422, '同一次任务不能重复选择一条线索')
        with closing(self.connect()) as conn:
            selected = []
            for ref in refs:
                row = conn.execute('SELECT body,revision FROM events WHERE user_id=? AND event_id=?', (uid, ref['event_id'])).fetchone()
                if not row:
                    raise HTTPException(404, '线索不存在或不属于当前用户')
                if row['revision'] != ref['revision']:
                    raise HTTPException(409, '线索版本已变化，请同步后重新选择')
                selected.append(json.loads(row['body']))
        screens = []
        for event in selected:
            row = store.db.execute('SELECT screen_id FROM mobile_evidence WHERE event_id=? AND revision=? AND work_id=?',
                (event['event_id'], event['revision'], data.work_id)).fetchone()
            if row:
                screens.append(row[0])
                continue
            screen = event['payload'].get('screen')
            if screen is not None and event.get('occurred_at'):
                metadata = '用户核对的事项发生时间：' + event['occurred_at'] + '；时区：' + event['timezone'] + '。这不是消息发送时间。'
                blocks = list(screen['blocks'])
                if len(blocks) >= 60 or sum(len(b['text']) for b in blocks)+len(metadata) > 24000:
                    raise HTTPException(422, '消息已达输入上限，请把确定时间作为独立线索补充')
                key = 'capture_event_time'
                while key in {b['id'] for b in blocks}: key += '_'
                screen = {**screen, 'blocks': blocks + [{'id': key, 'kind': 'key_value', 'text': metadata}]}
            if screen is None:
                text = event['payload']['text']
                blocks = [{'id': f'text_{i//4000}', 'kind': 'paragraph', 'text': text[i:i+4000]} for i in range(0, len(text), 4000)]
                # occurred_at means event occurrence, NEVER message publication time.
                blocks.append({'id': 'metadata', 'kind': 'key_value', 'text':
                    f"用户确认的来源渠道：{event['source_kind']}；语义类型：{event['event_type']}；时区：{event['timezone']}；"
                    f"事项发生时间：{event.get('occurred_at') or '未知'}。此时间不是消息发送时间，采集时间也不是消息发送时间。"})
                screen = {'session_id': data.work_id, 'app_package': 'cn.weiguang.app', 'app_name': '微光 · 用户确认线索',
                    'page_title': event['event_type'], 'captured_at': event['captured_at'], 'blocks': blocks}
            # The authenticated owner/work boundary is decided here, never by the capture client.
            source = (await self.call(uid, 'POST', 'screens', {**screen,
                'event_id': f"mobile:{data.work_id}:{event['event_id']}:{event['revision']}"})).json()['screen']
            store.db.execute('INSERT INTO mobile_evidence VALUES(?,?,?,?)',
                (event['event_id'], event['revision'], data.work_id, source['id']))
            screens.append(source['id'])
        return (await self.call(uid, 'POST', 'tasks', {'event_id': str(data.request_id), 'work_id': data.work_id,
            'instruction': data.instruction, 'screen_ids': screens, 'interaction_protocol': 'cloud-v1'})).json()

    def decorate(self, uid, task):
        store = self.entries[uid].app.state.store
        ids = set(task['screen_ids'])
        bundle = task.get('memory_context')
        if bundle:
            from screenmind.memory import ContextBuilder
            ids.update(r.split(':', 1)[0] for r in ContextBuilder.source_refs(bundle) if r.startswith('scr_'))
        task['mobile_evidence'] = [dict(r) for r in store.db.execute('SELECT event_id,revision,screen_id FROM mobile_evidence') if r['screen_id'] in ids]
        return task


def install(app, main):
    plan_assets = Path(__file__).with_name("plan_web")

    @app.get("/plans", include_in_schema=False)
    async def plan_page():
        return FileResponse(plan_assets / "index.html", headers={
            "Cache-Control": "no-store",
            "Content-Security-Policy": "default-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
            "Referrer-Policy": "no-referrer",
        })

    app.mount("/plan-assets", StaticFiles(directory=plan_assets), name="plan_assets")
    router = APIRouter(prefix='/v1/agent', tags=['Agent integration'])

    def authenticated_user(authorization: str | None = Header(default=None)):
        # Read and close auth connection before any await. Do not inherit BEGIN IMMEDIATE.
        with closing(sqlite3.connect(main.database_path())) as conn:
            conn.row_factory = sqlite3.Row
            return main.session_user(authorization, conn)

    async def forward(request, user, method, path, body=None):
        hub = request.app.state.agent_hub
        async with hub.lock(user['id']):
            await hub.ready(user['id'])
            result = (await hub.call(user['id'], method, path, body)).json()
            if 'task' in result:
                result['task'] = hub.decorate(user['id'], result['task'])
            return result

    @router.get('/health')
    async def health(request: Request, user=Depends(authenticated_user)):
        return await forward(request, user, 'GET', 'health')

    @router.get('/subagents')
    async def subagents(request: Request, user=Depends(authenticated_user)):
        return await forward(request, user, 'GET', 'subagents')

    @router.get('/works')
    async def works(request: Request, user=Depends(authenticated_user)):
        return await forward(request, user, 'GET', 'works')

    @router.post('/works', status_code=201)
    async def create_work(body: WorkInput, request: Request, user=Depends(authenticated_user)):
        return await forward(request, user, 'POST', 'works', body.model_dump(mode='json'))

    @router.get('/works/{wid}')
    async def work(wid: Identifier, request: Request, user=Depends(authenticated_user)):
        return await forward(request, user, 'GET', 'works/' + wid)

    @router.get('/works/{wid}/plan')
    async def plan(wid: Identifier, request: Request, response: Response, user=Depends(authenticated_user)):
        response.headers["Cache-Control"] = "no-store"
        plan = await forward(request, user, 'GET', f'works/{wid}/plan')
        return {**plan, 'supports_opinions': True}

    @router.post('/works/{wid}/plan/opinions', status_code=202)
    async def plan_opinion(wid: Identifier, body: PlanOpinion, request: Request, user=Depends(authenticated_user)):
        return await forward(request, user, 'POST', f'works/{wid}/plan/opinions', body.model_dump(mode="json"))

    @router.get('/works/{wid}/memory-index')
    async def memory_status(wid: Identifier, request: Request, user=Depends(authenticated_user)):
        return await forward(request, user, 'GET', f'works/{wid}/memory-index')

    @router.post('/works/{wid}/memory-index/rebuild', status_code=202)
    async def rebuild_memory(wid: Identifier, request: Request, user=Depends(authenticated_user)):
        return await forward(request, user, 'POST', f'works/{wid}/memory-index/rebuild', {})

    @router.get('/works/{wid}/memories')
    async def memories(wid: Identifier, request: Request, limit: int = Query(default=20, ge=1, le=50),
                       after: str = Query(default='', max_length=200), user=Depends(authenticated_user)):
        from urllib.parse import urlencode
        return await forward(request, user, 'GET', f'works/{wid}/memories?' + urlencode({'limit': limit, 'after': after}))

    @router.post('/tasks', status_code=202)
    async def task(body: TaskRequest, request: Request, user=Depends(authenticated_user)):
        hub = request.app.state.agent_hub
        async with hub.lock(user['id']):
            await hub.ready(user['id'])
            result = await hub.create_task(user['id'], body)
            result['task'] = hub.decorate(user['id'], result['task'])
            return result

    @router.get('/tasks')
    async def tasks(request: Request, user=Depends(authenticated_user)):
        return await forward(request, user, 'GET', 'tasks')

    @router.get('/tasks/{tid}')
    async def get_task(tid: Identifier, request: Request, user=Depends(authenticated_user)):
        return await forward(request, user, 'GET', 'tasks/' + tid)

    @router.get('/tasks/{tid}/delegations')
    async def delegations(tid: Identifier, request: Request, user=Depends(authenticated_user)):
        return await forward(request, user, 'GET', f'tasks/{tid}/delegations')

    @router.post('/tasks/{tid}/responses', status_code=202)
    async def respond(tid: Identifier, body: InteractionResponse, request: Request, user=Depends(authenticated_user)):
        return await forward(request, user, 'POST', f'tasks/{tid}/responses', body.model_dump(mode='json'))

    @router.post('/tasks/{tid}/actions', status_code=202)
    async def action(tid: Identifier, body: ActionInput, request: Request, user=Depends(authenticated_user)):
        return await forward(request, user, 'POST', f'tasks/{tid}/actions', body.model_dump(mode='json'))

    @router.post('/works/{wid}/action-proposals', status_code=201)
    async def propose_action(wid: Identifier, body: CalendarRequest, request: Request, user=Depends(authenticated_user)):
        return await forward(request, user, 'POST', f'works/{wid}/action-proposals', body.model_dump(mode='json'))

    @router.get('/actions/{aid}')
    async def get_action(aid: Identifier, request: Request, user=Depends(authenticated_user)):
        return await forward(request, user, 'GET', 'actions/' + aid)

    @router.post('/actions/{aid}/reconcile')
    async def reconcile(aid: Identifier, request: Request, user=Depends(authenticated_user)):
        return await forward(request, user, 'POST', f'actions/{aid}/reconcile', {})

    @router.get('/actions/{aid}/artifact')
    async def artifact(aid: Identifier, request: Request, user=Depends(authenticated_user)):
        hub = request.app.state.agent_hub
        async with hub.lock(user['id']):
            await hub.ready(user['id'])
            result = await hub.call(user['id'], 'GET', f'actions/{aid}/artifact')
            return Response(result.content, media_type='text/calendar', headers={'Content-Disposition': f'attachment; filename="{aid}.ics"'})

    @router.get('/actions/{aid}/calendar')
    async def calendar(aid: Identifier, request: Request, user=Depends(authenticated_user)):
        hub = request.app.state.agent_hub
        async with hub.lock(user['id']):
            await hub.ready(user['id'])
            result = await hub.call(user['id'], 'GET', f'actions/{aid}/artifact')
            return {'filename': aid + '.ics', 'content': result.content.decode('utf-8'), 'calendar_imported': False}

    app.include_router(router)
    return authenticated_user
