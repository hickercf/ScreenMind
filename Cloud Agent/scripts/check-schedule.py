"""Opt-in real-model Schedule check; synthetic data, isolated database, no external actions."""
import argparse
from dataclasses import replace
from datetime import datetime, timezone, timedelta
import json
from pathlib import Path
import time
import uuid

from fastapi.testclient import TestClient
from screenmind.api import create_app
from screenmind.config import Settings


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file")
    parser.add_argument("--run", action="store_true")
    args = parser.parse_args()
    if not args.run:
        parser.error("--run permits billed calls with synthetic evidence")
    cfg = Settings.from_env(args.env_file)
    if cfg.provider != "openai-compatible":
        parser.error("Configure a real model")
    cfg = replace(cfg, db_path=Path("artifacts") / ("schedule-live-" + uuid.uuid4().hex + ".db"),
        allowed_hosts=("testserver",), api_key="", memory_mode="basic", caldav_url="")
    report = {"synthetic": True, "model": cfg.model, "stages": [], "passed": False}
    def save():
        Path("artifacts/schedule-live-check.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    with TestClient(create_app(cfg)) as client:
        def post(path, body):
            r = client.post("/api/" + path, json={"event_id": uuid.uuid4().hex, **body})
            r.raise_for_status()
            return r.json()
        def wait(tid, expected):
            start = time.monotonic()
            while time.monotonic()-start < cfg.task_timeout + 35:
                task = client.get("/api/tasks/"+tid).json()["task"]
                if task["status"] in {"waiting_input", "failed", "completed"}:
                    out = task.get("interaction") or {}
                    report["stages"].append({"task_id": tid, "status": task["status"], "kind": out.get("kind"),
                        "seconds": round(time.monotonic()-start, 2)})
                    save()
                    assert out.get("kind") == expected, (task["status"], out.get("kind"), task.get("error"))
                    return task
                time.sleep(.2)
            raise TimeoutError("Schedule check timeout")
        def respond(task, kind, **value):
            out = task["interaction"]
            return post("tasks/"+task["id"]+"/responses", {"kind": kind, "expected_revision": task["revision"],
                "output_version": out["output_version"], **value})
        wid = post("works", {"title": "合成答辩日程验证", "subagent_id": "schedule"})["work"]["id"]
        def task(text, stamp):
            sid = post("screens", {"session_id": wid, "app_package": "com.example", "app_name": "合成测试",
                "page_title": "日程通知", "captured_at": stamp, "blocks": [{"id": "notice", "kind": "chat", "speaker": "老师",
                "text": text, "message_at": stamp}]})["screen"]["id"]
            return post("tasks", {"work_id": wid, "instruction": "根据通知更新日程，检查明确的依赖冲突；冲突时询问我，确认前不修改正式状态。",
                "screen_ids": [sid], "interaction_protocol": "cloud-v1"})["task"]["id"]
        first = wait(task("安排两项日程：2026年12月10日17:00提交材料，18:00参加答辩。全部使用UTC+8时区。提交材料必须早于答辩。", "2026-11-08T10:00:00+08:00"), "state_proposal")
        respond(first, "accept_state", proposal_id=first["interaction"]["proposal_id"])
        changed = wait(task("答辩改到2026年12月10日16:00，UTC+8时区。提交材料的时间尚未调整，仍要求先交材料再答辩。", "2026-11-09T10:00:00+08:00"), "clarification")
        respond(changed, "answer", question_id=changed["interaction"]["question_id"], answer="确认答辩提前到16:00，提交材料改到同一天15:00，都是UTC+8。")
        changed = wait(changed["id"], "state_proposal")
        respond(changed, "accept_state", proposal_id=changed["interaction"]["proposal_id"])
        work = client.get("/api/works/"+wid).json()["work"]
        assert work["memory_version"] == 2
        hours = sorted(datetime.fromisoformat(i["at"]).astimezone(timezone(timedelta(hours=8))).hour for i in work["state"]["items"].values())
        assert hours == [15, 16], hours
        report.update(passed=True, confirmed_state=work["state"], formal_version=2)
        save()
        print(json.dumps({"passed": True, "model": cfg.model, "stages": report["stages"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
