"""SQLite domain state. Transactions never span model calls or other awaits."""
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sqlite3
import uuid


def now():
    return datetime.now(timezone.utc).isoformat()


def encode(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def fingerprint(scope, body):
    # New nullable routing fields must not invalidate pre-v0.2 request receipts.
    if scope == "task":
        body = {k: v for k, v in body.items() if k not in {"work_id", "subagent_id"} or v is not None}
    return hashlib.sha256(encode(body).encode()).hexdigest()


class DomainError(Exception):
    def __init__(self, message, status=409):
        super().__init__(message)
        self.status = status


class Store:
    def __init__(self, path: Path, owner: str, registry=None):
        from .agents import Registry
        from .router import Router
        self.registry = registry or Registry()
        self.router = Router(self.registry)
        path.parent.mkdir(parents=True, exist_ok=True)
        self.owner = owner
        self.db = sqlite3.connect(path, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.execute("PRAGMA busy_timeout=5000")
        self.db.executescript("""
        CREATE TABLE IF NOT EXISTS screens (
          id TEXT PRIMARY KEY, owner TEXT NOT NULL, payload TEXT NOT NULL, created_at TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS tasks (
          id TEXT PRIMARY KEY, owner TEXT NOT NULL, instruction TEXT NOT NULL,
          screen_ids TEXT NOT NULL, status TEXT NOT NULL, revision INTEGER NOT NULL,
          card_version INTEGER NOT NULL DEFAULT 0, previous_card TEXT,
          error TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS inputs (
          seq INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL REFERENCES tasks(id),
          revision INTEGER NOT NULL, payload TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS cards (
          task_id TEXT NOT NULL REFERENCES tasks(id), version INTEGER NOT NULL,
          revision INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(task_id, version));
        CREATE TABLE IF NOT EXISTS receipts (
          owner TEXT NOT NULL, scope TEXT NOT NULL, event_id TEXT NOT NULL,
          fingerprint TEXT NOT NULL, result_id TEXT NOT NULL, PRIMARY KEY(owner, scope, event_id));
        CREATE TABLE IF NOT EXISTS events (
          seq INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT NOT NULL, task_id TEXT,
          kind TEXT NOT NULL, payload TEXT NOT NULL, at TEXT NOT NULL);
        CREATE INDEX IF NOT EXISTS events_owner_seq ON events(owner, seq);
        CREATE INDEX IF NOT EXISTS tasks_owner_status ON tasks(owner, status);
        CREATE TABLE IF NOT EXISTS works (
          id TEXT PRIMARY KEY, owner TEXT NOT NULL, title TEXT NOT NULL,
          subagent_id TEXT NOT NULL, memory_version INTEGER NOT NULL DEFAULT 0,
          state TEXT NOT NULL DEFAULT '{}', created_at TEXT NOT NULL, updated_at TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS work_screens (
          screen_id TEXT PRIMARY KEY REFERENCES screens(id), work_id TEXT NOT NULL REFERENCES works(id));
        CREATE TABLE IF NOT EXISTS memories (
          work_id TEXT NOT NULL REFERENCES works(id), version INTEGER NOT NULL,
          task_id TEXT NOT NULL REFERENCES tasks(id), state TEXT NOT NULL, at TEXT NOT NULL,
          PRIMARY KEY(work_id, version));
        """)
        # Additive migration keeps v0.1 tasks and receipts usable.
        columns = {r[1] for r in self.db.execute("PRAGMA table_info(tasks)")}
        for name, definition in {"work_id": "TEXT REFERENCES works(id)",
                                 "subagent_id": "TEXT NOT NULL DEFAULT 'information'",
                                 "memory_base": "INTEGER", "memory_snapshot": "TEXT",
                                 "memory_proposal": "TEXT"}.items():
            if name not in columns:
                self.db.execute(f"ALTER TABLE tasks ADD COLUMN {name} {definition}")

    @contextmanager
    def transaction(self):
        self.db.execute("BEGIN IMMEDIATE")
        try:
            yield
            self.db.execute("COMMIT")
        except BaseException:
            self.db.execute("ROLLBACK")
            raise

    def close(self):
        self.db.close()

    def event(self, task_id, kind, payload):
        self.db.execute("INSERT INTO events(owner,task_id,kind,payload,at) VALUES(?,?,?,?,?)",
                        (self.owner, task_id, kind, encode(payload), now()))

    def replay(self, scope, body):
        row = self.db.execute("SELECT * FROM receipts WHERE owner=? AND scope=? AND event_id=?",
                              (self.owner, scope, body["event_id"])).fetchone()
        if row and row["fingerprint"] != fingerprint(scope, body):
            raise DomainError("同一 event_id 已用于不同内容，请生成新的 event_id")
        return row["result_id"] if row else None

    def receipt(self, scope, body, result_id):
        self.db.execute("INSERT INTO receipts VALUES(?,?,?,?,?)",
                        (self.owner, scope, body["event_id"], fingerprint(scope, body), result_id))

    def get_screen(self, screen_id):
        row = self.db.execute("SELECT * FROM screens WHERE id=? AND owner=?", (screen_id, self.owner)).fetchone()
        if not row:
            raise DomainError("屏幕记录不存在", 404)
        return {"id": row["id"], **json.loads(row["payload"])}

    def list_screens(self):
        rows = self.db.execute("SELECT id FROM screens WHERE owner=? ORDER BY created_at DESC LIMIT 100", (self.owner,)).fetchall()
        return [self.get_screen(r["id"]) for r in rows]

    def work(self, wid):
        row = self.db.execute("SELECT * FROM works WHERE id=? AND owner=?", (wid, self.owner)).fetchone()
        if not row:
            raise DomainError("事项不存在", 404)
        result = dict(row)
        result.pop("owner")
        result["state"] = json.loads(result["state"])
        result["source_ref"] = f"memory:{wid}:{result['memory_version']}"
        return result

    def list_works(self):
        return [self.work(r[0]) for r in self.db.execute(
            "SELECT id FROM works WHERE owner=? ORDER BY updated_at DESC LIMIT 100", (self.owner,))]

    def memory_history(self, wid):
        self.work(wid)
        return [{"version": r["version"], "task_id": r["task_id"], "state": json.loads(r["state"]), "at": r["at"]}
                for r in self.db.execute("SELECT * FROM memories WHERE work_id=? ORDER BY version DESC LIMIT 100", (wid,))]

    def create_work(self, body):
        with self.transaction():
            existing = self.replay("work", body)
            if existing:
                return self.work(existing), True
            self.registry.get(body["subagent_id"])
            wid, timestamp = "work_" + uuid.uuid4().hex[:16], now()
            self.db.execute("INSERT INTO works(id,owner,title,subagent_id,created_at,updated_at) VALUES(?,?,?,?,?,?)",
                            (wid, self.owner, body["title"], body["subagent_id"], timestamp, timestamp))
            self.receipt("work", body, wid)
            self.event(None, "work.created", {"work_id": wid, "subagent_id": body["subagent_id"]})
        return self.work(wid), False

    def scope_screens(self, ids, wid):
        for sid in ids:
            row = self.db.execute("SELECT work_id FROM work_screens WHERE screen_id=?", (sid,)).fetchone()
            if row and row[0] != wid:
                raise DomainError("屏幕已属于其他事项，不能跨事项读取", 422)
            if wid and not row:
                used = self.db.execute("""SELECT 1 FROM tasks, json_each(tasks.screen_ids) AS source
                    WHERE source.value=? AND (tasks.work_id IS NULL OR tasks.work_id<>?) LIMIT 1""", (sid, wid)).fetchone()
                if used:
                    raise DomainError("屏幕已用于其他独立任务，不能重新绑定到事项", 422)
                self.db.execute("INSERT INTO work_screens VALUES(?,?)", (sid, wid))

    def save_screen(self, body):
        with self.transaction():
            existing = self.replay("screen", body)
            if existing:
                return self.get_screen(existing), True
            sid = "scr_" + uuid.uuid4().hex[:16]
            self.db.execute("INSERT INTO screens VALUES(?,?,?,?)", (sid, self.owner, encode(body), now()))
            self.receipt("screen", body, sid)
            self.event(None, "screen.received", {"screen_id": sid, "app_name": body["app_name"]})
        return self.get_screen(sid), False

    def validate_screens(self, ids):
        ids = list(dict.fromkeys(ids))
        if len(ids) > 20:
            raise DomainError("每个任务最多关联 20 份屏幕记录", 422)
        screens = [self.get_screen(s) for s in ids]
        if sum(len(b["text"]) for s in screens for b in s["blocks"]) > 48000:
            raise DomainError("任务上下文超过 48000 字符，请缩小屏幕范围", 422)
        return ids

    def create_task(self, body):
        with self.transaction():
            existing = self.replay("task", body)
            if existing:
                return self.task(existing), True
            ids = self.validate_screens(body["screen_ids"])
            wid = body.get("work_id")
            work = self.work(wid) if wid else None
            agent_id, reason = self.router.route(body, work)
            self.scope_screens(ids, wid)
            tid, timestamp = "task_" + uuid.uuid4().hex[:16], now()
            self.db.execute("INSERT INTO tasks(id,owner,instruction,screen_ids,status,revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                            (tid, self.owner, body["instruction"], encode(ids), "queued", 1, timestamp, timestamp))
            self.db.execute("INSERT INTO inputs(task_id,revision,payload) VALUES(?,?,?)", (tid, 1, encode(body)))
            self.db.execute("UPDATE tasks SET work_id=?,subagent_id=? WHERE id=?", (wid, agent_id, tid))
            self.receipt("task", body, tid)
            self.event(tid, "router.dispatched", {"revision": 1, "reason": reason, "subagent_id": agent_id, "work_id": wid})
        return self.task(tid), False

    def task(self, tid):
        row = self.db.execute("SELECT * FROM tasks WHERE id=? AND owner=?", (tid, self.owner)).fetchone()
        if not row:
            raise DomainError("任务不存在", 404)
        task = dict(row)
        task.pop("owner")
        task["screen_ids"] = json.loads(task["screen_ids"])
        task["previous_card"] = json.loads(task["previous_card"]) if task["previous_card"] else None
        for key in ("memory_snapshot", "memory_proposal"):
            task[key] = json.loads(task[key]) if task[key] else None
        card = self.db.execute("SELECT payload,revision FROM cards WHERE task_id=? AND version=?", (tid, task["card_version"])).fetchone()
        task["card"] = json.loads(card["payload"]) if card else None
        task["card_revision"] = card["revision"] if card else None
        task["inputs"] = [json.loads(r[0]) for r in self.db.execute("SELECT payload FROM inputs WHERE task_id=? ORDER BY seq", (tid,))]
        return task

    def list_tasks(self):
        rows = self.db.execute("SELECT id FROM tasks WHERE owner=? ORDER BY updated_at DESC LIMIT 100", (self.owner,)).fetchall()
        # List view omits full inputs, evidence and cards; details have a dedicated endpoint.
        return [{k: v for k, v in self.task(r[0]).items() if k not in {"inputs", "previous_card", "card", "memory_snapshot", "memory_proposal"}} for r in rows]

    def action(self, tid, body):
        with self.transaction():
            task = self.task(tid)
            if self.replay("action:" + tid, body):
                return task, True
            if task["revision"] != body["expected_revision"]:
                raise DomainError("任务已经更新，请刷新后重试")
            kind = body["kind"]
            if kind == "retry" and task["status"] != "failed":
                raise DomainError("只有失败任务可以重试")
            if kind == "cancel" and task["status"] in {"completed", "cancelled"}:
                raise DomainError("任务已经结束")
            if kind == "revise" and task["status"] in {"completed", "cancelled", "failed"}:
                raise DomainError("该状态不接受修改，请先重试或创建新任务")
            if kind == "confirm" and task["status"] != "waiting_input":
                raise DomainError("只有待确认的卡片可以完成任务")
            if body.get("card_version") is not None and body["card_version"] != task["card_version"]:
                raise DomainError("卡片版本已过期，请刷新后重试")
            previous = task["previous_card"] or task["card"]
            if body["edits"]:
                if task["status"] != "waiting_input" or not task["card"]:
                    raise DomainError("请等待当前卡片完成后再编辑")
                previous = task["card"]
                fields = {f["id"]: f for f in previous["fields"]}
                if not set(body["edits"]).issubset(fields):
                    raise DomainError("修改包含不存在的字段", 422)
                for key, value in body["edits"].items():
                    if not fields[key].get("editable", True):
                        raise DomainError("该字段由业务规则计算，请通过补充要求修改计划", 422)
                    fields[key]["value"], fields[key]["origin"] = value, "user"
            ids = self.validate_screens(task["screen_ids"] + body["screen_ids"])
            self.scope_screens(ids, task["work_id"])
            # Bound accumulated user instructions as well as OCR context.
            history_size = sum(len(i.get("message", "")) + len(encode(i.get("edits", {}))) for i in task["inputs"])
            if history_size + len(body["message"]) + len(encode(body["edits"])) > 24000:
                raise DomainError("任务交互历史过长，请创建新任务", 422)
            status = {"confirm": "completed", "cancel": "cancelled"}.get(kind, "queued")
            revision = task["revision"] + 1
            if kind == "confirm" and task["work_id"] and task["memory_proposal"] is not None:
                work = self.work(task["work_id"])
                if work["memory_version"] != task["memory_base"]:
                    raise DomainError("事项记忆已被其他任务更新，请补充要求或屏幕后重新生成方案，再确认")
                version = work["memory_version"] + 1
                timestamp = now()
                self.db.execute("UPDATE works SET state=?,memory_version=?,updated_at=? WHERE id=?",
                                (encode(task["memory_proposal"]), version, timestamp, work["id"]))
                self.db.execute("INSERT INTO memories VALUES(?,?,?,?,?)",
                                (work["id"], version, tid, encode(task["memory_proposal"]), timestamp))
                self.event(tid, "memory.committed", {"work_id": work["id"], "version": version})
            self.db.execute("UPDATE tasks SET status=?,revision=?,screen_ids=?,previous_card=?,error=NULL,updated_at=? WHERE id=?",
                            (status, revision, encode(ids), encode(previous) if previous else None, now(), tid))
            self.db.execute("INSERT INTO inputs(task_id,revision,payload) VALUES(?,?,?)", (tid, revision, encode(body)))
            if kind in {"revise", "retry", "cancel"}:
                self.db.execute("UPDATE tasks SET memory_proposal=NULL WHERE id=?", (tid,))
            self.receipt("action:" + tid, body, tid)
            self.event(tid, "task." + kind, {"revision": revision, "status": status})
        return self.task(tid), False

    def queued(self):
        return [r[0] for r in self.db.execute("SELECT id FROM tasks WHERE owner=? AND status='queued' ORDER BY updated_at", (self.owner,))]

    def claim(self, tid):
        with self.transaction():
            task = self.task(tid)
            if task["status"] != "queued":
                return None
            self.scope_screens(task["screen_ids"], task["work_id"])
            if task["work_id"]:
                memory = self.work(task["work_id"])
                self.db.execute("UPDATE tasks SET memory_snapshot=?,memory_base=?,memory_proposal=NULL WHERE id=?",
                                (encode(memory), memory["memory_version"], tid))
            self.db.execute("UPDATE tasks SET status='running',updated_at=? WHERE id=?", (now(), tid))
            self.event(tid, "worker.started", {"revision": task["revision"]})
        return self.task(tid)

    def current(self, tid, revision):
        row = self.db.execute("SELECT 1 FROM tasks WHERE id=? AND owner=? AND revision=? AND status='running'", (tid, self.owner, revision)).fetchone()
        return bool(row)

    def publish(self, tid, revision, card, mode, memory_proposal=None):
        with self.transaction():
            if not self.current(tid, revision):
                return False
            version = self.task(tid)["card_version"] + 1
            self.db.execute("INSERT INTO cards VALUES(?,?,?,?)", (tid, version, revision, encode(card)))
            self.db.execute("UPDATE tasks SET status='waiting_input',card_version=?,previous_card=?,updated_at=? WHERE id=?", (version, encode(card), now(), tid))
            self.db.execute("UPDATE tasks SET memory_proposal=? WHERE id=?",
                            (encode(memory_proposal) if memory_proposal is not None else None, tid))
            self.event(tid, "card.published", {"revision": revision, "version": version, "mode": mode})
        return True

    def fail(self, tid, revision, message):
        with self.transaction():
            if self.current(tid, revision):
                self.db.execute("UPDATE tasks SET status='failed',error=?,updated_at=? WHERE id=?", (message, now(), tid))
                self.event(tid, "worker.failed", {"revision": revision, "error": message})

    def recover(self):
        with self.transaction():
            rows = self.db.execute("SELECT id FROM tasks WHERE owner=? AND status='running'", (self.owner,)).fetchall()
            for row in rows:
                self.db.execute("UPDATE tasks SET status='queued',revision=revision+1,updated_at=? WHERE id=?", (now(), row[0]))
                self.event(row[0], "task.recovered", {"reason": "interrupted_execution_requeued"})
        return len(rows)

    def events(self, after=0, tid=None, limit=200):
        rows = self.db.execute("SELECT * FROM events WHERE owner=? AND seq>? AND (? IS NULL OR task_id=?) ORDER BY seq LIMIT ?", (self.owner, after, tid, tid, limit)).fetchall()
        return [{"seq": r["seq"], "task_id": r["task_id"], "kind": r["kind"], "payload": json.loads(r["payload"]), "at": r["at"]} for r in rows]
