"""Authenticated AuraFlow 1.2 routes, backed by the same state as the native home."""
import asyncio
from contextlib import asynccontextmanager, closing
from datetime import date as Day, datetime
import json
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field, StrictBool

from . import auraflow_contract as wire
from .home_context import CITIES, city_view
from .home_timing import shift_schedule
from .home_sdui import HomeSduiAction, apply_home_action, build_home_sdui, load_user_state, save_user_state


class Body(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Execute(Body):
    actionId: Literal["SHIFT_EVENT_TIME", "ADOPT_GAP_SPOT", "APPLY_PLAN_B", "ADOPT_EXPLORE_PLAN"]
    payload: dict


class Dismiss(Body):
    reason: str = Field(default="USER_SWIPE_DISMISS", max_length=300)
    sourceChatId: str | None = Field(default=None, max_length=120)


class DayMode(Body):
    date: Day
    mode: Literal["REST_MODE", "EMPTY_EXPLORE", "ACTIVE_TIMELINE"]


class Explore(Body):
    date: Day
    lat: float | None = Field(None, ge=-90, le=90)
    lng: float | None = Field(None, ge=-180, le=180)
    excludePlanIds: list[str] = Field(default_factory=list, max_length=100)


class CityInput(Body):
    cityCode: str = Field(min_length=6, max_length=6)


class PlaceInput(Body):
    placeId: str = Field(pattern=r"^[A-Za-z0-9_-]{1,64}$")


class CommuteInput(Body):
    date: Day
    fromEventId: str = Field(min_length=1, max_length=120)
    toEventId: str = Field(min_length=1, max_length=120)


class Confirm(Body):
    actionId: str = Field(min_length=1, max_length=80)
    selectedPlanId: str | None = Field(None, max_length=120)
    idempotencyKey: str = Field(min_length=1, max_length=128)


class Entity(Body):
    entityKey: Literal["budget", "selectedPlanId", "preference"]
    newValue: int | str


class Check(Body):
    checked: StrictBool


def today():
    return datetime.now(wire.ZONE).date()


def find(doc, eid):
    card = next((c for c in wire.pool(doc) if c["id"] == eid), None)
    if card is None:
        raise HTTPException(404, "找不到当前账号的首页事件")
    return card


def require_editable(card):
    if card.get("lifecycleStage") == "ARCHIVED" or card.get("sourceWorkId"):
        raise HTTPException(409, "已归档或由云端事项管理")


class Home:
    def __init__(self, conn, hub, uid, context):
        self.conn, self.hub, self.uid = conn, hub, uid
        self.context = context
        self.state = load_user_state(conn, uid)
        self.live = not self.state["showcaseMode"]
        self.doc = build_home_sdui(conn, hub, uid, live=self.live)

    def save(self):
        save_user_state(self.conn, self.uid, self.state)
        self.doc = build_home_sdui(self.conn, self.hub, self.uid, live=self.live)

    def apply(self, action, **params):
        self.doc = apply_home_action(self.conn, self.hub, self.uid, HomeSduiAction(action=action, **params), live=self.live)
        self.state = load_user_state(self.conn, self.uid)

    def timeline(self, day=None):
        selected = day or today()
        result = wire.timeline(self.doc, selected, self.state)
        if self.live:
            context = self.context.snapshot(self.state, wire.pool(self.doc), selected)
            result["homeContext"] = context
            weather = context["weather"]
            if weather["status"] == "available":
                city = context["city"]["name"]
                result["ambientHeader"]["weatherSummary"] = f"{city} · {weather['condition']} {weather['temperatureC']:g}°C"
        return result

    def require_live(self):
        if not self.live:
            raise HTTPException(409, "请先退出演示模式")

    def commute_pair(self, body):
        self.require_live()
        for first, second in self.context.pairs(wire.pool(self.doc), body.date):
            if (first["id"], second["id"]) == (body.fromEventId, body.toEventId):
                return first, second
        raise HTTPException(409, "日程顺序已变化，请刷新后再估算")

    def detail(self, eid, caps="NATIVE_V1"):
        edits = self.state.get("detailEdits", {}).get(eid)
        return wire.detail(find(self.doc, eid), self.doc, edits, caps)

    def card_patch(self, card):
        now = wire.home_now(self.doc)
        return wire.card_view(card, wire.event_day(card, now.date()), now)

    def native_card(self, card):
        card = dict(card, timing=wire.timing(card, wire.event_day(card, today())))
        edits = self.state.get("detailEdits", {}).get(card["id"], {})
        if not edits or not card.get("plans") or card.get("lifecycleStage") != "PROPOSAL":
            return card
        options = [p for p in card["plans"] if p["price"] <= edits.get("budget", float("inf"))]
        selected = self.detail(card["id"])["stateBindings"]["selectedPlanId"]
        return dict(card, plans=options, selectedPlanIdx=next((i for i,p in enumerate(options) if p["id"] == selected), 0))

    def day_settings(self, day):
        return self.state.setdefault("days", {}).setdefault(day.isoformat(), {})


def stream_updates(previous, current):
    if previous is None:
        return [("DAY_MODE_CHANGE", {"dayMode": current["dayMode"], "timeline": current}),
                ("CAPTURE_STATUS", current["ambientHeader"]["capturePill"])]
    updates = []
    if previous["dayMode"] != current["dayMode"]:
        updates.append(("DAY_MODE_CHANGE", {"dayMode": current["dayMode"]}))
    def by_id(doc):
        return {i["eventId"]: i for i in [*doc["timelineEvents"], *doc["archivedSection"]["items"], doc["nowFocusCard"]] if i}
    old, new = by_id(previous), by_id(current)
    for eid in old.keys() | new.keys():
        before, after = old.get(eid), new.get(eid)
        if before == after:
            continue
        updates.append(("EVENT_UPSERT", {"eventId": eid, "card": after, "removed": after is None}))
        connector = (after or {}).get("connectorToNext") or {}
        if connector.get("style") == "ALERT_PULSE" and connector != (before or {}).get("connectorToNext"):
            updates.append(("CONNECTOR_ALERT", {"eventId": eid, "connector": connector}))
    if current["ambientHeader"] != previous["ambientHeader"] or old.keys() != new.keys():
        updates.append(("CAPTURE_STATUS", current["ambientHeader"]["capturePill"]))
    if current != previous:
        if not updates:
            updates.append(("DAY_MODE_CHANGE", {"dayMode": current["dayMode"]}))
        name, data = updates[-1]
        updates[-1] = (name, {**data, "timeline": current})
    return updates


def install(app, main, open_conn):
    router = APIRouter(prefix="/api/v1", tags=["AuraFlow 1.2"])
    auth = Depends(main.short_session_user)

    @asynccontextmanager
    async def session(request, user, write=False):
        hub = getattr(request.app.state, "agent_hub", None)
        async with hub.lock(user["id"]):
            await hub.restore_existing(user["id"])
            with closing(open_conn()) as conn, conn:
                conn.execute("BEGIN IMMEDIATE" if write else "BEGIN")
                yield Home(conn, hub, user["id"], request.app.state.home_context)

    @router.get("/home/timeline")
    async def timeline(request: Request, date: Day | None = None, lat: float | None = Query(None, ge=-90, le=90),
                       lng: float | None = Query(None, ge=-180, le=180), clientCapabilities: str = "", user=auth):
        async with session(request, user) as home:
            result = home.timeline(date)
            if clientCapabilities == "ANDROID_COMPAT":
                settings = home.state.get("days", {}).get(result["date"], {})
                relay = home.doc["crossDayRelay"]
                result["nativeState"] = dict(home.doc, dayModeOverride=settings.get("mode", home.doc["dayModeOverride"]),
                    events=[home.native_card(c) for c in home.doc["events"]],
                    archivedEvents=[home.native_card(c) for c in home.doc["archivedEvents"]],
                    crossDayRelay=home.native_card(relay) if relay else None, exploreIndex=settings.get("exploreIndex", home.doc["exploreIndex"]))
            return result

    @router.put("/home/context/city")
    async def select_city(body: CityInput, request: Request, user=auth):
        if body.cityCode not in CITIES:
            raise HTTPException(422, "请选择支持的城市")
        async with session(request, user, True) as home:
            home.require_live()
            home.state["homeCityCode"] = body.cityCode
            home.save()
        context = request.app.state.home_context
        if context.enabled:
            await context.refresh_weather(body.cityCode)
        return {"city": city_view(body.cityCode), "weather": context.weather(body.cityCode)}

    @router.post("/home/context/weather/refresh")
    async def refresh_weather(request: Request, user=auth):
        async with session(request, user) as home:
            home.require_live()
            code = home.state.get("homeCityCode")
        context = request.app.state.home_context
        if city_view(code) and context.enabled:
            await context.refresh_weather(code)
        return {"city": city_view(code), "weather": context.weather(code)}

    @router.get("/places/search")
    async def search_places(request: Request, cityCode: str = Query(min_length=6, max_length=6),
                            query: str = Query(min_length=2, max_length=80), user=auth):
        if cityCode not in CITIES:
            raise HTTPException(422, "请选择支持的城市")
        if len(query.strip()) < 2:
            raise HTTPException(422, "请输入至少两个字的地点关键词")
        return await request.app.state.home_context.search(cityCode, query.strip())

    @router.put("/events/{eventId}/place")
    async def confirm_place(eventId: str, body: PlaceInput, request: Request, user=auth):
        async with session(request, user) as home:
            home.require_live()
            find(home.doc, eventId)
        result = await request.app.state.home_context.resolve_place(body.placeId)
        if result["status"] == "empty":
            raise HTTPException(404, "找不到该地点，请重新搜索")
        if result["status"] != "available":
            raise HTTPException(503, "地点服务暂不可用")
        place = {k: result["place"][k] for k in ("placeId", "name", "address")}
        async with session(request, user, True) as home:
            home.require_live()
            find(home.doc, eventId)
            home.state.setdefault("homePlaces", {})[eventId] = {**place, "source": "amap"}
            home.save()
        return place

    @router.delete("/events/{eventId}/place")
    async def clear_place(eventId: str, request: Request, user=auth):
        async with session(request, user, True) as home:
            home.require_live()
            find(home.doc, eventId)
            home.state.setdefault("homePlaces", {}).pop(eventId, None)
            home.save()
        return {"ok": True}

    @router.post("/home/commutes/refresh")
    async def refresh_commute(body: CommuteInput, request: Request, user=auth):
        async with session(request, user) as home:
            first, second = home.commute_pair(body)
            places = home.state.get("homePlaces", {})
            estimate = home.context.commute(first, second, body.date, places)
            if estimate["status"] != "not_loaded":
                return estimate
            origin = places[first["id"]]["placeId"]
            destination = places[second["id"]]["placeId"]
        await request.app.state.home_context.refresh_walk(origin, destination)
        async with session(request, user) as home:
            first, second = home.commute_pair(body)
            return home.context.commute(first, second, body.date, home.state.get("homePlaces", {}))

    @router.get("/events/{eventId}/detail")
    async def detail(eventId: str, request: Request, clientCapabilities: str = "NATIVE_V1",
                     lat: float | None = Query(None, ge=-90, le=90), lng: float | None = Query(None, ge=-180, le=180), user=auth):
        async with session(request, user) as home:
            return home.detail(eventId, clientCapabilities)

    @router.post("/events/{eventId}/recalculate")
    async def recalculate(eventId: str, body: Entity, request: Request, user=auth):
        async with session(request, user, True) as home:
            card = find(home.doc, eventId)
            require_editable(card)
            if card.get("lifecycleStage") != "PROPOSAL":
                raise HTTPException(409, "仅可调整提案")
            edits = home.state.setdefault("detailEdits", {}).setdefault(eventId, {})
            if body.entityKey == "budget":
                if type(body.newValue) != int or body.newValue < 0:
                    raise HTTPException(422, "预算需为非负整数")
            elif body.entityKey == "selectedPlanId":
                plans = [p for p in card.get("plans", []) if p["price"] <= edits.get("budget", float("inf"))]
                if not any(p["id"] == body.newValue for p in plans):
                    raise HTTPException(422, "方案无效")
            elif not isinstance(body.newValue, str) or not 0 < len(body.newValue) <= 120:
                raise HTTPException(422, "偏好需为简短文本")
            edits[body.entityKey] = body.newValue
            home.save()
            if body.entityKey == "selectedPlanId":
                home.apply("select_plan", card_id=eventId, plan_idx=next(i for i,p in enumerate(card["plans"]) if p["id"] == body.newValue))
            return home.detail(eventId)

    @router.post("/events/{eventId}/confirm")
    async def confirm(eventId: str, body: Confirm, request: Request, user=auth):
        async with session(request, user, True) as home:
            scope = "live" if home.live else "showcase"
            fingerprint = json.dumps([eventId, body.actionId, body.selectedPlanId])
            row = home.conn.execute("SELECT fingerprint, reply FROM home_confirmations WHERE user_id=? AND scope=? AND key=?",
                                    (home.uid, scope, body.idempotencyKey)).fetchone()
            if row:
                if row["fingerprint"] != fingerprint:
                    raise HTTPException(409, "该幂等键已用于其他确认请求")
                return json.loads(row["reply"])
            card = find(home.doc, eventId)
            require_editable(card)
            detail = home.detail(eventId)
            dock = detail["stickyDock"] or {}
            if dock.get("dockType") != "SLIDE_CONFIRM_BAR" or dock.get("actionId") != body.actionId:
                raise HTTPException(409, "请刷新后确认当前提案")
            plans = next((b["data"]["plans"] for b in detail["detailBlocks"] if b["blockType"] == "PLAN_DECK_CAROUSEL"), [])
            if plans or body.selectedPlanId is not None:
                if not any(p["planId"] == body.selectedPlanId for p in plans):
                    raise HTTPException(422, "请选择有效的候选方案")
                idx = next(i for i,p in enumerate(card["plans"]) if p["id"] == body.selectedPlanId)
                home.state.setdefault("detailEdits", {}).setdefault(eventId, {})["selectedPlanId"] = body.selectedPlanId
                home.save()
                home.apply("select_plan", card_id=eventId, plan_idx=idx)
            home.apply("confirm_card", card_id=eventId)
            result = home.detail(eventId)
            result["homeCardPatch"] = home.card_patch(find(home.doc, eventId))
            home.conn.execute("INSERT INTO home_confirmations VALUES (?,?,?,?,?)",
                              (home.uid, scope, body.idempotencyKey, fingerprint, json.dumps(result, ensure_ascii=False)))
            return result

    @router.patch("/events/{eventId}/checklist/{itemId}")
    async def check(eventId: str, itemId: str, body: Check, request: Request, user=auth):
        async with session(request, user, True) as home:
            card = find(home.doc, eventId)
            require_editable(card)
            items = wire.checklist(card)
            idx = next((i for i,x in enumerate(items) if x["itemId"] == itemId), None)
            if idx is None:
                raise HTTPException(404, "清单项不存在")
            field = "checklistDone" if card.get("checklist") else "notesDone"
            home.state["cardOverrides"].setdefault(eventId, {}).setdefault(field, {})[str(idx)] = body.checked
            home.save()
            return dict(home.detail(eventId), homeCardPatch=home.card_patch(find(home.doc, eventId)))

    @router.post("/home/events/{eventId}/dismiss")
    async def dismiss(eventId: str, body: Dismiss, request: Request, user=auth):
        async with session(request, user, True) as home:
            if eventId not in home.state["dismissedIds"]:
                card = find(home.doc, eventId)
                if card.get("lifecycleStage") != "PROPOSAL":
                    raise HTTPException(409, "仅可移除待确认提案")
                home.apply("dismiss_misjudgment", card_id=eventId, reason=body.reason)
                if body.sourceChatId and home.live:
                    home.conn.execute("UPDATE home_feedback SET source_chat_id=? WHERE user_id=? AND card_id=?",
                                      (body.sourceChatId, home.uid, eventId))
            return home.timeline()

    @router.post("/home/reminders/{reminderId}/ack")
    async def ack(reminderId: str, request: Request, user=auth):
        async with session(request, user, True) as home:
            card = find(home.doc, reminderId.removeprefix("rem_"))
            if not reminderId.startswith("rem_") or not card.get("reminderStrip"):
                raise HTTPException(404, "提醒不存在")
            home.apply("dismiss_reminder", card_id=card["id"])
            return home.timeline()

    @router.post("/home/day-mode")
    async def day_mode(body: DayMode, request: Request, user=auth):
        async with session(request, user, True) as home:
            home.day_settings(body.date)["mode"] = body.mode
            home.save()
            return home.timeline(body.date)

    @router.post("/home/explore/refresh")
    async def refresh(body: Explore, request: Request, user=auth):
        async with session(request, user, True) as home:
            settings = home.day_settings(body.date)
            initial = settings.get("exploreIndex", home.doc["exploreIndex"])
            plans = home.doc["explorePresets"].get(home.doc["weatherContext"]["mode"], [])
            for offset in range(1, len(plans) + 1):
                idx = (initial + offset) % len(plans)
                if plans[idx]["id"] not in body.excludePlanIds:
                    settings["exploreIndex"] = idx
                    home.save()
                    return wire.explore_view(home.doc, idx)
            if plans:
                raise HTTPException(409, "当前候选路线均已排除")
            return wire.explore_view(home.doc, 0)

    @router.post("/home/actions/execute")
    async def execute(body: Execute, request: Request, user=auth):
        async with session(request, user, True) as home:
            p = body.payload
            if body.actionId == "SHIFT_EVENT_TIME":
                target = find(home.doc, p.get("targetEventId"))
                require_editable(target)
                shift = p.get("shiftMinutes", 30)
                if type(shift) != int or not 5 <= shift <= 240:
                    raise HTTPException(422, "顺延范围为 5 至 240 分钟")
                updated = shift_schedule(target, wire.event_day(target, today()), shift)
                if not updated:
                    raise HTTPException(409, "事项没有可顺延的开始时间")
                overrides = home.state["cardOverrides"]
                overrides.setdefault(target["id"], {}).update(updated)
                previous = next((a for a,b in zip(home.doc["events"],home.doc["events"][1:]) if b["id"]==target["id"]), None)
                if previous:
                    overrides.setdefault(previous["id"], {})["connectorToNext"] = {"connectorState":"NORMAL_SOLID", "label":"已顺延"}
                home.save()
            elif body.actionId == "ADOPT_EXPLORE_PLAN":
                if home.live:
                    raise HTTPException(409, "该账号暂无漫游供应数据")
                settings = home.day_settings(today())
                idx = settings.get("exploreIndex", home.doc["exploreIndex"])
                plan = wire.explore_view(home.doc, idx)["currentPlan"]
                if not plan or p.get("planId") != plan["planId"]:
                    raise HTTPException(409, "漫游提案已变化")
                settings.pop("mode", None)
                home.state["exploreIndex"] = idx
                home.save()
                home.apply("adopt_explore")
            else:
                card = find(home.doc, p.get("eventId"))
                if home.live:
                    raise HTTPException(409, "此操作需要已下发的备选方案")
                connector = card.get("connectorToNext") or {}
                if body.actionId == "APPLY_PLAN_B":
                    if connector.get("connectorState") != "ALERT_PULSE" or p.get("choice") not in ("switch_hsr","shift_dinner"):
                        raise HTTPException(409, "备选方案已变化")
                    home.apply("apply_plan_b", card_id=card["id"], choice=p["choice"])
                else:
                    spot = next((s for s in connector.get("gapSpots", []) if s["title"] == p.get("title")), None)
                    if not spot:
                        raise HTTPException(409, "当前空档未提供该建议")
                    home.apply("insert_gap_spot", card_id=card["id"], title=spot["title"], time=spot["time"],
                               subtitle=spot["subtitle"], duration_minutes=spot.get("durationMinutes"))
            return home.timeline()

    @router.get("/home/stream")
    async def stream(request: Request, date: Day | None = None, user=auth):
        async def events():
            previous = None
            last_alerts = {}
            last_count = None
            last_details = None
            heartbeat = 0
            while not await request.is_disconnected():
                try:
                    main.short_session_user(request.headers.get("authorization"))
                except HTTPException:
                    return
                async with session(request, user) as home:
                    current = home.timeline(date)
                    visible = {x["eventId"] for x in current["timelineEvents"]}
                    if current["nowFocusCard"]:
                        visible.add(current["nowFocusCard"]["eventId"])
                    alerts = {c["id"]: c["connectorToNext"]["label"] for c in home.doc["events"]
                              if c["id"] in visible and (c.get("connectorToNext") or {}).get("connectorState") == "ALERT_PULSE"}
                    count = home.doc["stats"]["realEventCount"]
                    details = {c["id"]: (c, home.state.get("detailEdits", {}).get(c["id"])) for c in wire.pool(home.doc)}
                updates = stream_updates(previous, current)
                if last_details is not None:
                    sent_ids = {data["eventId"] for name, data in updates if name == "EVENT_UPSERT"}
                    for eid in (last_details.keys() | details.keys()) - sent_ids:
                        if last_details.get(eid) != details.get(eid):
                            updates.append(("EVENT_UPSERT", {"eventId": eid, "detailChanged": True, "removed": eid not in details}))
                sent = {data["eventId"] for name,data in updates if name == "CONNECTOR_ALERT"}
                for eid, label in alerts.items():
                    if eid not in sent and last_alerts.get(eid) != label:
                        updates.append(("CONNECTOR_ALERT", {"eventId": eid, "connector": {"style":"ALERT_PULSE","label":label}}))
                if last_count is not None and count != last_count:
                    updates.append(("CAPTURE_STATUS", dict(current["ambientHeader"]["capturePill"],
                                   serviceState="CAPTURE_UPDATED", statusText="线索已同步", realEventCount=count)))
                last_alerts, last_count, last_details = alerts, count, details
                for name, data in updates:
                    yield f"event: {name}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"
                previous = current
                if heartbeat % 10 == 0:
                    yield ": heartbeat\n\n"
                heartbeat += 1
                await asyncio.sleep(1.5)
        return StreamingResponse(events(), media_type="text/event-stream", headers={"Cache-Control":"no-cache","X-Accel-Buffering":"no"})

    app.include_router(router)
