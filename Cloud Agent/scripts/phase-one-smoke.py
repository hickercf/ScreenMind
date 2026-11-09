"""Synthetic mobile-contract smoke over real HTTP. Run against a dedicated mock server."""
import argparse
import json
import os
from pathlib import Path
import time
import uuid

import httpx


def uid():
    return uuid.uuid4().hex


def run(base_url):
    key = os.environ.get("SCREENMIND_API_KEY", "")
    headers = {"Authorization": "Bearer " + key} if key else {}
    with httpx.Client(base_url=base_url, headers=headers, timeout=10) as client:
        def request(method, path, body=None):
            result = client.request(method, path, json=body) if body is not None else client.request(method, path)
            result.raise_for_status()
            return result.json()

        health = request("GET", "/api/health")
        if health["provider"] != "mock":
            raise RuntimeError("Use a dedicated mock server; this smoke must not call a real model")
        contract = request("GET", "/api/contracts")
        assert contract["version"] == "cloud-v1"
        work = request("POST", "/api/works", {"event_id": uid(), "title": "合成端云联调", "subagent_id": "information"})["work"]
        sources, trace = [], []
        for round_number, text in enumerate(["材料周五17:00截止", "材料改为周六15:00截止"], 1):
            source = request("POST", "/api/screens", {"event_id": uid(), "session_id": "synthetic-mobile",
                "app_package": "com.example.screenmind", "app_name": "联调示例", "page_title": "合成通知",
                "captured_at": "2026-11-09T18:00:00+08:00", "blocks": [
                    {"id": "deadline", "kind": "paragraph", "text": text, "bbox": [10, 20, 900, 100]}]})["screen"]
            sources.append(source["id"])
            resolution = request("POST", "/api/works/resolve", {"screen_ids": [source["id"]], "work_id": work["id"]})
            assert resolution["status"] == "selected"
            task = request("POST", "/api/tasks", {"event_id": uid(), "instruction": "整理通知",
                "screen_ids": [source["id"]], "work_id": work["id"], "interaction_protocol": "cloud-v1"})["task"]
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                task = request("GET", "/api/tasks/" + task["id"])["task"]
                if task["status"] == "waiting_input":
                    break
                if task["status"] in {"failed", "cancelled"}:
                    raise RuntimeError("Synthetic task failed: " + str(task["error"]))
                time.sleep(.05)
            else:
                raise RuntimeError("Timed out waiting for synthetic card")
            output = task["interaction"]
            assert output["kind"] == "state_proposal" and output["base_state_version"] == round_number - 1
            assert request("GET", "/api/works/" + work["id"])["work"]["memory_version"] == round_number - 1
            history = task["memory_context"]["history"]
            if round_number == 2:
                assert any(h.get("screen", {}).get("id") == sources[0] for h in history)
            body = {"event_id": uid(), "contract_version": "cloud-v1", "kind": "accept_state",
                "expected_revision": output["revision"], "output_version": output["output_version"],
                "proposal_id": output["proposal_id"]}
            endpoint = "/api/tasks/" + task["id"] + "/responses"
            accepted = request("POST", endpoint, body)
            assert accepted["task"]["status"] == "completed"
            assert request("POST", endpoint, body)["replayed"] is True
            state = request("GET", "/api/works/" + work["id"])["work"]
            assert state["memory_version"] == round_number
            trace.append({"round": round_number, "task_id": task["id"], "state_version": state["memory_version"],
                          "history_records": len(history), "duplicate_response_replayed": True})
        return {"synthetic": True, "transport": "HTTP", "provider": "mock", "android_verified": False,
                "result": "passed", "work_id": work["id"], "rounds": trace}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8765")
    args = parser.parse_args()
    report = run(args.base_url)
    path = Path("artifacts/phase-one-http-report.json")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))
