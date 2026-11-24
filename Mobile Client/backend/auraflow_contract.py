"""Wire representations for the AuraFlow 1.2 home and event-detail endpoints."""
from datetime import datetime, time, timedelta, timezone
import re

ZONE = timezone(timedelta(hours=8))
THEMES = {"TRAVEL": "TRAVEL_CYAN", "DINING": "DINING_AMBER", "SHOPPING": "SHOPPING_EMERALD",
          "EXPLORE": "EXPLORE_TEAL", "OFFICE": "OFFICE_VIOLET"}
CONFIRM = {"TRAVEL": "BOOK_FLIGHT_TICKET", "DINING": "RESERVE_TABLE", "SHOPPING": "CLAIM_COUPON"}


def timestamp(value):
    if value:
        try:
            return datetime.fromisoformat(value).astimezone(ZONE)
        except ValueError:
            pass
    return None


def event_time(card, day):
    stamp = timestamp(card.get("scheduledAt"))
    if stamp:
        return stamp
    value = card.get("time", "")
    if re.fullmatch(r"\d{2}:\d{2}", value):
        return datetime.combine(day, time.fromisoformat(value), ZONE)
    return None


def event_day(card, fallback):
    stamp = timestamp(card.get("scheduledAt") or card.get("completedAt"))
    if stamp:
        return stamp.date()
    captured = timestamp(card.get("capturedAt"))
    if captured is None:
        return fallback
    base = captured.date()
    text = card.get("originalText", "")
    # A later return phrase belongs to the relay, not the outbound clue.
    text = re.split(r"[，,。；;]", text, maxsplit=1)[0]
    relative = re.search(r"今天|今日|今晚|明天|明早|明日|后天|(?:(下|本|这)?周([一二三四五六日天]))", text)
    if not relative:
        return base
    if relative.group(2):
        weekday = "一二三四五六日".index(relative[2].replace("天","日"))
        delta = weekday - base.weekday()
        if relative[1] == "下":
            delta += 7
        elif delta < 0 and not relative[1]:
            delta += 7
        return base + timedelta(days=delta)
    return base + timedelta(days={"明天":1,"明早":1,"明日":1,"后天":2}.get(relative[0], 0))


def pool(doc):
    return doc["events"] + doc["archivedEvents"] + ([doc["crossDayRelay"]] if doc["crossDayRelay"] else [])


def checklist(card):
    if card.get("checklist"):
        return [{"itemId": i["id"], "label": i["name"], "checked": i["done"],
                 "subText": " · ".join(filter(None, [i.get("floor"), i.get("save")]))} for i in card["checklist"]]
    return [{"itemId": "note-" + str(idx), "label": note["text"], "checked": note["done"], "subText": ""}
            for idx, note in enumerate(card.get("notes", []))]


def card_view(card, day, now):
    category = card.get("category", "OFFICE")
    stage = card.get("lifecycleStage", "ARCHIVED")
    theme = THEMES.get(category, "GENERIC_SILVER")
    proposal = stage == "PROPOSAL"
    eid = card["id"]
    stamp = event_time(card, day)
    items = checklist(card)
    badge = {"type": "CTA_PILL" if proposal else "STATUS_TEXT", "text": card.get("badge", "已结束")}
    if not proposal and category == "SHOPPING" and items:
        badge.update(type="PROGRESS_RING", current=sum(i["checked"] for i in items), total=len(items))
    elif stage == "CONFIRMED" and category == "TRAVEL" and stamp:
        badge.update(type="COUNTDOWN_TAG", targetIso=stamp.isoformat(), prefix="出发")
    elif category == "EXPLORE" and card.get("exploreMeta"):
        badge.update(type="DISTANCE_TAG", text=card["exploreMeta"]["duration"], icon="WALK")
    reminder = card.get("reminderStrip") or {}
    prep = None
    if reminder.get("text") and not reminder.get("dismissed") and stamp and timedelta(0) <= stamp-now <= timedelta(hours=12):
        prep = {"id": "rem_" + eid, "iconType": "PREPARATION", "text": reminder["text"], "dismissible": True}
    route = {"screen": "EVENT_DETAIL", "eventId": eid, "themeToken": theme, "previewTitle": card["title"],
             "previewTime": card.get("time", ""), "sharedTransitionKey": "card_container_" + eid,
             "initialMode": "ARCHIVE_READONLY" if stage == "ARCHIVED" else "PROPOSAL_DECISION" if proposal else "CONFIRMED_HOLO"}
    return {"eventId": eid, "tripGroupId": card.get("tripGroupId"), "timeDisplay": card.get("time", ""),
            "category": category, "lifecycleStage": stage, "themeToken": theme, "title": card["title"],
            "subtitle": card.get("subtitle", card.get("summary", "")), "rightBadge": badge,
            "isDismissible": proposal, "sourceHint": card.get("subtitle") if proposal and card.get("sourceEventId") else None,
            "prepReminder": prep, "detailRoute": route, "connectorToNext": None}


