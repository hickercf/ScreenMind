"""Unit tests for the AuraFlow Home SDUI generator and authenticated API."""
import os
import tempfile
import unittest
from datetime import datetime, timezone
from uuid import uuid4

from fastapi.testclient import TestClient
from backend import main


class HomeSduiTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.previous_db = os.environ.get("WEIGUANG_DB")
        os.environ["WEIGUANG_DB"] = self.temp.name + "/home_sdui.sqlite3"
        main.ATTEMPTS.clear()
        self.client = TestClient(main.app)
        self.client.__enter__()
        self.alice = self.register("alice_home")
        self.bob = self.register("bob_home")

    def tearDown(self):
        self.client.__exit__(None, None, None)
        if self.previous_db is None:
            os.environ.pop("WEIGUANG_DB", None)
        else:
            os.environ["WEIGUANG_DB"] = self.previous_db
        self.temp.cleanup()

    def register(self, username):
        resp = self.client.post("/v1/auth/register", json={"username": username, "password": "test-password-123"})
        self.assertEqual(resp.status_code, 201, resp.text)
        return resp.json()

    def req(self, user, method, path, data=None):
        return self.client.request(method, path, json=data, headers={"Authorization": "Bearer " + user["access_token"]})

    def add_event(self, user, text, source_kind="screenshot_ocr", event_type="message"):
        payload = {
            "event_id": str(uuid4()),
            "revision": 1,
            "event_type": event_type,
            "source_kind": source_kind,
            "captured_at": datetime.now(timezone.utc).isoformat(),
            "payload": {"text": text},
        }
        resp = self.req(user, "POST", "/v1/events", payload)
        self.assertEqual(resp.status_code, 200, resp.text)
        return payload

    def test_empty_day_dispatches_explore_and_adopts_to_timeline(self):
        # Empty day -> EMPTY_EXPLORE
        doc = self.req(self.alice, "GET", "/v1/home/sdui").json()
        self.assertEqual(doc["dayMode"], "EMPTY_EXPLORE")
        self.assertEqual(doc["weatherContext"]["mode"], "sunny")
        self.assertEqual(doc["exploreIndex"], 0)

        # Switch weather to rainy & cycle inspiration
        doc = self.req(self.alice, "POST", "/v1/home/sdui/actions", {"action": "set_weather", "weather": "rainy"}).json()
        self.assertEqual(doc["weatherContext"]["mode"], "rainy")
        doc = self.req(self.alice, "POST", "/v1/home/sdui/actions", {"action": "cycle_explore"}).json()
        self.assertEqual(doc["exploreIndex"], 1)

        # Adopt micro-itinerary -> switches to ACTIVE_TIMELINE with 3 confirmed EXPLORE cards
        doc = self.req(self.alice, "POST", "/v1/home/sdui/actions", {"action": "adopt_explore"}).json()
        self.assertEqual(doc["dayMode"], "ACTIVE_TIMELINE")
        self.assertEqual(len(doc["events"]), 3)
        self.assertTrue(all(e["category"] == "EXPLORE" and e["lifecycleStage"] == "CONFIRMED" for e in doc["events"]))

        # Account isolation: Bob still sees EMPTY_EXPLORE
        bob_doc = self.req(self.bob, "GET", "/v1/home/sdui").json()
        self.assertEqual(bob_doc["dayMode"], "EMPTY_EXPLORE")
        self.assertEqual(len(bob_doc["events"]), 0)

    def test_real_clues_classify_connectors_auto_verify_and_cross_day_relay(self):
        # 10:00 morning meeting (confirmed via calendar) + 14:30 travel proposal with return intent + 15:00 dining (conflict < 50m)
        self.add_event(self.alice, "10:00 项目晨会需求对齐", source_kind="calendar", event_type="schedule")
        travel_ev = self.add_event(self.alice, "周五14:30乘MU5102飞上海出差，周日回")
        self.add_event(self.alice, "15:00 外滩鹿园4人聚餐")
        self.add_event(self.alice, "20:00 恒隆广场买伴手礼领券")

        doc = self.req(self.alice, "GET", "/v1/home/sdui").json()
        self.assertEqual(doc["dayMode"], "ACTIVE_TIMELINE")
        cats = [e["category"] for e in doc["events"]]
        self.assertEqual(cats, ["OFFICE", "TRAVEL", "DINING", "SHOPPING"])

        # Gap between 10:00 and 14:30 is 4.5h -> GAP_SUGGESTION
        self.assertEqual(doc["events"][0]["connectorToNext"]["connectorState"], "GAP_SUGGESTION")
        # Gap between 14:30 and 15:00 is 30m -> CONFLICT_DASHED
        self.assertEqual(doc["events"][1]["connectorToNext"]["connectorState"], "CONFLICT_DASHED")
        # Cross-day relay detected from "周日回"
        self.assertIsNotNone(doc["crossDayRelay"])
        self.assertEqual(doc["crossDayRelay"]["direction"], "return")

        # Resolve conflict -> shifts dining by 30m and turns connector to NORMAL_SOLID
        travel_id = f"evt_{travel_ev['event_id']}"
        doc = self.req(self.alice, "POST", "/v1/home/sdui/actions", {
            "action": "resolve_conflict", "card_id": travel_id, "shift_minutes": 30
        }).json()
        t_card = next(e for e in doc["events"] if e["id"] == travel_id)
        self.assertEqual(t_card["connectorToNext"]["connectorState"], "NORMAL_SOLID")
        d_card = next(e for e in doc["events"] if e["category"] == "DINING")
        self.assertEqual(d_card["time"], "15:30")

        # Zero-manual SMS/ticket auto-verification: add an OCR ticket confirmation clue
        self.add_event(self.alice, "航旅纵横：MU5102 已出票，登机口 B24 座位 14A")
        doc = self.req(self.alice, "GET", "/v1/home/sdui").json()
        t_card = next(e for e in doc["events"] if e["id"] == travel_id)
        self.assertEqual(t_card["lifecycleStage"], "CONFIRMED")
        self.assertEqual(t_card["badge"], "已出票")

    def test_misjudgment_swipe_dismiss_records_negative_feedback(self):
        joke = self.add_event(self.alice, "走，明天09:00飞巴黎喝咖啡！")
        doc = self.req(self.alice, "GET", "/v1/home/sdui").json()
        joke_card = next(e for e in doc["events"] if e["id"] == f"evt_{joke['event_id']}")
        self.assertTrue(joke_card.get("isMisjudgment"))

        # Dismiss via swipe -> removed from timeline and feedback recorded
        doc = self.req(self.alice, "POST", "/v1/home/sdui/actions", {
            "action": "dismiss_misjudgment",
            "card_id": joke_card["id"],
            "reason": "group_chat_joke",
        }).json()
        self.assertFalse(any(e["id"] == joke_card["id"] for e in doc["events"]))
        self.assertEqual(doc["stats"]["negativeFeedbackCount"], 1)

        fb = self.req(self.alice, "GET", "/v1/home/sdui/feedback").json()["items"]
        self.assertEqual(len(fb), 1)
        self.assertEqual(fb[0]["source_event_id"], joke["event_id"])
        self.assertEqual(fb[0]["reason"], "group_chat_joke")

        # Bob cannot see Alice's feedback
        bob_fb = self.req(self.bob, "GET", "/v1/home/sdui/feedback").json()["items"]
        self.assertEqual(bob_fb, [])

    def test_showcase_lifecycle_checklist_and_plan_b(self):
        # Load showcase
        doc = self.req(self.alice, "POST", "/v1/home/sdui/actions", {
            "action": "simulate_scenario", "scenario": "load_showcase"
        }).json()
        self.assertTrue(doc["showcaseMode"])
        self.assertEqual(len(doc["events"]), 4)
        self.assertEqual(len(doc["archivedEvents"]), 1)

        # Slide-to-confirm travel card
        doc = self.req(self.alice, "POST", "/v1/home/sdui/actions", {
            "action": "confirm_card", "card_id": "evt_travel_outbound"
        }).json()
        travel = next(e for e in doc["events"] if e["id"] == "evt_travel_outbound")
        self.assertEqual(travel["lifecycleStage"], "CONFIRMED")
        self.assertEqual(travel["badge"], "已出票")

        # Toggle shopping checklist -> updates progress badge 1/3
        doc = self.req(self.alice, "POST", "/v1/home/sdui/actions", {
            "action": "toggle_checklist", "card_id": "evt_shopping_plaza66", "item_idx": 0
        }).json()
        shop = next(e for e in doc["events"] if e["id"] == "evt_shopping_plaza66")
        self.assertEqual(shop["badge"], "1/3")
        self.assertTrue(shop["checklist"][0]["done"])

        # Trigger emergency alert & apply Plan B (switch to HSR G14)
        doc = self.req(self.alice, "POST", "/v1/home/sdui/actions", {
            "action": "simulate_scenario", "scenario": "emergency_alert"
        }).json()
        travel = next(e for e in doc["events"] if e["id"] == "evt_travel_outbound")
        self.assertEqual(travel["connectorToNext"]["connectorState"], "ALERT_PULSE")

        doc = self.req(self.alice, "POST", "/v1/home/sdui/actions", {
            "action": "apply_plan_b", "card_id": "evt_travel_outbound", "choice": "switch_hsr"
        }).json()
        travel = next(e for e in doc["events"] if e["id"] == "evt_travel_outbound")
        self.assertEqual(travel["connectorToNext"]["connectorState"], "NORMAL_SOLID")
        self.assertIn("G14", travel["title"])

    def test_live_actions_use_source_and_survive_showcase(self):
        ev = self.add_event(self.alice, "下周五去成都，周日回")
        self.add_event(self.alice, "航班 MU5102 已出票")
        self.add_event(self.alice, "坐标 31.1,121.0", source_kind="location", event_type="location")
        path = "/v1/home/sdui"
        card_id = "evt_" + ev["event_id"]
        doc = self.req(self.alice, "GET", path).json()
        card = next(c for c in doc["events"] if c["id"] == card_id)
        self.assertEqual(card["lifecycleStage"], "PROPOSAL")
        self.assertNotIn("plans", card)
        self.assertEqual(doc["crossDayRelay"]["sourceEventId"], ev["event_id"])
        self.assertEqual(len(doc["events"]), 2)
        def action(name, **fields):
            return self.req(self.alice, "POST", path + "/actions", {"action": name, **fields}).json()

        action("simulate_scenario", scenario="load_showcase")
        result = self.req(self.alice, "POST", path + "/actions?live=true",
                          {"action": "confirm_card", "card_id": card_id}).json()
        updated = next(c for c in result["events"] if c["id"] == card_id)
        self.assertEqual(updated["title"], card["title"])
        self.assertEqual(updated["badge"], "已确认")
        self.assertTrue(self.req(self.alice, "GET", path).json()["showcaseMode"])
        restored = action("simulate_scenario", scenario="exit_showcase")
        self.assertEqual(next(c for c in restored["events"] if c["id"] == card_id)["lifecycleStage"], "CONFIRMED")
        finished = action("complete_card", card_id=card_id)
        self.assertEqual(next(c for c in finished["events"] if c["id"] == card_id)["lifecycleStage"], "ARCHIVED")
        self.assertEqual(self.req(self.bob, "POST", path + "/actions",
                                  {"action": "complete_card", "card_id": card_id}).status_code, 404)

    def test_cloud_agent_works_surface_on_home_sdui(self):
        # Create a real Cloud Agent schedule work and submit an event
        ev = self.add_event(
            self.alice,
            '{"schedule_changes":[{"id":"demo_meet","title":"项目评审","at":"2026-12-10T16:00:00+08:00"}]}',
            source_kind="manual",
            event_type="schedule",
        )
        work_resp = self.req(self.alice, "POST", "/v1/agent/works", {
            "event_id": str(uuid4()),
            "title": "评审日程",
            "subagent_id": "schedule",
        })
        self.assertEqual(work_resp.status_code, 201, work_resp.text)
        work_id = work_resp.json()["work"]["id"]

        task_resp = self.req(self.alice, "POST", "/v1/agent/tasks", {
            "request_id": str(uuid4()),
            "work_id": work_id,
            "instruction": "整理评审日程",
            "events": [{"event_id": ev["event_id"], "revision": 1}],
        })
        self.assertEqual(task_resp.status_code, 202, task_resp.text)
        task_id = task_resp.json()["task"]["id"]

        # Wait briefly for mock worker to produce waiting_input proposal
        import time
        task = None
        for _ in range(30):
            task = self.req(self.alice, "GET", f"/v1/agent/tasks/{task_id}").json()["task"]
            if task["status"] == "waiting_input":
                break
            time.sleep(0.05)
        self.assertEqual(task["status"], "waiting_input")

        # Home SDUI should surface the waiting_input proposal card!
        doc = self.req(self.alice, "GET", "/v1/home/sdui").json()
        self.assertTrue(any(e.get("sourceTaskId") == task_id and e["lifecycleStage"] == "PROPOSAL" for e in doc["events"]))

        # Confirm the Agent state proposal -> Home SDUI now surfaces the CONFIRMED schedule card!
        inter = task["interaction"]
        resp = self.req(self.alice, "POST", f"/v1/agent/tasks/{task_id}/responses", {
            "event_id": str(uuid4()),
            "expected_revision": task["revision"],
            "output_version": inter["output_version"],
            "kind": "accept_state",
            "proposal_id": inter["proposal_id"],
        })
        self.assertEqual(resp.status_code, 202, resp.text)
        for _ in range(30):
            work = self.req(self.alice, "GET", f"/v1/agent/works/{work_id}").json()["work"]
            if work["memory_version"] >= 1:
                break
            time.sleep(0.05)

        doc = self.req(self.alice, "GET", "/v1/home/sdui").json()
        confirmed = [e for e in doc["events"] if e.get("sourceWorkId") == work_id and e["lifecycleStage"] == "CONFIRMED"]
        self.assertTrue(len(confirmed) >= 1)
        self.assertEqual(confirmed[0]["title"], "项目评审")


    def test_wire_confirm_retry_budget_and_checklist_are_consistent(self):
        self.req(self.alice, "POST", "/v1/home/sdui/actions", {"action":"simulate_scenario","scenario":"load_showcase"})
        base = "/api/v1/events/evt_travel_outbound"
        data = self.req(self.alice, "POST", base + "/recalculate", {"entityKey":"budget","newValue":700}).json()
        self.assertEqual(data["stateBindings"]["selectedPlanId"], "g14")
        body = {"actionId":"BOOK_FLIGHT_TICKET","selectedPlanId":"g14","idempotencyKey":"confirm-1"}
        result = self.req(self.alice, "POST", base + "/confirm", body).json()
        self.assertEqual(result["homeCardPatch"]["lifecycleStage"], "CONFIRMED")
        self.assertIn("HOLO_3D_PASS_CARD", [b["blockType"] for b in result["detailBlocks"]])
        self.assertEqual(self.req(self.alice, "POST", base + "/confirm", body).json(), result)
        self.assertEqual(self.req(self.alice, "POST", base + "/confirm", dict(body, selectedPlanId="mu5102")).status_code, 409)
        self.assertEqual(self.req(self.bob, "GET", base + "/detail").status_code, 404)
        self.req(self.alice, "POST", "/v1/home/sdui/actions", {"action":"confirm_card","card_id":"evt_shopping_plaza66"})
        item = "/api/v1/events/evt_shopping_plaza66/checklist/c1"
        for _ in range(2):
            patch = self.req(self.alice, "PATCH", item, {"checked":True}).json()["homeCardPatch"]
            self.assertEqual((patch["rightBadge"]["current"],patch["rightBadge"]["total"]), (1,3))

    def test_wire_day_partition_and_live_dismissal(self):
        event = self.add_event(self.alice, "今天14:00飞上海出差")
        eid = "evt_" + event["event_id"]
        url = "/api/v1/home/timeline"
        doc = self.req(self.alice, "GET", url).json()
        self.assertEqual(doc["schemaVersion"], "1.2")
        ids = [c["eventId"] for c in [doc["nowFocusCard"], *doc["timelineEvents"]] if c]
        self.assertEqual(ids, [eid])
        other = "2035-01-02"
        self.assertEqual(self.req(self.alice, "GET", url+"?date="+other).json()["dayMode"], "EMPTY_EXPLORE")
        self.req(self.alice, "POST", "/api/v1/home/day-mode", {"date":other, "mode":"REST_MODE"})
        self.assertEqual(self.req(self.alice, "GET", url+"?date="+other).json()["dayMode"], "REST_MODE")
        self.assertEqual(self.req(self.alice, "GET", url).json()["dayMode"], "ACTIVE_TIMELINE")
        dismiss = "/api/v1/home/events/"+eid+"/dismiss"
        for _ in range(2):
            self.assertEqual(self.req(self.alice, "POST", dismiss, {"reason":"USER_SWIPE_DISMISS","sourceChatId":"fixture-chat"}).status_code,200)
        feedback = self.req(self.alice,"GET","/v1/home/sdui/feedback").json()["items"]
        self.assertEqual([(x["source_event_id"], x["source_chat_id"]) for x in feedback], [(event["event_id"], "fixture-chat")])


if __name__ == "__main__":
    unittest.main()
