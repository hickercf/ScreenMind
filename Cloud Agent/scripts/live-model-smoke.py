"""Opt-in integration test; calls the configured real model using synthetic evidence only."""
import argparse
from dataclasses import replace
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi.testclient import TestClient
from screenmind.api import create_app
from screenmind.config import Settings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", action="store_true", help="Explicitly enable billed model requests")
    parser.add_argument("--db", default="data/live-model-check.sqlite3")
    args = parser.parse_args()
    if not args.run:
        parser.error("Pass --run to invoke the real model")
    settings = Settings.from_env()
    if settings.provider != "openai-compatible":
        raise SystemExit("Configure a real provider before running this check")
    settings = replace(settings, db_path=Path(args.db), allowed_hosts=("testserver",), api_key="")
    report = {"model": settings.model, "base_url": settings.base_url,
              "at": datetime.now(timezone.utc).isoformat(), "passed": False, "stages": []}
    output = Path("artifacts/real-model-report.json")
    output.parent.mkdir(exist_ok=True)

    def record():
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    with TestClient(create_app(settings)) as client:
        def post(path, body):
            response = client.post("/api/" + path, json={"event_id": uuid.uuid4().hex, **body})
            if response.status_code >= 300:
                raise RuntimeError(f"Local API rejected {path}: HTTP {response.status_code}")
            return response.json()

        def screen(texts):
            return post("screens", {"session_id": "synthetic-live-check", "app_package": "com.tencent.mm",
                "app_name": "微信 · 真实模型合成测试", "page_title": "生日聚会验证",
                "captured_at": datetime.now(timezone.utc).isoformat(),
                "blocks": [{"id": "b" + str(i), "kind": "chat", "speaker": speaker, "text": text}
                           for i, (speaker, text) in enumerate(texts)]})["screen"]["id"]

        def wait(tid, stage):
            start = time.monotonic()
            while time.monotonic() - start < settings.task_timeout + 10:
                task = client.get("/api/tasks/" + tid).json()["task"]
                if task["status"] == "failed":
                    raise RuntimeError(task["error"])
                if task["status"] == "waiting_input":
                    events = client.get("/api/events", params={"task_id": tid}).json()["events"]
                    report["stages"].append({"stage": stage, "task_id": tid, "elapsed_seconds": round(time.monotonic() - start, 2),
                        "revision": task["revision"], "card_version": task["card_version"], "card": task["card"],
                        "model_requests_total": sum(e["kind"] == "model.requested" for e in events),
                        "tool_rejections_total": sum(e["kind"] == "tool.rejected" for e in events)})
                    record()
                    print(json.dumps({"stage": stage, "status": task["status"], "elapsed_seconds": report["stages"][-1]["elapsed_seconds"]}), flush=True)
                    return task
                time.sleep(0.2)
            raise RuntimeError("Live test timed out")

        def act(task, kind, **extra):
            return post("tasks/" + task["id"] + "/actions", {"kind": kind, "expected_revision": task["revision"], **extra})["task"]

        def state(work_id):
            return client.get("/api/works/" + work_id).json()["work"]

        def people(task):
            return {p["name"]: p for p in task["memory_proposal"]["plan"]["participants"]}

        def check(condition, message):
            if not condition:
                raise RuntimeError("Semantic check failed: " + message)

        try:
            work = post("works", {"title": "真实模型验证 · 生日 Party", "subagent_id": "party"})["work"]
            wid = work["id"]
            report["work_id"] = wid
            sid = screen([
                ("组织者", "咱们定在2026年11月14日18:30去湖畔餐厅过生日。小林、小王、小李、小陈、小张、小周、小吴、小赵这八个人都已经确定参加了。"),
                ("组织者", "餐厅这个套餐一共688元，是整桌固定总价，不是每位688。大家的人均预算上限150元，先按套餐费用平摊。"),
                ("小林", "生日蛋糕我负责带，大家不用再买了。")])

            def create(sid, stage):
                task = post("tasks", {"work_id": wid, "instruction": "根据新消息和已确认记忆整理聚会候选方案，分析人数、预算及责任变化，等待确认。", "screen_ids": [sid]})["task"]
                return wait(task["id"], stage)

            initial = create(sid, "initial_plan")
            check(len(people(initial)) == 8 and all(p["status"] == "attending" for p in people(initial).values()), "initial eight attendees")
            plan = initial["memory_proposal"]["plan"]
            check(plan["fixed_cost_cents"] == 68800 and plan["budget_per_person_cents"] == 15000, "total versus per-person cost")
            check(any("蛋糕" in r["title"] and r["owner_id"] == people(initial)["小林"]["id"] for r in plan["responsibilities"]), "cake assignment")
            check(state(wid)["memory_version"] == 0, "draft must not commit")
            act(initial, "confirm", card_version=initial["card_version"])

            tentative = create(screen([("小林", "我这边临时有点事情，可能赶不过来了，还没确定，先别把我划掉，我晚些回复。")]), "tentative_departure")
            check(people(tentative)["小林"]["status"] == "tentative", "uncertainty is not confirmed departure")
            act(tentative, "cancel")
            check(state(wid)["memory_version"] == 1, "cancel preserves confirmed memory")

            departure = create(screen([("小林", "确定了，我今晚确实去不了了，蛋糕也没办法带过去了，麻烦你们另外找个人安排一下。")]), "confirmed_departure")
            check(people(departure)["小林"]["status"] == "declined", "confirmed departure")
            check(sum(p["status"] == "attending" for p in people(departure).values()) == 7, "seven remain")
            check(any("98.29" in f["value"] for f in departure["card"]["fields"]), "recomputed share")
            check(any("待重新分配" in f["value"] for f in departure["card"]["fields"]), "responsibility gap")
            check(state(wid)["memory_version"] == 1, "new draft preserves initial memory")
            act(departure, "revise", message="我刚跟小王确认好了，由小王接手买蛋糕。小林不来的决定不变，其他安排也保持原样。")
            revised = wait(departure["id"], "reassign_cake")
            check(people(revised)["小林"]["status"] == "declined", "revision preserves departure")
            check(any("蛋糕" in r["title"] and r["owner_id"] == people(revised)["小王"]["id"] for r in revised["memory_proposal"]["plan"]["responsibilities"]), "reassignment to Xiaowang")
            act(revised, "confirm", card_version=revised["card_version"])
            check(state(wid)["memory_version"] == 2, "final memory version")
            report["passed"] = True
            report["final_memory_version"] = 2
            record()
            print(json.dumps({"passed": True, "model": settings.model, "work_id": wid, "report": str(output)}), flush=True)
        except Exception as exc:
            # No request payloads, Authorization headers, Settings repr or reasoning are recorded.
            report["error"] = str(exc).replace(settings.model_key, "[redacted]")
            record()
            print(json.dumps({"passed": False, "error": report["error"]}, ensure_ascii=False), flush=True)
            raise SystemExit(1) from None


if __name__ == "__main__":
    main()