def explore_view(doc, index):
    weather = doc["weatherContext"]
    choices = doc["explorePresets"].get(weather["mode"], [])
    plan = None
    if choices:
        p = choices[index % len(choices)]
        duration, _, distance = p["duration"].partition(" · ")
        plan = {"planId": p["id"], "themeTitle": p["title"], "vibeTag": p["tag"], "totalDurationText": duration,
                "totalDistanceText": distance, "themeToken": THEMES["EXPLORE"],
                "stops": [{"timeHint": s["time"], "name": s["name"], "tag": s["note"]} for s in p["stops"]]}
    return {"weatherContext": {"conditionIcon": weather["mode"], "tempText": weather["label"],
                               "headline": weather["city"]}, "currentPlan": plan, "refreshToken": plan["planId"] if plan else None}


def timeline(doc, day, state, now=None):
    now = now or datetime.now(ZONE)
    if doc["showcaseMode"]:
        now = now.replace(hour=12, minute=16, second=0, microsecond=0)
    def belongs(card):
        return day == event_day(card, now.date())

    cards = [c for c in doc["events"] if belongs(c) and c.get("lifecycleStage") != "ARCHIVED"]
    cards.sort(key=lambda c: event_time(c, day) or datetime.max.replace(tzinfo=ZONE))
    archived = [c for c in doc["archivedEvents"] + doc["events"] if c.get("lifecycleStage") == "ARCHIVED" and belongs(c)]
    daily = state.get("days", {}).get(day.isoformat(), {})
    mode = daily.get("mode") or (doc["dayMode"] if doc["dayModeOverride"] != "AUTO" and day == now.date() else None)
    mode = mode or ("ACTIVE_TIMELINE" if cards else "EMPTY_EXPLORE")
    if mode == "ACTIVE_TIMELINE" and not cards:
        mode = "EMPTY_EXPLORE"
    relay = doc.get("crossDayRelay")
    group = None
    if relay:
        parent = next((c for c in cards if c["category"] == "TRAVEL" and
                       ((relay.get("sourceEventId") and c.get("sourceEventId") == relay["sourceEventId"]) or
                        (doc["showcaseMode"] and c["id"] == "evt_travel_outbound"))), None)
        group = "trip_" + parent["id"] if parent else None
    views = [card_view(c, day, now) for c in cards] if mode == "ACTIVE_TIMELINE" else []
    if group:
        for v in views:
            if v["eventId"] == parent["id"]:
                v["tripGroupId"] = group
    focus = None
    if views:
        idx = next((i for i, c in enumerate(cards) if (event_time(c, day) or now) >= now), None)
        if idx is not None:
            focus = views.pop(idx)
            c = cards[idx]
            stamp = event_time(c, day)
            checklist_items = checklist(c)
            ratio = sum(i["checked"] for i in checklist_items) / len(checklist_items) if checklist_items else 0.0
            focus.update(statusLabel="即将开始" if stamp else "待安排", countdownTargetIso=stamp.isoformat() if stamp else None,
                         progressRatio=ratio, primaryMeta=c.get("entities", [])[:3],
                         quickCta={"label": "去拍板" if focus["isDismissible"] else "查看详情",
                                   "routeTarget": "DETAIL_PROPOSAL" if focus["isDismissible"] else "DETAIL_HOLO"})
    by_id = {c["id"]: c for c in cards}
    original_next = {a["id"]: b["id"] for a, b in zip(doc["events"], doc["events"][1:])}
    for current, following in zip(views, views[1:]):
        c = by_id[current["eventId"]]
        raw = c.get("connectorToNext") if original_next.get(c["id"]) == following["eventId"] else None
        conn = {"style": "NORMAL_SOLID", "label": ""}
        if raw:
            style = raw["connectorState"]
            conn.update(style=style, label=raw["label"])
            if style == "CONFLICT_DASHED":
                conn["quickFixAction"] = {"actionId": "SHIFT_EVENT_TIME", "buttonText": raw.get("actionLabel", "顺延 30 分钟"),
                                          "payload": {"targetEventId": following["eventId"], "shiftMinutes": 30}}
            elif style == "GAP_SUGGESTION":
                conn["suggestions"] = raw.get("gapSpots", [])
                gap = raw.get("gapHours")
                if gap:
                    conn["gapMinutes"] = round(float(gap.rstrip("h")) * 60)
            elif style == "ALERT_PULSE":
                conn.update(severity="HIGH", alertType="DELAY", planBOptions=[
                    {"actionId": "APPLY_PLAN_B", "payload": {"eventId": c["id"], "choice": choice}}
                    for choice in ("switch_hsr", "shift_dinner")] if doc["showcaseMode"] else [])
        current["connectorToNext"] = conn
    archive_views = [dict(card_view(c, day, now), summaryTag=c.get("summary", "已结束")) for c in archived]
    pill = {"serviceState": "IDLE_LISTENING", "statusText": "灵枢静默感知中", "accentToken": "TRAVEL_CYAN", "linkedEventId": None}
    return {"schemaVersion": "1.2", "date": day.isoformat(), "dayMode": mode,
            "simulatedAt": now.isoformat() if doc["showcaseMode"] else None,
            "ambientHeader": {"dateDisplay": f"{day.month}月{day.day}日 · 周{'一二三四五六日'[day.weekday()]}",
                              "weatherSummary": doc["weatherContext"]["label"], "capturePill": pill},
            "archivedSection": {"count": len(archive_views), "items": archive_views}, "nowFocusCard": focus, "timelineEvents": views,
            "multiDayAnchors": [{"tripGroupId": group, "targetDateDisplay": relay["time"], "title": relay["title"],
                                 "hintText": relay["subtitle"], "targetEventId": relay["id"]}] if group and mode == "ACTIVE_TIMELINE" else [],
            "exploreCard": explore_view(doc, daily.get("exploreIndex", doc["exploreIndex"])) if mode == "EMPTY_EXPLORE" else None}


