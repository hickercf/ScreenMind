"""Run the entire API roundtrip against a running local server, without a browser."""
import argparse
import json
from pathlib import Path
import sys
import time
import uuid
import httpx

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8765")
    args = parser.parse_args()
    import os
    key = os.getenv("SCREENMIND_API_KEY", "")
    headers = {"Authorization": "Bearer " + key} if key else {}
    with httpx.Client(base_url=args.url, headers=headers, timeout=10) as client:
        def post(path, body):
            response = client.post(path, json=body)
            response.raise_for_status()
            return response.json()

        def wait(tid):
            for _ in range(300):
                response = client.get("/api/tasks/" + tid)
                response.raise_for_status()
                task = response.json()["task"]
                if task["status"] == "waiting_input":
                    return task
                if task["status"] == "failed":
                    raise RuntimeError(task["error"])
                time.sleep(0.5)
            raise TimeoutError("Task did not finish")

        ids = []
        for name in ["screen-notice.json", "screen-chat.json"]:
            body = json.loads((ROOT / "examples" / name).read_text(encoding="utf-8"))
            ids.append(post("/api/screens", body)["screen"]["id"])
        task = post("/api/tasks", {"event_id": uuid.uuid4().hex, "instruction": "整理这些屏幕中的报名要求并保留出处", "screen_ids": ids})["task"]
        task = wait(task["id"])
        field = task["card"]["fields"][0]
        post(f"/api/tasks/{task['id']}/actions", {"event_id": uuid.uuid4().hex,
             "expected_revision": task["revision"], "card_version": task["card_version"], "kind": "revise",
             "message": "保留我的修订", "edits": {field["id"]: "预审时间：周四 17:00"}})
        task = wait(task["id"])
        assert any(f["id"] == field["id"] and f["value"] == "预审时间：周四 17:00" and f["origin"] == "user" for f in task["card"]["fields"])
        done = post(f"/api/tasks/{task['id']}/actions", {"event_id": uuid.uuid4().hex,
                    "expected_revision": task["revision"], "card_version": task["card_version"], "kind": "confirm"})
        print(json.dumps({"task_id": task["id"], "status": done["task"]["status"], "card_version": task["card_version"], "edited_value_retained": True}, ensure_ascii=False))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
