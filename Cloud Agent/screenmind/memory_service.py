"""Durable indexing outside SQLite transactions; retrieval always returns original evidence."""
import asyncio
from copy import deepcopy
import hashlib
import json
import time

from .memory import screen_with_refs
from .retrieval import terms
from .store import encode, now


def enqueue(store, wid, ref):
    store.db.execute("""INSERT OR IGNORE INTO memory_jobs(owner,work_id,source_ref,status,attempts)
        VALUES(?,?,?,'pending',0)""", (store.owner, wid, ref))


def source_record(store, wid, ref):
    if ref.startswith("input:"):
        _, tid, revision = ref.split(":")
        row = store.db.execute("""SELECT i.payload FROM inputs i JOIN tasks t ON t.id=i.task_id
            WHERE t.owner=? AND t.work_id=? AND t.id=? AND i.revision=?""", (store.owner, wid, tid, int(revision))).fetchone()
        if not row:
            return None
        body = json.loads(row[0])
        if body.get("kind") != "revise":
            return None
        item = {"kind": "user_input", "source_ref": ref, "message": body.get("message", ""),
                "edits": body.get("edits", {}), "acceptance": "historical_input_not_current_instruction"}
        return {"item": item, "text": item["message"] + encode(item["edits"]), "timestamp": None, "speaker": "user"}
    sid, bid = ref.split(":", 1)
    row = store.db.execute("""SELECT s.payload,s.created_at FROM screens s JOIN work_screens ws ON ws.screen_id=s.id
        WHERE s.owner=? AND s.id=? AND ws.work_id=? AND s.deleted_at IS NULL""", (store.owner, sid, wid)).fetchone()
    if not row:
        return None
    screen = screen_with_refs({"id": sid, **json.loads(row[0])})
    block = next((b for b in screen["blocks"] if b["id"] == bid), None)
    if not block:
        return None
    screen["blocks"] = [block]
    return {"item": {"kind": "screen", "recorded_at": row[1], "acceptance": "evidence_only", "screen": screen},
            "text": block["text"], "timestamp": block.get("message_at"), "speaker": block.get("speaker")}


