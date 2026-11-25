"""Shared date and time rules for home cards and their detail views."""
from datetime import date, datetime, time, timedelta, timezone
import re

ZONE = timezone(timedelta(hours=8))
ZONE_LABEL = "UTC+08:00"
_CLOCK = re.compile(r"(?:[01]?\d|2[0-3]):[0-5]\d")
_RELATIVE_DAY = re.compile(r"今天|今日|今晚|明天|明早|明日|后天|(?:(下|本|这)?周([一二三四五六日天]))")
_LEGACY_GAP_DURATION = re.compile(r"(?:停留|停靠|用时|时长)\s*(\d{1,3})\s*分钟")
_CALENDAR_RANGE = re.compile(r"^(\d{2}-\d{2} \d{2}:\d{2})\s+—\s+(\d{2}-\d{2} \d{2}:\d{2})$", re.M)


def timestamp(value):
    if not isinstance(value, str):
        return None
    try:
        stamp = datetime.fromisoformat(value)
    except ValueError:
        return None
    return stamp if stamp.utcoffset() is not None else None


def clock(value):
    if not isinstance(value, str) or not _CLOCK.fullmatch(value):
        return None
    hours, minutes = map(int, value.split(":"))
    return time(hours, minutes)


def event_day(card, fallback):
    stamp = timestamp(card.get("scheduledAt"))
    if stamp:
        return stamp.date() if card.get("allDay") else stamp.astimezone(ZONE).date()
    if card.get("scheduleDate"):
        return date.fromisoformat(card["scheduleDate"])
    captured = timestamp(card.get("capturedAt"))
    completed = timestamp(card.get("completedAt"))
    base = (captured or completed).astimezone(ZONE).date() if captured or completed else fallback
    phrase = card.get("relativeDayHint", "")
    if not phrase and captured:
        # A later return phrase belongs to the relay, not the outbound clue.
        phrase = re.split(r"[，,。；;]", card.get("originalText", ""), maxsplit=1)[0]
    relative = _RELATIVE_DAY.search(phrase)
    if not relative:
        return base
    if relative.group(2):
        weekday = "一二三四五六日".index(relative[2].replace("天", "日"))
        delta = weekday - base.weekday()
        if relative[1] == "下":
            delta += 7
        elif delta < 0 and not relative[1]:
            delta += 7
        return base + timedelta(days=delta)
    return base + timedelta(days={"明天": 1, "明早": 1, "明日": 1, "后天": 2}.get(relative[0], 0))


def event_time(card, day):
    stamp = timestamp(card.get("scheduledAt"))
    if stamp:
        return stamp
    value = card.get("time", "")
    if card.get("relativeDayHint"):
        match = _CLOCK.search(value)
        value = match[0] if match else ""
    parsed = clock(value)
    return datetime.combine(day, parsed, ZONE) if parsed is not None else None


def calendar_range_end(start_value, text):
    start = timestamp(start_value)
    match = _CALENDAR_RANGE.search(text)
    if start is None or match is None:
        return None
    from_label, to_label = match.groups()
    year = start.astimezone(ZONE).year
    try:
        first = datetime.strptime(f"{year} {from_label}", "%Y %m-%d %H:%M")
        last_year = year + int(to_label[:5] < from_label[:5])
        last = datetime.strptime(f"{last_year} {to_label}", "%Y %m-%d %H:%M")
    except ValueError:
        return None
    delta = last - first
    return (start + delta).isoformat() if delta > timedelta(0) else None


def gap_duration_minutes(subtitle):
    match = _LEGACY_GAP_DURATION.search(subtitle or "")
    return int(match[1]) if match and int(match[1]) > 0 else None


def gap_subtitle(subtitle, minutes):
    return _LEGACY_GAP_DURATION.sub(f"停留 {minutes} 分钟", subtitle) if minutes is not None else subtitle


def duration_label(minutes):
    return f"{minutes} 分钟" if minutes is not None else ""


def event_bounds(card, day):
    start = event_time(card, day)
    end = timestamp(card.get("endsAt"))
    if start is None:
        return None, None
    if end is None and not card.get("allDay"):
        if minutes := card.get("durationMinutes"):
            end = start + timedelta(minutes=minutes)
        elif (end_clock := clock(card.get("endTime"))) is not None:
            end = datetime.combine(start.astimezone(ZONE).date(), end_clock, ZONE)
            if end < start:
                end += timedelta(days=1)
    return start, end if end and end > start else None


def timing(card, day):
    start, end = event_bounds(card, day)
    completed = timestamp(card.get("completedAt"))
    all_day = bool(card.get("allDay"))
    return {
        "startAt": start.isoformat() if start else None,
        "endAt": end.isoformat() if end else None,
        "durationMinutes": int((end - start).total_seconds() // 60) if end and not all_day else None,
        "allDay": all_day,
        "completedAt": completed.isoformat() if completed else None,
    }


def shift_schedule(card, day, minutes):
    start, end = event_bounds(card, day)
    if start is None or card.get("allDay"):
        return None
    delta = timedelta(minutes=minutes)
    shifted = start + delta
    return {"time": shifted.astimezone(ZONE).strftime("%H:%M"),
            "scheduledAt": shifted.isoformat(),
            **({"endsAt": (end + delta).isoformat()} if end else {})}
