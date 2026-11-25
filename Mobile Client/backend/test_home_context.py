from datetime import datetime, timedelta
from uuid import uuid4

import httpx
import pytest
from fastapi.testclient import TestClient

from backend import home_context, main
from backend.home_context import HomeContext
from backend.home_timing import ZONE


@pytest.fixture
def gateway(tmp_path, monkeypatch):
    monkeypatch.setenv("WEIGUANG_DB", str(tmp_path / "home.sqlite3"))
    monkeypatch.delenv("SCREENMIND_AMAP_WEB_KEY", raising=False)
    main.ATTEMPTS.clear()
    with TestClient(main.app) as client:
        yield client


def account(client, suffix):
    result = client.post("/v1/auth/register", json={
        "username": "context_test_" + suffix, "password": "test-password-123",
    })
    assert result.status_code == 201
    return {"Authorization": f"Bearer {result.json()['access_token']}"}


def event(client, headers, day, start, end, title):
    event_id = str(uuid4())
    result = client.post("/v1/events", headers=headers, json={
        "event_id": event_id, "event_type": "schedule", "source_kind": "calendar",
        "captured_at": datetime.now(ZONE).isoformat(),
        "occurred_at": f"{day}T{start}:00+08:00", "ended_at": f"{day}T{end}:00+08:00",
        "payload": {"text": title},
    })
    assert result.status_code == 200, result.text
    return "evt_" + event_id


def context(client, headers, day=None):
    params = {"clientCapabilities": "ANDROID_COMPAT"}
    if day is not None:
        params["date"] = day
    response = client.get("/api/v1/home/timeline", headers=headers, params=params)
    assert response.status_code == 200, response.text
    return response.json()


def provider(client, handler):
    upstream = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    prior = main.app.state.home_context
    main.app.state.home_context = HomeContext(upstream, "fixture-amap-key")
    return prior, upstream


def restore(client, old, upstream):
    client.portal.call(main.app.state.home_context.close)
    client.portal.call(upstream.aclose)
    main.app.state.home_context = old


def test_city_can_be_saved_with_provider_disabled(gateway):
    alice = account(gateway, "alice")
    bob = account(gateway, "bob")
    assert context(gateway, alice)["homeContext"]["weather"]["status"] == "city_needed"
    saved = gateway.put("/api/v1/home/context/city", headers=alice, json={"cityCode": "310000"})
    assert saved.status_code == 200
    assert saved.json()["weather"] == {"status": "unavailable"}
    assert context(gateway, alice)["homeContext"]["city"]["name"] == "上海"
    assert context(gateway, bob)["homeContext"]["city"] is None


def test_weather_uses_observation_timestamp_and_does_not_fetch_on_snapshot(gateway, monkeypatch):
    alice = account(gateway, "alice")
    calls = []
    reported = datetime.now(ZONE).replace(microsecond=0)

    def upstream(request):
        assert request.url.host == "restapi.amap.com"
        assert request.url.path == "/v3/weather/weatherInfo"
        code = request.url.params["city"]
        calls.append(code)
        if code == "110000":
            raise httpx.ReadTimeout("fixture timeout")
        return httpx.Response(200, json={"status": "1", "lives": [{
            "temperature": "18", "weather": "多云", "reporttime": reported.strftime("%Y-%m-%d %H:%M:%S"),
        }]})

    old, client = provider(gateway, upstream)
    try:
        assert gateway.put("/api/v1/home/context/city", headers=alice, json={"cityCode": "310000"}).status_code == 200
        for _ in range(3):
            data = context(gateway, alice)
            assert data["ambientHeader"]["weatherSummary"] == "上海 · 多云 18°C"
            assert data["homeContext"]["weather"]["reportedAt"] == reported.isoformat()
            assert "nativeState" in data
        assert calls == ["310000"]
        other_day = (reported.date() + timedelta(days=1)).isoformat()
        assert context(gateway, alice, other_day)["homeContext"]["weather"]["status"] == "not_for_date"

        assert gateway.put("/api/v1/home/context/city", headers=alice, json={"cityCode": "110000"}).status_code == 200
        data = context(gateway, alice)
        assert data["ambientHeader"]["weatherSummary"] == ""
        assert data["homeContext"]["weather"] == {"status": "unavailable"}
        assert data["nativeState"]["events"] == []
        assert gateway.post("/api/v1/home/context/weather/refresh", headers=alice).json()["weather"]["status"] == "unavailable"
        assert calls == ["310000", "110000"]
        clock = home_context.monotonic()
        monkeypatch.setattr(home_context, "monotonic", lambda: clock + 21)
        assert context(gateway, alice)["homeContext"]["weather"] == {"status": "unavailable"}
        assert calls == ["310000", "110000"]
        assert gateway.post("/api/v1/home/context/weather/refresh", headers=alice).json()["weather"]["status"] == "unavailable"
        assert calls == ["310000", "110000", "110000"]
    finally:
        restore(gateway, old, client)


