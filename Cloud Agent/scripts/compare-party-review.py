"""Compare a standalone assistant and two Cloud Agent configurations on one synthetic change."""
import argparse
from contextlib import closing
from dataclasses import replace
from datetime import datetime, timezone
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import time
from uuid import uuid4

from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from screenmind.api import create_app
from screenmind.config import Settings
from screenmind.simplemem_adapter import JsonLLMClient


def post(client, path, **body):
    response = client.post("/api/" + path, json={"event_id": uuid4().hex, **body})
    response.raise_for_status()
    return response.json()


def upload(client, lines, day):
    stamp = f"2026-11-{day}T12:00:00+08:00"
    return post(client, "screens", session_id="synthetic-party", app_package="com.example.party",
                app_name="合成测试", page_title="聚会测试群", captured_at=stamp, blocks=[
                    {"id": f"b{i}", "kind": "chat", "speaker": speaker, "text": text, "message_at": stamp}
                    for i, (speaker, text) in enumerate(lines)])["screen"]


def wait(client, task_id, timeout):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        response = client.get("/api/tasks/" + task_id)
        response.raise_for_status()
        task = response.json()["task"]
        if task["status"] in {"waiting_input", "completed", "failed", "cancelled"}:
            return task
        time.sleep(.2)
    raise TimeoutError("Cloud Agent timeout")


def snapshot(client, wid):
    response = client.get("/api/works/" + wid)
    response.raise_for_status()
    return response.json()["work"]


def seed(settings):
    with TestClient(create_app(replace(settings, provider="mock", evidence_review=False))) as client:
        wid = post(client, "works", title="合成聚会", subagent_id="party")["work"]["id"]
        old = upload(client, [("组织者", text) for text in (
            "参加成员：小林、小王、小李、小陈、小张、小周、小吴、小赵",
            "套餐总价：688元", "人均预算：150元", "蛋糕负责人：小林")], "18")
        task = post(client, "tasks", work_id=wid, screen_ids=[old["id"]], instruction="建立聚会计划",
                    interaction_protocol="cloud-v1")["task"]
        task = wait(client, task["id"], 15)
        out = task["interaction"]
        post(client, f"tasks/{task['id']}/responses", kind="accept_state", expected_revision=task["revision"],
             output_version=out["output_version"], proposal_id=out["proposal_id"])
        work = snapshot(client, wid)
        assert work["memory_version"] == 1
        new = upload(client, [("小王", "可能晚到，但我确定会参加。"),
                              ("小林", "确定来不了了，蛋糕我还没订。"),
                              ("组织者", "餐厅说整桌价格仍是688元。")], "19")
    return wid, work, old, new


def checks(result, required_ref):
    return {
        "attendance": result.get("attending") == 7,
        "correct_member": result.get("declined") == ["小林"],
        "share": str(result.get("share")) == "98.29",
        "cake_open": result.get("cake_open") is True,
        "departure_evidence": required_ref in (result.get("source_refs") or []),
    }


def framework_result(task):
    plan = (task.get("memory_proposal") or {}).get("plan") or {}
    people = plan.get("participants", [])
    fields = (task.get("card") or {}).get("fields", [])
    cost = next((f["value"] for f in fields if f["id"] == "cost"), "")
    return {
        "attending": sum(x["status"] == "attending" for x in people),
        "declined": [x["name"] for x in people if x["status"] == "declined"],
        "share": "98.29" if "¥98.29/人" in cost else None,
        "cake_open": any("蛋糕" in f["label"] and "待重新分配" in f["value"] for f in fields),
        "source_refs": sorted({ref for f in fields for ref in f["source_refs"]}),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", action="store_true", help="Permit real-model calls with synthetic materials")
    parser.add_argument("--output", type=Path, default=Path("artifacts/contest-party-comparison.json"))
    args = parser.parse_args()
    cfg = Settings.from_env()
    if not args.run or cfg.provider != "openai-compatible":
        parser.error("Configure a real provider and pass --run")
    report = {"at": datetime.now(timezone.utc).isoformat(), "synthetic": True, "model": cfg.model,
              "scenario": "party_exit_v1", "runs": {}}
    args.output.parent.mkdir(parents=True, exist_ok=True)

    def record(name, value):
        report["runs"][name] = value
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    with tempfile.TemporaryDirectory(prefix="screenmind-review-") as temp:
        cfg = replace(cfg, db_path=Path(temp) / "seed.db", allowed_hosts=("testserver",), api_key="",
                      memory_mode="basic", caldav_url="", evidence_review=False)
        wid, work, old, new = seed(cfg)
        ref = new["id"] + ":b1"
        instruction = "只分析聚会变化，核对确定参加人数、退出成员、人均费用及蛋糕是否需要新负责人。引用原文。"
        client = JsonLLMClient(cfg)
        start = time.monotonic()
        try:
            result = client.extract_json(client.chat_completion([
                {"role": "system", "content": "你协助组织聚会。根据已确认状态和同样的原始消息给出结果，"
                 "不得把晚到当成退出。只返回 JSON，键为 attending(整数)、declined(姓名数组)、"
                 "share(两位小数字符串)、cake_open(布尔值)、source_refs(来源ID数组)。"},
                {"role": "user", "content": json.dumps({
                    "instruction": instruction, "confirmed": work["state"]["plan"],
                    "history": old, "new": new,
                    "reference_format": "screen.id:block.id，例如 " + old["id"] + ":b0"
                }, ensure_ascii=False)}
            ], temperature=0))
            record("assistant", {"seconds": round(time.monotonic() - start, 2), "result": result,
                                 "checks": checks(result, ref), "usage": client.last_usage})
        except Exception as exc:
            record("assistant", {"error_type": type(exc).__name__, "seconds": round(time.monotonic() - start, 2)})
        for enabled in (False, True):
            name = "review_available" if enabled else "review_disabled"
            path = Path(temp) / (name + ".db")
            with closing(sqlite3.connect(cfg.db_path)) as source, closing(sqlite3.connect(path)) as destination:
                source.backup(destination)
            with TestClient(create_app(replace(cfg, evidence_review=enabled, db_path=path))) as cloud:
                start = time.monotonic()
                task = post(cloud, "tasks", work_id=wid, screen_ids=[new["id"]],
                            instruction=instruction + "若有证据审阅工具，先用它核对。",
                            interaction_protocol="cloud-v1")["task"]
                tid = task["id"]
                try:
                    task = wait(cloud, tid, cfg.task_timeout + 10)
                except TimeoutError:
                    record(name, {"status": "timeout", "seconds": round(time.monotonic() - start, 2)})
                    continue
                elapsed = round(time.monotonic() - start, 2)
                result = framework_result(task)
                events = cloud.get("/api/events", params={"task_id": tid}).json()["events"]
                delegation = cloud.get(f"/api/tasks/{tid}/delegations").json()["delegations"]
                record(name, {"seconds": elapsed, "status": task["status"], "result": result,
                              "checks": checks(result, ref),
                              "main_model_turns": sum(e["kind"] == "model.requested" for e in events),
                              "helper_statuses": [d["status"] for d in delegation],
                              "formal_version_after_proposal": snapshot(cloud, wid)["memory_version"]})
    print(args.output)


if __name__ == "__main__":
    main()
