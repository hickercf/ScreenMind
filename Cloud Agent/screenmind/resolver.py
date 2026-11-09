"""Explicit work association, separate from capability routing. No semantic guessing."""
from .store import DomainError


class WorkResolver:
    def __init__(self, store):
        self.store = store

    def inspect(self, screen_ids, work_id=None):
        ids = self.store.validate_screens(screen_ids)
        selected = self.store.work(work_id) if work_id else None
        bound = set()
        for sid in ids:
            row = self.store.db.execute("SELECT work_id FROM work_screens WHERE screen_id=?", (sid,)).fetchone()
            if row:
                self.store.work(row[0])
                bound.add(row[0])
            elif selected:
                used = self.store.db.execute("""SELECT 1 FROM tasks,json_each(tasks.screen_ids) s
                    WHERE tasks.owner=? AND s.value=? AND tasks.work_id IS NULL LIMIT 1""",
                    (self.store.owner, sid)).fetchone()
                if used:
                    raise DomainError("屏幕已用于独立任务，不能重新绑定到事项", 422)
        if selected and bound - {work_id}:
            raise DomainError("材料属于其他事项，请重新选择材料", 422)
        if selected:
            return {"status": "selected", "work_id": work_id, "candidate_work_ids": [work_id], "reason": "explicit_selection"}
        return {"status": "needs_selection" if bound else "unassigned", "work_id": None,
                "candidate_work_ids": sorted(bound), "reason": "source_binding" if bound else "no_binding"}

    def resolve(self, screen_ids, work_id=None):
        result = self.inspect(screen_ids, work_id)
        if result["status"] == "needs_selection":
            raise DomainError("材料已关联事项，请明确选择匹配的 work_id", 422)
        return self.store.work(work_id) if work_id else None