def detail(card, doc, edits=None, capabilities="NATIVE_V1"):
    edits = edits or {}
    stage, cat = card.get("lifecycleStage", "ARCHIVED"), card.get("category", "OFFICE")
    theme = THEMES.get(cat, "GENERIC_SILVER")
    blocks = []
    def add(kind, data):
        blocks.append({"blockId": card["id"] + ":" + kind, "blockType": kind, "data": data})
    base = {"title": card["title"], "subtitle": card.get("subtitle", card.get("summary", "")), "badgeText": card.get("badge", "已归档")}
    plans = card.get("plans", [])
    if "budget" in edits:
        plans = [p for p in plans if p["price"] <= edits["budget"]]
    selected = edits.get("selectedPlanId")
    original = card.get("plans", [])
    idx = card.get("selectedPlanIdx", 0)
    if selected not in {p["id"] for p in plans}:
        preferred = original[idx]["id"] if 0 <= idx < len(original) else None
        selected = preferred if any(p["id"] == preferred for p in plans) else next((p["id"] for p in plans), None)
    selected_plan = next((p for p in original if p["id"] == selected), {})
    if stage == "PROPOSAL" and card.get("entities"):
        add("ENTITY_CHIP_BAR", {"entities": [{"label": e} for e in card["entities"]], "values": edits})
    if stage == "PROPOSAL" and plans and cat == "TRAVEL":
        reverse = card.get("direction") == "return"
        origin = {"code": "PEK", "name": "北京首都", "lat": 40.0799, "lng": 116.6031}
        destination = {"code": "SHA", "name": "上海虹桥", "lat": 31.1979, "lng": 121.3363}
        add("MAP_TRAVEL_TRAJECTORY", {"defaultViewMode": "CORRIDOR", "viewModes": [{"key": "CORRIDOR", "label": "城际走廊"}],
            "origin": destination if reverse else origin, "destination": origin if reverse else destination,
            "routes": [{"routeId": p["id"], "linkedPlanIds": [p["id"]], "transportMode": "HSR" if p["mode"] == "rail" else "FLIGHT",
                        "arcCurvature": -.18 if p["mode"] == "rail" else .28, "durationText": p["modeText"], "colorToken": theme} for p in plans],
            "intermodalLegs": []})
    if stage == "PROPOSAL" and plans and cat == "DINING":
        friends = doc["diningFriends"]
        venues = [(31.2368,121.4865),(31.2280,121.4547),(31.2186,121.4278)]
        points = [(31.1979,121.3363),(31.2397,121.4998),(31.2244,121.4450),(31.2082,121.4720)]
        add("MAP_DINING_ISOCHRONE", {"centroid": {"label": "同行人中心", "lat": 31.23, "lng": 121.47},
            "rings": [{"minutes": m, "radiusMeters": r} for m, r in [(15,2500),(30,5500),(45,9000)]],
            "participants": [{"id": str(i), "name": f["name"], "avatarTag": f["short"], "lat": pt[0], "lng": pt[1]}
                             for i, (f, pt) in enumerate(zip(friends, points))],
            "venues": [{"venueId": p["id"], "linkedPlanId": p["id"], "name": p["name"], "lat": venues[original.index(p)][0],
                        "lng": venues[original.index(p)][1], "avgTransitMinutes": p["avgCommute"],
                        "commuteByParticipant": {str(i): m for i,m in enumerate(p["commutes"])}} for p in plans]})
    if card.get("priceHistory"):
        history = card["priceHistory"]
        add("CHART_PRICE_TREND", {"currency": "¥", "itemName": card["title"], "currentPrice": history[-1]["price"],
            "historicalLowest": min(p["price"] for p in history), "series": [dict(p, platform=p["tag"]) for p in history]})
    if card.get("checklist"):
        items = card["checklist"]
        add("MAP_INDOOR_ROUTE", {"venueName": card["title"], "floors": list(dict.fromkeys(i["floor"] for i in items)),
            "stops": [{"order": n+1, "floor": i["floor"], "shopName": i["name"], "couponTag": i["save"],
                       "xRatio": .25 + n*.2, "yRatio": .65 - n*.15} for n, i in enumerate(items)]})
    if card.get("exploreMeta"):
        add("MAP_CITYWALK_PATH", card["exploreMeta"])
    if stage == "PROPOSAL" and plans:
        add("PLAN_DECK_CAROUSEL", {"selectionKey": "selectedPlanId", "plans": [
            {"planId": p["id"], "isRecommended": n == 0, "title": p.get("carrier") or p.get("name"),
             "subtitle": p.get("modeText") or p.get("address"), "priceText": "¥" + str(p["price"]),
             "scoreRing": {"value": p.get("punctuality",p.get("match")), "label": "准点率" if cat == "TRAVEL" else "匹配度", "unit": "%"},
             "highlights": [p["note"]], "dockOverrideLabel": "确认 · ¥" + str(p["price"])} for n,p in enumerate(plans)]})
    if stage == "CONFIRMED" and doc["showcaseMode"] and cat in CONFIRM:
        p = selected_plan
        add("HOLO_3D_PASS_CARD", {"passVariant": {"TRAVEL": "BOARDING_PASS", "DINING": "DINING_RESERVATION", "SHOPPING": "SHOPPING_COUPON"}[cat],
            "themeToken": theme, "issuer": "本地模拟服务", "statusChip": base["badgeText"],
            "heroLeft": {"primary": p.get("depHub") or p.get("name") or card["title"]}, "heroRight": {"primary": p.get("arrHub") or p.get("room") or ""},
            "metaGrid": [{"label": k, "value": p[v]} for k,v in [("班次","carrier"),("座位","seat"),("登机口","gate"),("门店","address")] if v in p],
            "barcode": {"format": "QR_CODE", "payload": "DEMO:"+card["id"], "hintText": "模拟凭证"}})
    if checklist(card):
        add("INTERACTIVE_CHECKLIST", {"syncTarget": "HOME_PROGRESS_RING", "title": "事项清单", "items": checklist(card), "readOnly": stage == "ARCHIVED"})
    add("SUMMARY_KV_CARD", {"title": card["title"], "summary": base["subtitle"], "entries": [{"label": "状态", "value": base["badgeText"]}]})
    if capabilities != "NATIVE_V1":
        blocks = [b for b in blocks if b["blockType"] == "SUMMARY_KV_CARD"]
    dock = None
    if stage == "PROPOSAL" and not card.get("sourceWorkId") and (not original or plans):
        dock = {"dockType": "SLIDE_CONFIRM_BAR", "label": "确认方案" if plans else "确认事项",
                "actionId": CONFIRM.get(cat, "CONFIRM_EVENT") if doc["showcaseMode"] else "CONFIRM_EVENT", "requiresPlanId": bool(original)}
    elif stage == "CONFIRMED":
        dock = {"dockType": "DUAL_ACTION_BAR", "label": "查看详情", "actions": []}
    return {"schemaVersion": "1.2", "eventId": card["id"], "category": cat, "lifecycleStage": stage, "themeToken": theme,
            "header": base, "stateBindings": {"selectedPlanId": selected, **{k:v for k,v in edits.items() if k != "selectedPlanId"}},
            "detailBlocks": blocks, "stickyDock": dock}
