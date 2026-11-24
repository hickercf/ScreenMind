"""Run from Mobile Client: python -m scripts.home_simulator.server (local gateway on port 8765)."""
from contextlib import closing
from pathlib import Path
import json
import os
import secrets
import sqlite3
import sys
import tempfile
import threading
import time
from unittest.mock import patch
from uuid import uuid4

import httpx
import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from fastapi.testclient import TestClient
from pydantic import BaseModel, Field
from starlette.middleware.trustedhost import TrustedHostMiddleware

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT.parent / "Cloud Agent"))
from backend import main
from .scenarios import ACTIONS, CATALOG, EVENTS, HOME, steps_for

GATEWAY = "http://127.0.0.1:8765"
STATE = Path(os.environ.get("LOCALAPPDATA") or Path.home()) / "ScreenMind" / "home-simulator"
WEB = Path(__file__).with_name("web")
lock = threading.Lock()


def checked(response):
    if not response.is_success:
        raise HTTPException(502, f"{response.request.method} {response.request.url.path} · {response.status_code} · {response.text[:250]}")
    return response.json()


class Session:
    def __init__(self, client, auth):
        self.client = client
        self.auth = auth

    def call(self, method, path, body=None):
        for attempt in range(2):
            response = self.client.request(method, path, json=body, headers={"Authorization": "Bearer " + self.auth["access_token"]})
            if response.status_code == 401 and attempt == 0:
                self.auth = checked(self.client.post("/v1/auth/refresh", json={"refresh_token": self.auth["refresh_token"]}))
                continue
            return response.status_code, checked(response)


def cards(doc):
    items = doc["events"] + doc["archivedEvents"] + ([doc["crossDayRelay"]] if doc.get("crossDayRelay") else [])
    return {item["id"]: item for item in items}


def diff(before, after):
    previous, current = cards(before), cards(after)
    changes = []
    for key in dict.fromkeys([*previous, *current]):
        a, b = previous.get(key), current.get(key)
        if a != b:
            changes.append({"id": key, "title": (b or a)["title"], "kind": "新增" if a is None else "移除" if b is None else "更新",
                            "fields": [field for field in dict.fromkeys([*(a or {}), *(b or {})]) if (a or {}).get(field) != (b or {}).get(field)],
                            "before": a, "after": b})
    return changes


class Runner:
    def __init__(self, session):
        self.session = session
        self.refs = {}
        self.frames = []
        self.doc = self.read_home()

    def read_home(self):
        self.contract = self.session.call("GET", "/api/v1/home/timeline?clientCapabilities=ANDROID_COMPAT")[1]
        native = self.contract.pop("nativeState")
        return dict(native, dayMode=self.contract["dayMode"])

    def wait_for(self, path, field, done):
        until = time.monotonic() + 15
        while time.monotonic() < until:
            result = self.session.call("GET", path)[1][field]
            if done(result):
                return result
            if result.get("status") in ("failed", "cancelled"):
                break
            time.sleep(.1)
        raise HTTPException(502, "Agent 尚未产生预期结果，请检查本地网关的 mock 模型配置。")

    def run_step(self, step):
        path = step["path"].replace("$task", self.refs.get("$task", ""))
        body = {k: self.refs.get(v, v) if isinstance(v, str) else v for k, v in step["body"].items()}
        method = step.get("method", "POST")
        status, reply = self.session.call(method, path, body if method != "GET" else None)
        if path == "/v1/agent/works":
            self.refs["$work"] = reply["work"]["id"]
        elif path == "/v1/agent/tasks":
            self.refs["$task"] = reply["task"]["id"]
            task = self.wait_for(path + "/" + self.refs["$task"], "task", lambda t: t["status"] == "waiting_input")
            interaction = task["interaction"]
            if "proposal_id" not in interaction:
                raise HTTPException(502, "Agent 要求补充信息，尚无可接受的候选方案。")
            self.refs.update({"$revision": task["revision"], "$version": interaction["output_version"], "$proposal": interaction["proposal_id"]})
        elif path.endswith("/responses"):
            self.wait_for("/v1/agent/works/" + self.refs["$work"], "work", lambda w: w["memory_version"] > 0)
        before, self.doc = self.doc, self.read_home()
        details = {eid: self.session.call("GET", "/api/v1/events/" + eid + "/detail")[1] for eid in cards(self.doc)}
        self.frames.append({"title": step["title"], "rule": step["rule"], "path": path, "body": body, "status": status,
                            "method": method, "contract": self.contract, "details": details,
                            "reply": reply, "doc": self.doc, "changes": diff(before, self.doc),
                            "previousMode": before["dayMode"]})


