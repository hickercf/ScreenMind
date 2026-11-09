"""Authorized outbox actions: export an ICS file or PUT to a configured CalDAV calendar."""
import asyncio
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
from typing import Literal
import uuid

import httpx
from pydantic import AwareDatetime, Field
from .models import StrictModel, Identifier
from .store import DomainError, encode, now


class CalendarRequest(StrictModel):
    event_id: Identifier
    expected_state_version: int = Field(ge=1)
    item_id: Identifier
    end: AwareDatetime
    tool: Literal["calendar.export", "calendar.caldav"] = "calendar.export"


def calendar_bytes(parameters, action_id, created_at):
    def dt(value):
        return datetime.fromisoformat(value).astimezone(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    title = parameters["title"].replace("\\", "\\\\").replace("\r", "").replace("\n", "\\n").replace(";", "\\;").replace(",", "\\,")
    lines = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//ScreenMind//Cloud Agent//ZH", "BEGIN:VEVENT",
        "UID:" + action_id + "@screenmind", "DTSTAMP:" + dt(created_at), "DTSTART:" + dt(parameters["start"]),
        "DTEND:" + dt(parameters["end"]), "SUMMARY:" + title, "END:VEVENT", "END:VCALENDAR"]
    folded = []
    for line in lines:
        current = ""
        for char in line:
            if len((current + char).encode("utf-8")) > 75:
                folded.append(current)
                current = " "
            current += char
        folded.append(current)
    return ("\r\n".join(folded) + "\r\n").encode("utf-8")


class ExportCalendar:
    name = "calendar.export"
    def __init__(self, path):
        self.root = Path(path).resolve()

    def path(self, action_id):
        if not action_id.startswith("action_") or not action_id[7:].isalnum():
            raise ValueError("Invalid action id")
        return self.root / (action_id + ".ics")

    async def execute(self, action):
        payload = calendar_bytes(action["parameters"], action["id"], action["created_at"])
        path = self.path(action["id"])
        def write():
            self.root.mkdir(parents=True, exist_ok=True)
            temporary = path.with_suffix(".tmp")
            temporary.write_bytes(payload)
            temporary.replace(path)
        await asyncio.to_thread(write)
        return {"status": "succeeded", "receipt": {"sha256": hashlib.sha256(payload).hexdigest(),
            "filename": path.name, "download_url": "/api/actions/" + action["id"] + "/artifact", "calendar_imported": False}}

    async def reconcile(self, action):
        path = self.path(action["id"])
        payload = calendar_bytes(action["parameters"], action["id"], action["created_at"])
        if path.exists() and await asyncio.to_thread(path.read_bytes) == payload:
            return {"status": "succeeded", "receipt": {"sha256": hashlib.sha256(payload).hexdigest(),
                "filename": path.name, "download_url": "/api/actions/" + action["id"] + "/artifact", "calendar_imported": False}}
        return {"status": "failed", "receipt": {"reason": "artifact_missing_or_mismatched"}}


class CalDAVCalendar:
    name = "calendar.caldav"
    def __init__(self, settings, transport=None):
        self.url = settings.caldav_url.rstrip("/") + "/"
        self.auth = (settings.caldav_username, settings.caldav_password)
        self.transport = transport

    async def execute(self, action):
        if action["parameters"].get("calendar_target") != self.url:
            return {"status": "failed", "receipt": {"reason": "destination_configuration_changed"}}
        payload = calendar_bytes(action["parameters"], action["id"], action["created_at"])
        async with httpx.AsyncClient(auth=self.auth, timeout=15, follow_redirects=False, transport=self.transport) as client:
            response = await client.put(self.url + action["id"] + ".ics", content=payload,
                headers={"Content-Type": "text/calendar; charset=utf-8", "If-None-Match": "*"})
        if response.status_code in {201, 204}:
            return {"status": "succeeded", "receipt": {"uid": action["id"] + "@screenmind", "etag": response.headers.get("etag")}}
        if response.status_code == 412:
            return await self.reconcile(action)
        return {"status": "unknown" if response.status_code >= 500 or response.status_code == 202 else "failed",
                "receipt": {"http_status": response.status_code}}

    async def reconcile(self, action):
        if action["parameters"].get("calendar_target") != self.url:
            return {"status": "unknown", "receipt": {"reason": "destination_configuration_changed"}}
        expected = calendar_bytes(action["parameters"], action["id"], action["created_at"])
        async with httpx.AsyncClient(auth=self.auth, timeout=15, follow_redirects=False, transport=self.transport) as client:
            response = await client.get(self.url + action["id"] + ".ics")
        if response.status_code == 404:
            return {"status": "failed", "receipt": {"reason": "not_found"}}
        # Require exact relevant fields; a same UID with different data is not success.
        def relevant(raw):
            text = raw.decode("utf-8").replace("\r\n ", "").replace("\r\n\t", "")
            return sorted(line for line in text.splitlines() if line.startswith(("UID:", "DTSTART:", "DTEND:", "SUMMARY:")))
        if response.status_code == 200 and relevant(response.content) == relevant(expected):
            return {"status": "succeeded", "receipt": {"uid": action["id"] + "@screenmind", "reconciled": True}}
        return {"status": "unknown", "receipt": {"reason": "unable_to_verify"}}


