"""Authenticated mobile gateway: legacy rule cards and isolated Cloud Agent runtimes."""
import hashlib
import json
import os
import re
import secrets
import sqlite3
import threading
import time
from collections import defaultdict, deque
from contextlib import asynccontextmanager, closing
from datetime import datetime
from pathlib import Path
from typing import Literal
from uuid import UUID, uuid4

from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field, field_validator

PASSWORDS = PasswordHasher()
DUMMY_HASH = PASSWORDS.hash(secrets.token_urlsafe(32))
RATE_LOCK = threading.Lock()
ATTEMPTS = defaultdict(deque)

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
 id TEXT PRIMARY KEY, username TEXT UNIQUE NOT NULL, password_hash TEXT NOT NULL, created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
 id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id),
 access_hash TEXT UNIQUE NOT NULL, refresh_hash TEXT UNIQUE NOT NULL,
 access_until INTEGER NOT NULL, refresh_until INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS groups (
 id TEXT PRIMARY KEY, name TEXT NOT NULL, owner_id TEXT NOT NULL REFERENCES users(id)
);
CREATE TABLE IF NOT EXISTS memberships (
 group_id TEXT NOT NULL REFERENCES groups(id), user_id TEXT NOT NULL REFERENCES users(id),
 role TEXT NOT NULL, PRIMARY KEY(group_id,user_id)
);
CREATE TABLE IF NOT EXISTS invites (
 code_hash TEXT PRIMARY KEY, group_id TEXT NOT NULL REFERENCES groups(id), expires_at INTEGER NOT NULL, uses_left INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS events (
 user_id TEXT NOT NULL REFERENCES users(id), event_id TEXT NOT NULL, revision INTEGER NOT NULL,
 body TEXT NOT NULL, PRIMARY KEY(user_id,event_id)
);
CREATE TABLE IF NOT EXISTS decisions (
 user_id TEXT NOT NULL REFERENCES users(id), decision_id TEXT NOT NULL,
 request_id TEXT NOT NULL, body TEXT NOT NULL, PRIMARY KEY(user_id,decision_id), UNIQUE(user_id,request_id)
);
CREATE TABLE IF NOT EXISTS feedback (
 user_id TEXT NOT NULL REFERENCES users(id), decision_id TEXT NOT NULL, action TEXT NOT NULL,
 reason TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(user_id,decision_id)
);
CREATE TABLE IF NOT EXISTS shared_events (
 share_id TEXT PRIMARY KEY, group_id TEXT NOT NULL REFERENCES groups(id), user_id TEXT NOT NULL REFERENCES users(id),
 event_id TEXT NOT NULL, text TEXT NOT NULL, created_at INTEGER NOT NULL, UNIQUE(group_id,user_id,event_id)
);
CREATE TABLE IF NOT EXISTS agent_invalidations (
 seq INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL, event_id TEXT NOT NULL, revision INTEGER
);
"""


def database_path():
    return Path(os.getenv('WEIGUANG_DB', str(Path(__file__).with_name('weiguang.sqlite3'))))


def initialize():
    path = database_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    with closing(sqlite3.connect(path)) as conn, conn:
        conn.execute('PRAGMA journal_mode=WAL')
        conn.executescript(SCHEMA)


@asynccontextmanager
async def lifespan(app):
    initialize()
    from .agent_bridge import AgentHub
    from screenmind.api import ProcessLock
    lock = ProcessLock(database_path().with_suffix('.gateway'))
    app.state.agent_hub = AgentHub(database_path)
    try:
        yield
    finally:
        await app.state.agent_hub.close()
        lock.close()


app = FastAPI(title='微光 + ScreenMind Agent API', version='0.2.0', lifespan=lifespan)


def db():
    conn = sqlite3.connect(database_path(), timeout=15, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute('PRAGMA foreign_keys=ON')
    conn.execute('BEGIN IMMEDIATE')
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def now():
    return int(time.time())


def throttle(request, operation, limit=12):
    key = (request.client.host if request.client else 'local', operation)
    current = time.monotonic()
    with RATE_LOCK:
        queue = ATTEMPTS[key]
        while queue and queue[0] < current - 60:
            queue.popleft()
        if len(queue) >= limit:
            raise HTTPException(429, '操作过于频繁，请稍后再试')
        queue.append(current)


class StrictModel(BaseModel):
    model_config = ConfigDict(extra='forbid')


class Credentials(StrictModel):
    username: str = Field(min_length=3, max_length=32, pattern=r'^[A-Za-z0-9_]+$')
    password: str = Field(min_length=8, max_length=128)


class Refresh(StrictModel):
    refresh_token: str = Field(min_length=20, max_length=256)


class GroupInput(StrictModel):
    name: str = Field(min_length=1, max_length=40)

    @field_validator('name')
    @classmethod
    def nonblank(cls, value):
        if not value.strip():
            raise ValueError('组名不能为空')
        return value.strip()


class InviteInput(StrictModel):
    code: str = Field(min_length=6, max_length=32)


class Payload(StrictModel):
    text: str = Field(min_length=1, max_length=10000)

    @field_validator('text')
    @classmethod
    def nonblank(cls, value):
        if not value.strip():
            raise ValueError('线索不能为空')
        return value.strip()


class ContextEvent(StrictModel):
    schema_version: Literal[1] = 1
    event_id: UUID
    revision: int = Field(default=1, ge=1)
    event_type: Literal['message', 'schedule', 'location', 'note']
    source_kind: Literal['screenshot_ocr', 'manual', 'calendar', 'location']
    captured_at: datetime
    occurred_at: datetime | None = None
    timezone: str = Field(default='Asia/Shanghai', max_length=80)
    review_status: Literal['confirmed'] = 'confirmed'
    payload: Payload

    @field_validator('captured_at', 'occurred_at')
    @classmethod
    def aware_time(cls, value):
        if value is not None and value.utcoffset() is None:
            raise ValueError('时间必须包含时区偏移')
        return value


class DecisionRequest(StrictModel):
    request_id: UUID
    event_ids: list[UUID] = Field(min_length=1, max_length=10)


class FeedbackInput(StrictModel):
    action: Literal['accepted', 'dismissed']
    reason: str = Field(default='', max_length=500)


class ShareInput(StrictModel):
    event_id: UUID


def session_user(authorization: str | None = Header(default=None), conn=Depends(db)):
    if not authorization or not authorization.startswith('Bearer '):
        raise HTTPException(401, '请先登录')
    token_hash = digest(authorization[7:])
    row = conn.execute('SELECT u.id,u.username,s.id AS session_id FROM sessions s JOIN users u ON u.id=s.user_id WHERE access_hash=? AND access_until>? AND refresh_until>?', (token_hash, now(), now())).fetchone()
    if not row:
        raise HTTPException(401, '登录已过期，请重新登录')
    return dict(row)


def short_session_user(authorization: str | None = Header(default=None)):
    with closing(sqlite3.connect(database_path())) as conn:
        conn.row_factory = sqlite3.Row
        return session_user(authorization, conn)


def issue_session(conn, user_id, username, session_id=None):
    access = secrets.token_urlsafe(32)
    refresh = secrets.token_urlsafe(48)
    session_id = session_id or str(uuid4())
    conn.execute('INSERT OR REPLACE INTO sessions VALUES (?,?,?,?,?,?)',
                 (session_id, user_id, digest(access), digest(refresh), now() + 900, now() + 604800))
    return {'access_token': access, 'refresh_token': refresh, 'expires_in': 900,
            'user': {'id': user_id, 'username': username}}


def require_member(conn, group_id, user, owner=False):
    member = conn.execute('SELECT role FROM memberships WHERE group_id=? AND user_id=?', (group_id, user['id'])).fetchone()
    if not member or (owner and member['role'] != 'owner'):
        raise HTTPException(403, '没有此测试组的操作权限')
    return member


@app.get('/health')
def health():
    return {'status': 'ok', 'decision_provider': 'rule_demo', 'schema_version': 1, 'agent_api': '/v1/agent'}


@app.post('/v1/auth/register', status_code=201)
def register(data: Credentials, request: Request, conn=Depends(db)):
    throttle(request, 'register', 6)
    username = data.username.lower()
    user_id = str(uuid4())
    try:
        conn.execute('INSERT INTO users VALUES (?,?,?,?)', (user_id, username, PASSWORDS.hash(data.password), now()))
    except sqlite3.IntegrityError:
        raise HTTPException(409, '账号已存在')
    return issue_session(conn, user_id, username)


@app.post('/v1/auth/login')
def login(data: Credentials, request: Request, conn=Depends(db)):
    throttle(request, 'login')
    row = conn.execute('SELECT * FROM users WHERE username=?', (data.username.lower(),)).fetchone()
    try:
        PASSWORDS.verify(row['password_hash'] if row else DUMMY_HASH, data.password)
    except (VerificationError, InvalidHashError):
        raise HTTPException(401, '账号或密码错误')
    if not row:
        raise HTTPException(401, '账号或密码错误')
    return issue_session(conn, row['id'], row['username'])


@app.post('/v1/auth/refresh')
def refresh_session(data: Refresh, request: Request, conn=Depends(db)):
    throttle(request, 'refresh', 60)
    row = conn.execute('SELECT s.*,u.username FROM sessions s JOIN users u ON u.id=s.user_id WHERE refresh_hash=? AND refresh_until>?', (digest(data.refresh_token), now())).fetchone()
    if not row:
        raise HTTPException(401, '会话已失效，请重新登录')
    return issue_session(conn, row['user_id'], row['username'], row['id'])


@app.post('/v1/auth/logout')
def logout(user=Depends(session_user), conn=Depends(db)):
    conn.execute('DELETE FROM sessions WHERE id=?', (user['session_id'],))
    return {'ok': True}


@app.get('/v1/me')
def me(user=Depends(session_user)):
    return {'id': user['id'], 'username': user['username']}


@app.get('/v1/groups')
def groups(user=Depends(session_user), conn=Depends(db)):
    rows = conn.execute('SELECT g.id,g.name,m.role FROM groups g JOIN memberships m ON m.group_id=g.id WHERE m.user_id=? ORDER BY g.rowid DESC', (user['id'],)).fetchall()
    return {'items': [dict(row) for row in rows]}


@app.post('/v1/groups', status_code=201)
def create_group(data: GroupInput, user=Depends(session_user), conn=Depends(db)):
    group_id = str(uuid4())
    conn.execute('INSERT INTO groups VALUES (?,?,?)', (group_id, data.name, user['id']))
    conn.execute('INSERT INTO memberships VALUES (?,?,?)', (group_id, user['id'], 'owner'))
    return {'id': group_id, 'name': data.name, 'role': 'owner'}


@app.get('/v1/groups/{group_id}')
def group_detail(group_id: str, user=Depends(session_user), conn=Depends(db)):
    require_member(conn, group_id, user)
    group = dict(conn.execute('SELECT id,name FROM groups WHERE id=?', (group_id,)).fetchone())
    group['members'] = [dict(row) for row in conn.execute('SELECT u.id,u.username,m.role FROM memberships m JOIN users u ON u.id=m.user_id WHERE m.group_id=? ORDER BY m.role DESC,u.username', (group_id,))]
    group['shared'] = [dict(row) for row in conn.execute('SELECT s.share_id,s.text,s.created_at,u.username FROM shared_events s JOIN users u ON u.id=s.user_id WHERE s.group_id=? ORDER BY s.created_at DESC', (group_id,))]
    return group


@app.post('/v1/groups/{group_id}/invites')
def invite(group_id: str, user=Depends(session_user), conn=Depends(db)):
    require_member(conn, group_id, user, owner=True)
    code = secrets.token_hex(5).upper()
    expires_at = now() + 86400
    conn.execute('INSERT INTO invites VALUES (?,?,?,?)', (digest(code), group_id, expires_at, 5))
    return {'code': code, 'expires_at': expires_at, 'uses_left': 5}


@app.post('/v1/groups/join')
def join(data: InviteInput, request: Request, user=Depends(session_user), conn=Depends(db)):
    throttle(request, 'join')
    row = conn.execute('SELECT * FROM invites WHERE code_hash=? AND expires_at>? AND uses_left>0', (digest(data.code.strip().upper()), now())).fetchone()
    if not row:
        raise HTTPException(400, '邀请码无效、已过期或已用完')
    existing = conn.execute('SELECT 1 FROM memberships WHERE group_id=? AND user_id=?', (row['group_id'], user['id'])).fetchone()
    if not existing:
        conn.execute('INSERT INTO memberships VALUES (?,?,?)', (row['group_id'], user['id'], 'member'))
        conn.execute('UPDATE invites SET uses_left=uses_left-1 WHERE code_hash=?', (row['code_hash'],))
    return {'group_id': row['group_id']}


@app.delete('/v1/groups/{group_id}/members/{member_id}')
def remove_member(group_id: str, member_id: str, user=Depends(session_user), conn=Depends(db)):
    require_member(conn, group_id, user, owner=True)
    if member_id == user['id']:
        raise HTTPException(400, '组长不能移除自己')
    conn.execute('DELETE FROM memberships WHERE group_id=? AND user_id=?', (group_id, member_id))
    conn.execute('DELETE FROM shared_events WHERE group_id=? AND user_id=?', (group_id, member_id))
    return {'ok': True}


@app.post('/v1/events')
async def upsert_event(data: ContextEvent, request: Request, user=Depends(short_session_user)):
    event_id = str(data.event_id)
    body = data.model_dump_json()
    hub = request.app.state.agent_hub
    async with hub.lock(user['id']):
        with closing(hub.connect()) as conn, conn:
            conn.execute('BEGIN IMMEDIATE')
            old = conn.execute('SELECT * FROM events WHERE user_id=? AND event_id=?', (user['id'], event_id)).fetchone()
            replay = old and old['revision'] == data.revision and old['body'] == body
            if not replay:
                expected = old['revision'] + 1 if old else 1
                if data.revision != expected:
                    raise HTTPException(409, '线索版本冲突，请刷新后重试')
                conn.execute('INSERT OR REPLACE INTO events VALUES (?,?,?,?)', (user['id'], event_id, data.revision, body))
                conn.execute('INSERT INTO agent_invalidations(user_id,event_id,revision) VALUES(?,?,?)', (user['id'], event_id, data.revision))
        await hub.invalidate(user['id'])
    return {'event_id': event_id, 'revision': data.revision, 'status': 'synced'}


@app.get('/v1/events')
def events(user=Depends(session_user), conn=Depends(db)):
    rows = conn.execute('SELECT body FROM events WHERE user_id=? ORDER BY rowid DESC', (user['id'],))
    return {'items': [json.loads(row['body']) for row in rows]}


@app.delete('/v1/events/{event_id}')
async def delete_event(event_id: str, request: Request, user=Depends(short_session_user)):
    hub = request.app.state.agent_hub
    async with hub.lock(user['id']):
        with closing(hub.connect()) as conn, conn:
            conn.execute('DELETE FROM events WHERE user_id=? AND event_id=?', (user['id'], event_id))
            conn.execute('DELETE FROM shared_events WHERE user_id=? AND event_id=?', (user['id'], event_id))
            conn.execute('INSERT INTO agent_invalidations(user_id,event_id,revision) VALUES(?,?,NULL)', (user['id'], event_id))
        await hub.invalidate(user['id'])
    return {'ok': True}


def decision_status(conn, user_id, decision):
    for evidence in decision['evidence']:
        row = conn.execute('SELECT revision FROM events WHERE user_id=? AND event_id=?', (user_id, evidence['event_id'])).fetchone()
        if not row or row['revision'] != evidence['revision']:
            return 'stale'
    if decision['expires_at'] <= now():
        return 'expired'
    return 'active'


def rule_decision(events):
    """Replace this provider with a validated AI implementation using docs/TEAM_HANDOFF.md."""
    has_relative_time = any(re.search(r'明天|后天|下周|周[一二三四五六日天]', event['payload']['text']) and event['occurred_at'] is None for event in events)
    return {
        'card_type': 'question' if has_relative_time else 'summary',
        'title': '先确认线索中的具体时间' if has_relative_time else '这些线索已准备好',
        'summary': '材料包含相对日期，打开原始线索确认具体日期后再安排。' if has_relative_time else '你可以逐条核对来源，确认后标记为已采纳。此内容由联调规则生成。',
        'actions': [{'type': 'accepted', 'label': '我已核对'}, {'type': 'dismissed', 'label': '暂时忽略'}],
    }


@app.post('/v1/decisions/generate')
def generate(data: DecisionRequest, user=Depends(session_user), conn=Depends(db)):
    previous = conn.execute('SELECT body FROM decisions WHERE user_id=? AND request_id=?', (user['id'], str(data.request_id))).fetchone()
    if previous:
        result = json.loads(previous['body'])
        if set(result['evidence_ids']) != set(map(str, data.event_ids)):
            raise HTTPException(409, '同一请求 ID 不能用于不同线索')
        result['status'] = decision_status(conn, user['id'], result)
        return result
    selected = []
    for event_id in dict.fromkeys(map(str, data.event_ids)):
        row = conn.execute('SELECT body FROM events WHERE user_id=? AND event_id=?', (user['id'], event_id)).fetchone()
        if not row:
            raise HTTPException(404, '部分线索不存在或不属于当前用户')
        selected.append(json.loads(row['body']))
    result = {'schema_version': 1, 'decision_id': str(uuid4()), 'provider': 'rule_demo',
              'created_at': now(), 'expires_at': now() + 86400, 'status': 'active',
              'evidence_ids': [event['event_id'] for event in selected],
              'evidence': [{'event_id': event['event_id'], 'revision': event['revision']} for event in selected],
              **rule_decision(selected)}
    conn.execute('INSERT INTO decisions VALUES (?,?,?,?)', (user['id'], result['decision_id'], str(data.request_id), json.dumps(result, ensure_ascii=False)))
    return result


@app.get('/v1/decisions')
def decisions(user=Depends(session_user), conn=Depends(db)):
    result = []
    for row in conn.execute('SELECT body FROM decisions WHERE user_id=? ORDER BY rowid DESC', (user['id'],)):
        item = json.loads(row['body'])
        item['status'] = decision_status(conn, user['id'], item)
        feedback = conn.execute('SELECT action FROM feedback WHERE user_id=? AND decision_id=?', (user['id'], item['decision_id'])).fetchone()
        item['feedback'] = feedback['action'] if feedback else None
        result.append(item)
    return {'items': result}


@app.put('/v1/decisions/{decision_id}/feedback')
def feedback(decision_id: str, data: FeedbackInput, user=Depends(session_user), conn=Depends(db)):
    row = conn.execute('SELECT body FROM decisions WHERE user_id=? AND decision_id=?', (user['id'], decision_id)).fetchone()
    if not row:
        raise HTTPException(404, '建议不存在')
    if decision_status(conn, user['id'], json.loads(row['body'])) != 'active':
        raise HTTPException(409, '建议依据已变化或建议已过期，请重新生成')
    conn.execute('INSERT OR REPLACE INTO feedback VALUES (?,?,?,?,?)', (user['id'], decision_id, data.action, data.reason, now()))
    return {'ok': True}


@app.post('/v1/groups/{group_id}/shares')
def share(group_id: str, data: ShareInput, user=Depends(session_user), conn=Depends(db)):
    require_member(conn, group_id, user)
    row = conn.execute('SELECT body FROM events WHERE user_id=? AND event_id=?', (user['id'], str(data.event_id))).fetchone()
    if not row:
        raise HTTPException(404, '线索不存在')
    text = json.loads(row['body'])['payload']['text']
    conn.execute('INSERT INTO shared_events VALUES (?,?,?,?,?,?) ON CONFLICT(group_id,user_id,event_id) DO UPDATE SET text=excluded.text,created_at=excluded.created_at', (str(uuid4()), group_id, user['id'], str(data.event_id), text, now()))
    return {'ok': True}


@app.delete('/v1/groups/{group_id}/shares/{event_id}')
def unshare(group_id: str, event_id: str, user=Depends(session_user), conn=Depends(db)):
    require_member(conn, group_id, user)
    conn.execute('DELETE FROM shared_events WHERE group_id=? AND user_id=? AND event_id=?', (group_id, user['id'], event_id))
    return {'ok': True}


# Register authenticated Agent routes after the legacy authentication helpers.
import sys
from .agent_bridge import install
install(app, sys.modules[__name__])
from .app_updates import router as updates_router
app.include_router(updates_router)