def preview(steps):
    # A real ASGI router plus an isolated temporary DB exercises validators and projectors without a network service.
    with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {
        "WEIGUANG_DB": str(Path(temp) / "fixture.sqlite3"), "WEIGUANG_AGENT_ROOT": str(Path(temp) / "agents"),
        "SCREENMIND_PROVIDER": "mock", "SCREENMIND_MEMORY_MODE": "basic", "SCREENMIND_MEMORY_EXTRACTOR": "raw",
        "SCREENMIND_MEMORY_PLANNING": "false", "SCREENMIND_EVIDENCE_REVIEW": "false", "SCREENMIND_EMBEDDING": "hash-demo",
    }), TestClient(main.app) as client:
        with closing(sqlite3.connect(main.database_path())) as conn, conn:
            conn.execute("INSERT INTO users VALUES (?,?,?,?)", ("fixture", "fixture", "", 0))
            auth = main.issue_session(conn, "fixture", "fixture")
        runner = Runner(Session(client, auth))
        for step in steps:
            runner.run_step(step)
        return runner.frames


class Studio:
    def __init__(self):
        self.run_id = None
        self.case = None
        self.steps = []
        self.mock = []
        self.remote = None
        self.session = None
        self.ledger = STATE / "studio-events.json"
        self.owned_events = set(json.loads(self.ledger.read_text()) if self.ledger.exists() else [])
        self.interrupted = False

    def prepare(self, case):
        if case not in CATALOG:
            raise HTTPException(404, "场景不存在")
        steps = steps_for(case)
        frames = preview(steps)
        self.case, self.run_id, self.steps, self.mock = case, uuid4().hex, steps, frames
        self.remote, self.interrupted = None, False
        return self.state()

    def gateway(self):
        if self.session is None:
            STATE.mkdir(parents=True, exist_ok=True)
            path = STATE / "studio-account.json"
            if path.exists():
                identity = json.loads(path.read_text(encoding="utf-8"))
            else:
                identity = {"username": "home_sim_" + secrets.token_hex(4), "password": secrets.token_hex(16)}
                with path.open("x", encoding="utf-8") as f:
                    json.dump(identity, f)
            client = httpx.Client(base_url=GATEWAY, timeout=20, trust_env=False)
            try:
                response = client.post("/v1/auth/login", json=identity)
                if response.status_code == 401:
                    response = client.post("/v1/auth/register", json=identity)
                self.session = Session(client, checked(response))
            except Exception:
                client.close()
                raise
        return self.session

    def save_events(self):
        temp = self.ledger.with_suffix(".tmp")
        temp.write_text(json.dumps(sorted(self.owned_events)), encoding="utf-8")
        temp.replace(self.ledger)

    def connect(self, run_id, through):
        if run_id != self.run_id or self.interrupted:
            raise HTTPException(409, "请重新运行模拟，再发送这组输入。")
        if not 0 <= through < len(self.steps):
            raise HTTPException(422, "步骤超出当前场景")
        session = self.gateway()
        if self.remote is None:
            # The account is dedicated to this console. Only remove clues created by this process.
            for eid in list(self.owned_events):
                session.call("DELETE", EVENTS + "/" + eid)
                self.owned_events.discard(eid)
                self.save_events()
            self.remote = Runner(session)
        try:
            for i in range(len(self.remote.frames), through + 1):
                step = self.steps[i]
                self.remote.run_step(step)
                if step["path"] == EVENTS:
                    self.owned_events.add(step["body"]["event_id"])
                    self.save_events()
        except Exception:
            # A response may be lost after a toggle committed. Start a fresh run instead of resending it.
            self.interrupted = True
            raise
        return self.state()

    def state(self):
        return {"id": self.run_id, "case": self.case, "mock": self.mock,
                "remote": self.remote.frames if self.remote else [], "interrupted": self.interrupted}


class CaseInput(BaseModel):
    case: str


class ConnectInput(BaseModel):
    run: str
    through: int = Field(ge=0)


app = FastAPI(title="ScreenMind 首页事件推演", docs_url=None, redoc_url=None)
app.add_middleware(TrustedHostMiddleware, allowed_hosts=["localhost", "127.0.0.1"])
studio = Studio()


@app.get("/")
def index():
    return FileResponse(WEB / "index.html")


@app.get("/api/catalog")
def catalog():
    return [{"id": key, "group": value[0], "title": value[1], "detail": value[2]} for key, value in CATALOG.items()]


@app.get("/api/state")
def state():
    with lock:
        return studio.state() if studio.run_id else studio.prepare("day")


@app.post("/api/run")
def run(body: CaseInput):
    with lock:
        return studio.prepare(body.case)


@app.post("/api/connect")
def connect(body: ConnectInput):
    with lock:
        try:
            return studio.connect(body.run, body.through)
        except httpx.RequestError as exc:
            raise HTTPException(502, "无法连接本地网关 127.0.0.1:8765。模拟报文仍可查看。") from exc


@app.get("/api/current")
def current():
    with lock:
        if not studio.remote:
            raise HTTPException(409, "请先发送一个步骤到本地后端")
        try:
            return studio.remote.read_home()
        except httpx.RequestError as exc:
            raise HTTPException(502, "暂时无法读取本地网关") from exc


app.mount("/static", StaticFiles(directory=WEB), name="static")


if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=8766)