def test_selected_places_walk_and_missing_route_stay_account_scoped(gateway):
    alice = account(gateway, "alice")
    bob = account(gateway, "bob")
    day = datetime.now(ZONE).date().isoformat()
    first = event(gateway, alice, day, "15:00", "15:30", "评审会议")
    second = event(gateway, alice, day, "15:55", "17:00", "讨论")
    cases = {
        "hallA": {"id": "hallA", "name": "创意园", "address": "一号入口", "location": "121.4,31.2"},
        "hallB": {"id": "hallB", "name": "创意园", "address": "二号入口", "location": "121.42,31.21"},
        "hallC": {"id": "hallC", "name": "联调厅", "address": "三号入口", "location": "121.44,31.22"},
    }
    calls = []

    def upstream(request):
        path = request.url.path
        calls.append(path)
        if path == "/v5/place/text":
            assert request.url.params["keywords"] == "创意园"
            assert request.url.params["region"] == "310000"
            return httpx.Response(200, json={"status": "1", "pois": list(cases.values())[:2]})
        if path == "/v5/place/detail":
            item = cases[request.url.params["id"]]
            return httpx.Response(200, json={"status": "1", "pois": [item]})
        assert path == "/v5/direction/walking"
        assert request.url.params["origin_id"] == "hallA"
        if request.url.params["destination_id"] == "hallC":
            raise httpx.ReadTimeout("fixture timeout")
        assert request.url.params["destination_id"] == "hallB"
        assert request.url.params["origin"] == "121.400000,31.200000"
        assert request.url.params["show_fields"] == "cost"
        return httpx.Response(200, json={"status": "1", "route": {"paths": [
            {"distance": "1800", "cost": {"duration": "1321"}},
        ]}})

    old, client = provider(gateway, upstream)
    try:
        body = {"date": day, "fromEventId": first, "toEventId": second}
        route = "/api/v1/home/commutes/refresh"
        assert gateway.post(route, headers=alice, json=body).json()["status"] == "place_needed"
        assert calls == []
        search = gateway.get("/api/v1/places/search", headers=alice, params={"query": "创意园", "cityCode": "310000"})
        assert [v["address"] for v in search.json()["items"]] == ["一号入口", "二号入口"]
        assert all("coord" not in row for row in search.json()["items"])
        first_path, second_path = [f"/api/v1/events/{eid}/place" for eid in (first, second)]
        assert gateway.put(first_path, headers=bob, json={"placeId": "hallB"}).status_code == 404
        assert calls == ["/v5/place/text"]
        assert gateway.put(first_path, headers=alice, json={"placeId": "hallA"}).status_code == 200
        assert gateway.put(second_path, headers=alice, json={"placeId": "hallB"}).status_code == 200
        assert context(gateway, alice)["homeContext"]["commutes"][0]["status"] == "not_loaded"
        row = gateway.post(route, headers=alice, json=body).json()
        assert (row["status"], row["gapMinutes"], row["routeMinutes"], row["remainingMinutes"]) == ("available", 25, 23, -3)
        assert row["source"] == "amap"
        assert context(gateway, alice)["homeContext"]["commutes"] == [row]
        assert gateway.post(route, headers=alice, json=body).json() == row
        assert calls.count("/v5/direction/walking") == 1
        assert context(gateway, bob)["homeContext"]["places"] == {}
        assert gateway.post(route, headers=bob, json=body).status_code == 409

        assert gateway.put(second_path, headers=alice, json={"placeId": "hallC"}).status_code == 200
        assert context(gateway, alice)["homeContext"]["commutes"][0]["status"] == "not_loaded"
        assert gateway.post(route, headers=alice, json=body).json()["status"] == "unavailable"
        assert context(gateway, alice)["homeContext"]["commutes"][0]["status"] == "unavailable"
        assert gateway.delete(second_path, headers=alice).status_code == 200
        assert context(gateway, alice)["homeContext"]["commutes"][0]["status"] == "place_needed"
    finally:
        restore(gateway, old, client)