class ActionService:
    def __init__(self, store, settings, tools=None):
        self.store = store
        store.action_service = self
        registered = [ExportCalendar(settings.action_path)]
        if settings.caldav_url:
            registered.append(CalDAVCalendar(settings))
        self.tools = {tool.name: tool for tool in (tools if tools is not None else registered)}

    def get(self, aid):
        row = self.store.db.execute("SELECT * FROM external_actions WHERE id=? AND owner=?", (aid, self.store.owner)).fetchone()
        if not row:
            raise DomainError("动作不存在", 404)
        result = dict(row)
        result.pop("owner")
        result["parameters"] = json.loads(result["parameters"])
        result["receipt"] = json.loads(result["receipt"]) if result["receipt"] else None
        return result

    def propose(self, wid, body):
        with self.store.transaction():
            work = self.store.work(wid)
            previous = self.store.replay("external:" + wid, body)
            if previous:
                action = self.get(previous)
                return self.store.task(action["task_id"]), True
            if work["memory_version"] != body["expected_state_version"]:
                raise DomainError("正式状态已变化，请重新预览")
            if body["tool"] not in self.tools:
                raise DomainError("动作执行器尚未配置", 422)
            unsettled = self.store.db.execute("""SELECT 1 FROM external_actions WHERE owner=? AND work_id=? AND tool=?
                AND json_extract(parameters,'$.item_id')=? AND (status IN ('authorized','executing','unknown','reconciling')
                OR (status='succeeded' AND base_version=?))""",
                (self.store.owner, wid, body["tool"], body["item_id"], work["memory_version"])).fetchone()
            if unsettled:
                raise DomainError("该日程已有进行中、结果未知或当前版本已成功的动作，请查看原动作记录")
            resolve_item = getattr(self.store.registry.get(work["subagent_id"]), "calendar_item", None)
            item = resolve_item(work["state"], body["item_id"]) if resolve_item else None
            if not item or not item.get("at"):
                raise DomainError("目前日历动作只支持已确认的 Schedule 条目", 422)
            start, end = datetime.fromisoformat(item["at"]), datetime.fromisoformat(body["end"])
            if end <= start or (end-start).total_seconds() > 7*86400:
                raise DomainError("结束时间必须晚于开始时间，且相差不超过7天", 422)
            parameters = {"title": item["title"], "start": item["at"], "end": body["end"], "item_id": body["item_id"]}
            parameters["calendar_target"] = getattr(self.tools[body["tool"]], "url", "download")
            aid, tid, stamp = "action_" + uuid.uuid4().hex[:16], "task_" + uuid.uuid4().hex[:16], now()
            self.store.db.execute("""INSERT INTO tasks(id,owner,instruction,screen_ids,status,revision,created_at,updated_at,
                work_id,subagent_id,memory_base,interaction_protocol) VALUES(?,?,?,'[]','waiting_input',1,?,?,?,?,?,'cloud-v1')""",
                (tid, self.store.owner, "授权日历动作", stamp, stamp, wid, work["subagent_id"], work["memory_version"]))
            self.store.db.execute("INSERT INTO external_actions VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                (aid, self.store.owner, tid, wid, work["memory_version"], body["tool"], encode(parameters), "proposed", None, stamp, stamp, None))
            self.store._save_interaction(self.store.task(tid), {"kind": "action_proposal", "action_id": aid,
                "base_state_version": work["memory_version"], "tool": body["tool"], "parameters": parameters, "execution_enabled": True})
            self.store.receipt("external:" + wid, body, aid)
            return self.store.task(tid), False

    def authorize(self, task, body, output):
        action = self.get(output["action_id"])
        if body["action_id"] != action["id"] or action["status"] != "proposed" or task["status"] != "waiting_input":
            raise DomainError("动作已处理或目标不匹配")
        if body["kind"] == "reject_action":
            state = "rejected"
            self.store.db.execute("UPDATE tasks SET status='cancelled',revision=revision+1 WHERE id=?", (task["id"],))
        else:
            if self.store.work(action["work_id"])["memory_version"] != action["base_version"]:
                raise DomainError("正式状态已变化，必须重新预览动作")
            duplicate = self.store.db.execute("""SELECT 1 FROM external_actions WHERE owner=? AND work_id=?
                AND tool=? AND id<>? AND json_extract(parameters,'$.item_id')=?
                AND (status IN ('authorized','executing','unknown','reconciling') OR (status='succeeded' AND base_version=?))""",
                (self.store.owner, action["work_id"], action["tool"], action["id"], action["parameters"]["item_id"], action["base_version"])).fetchone()
            if duplicate:
                raise DomainError("同一日程已有授权或成功动作，不能重复授权此预览")
            state = "authorized"
            # Waiting status is kept until executor records a verified result. It is not success.
        self.store.db.execute("UPDATE external_actions SET status=?,updated_at=? WHERE id=?", (state, now(), action["id"]))

    async def tick(self):
        row = self.store.db.execute("SELECT id FROM external_actions WHERE owner=? AND status='authorized' ORDER BY created_at LIMIT 1", (self.store.owner,)).fetchone()
        if not row:
            return False
        action = self.get(row[0])
        with self.store.transaction():
            if self.store.work(action["work_id"])["memory_version"] != action["base_version"]:
                self._finish(action, {"status": "stale", "receipt": {"reason": "state_changed_before_dispatch"}})
                return True
            self.store.db.execute("UPDATE external_actions SET status='executing',updated_at=? WHERE id=?", (now(), action["id"]))
        try:
            result = await self.tools[action["tool"]].execute(action)
        except asyncio.CancelledError:
            with self.store.transaction():
                self._finish(action, {"status": "unknown", "receipt": {"reason": "execution_interrupted"}})
            raise
        except Exception:
            result = {"status": "unknown", "receipt": {"reason": "execution_result_unknown"}}
        with self.store.transaction():
            self._finish(action, result)
        return True

    def _finish(self, action, result):
        if result["status"] not in {"succeeded", "failed", "unknown", "stale"}:
            result = {"status": "unknown", "receipt": {"reason": "invalid_tool_receipt"}}
        self.store.db.execute("UPDATE external_actions SET status=?,receipt=?,updated_at=? WHERE id=?",
            (result["status"], encode(result["receipt"]), now(), action["id"]))
        status = "completed" if result["status"] == "succeeded" else "failed"
        self.store.db.execute("UPDATE tasks SET status=?,revision=revision+1,error=?,updated_at=? WHERE id=?",
            (status, None if status == "completed" else "外部动作结果：" + result["status"], now(), action["task_id"]))
        self.store.event(action["task_id"], "action." + result["status"], {"action_id": action["id"], "tool": action["tool"]})

    async def reconcile(self, aid):
        action = self.get(aid)
        if action["status"] != "unknown":
            raise DomainError("仅对结果未知的动作执行核对")
        # Claim first so simultaneous reconcile requests cannot dispatch twice.
        self.store.db.execute("UPDATE external_actions SET status='reconciling' WHERE id=?", (aid,))
        try:
            result = await self.tools[action["tool"]].reconcile(action)
        except asyncio.CancelledError:
            self.store.db.execute("UPDATE external_actions SET status='unknown' WHERE id=?", (aid,))
            raise
        except Exception:
            result = {"status": "unknown", "receipt": {"reason": "verification_unavailable"}}
        with self.store.transaction():
            self._finish(action, result)
        return self.get(aid)

    async def run(self):
        while True:
            worked = await self.tick()
            await asyncio.sleep(.05 if worked else .5)
