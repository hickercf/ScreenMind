"""Read-only weather and walking estimates for the live home."""
import asyncio
from collections import OrderedDict
from datetime import datetime, timedelta
from math import ceil, isfinite
from time import monotonic

import httpx

from .home_timing import ZONE, event_bounds, event_day

CITIES = {
    "110000": "北京", "310000": "上海", "440100": "广州",
    "440300": "深圳", "330100": "杭州", "510100": "成都",
}
WALK_BUFFER_MINUTES = 5
_AMAP = "https://restapi.amap.com"


def city_view(code):
    return {"code": code, "name": CITIES[code]} if code in CITIES else None


class ProviderUnavailable(Exception):
    """The current response cannot be used."""


class HomeContext:
    def __init__(self, client, api_key):
        self.client = client
        self.api_key = api_key
        self.cache = OrderedDict()
        self.inflight = {}
        self.capacity = asyncio.Semaphore(6)

    @property
    def enabled(self):
        return bool(self.api_key)

    async def request(self, path, **params):
        if not self.enabled:
            raise ProviderUnavailable
        try:
            async with asyncio.timeout(5), self.capacity:
                response = await self.client.get(_AMAP + path, params={**params, "key": self.api_key})
                response.raise_for_status()
                result = response.json()
        except (TimeoutError, httpx.HTTPError, ValueError):
            raise ProviderUnavailable from None
        if not isinstance(result, dict) or str(result.get("status")) != "1":
            raise ProviderUnavailable
        return result

    def cached(self, key):
        entry = self.cache.get(key)
        if entry is not None:
            expires, value = entry
            if monotonic() < expires:
                return value
            del self.cache[key]
        return None

    async def load(self, key, ttl, fetch):
        cached = self.cached(key)
        if cached is not None:
            return cached
        task = self.inflight.get(key)
        if task is None:
            async def run():
                try:
                    try:
                        result = await fetch()
                    except ProviderUnavailable:
                        result = {"status": "unavailable"}
                    expire_after = ttl if result["status"] == "available" else 20
                    self.cache[key] = (monotonic() + expire_after, result)
                    self.cache.move_to_end(key)
                    if len(self.cache) > 256:
                        self.cache.popitem(last=False)
                    return result
                finally:
                    self.inflight.pop(key, None)
            task = asyncio.create_task(run())
            self.inflight[key] = task
        return await asyncio.shield(task)

    async def close(self):
        tasks = list(self.inflight.values())
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

    async def refresh_weather(self, code):
        async def fetch():
            reply = await self.request("/v3/weather/weatherInfo", city=code, extensions="base")
            try:
                item = reply["lives"][0]
                reported = datetime.fromisoformat(item["reporttime"])
                if reported.tzinfo is None:
                    reported = reported.replace(tzinfo=ZONE)
                temp = float(item["temperature"])
                condition = item["weather"].strip()
                if not isfinite(temp) or not condition:
                    raise ValueError
            except (KeyError, IndexError, TypeError, ValueError, AttributeError):
                raise ProviderUnavailable from None
            return {"status": "available", "condition": condition, "temperatureC": temp,
                    "reportedAt": reported.isoformat(), "source": "amap"}

        return await self.load(("weather", code), 900, fetch)

    def weather(self, code):
        if not city_view(code):
            return {"status": "city_needed"}
        key = ("weather", code)
        prior = self.cache.get(key)
        if prior and prior[1]["status"] == "unavailable":
            # Keep the fallback stable after the retry delay. A user refresh can retry.
            return prior[1]
        if key in self.inflight:
            return {"status": "loading"}
        cached = self.cached(key)
        if cached and cached["status"] == "available":
            if datetime.now(ZONE) - datetime.fromisoformat(cached["reportedAt"]) > timedelta(hours=2):
                return {"status": "stale", "reportedAt": cached["reportedAt"], "source": "amap"}
        return cached or {"status": "not_loaded" if self.enabled else "unavailable"}

    @staticmethod
    def place(item):
        if not isinstance(item, dict):
            return None
        pid, name, coords = item.get("id"), item.get("name"), item.get("location")
        if not all(isinstance(v, str) and v.strip() for v in (pid, name, coords)):
            return None
        if len(pid) > 64 or any(not (c.isascii() and (c.isalnum() or c in "-_")) for c in pid):
            return None
        try:
            lng, lat = map(float, coords.split(","))
            if not (-180 <= lng <= 180 and -90 <= lat <= 90):
                return None
        except ValueError:
            return None
        address = item.get("address")
        return {"placeId": pid, "name": name.strip()[:120],
                "address": address.strip()[:200] if isinstance(address, str) else "",
                "coord": f"{lng:.6f},{lat:.6f}"}

    async def search(self, code, query):
        try:
            reply = await self.request("/v5/place/text", region=code, city_limit="true",
                                       keywords=query, page_size=8)
            if not isinstance(reply.get("pois"), list):
                raise ProviderUnavailable
        except ProviderUnavailable:
            return {"status": "unavailable", "items": []}
        places = [p for item in reply["pois"][:8] if (p := self.place(item))]
        return {"status": "available" if places else "empty",
                "items": [{k: p[k] for k in ("placeId", "name", "address")} for p in places]}

    async def resolve_place(self, place_id):
        async def fetch():
            reply = await self.request("/v5/place/detail", id=place_id)
            items = reply.get("pois")
            if not isinstance(items, list):
                raise ProviderUnavailable
            for item in items:
                if (place := self.place(item)) and place["placeId"] == place_id:
                    return {"status": "available", "place": place}
            return {"status": "empty"}
        return await self.load(("place", place_id), 600, fetch)

    async def refresh_walk(self, origin, destination):
        async def fetch():
            a, b = await asyncio.gather(self.resolve_place(origin), self.resolve_place(destination))
            if not all(record["status"] == "available" for record in (a, b)):
                raise ProviderUnavailable
            reply = await self.request("/v5/direction/walking", origin=a["place"]["coord"],
                                       destination=b["place"]["coord"], origin_id=origin,
                                       destination_id=destination, show_fields="cost")
            try:
                paths = reply["route"]["paths"]
                if paths == []:
                    return {"status": "empty"}
                route = paths[0]
                duration, distance = int(route["cost"]["duration"]), int(route["distance"])
                if duration < 0 or distance < 0:
                    raise ValueError
            except (KeyError, IndexError, TypeError, ValueError):
                raise ProviderUnavailable from None
            return {"status": "available", "routeMinutes": ceil(duration / 60), "distanceMeters": distance,
                    "estimatedAt": datetime.now(ZONE).isoformat(), "source": "amap"}
        return await self.load(("walk", origin, destination), 600, fetch)

    def commute(self, first, second, day, places):
        first_start, first_end = event_bounds(first, day)
        second_start, _ = event_bounds(second, day)
        result = {"fromEventId": first["id"], "toEventId": second["id"], "mode": "walking"}
        if not (first_start and first_end and second_start):
            return dict(result, status="time_needed")
        gap = int((second_start - first_end).total_seconds() // 60)
        result["gapMinutes"] = gap
        if gap < 0:
            return dict(result, status="overlap")
        origin, destination = places.get(first["id"]), places.get(second["id"])
        if not origin or not destination:
            return dict(result, status="place_needed")
        estimate = self.cached(("walk", origin["placeId"], destination["placeId"]))
        if not estimate:
            return dict(result, status="not_loaded" if self.enabled else "unavailable")
        if estimate["status"] != "available":
            return dict(result, **estimate)
        remaining = gap - estimate["routeMinutes"] - WALK_BUFFER_MINUTES
        return dict(result, **estimate, bufferMinutes=WALK_BUFFER_MINUTES, remainingMinutes=remaining)

    @staticmethod
    def pairs(cards, day):
        reference = datetime.now(ZONE).date()
        timed = []
        for card in cards:
            if card.get("allDay") or card["lifecycleStage"] == "ARCHIVED" or event_day(card, reference) != day:
                continue
            start, _ = event_bounds(card, day)
            if start is not None:
                timed.append((start, card["id"], card))
        timed.sort(key=lambda item: (item[0], item[1]))
        ordered = [item[2] for item in timed]
        return zip(ordered, ordered[1:])

    def snapshot(self, settings, cards, day):
        code = settings.get("homeCityCode")
        places = settings.get("homePlaces", {})
        visible_places = {c["id"]: places[c["id"]] for c in cards if c["id"] in places}
        weather = self.weather(code) if day == datetime.now(ZONE).date() else {"status": "not_for_date"}
        return {"cityOptions": [city_view(code) for code in CITIES], "city": city_view(code),
                "weather": weather, "places": visible_places,
                "commutes": [self.commute(a, b, day, places) for a, b in self.pairs(cards, day)]}