class MemoryService:
    def __init__(self, store, encoder, index, extractor):
        self.store, self.encoder, self.index, self.extractor = store, encoder, index, extractor
        self.lock = asyncio.Lock()

    async def index_one(self):
        row = self.store.db.execute("""SELECT * FROM memory_jobs WHERE owner=? AND status IN ('pending','delete_pending')
            AND attempts<3 ORDER BY rowid LIMIT 1""", (self.store.owner,)).fetchone()
        if not row:
            return False
        wid, ref, generation = row["work_id"], row["source_ref"], row["generation"]
        delete = row["status"] == "delete_pending"
        self.store.db.execute("UPDATE memory_jobs SET status='running',attempts=attempts+1 WHERE owner=? AND work_id=? AND source_ref=?",
                              (self.store.owner, wid, ref))
        started = time.monotonic()
        try:
            record = source_record(self.store, wid, ref)
            if delete or not record:
                async with self.lock:
                    await asyncio.to_thread(self.index.delete, self.store.owner, wid, ref)
                self.store.db.execute("UPDATE memory_jobs SET status='deleted' WHERE owner=? AND work_id=? AND source_ref=?",
                                      (self.store.owner, wid, ref))
                return True
            entry = await asyncio.to_thread(self.extractor.extract, record["text"], record["timestamp"], record["speaker"])
            vector = (await asyncio.to_thread(self.encoder.encode, [entry["text"]]))[0]
            current = self.store.db.execute("SELECT generation,status FROM memory_jobs WHERE owner=? AND work_id=? AND source_ref=?",
                                            (self.store.owner, wid, ref)).fetchone()
            if current["generation"] != generation or not source_record(self.store, wid, ref):
                return True
            async with self.lock:
                await asyncio.to_thread(self.index.put, self.store.owner, wid, ref, vector)
            current = self.store.db.execute("SELECT generation FROM memory_jobs WHERE owner=? AND work_id=? AND source_ref=?",
                                            (self.store.owner, wid, ref)).fetchone()
            if current[0] != generation or not source_record(self.store, wid, ref):
                return True  # canonical whitelist already excludes stale vector; delete job will purge it
            with self.store.transaction():
                self.store.db.execute("INSERT OR REPLACE INTO memory_entries VALUES(?,?,?,?,?,?,?)",
                    (self.store.owner, wid, ref, encode(entry), self.encoder.name, self.extractor.name, generation))
                self.store.db.execute("UPDATE memory_jobs SET status='done',error=NULL WHERE owner=? AND work_id=? AND source_ref=?",
                                      (self.store.owner, wid, ref))
                self.store.event(None, "memory.indexed", {"work_id": wid, "source_ref": ref,
                    "encoder": self.encoder.name, "extractor": self.extractor.name, "elapsed_ms": round((time.monotonic()-started)*1000)})
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            current = self.store.db.execute("SELECT generation FROM memory_jobs WHERE owner=? AND work_id=? AND source_ref=?",
                                            (self.store.owner, wid, ref)).fetchone()
            if current[0] == generation:
                self.store.db.execute("UPDATE memory_jobs SET status=?,error=? WHERE owner=? AND work_id=? AND source_ref=?",
                    ("delete_pending" if delete else "pending", type(exc).__name__, self.store.owner, wid, ref))
        return True

    async def run(self):
        while True:
            worked = await self.index_one()
            await asyncio.sleep(.05 if worked else .5)

    async def enrich(self, task):
        if not task["work_id"]:
            return task
        started = time.monotonic()
        base = deepcopy(task["memory_context"])
        wid = task["work_id"]
        query = (task["instruction"] + " " + " ".join(i["message"] for i in base["authenticated_user_inputs"][-3:])
                 + " " + " ".join(b["text"] for s in base["screens"] for b in s["blocks"]))[:6000]
        try:
            rows = self.store.db.execute("""SELECT e.source_ref,e.payload FROM memory_entries e JOIN memory_jobs j
                ON e.owner=j.owner AND e.work_id=j.work_id AND e.source_ref=j.source_ref
                WHERE e.owner=? AND e.work_id=? AND j.status='done' AND e.generation=j.generation
                AND e.encoder=? AND e.extractor=? ORDER BY j.rowid DESC LIMIT 2000""",
                (self.store.owner, wid, self.encoder.name, self.extractor.name)).fetchall()
            entries = {r[0]: json.loads(r[1]) for r in rows}
            query_terms = terms(query)
            lexical = sorted(entries, key=lambda r: len(terms(entries[r]["text"]) & query_terms), reverse=True)
            lexical = [r for r in lexical if terms(entries[r]["text"]) & query_terms][:12]
            symbolic = [r for r in entries if any(v and v in query for k in ("keywords", "persons", "entities") for v in entries[r].get(k, []))][:12]
            degraded = False
            try:
                vector = (await asyncio.to_thread(self.encoder.encode, [query], True))[0]
                async with self.lock:
                    semantic = await asyncio.to_thread(self.index.search, self.store.owner, wid, vector, 12)
                semantic = [r for r in semantic if r in entries]
            except Exception:
                semantic, degraded = [], True
            scores = {}
            for ranking in (semantic, lexical, symbolic):
                for rank, ref in enumerate(ranking):
                    scores[ref] = scores.get(ref, 0) + 1/(60 + rank + 1)
            # Raw supplementation for jobs that have not completed indexing, including failed jobs.
            pending = self.store.db.execute("""SELECT source_ref FROM memory_jobs WHERE owner=? AND work_id=?
                AND status NOT IN ('done','deleted','delete_pending') ORDER BY rowid DESC LIMIT 100""", (self.store.owner, wid))
            raw = []
            for row in pending:
                record = source_record(self.store, wid, row[0])
                if record and terms(record["text"]) & query_terms:
                    raw.append((len(terms(record["text"]) & query_terms), row[0]))
            chosen = [r for _, r in sorted(raw, reverse=True)[:4]] + sorted(scores, key=scores.get, reverse=True)[:8]
            items = []
            for ref in dict.fromkeys(chosen):
                record = source_record(self.store, wid, ref)
                if record:
                    items.append(record["item"])
            items += base["history"]
            history, seen, size = [], set(), 0
            current_refs = {b["source_ref"] for s in base["screens"] for b in s["blocks"]}
            current_refs.update(i["source_ref"] for i in base["authenticated_user_inputs"])
            for item in items:
                item = deepcopy(item)
                if item["kind"] == "screen":
                    item["screen"]["blocks"] = [b for b in item["screen"]["blocks"] if b["source_ref"] not in seen | current_refs]
                    refs = {b["source_ref"] for b in item["screen"]["blocks"]}
                else:
                    refs = {item["source_ref"]} - seen - current_refs
                if not refs:
                    continue
                cost = len(json.dumps(item, ensure_ascii=False))
                if size + cost > base["history_char_budget"]:
                    base["history_omitted"] = True
                    continue
                seen.update(refs)
                history.append(item)
                size += cost
            base.update(mode="hybrid-degraded" if degraded else "hybrid", history=history, history_chars_used=size,
                        retrieval={"encoder": self.encoder.name, "extractor": self.extractor.name,
                                   "indexed_candidates": len(entries), "unindexed_candidates": len(raw),
                                   "elapsed_ms": round((time.monotonic()-started)*1000)})
            # No await between validity check and storing the assembled snapshot.
            if self.store.current(task["id"], task["revision"]) and self.store.sources_live(task):
                self.store.db.execute("UPDATE tasks SET memory_context=? WHERE id=?", (encode(base), task["id"]))
                task = {**task, "memory_context": base}
        except Exception as exc:
            self.store.event(task["id"], "memory.fallback", {"revision": task["revision"], "reason": type(exc).__name__})
        return task
