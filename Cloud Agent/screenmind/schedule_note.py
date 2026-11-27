"""Read a single explicitly dated schedule answer in the local demo."""
from datetime import datetime, timedelta, timezone
import hashlib
import re
import unicodedata
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

LEGACY_QUESTION = "模拟模式请提供 schedule_changes JSON；真实模型可处理自然语言。"
DATE_QUESTION = "这项日程叫什么，定在什么时候？请带上完整日期和时区，例如“读书会，2026年12月10日 18:00（北京时间）”。"
REVIEW_SUMMARY = "请核对下面的日程。"
PROGRESS = {"pending": "待完成", "completed": "已完成"}
LOCAL_ZONES = {"北京时间": "Asia/Shanghai", "上海时间": "Asia/Shanghai", "台北时间": "Asia/Taipei",
               "香港时间": "Asia/Hong_Kong", "东京时间": "Asia/Tokyo", "伦敦时间": "Europe/London",
               "纽约时间": "America/New_York"}
DATE_TIME = re.compile(
    r"(?P<year>[0-9]{4})(?:年|-)(?P<month>[0-9]{1,2})(?:月|-)(?P<day>[0-9]{1,2})(?:日\s*|\s+)"
    r"(?P<period>上午|下午|凌晨|晚上|中午)?\s*(?P<hour>[0-9]{1,2})"
    r"(?::(?P<minute>[0-9]{2})(?::(?P<second>[0-9]{2}))?|[点时](?:(?P<half>半)|(?P<spoken_minute>[0-9]{1,2})分?)?)"
    r"\s*(?:\((?P<parenthesized_zone>[^()\s]+)\)|(?P<zone>[^()\s]+))"
)


def explicit_zone(name):
    name = name.replace("臺", "台").replace("時間", "时间")
    if name in LOCAL_ZONES:
        try:
            return ZoneInfo(LOCAL_ZONES[name])
        except ZoneInfoNotFoundError:
            return None
    if name.upper() in ("UTC", "GMT") or name in ("世界时", "协调世界时"):
        return timezone.utc
    offset = re.fullmatch(r"(?:UTC|GMT)([+-])([0-9]{1,2})(?::([0-9]{2}))?", name.upper())
    if not offset:
        return None
    hours, minutes = int(offset[2]), int(offset[3] or 0)
    if minutes > 59 or hours * 60 + minutes > 14 * 60:
        return None
    delta = timedelta(hours=hours, minutes=minutes)
    return timezone(delta if offset[1] == "+" else -delta)


def dated_answer(text):
    parts = re.split(r"[，,]|定在|安排在", text.strip().rstrip("。"), maxsplit=1)
    if len(parts) != 2:
        return None
    title = parts[0].strip()
    if not 1 <= len(title) <= 100 or any(c in title for c in "\r\n。！？；{}[]"):
        return None
    if title.startswith(("取消", "不要", "可能", "如果", "不确定", "不参加")):
        return None
    timing = unicodedata.normalize("NFKC", parts[1]).strip()
    match = DATE_TIME.fullmatch(timing)
    if not match:
        return None
    zone = explicit_zone(match["parenthesized_zone"] or match["zone"])
    if zone is None:
        return None
    hour = int(match["hour"])
    if match["period"]:
        if not 1 <= hour <= 12:
            return None
        if match["period"] == "晚上" and hour == 12:
            return None
        if match["period"] in ("下午", "晚上"):
            hour = hour % 12 + 12
        elif match["period"] == "中午":
            if hour != 12:
                return None
        elif match["period"] == "凌晨":
            hour %= 12
        elif hour == 12:
            return None
    minute = 30 if match["half"] else int(match["minute"] or match["spoken_minute"] or 0)
    try:
        at = datetime(int(match["year"]), int(match["month"]), int(match["day"]),
                      hour, minute, int(match["second"] or 0), tzinfo=zone)
    except ValueError:
        return None
    if at.utcoffset() != at.replace(fold=1).utcoffset():
        return None
    return title, at.isoformat()


def answer_change(answer, items):
    parsed = dated_answer(answer["message"])
    if parsed is None:
        return None
    title, at = parsed
    matches = [key for key, item in items.items() if item.get("title") == title]
    if len(matches) > 1:
        return None
    reference = answer["source_ref"]
    seed = reference.rsplit(":", 1)[0] + "\0" + title
    key = matches[0] if matches else "date_" + hashlib.sha256(seed.encode()).hexdigest()[:16]
    return {"id": key, "title": title, "at": at, "source_refs": [reference]}


def apply_missing_date(changes, items, answer):
    parsed = dated_answer(answer["message"])
    if not parsed:
        return
    title, at = parsed
    matching = [change for key, change in changes.items()
                if (change.get("title") or items.get(key, {}).get("title")) == title]
    if len(matching) == 1:
        target = matching[0]
    elif not matching and len(changes) == 1:
        target, = changes.values()
        if target.get("title") or items.get(target["id"], {}).get("title"):
            return
    else:
        return
    # A date answer does not authorize completion or dependencies found in a screenshot.
    if target.keys() - {"id", "title", "at", "source_refs"}:
        return
    refs = target["source_refs"]
    if target.get("at") and not any(ref.startswith("input:") for ref in refs):
        return
    source = [ref for ref in refs if not ref.startswith("input:")]
    target.update(title=title, at=at, source_refs=[*source, answer["source_ref"]])


def schedule_field(at, status, before):
    parts = [at, PROGRESS[status]]
    if before:
        parts.append("在" + before + "之前")
    return " · ".join(parts)


def read_schedule_card(card):
    if not card:
        return
    summaries = {"核对时间与依赖后确认更新": REVIEW_SUMMARY,
                 "忽略较早的通知；核对时间与依赖后确认更新": "已保留较新的通知。" + REVIEW_SUMMARY,
                 "忽略较早的通知；当前安排没有变化": "已保留较新的通知，日程没有变化。"}
    if card.get("summary") in summaries:
        card["summary"] = summaries[card["summary"]]
    for field in card.get("fields", []):
        if field.get("editable") is not False:
            continue
        parts = field.get("value", "").split(" · ", 2)
        if len(parts) != 3 or parts[1] not in PROGRESS:
            continue
        at, status, rule = parts
        if not (rule == "无先后约束" or rule.startswith("须早于：")):
            continue
        try:
            if datetime.fromisoformat(at).utcoffset() is None:
                continue
        except ValueError:
            continue
        before = "" if rule == "无先后约束" else rule.removeprefix("须早于：")
        field["value"] = schedule_field(at, status, before)
