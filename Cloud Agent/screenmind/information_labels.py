"""Reader-facing headings for screen excerpts, including cards saved by older mock models."""
import hashlib
import re
from datetime import datetime

BLOCK_NAMES = {"paragraph": "正文摘录", "chat": "聊天消息", "card": "卡片内容", "key_value": "要点"}
MOCK_EXCERPT_TITLE = "线索摘录"
OLD_EXCERPT_SUMMARY = re.compile(
    r"模拟模型按原文整理，不执行语义分析。当前收录 ([0-9]{1,3}) 份屏幕；用户修订由服务端保留。"
    r"( 本轮已收到补充要求；真实语义处理需配置模型。)?"
)
VERBOSE_EXCERPT_SUMMARY = re.compile(
    r"你选了 ([0-9]{1,3}) 份记录。演示模式按原文摘录，每条都保留了出处。( 已记下这次补充。)?"
)
HEADING = re.compile(r"([\u3400-\u9fff][\u3400-\u9fffA-Za-z0-9（）()、 ]{0,19})[：:]")
PHONE_APP = "微光 · 用户确认线索"
SOURCES = {"manual": "手动记录", "screenshot_ocr": "截图识别", "calendar": "系统日历", "location": "手机定位"}
NOTE_TYPES = {"note": "备忘", "message": "消息", "schedule": "日程", "location": "位置记录"}
PHONE_METADATA = re.compile(
    r"用户确认的来源渠道：([^；]+)；语义类型：([^；]+)；时区：[^；]*；"
    r"事项发生时间：([^。]+)。此时间不是消息发送时间，采集时间也不是消息发送时间。"
)
PHONE_OCCURRENCE = re.compile(r"用户核对的事项发生时间：([^；]+)；时区：[^。]*。这不是消息发送时间。")


def excerpt_id(reference):
    return "field_" + hashlib.sha256(reference.encode()).hexdigest()[:20]


def mock_excerpt_summary(count, has_followup):
    summary = f"摘录自 {count} 份线索。"
    return summary + " 已记下这次补充。" if has_followup else summary


def is_phone_source(screen):
    return screen.get("event_id", "").startswith("mobile:") and (
        screen["app_name"] != PHONE_APP or screen.get("app_package") == "cn.weiguang.app")


def excerpt_label(app_name, block):
    name = block.get("speaker")
    if not name:
        kind = block["kind"]
        heading = HEADING.match(block["text"].lstrip()) if kind == "key_value" else None
        name = heading[1].strip() if heading else BLOCK_NAMES[kind]
    return (app_name + " · " + name)[:100]


def _occurrence_sentence(value):
    try:
        stamp = datetime.fromisoformat(value)
    except ValueError:
        return "发生时间尚未填写。"
    offset = stamp.strftime("%z")
    zone = "（UTC" + offset[:3] + ":" + offset[3:5] + "）" if offset else ""
    clock = stamp.strftime("%H:%M:%S" if stamp.second else "%H:%M")
    return f"你确认的发生时间是 {stamp.year}年{stamp.month}月{stamp.day}日 {clock}{zone}。"


def phone_excerpt(app_name, block_id, text, label):
    """Present metadata added by the mobile adapter separately from quoted user text."""
    if app_name == PHONE_APP:
        label = label.removeprefix(PHONE_APP + " · ")
        if block_id == "metadata" and (match := PHONE_METADATA.fullmatch(text)):
            source, kind, occurred = match.groups()
            if source in SOURCES and kind in NOTE_TYPES:
                description = (f"你手动记下了这条{NOTE_TYPES[kind]}。" if source == "manual" else
                               f"这条{NOTE_TYPES[kind]}来自{SOURCES[source]}。")
                if occurred != "未知":
                    description += _occurrence_sentence(occurred)
                elif kind == "schedule":
                    description += "具体时间待确认。"
                return "记录信息", description
        return "ScreenMind · " + label, text
    if block_id.rstrip("_") == "capture_event_time" and (match := PHONE_OCCURRENCE.fullmatch(text)):
        return "已确认的发生时间", _occurrence_sentence(match[1])
    return label, text


def read_excerpt_labels(cards, get_screen):
    """Format decoded card copies while retaining the exact evidence and user edits."""
    screens = {}
    for card in cards:
        if not card or card.get("type") != "information":
            continue
        if card.get("title") == "屏幕信息整理 · 模拟结果":
            previous = OLD_EXCERPT_SUMMARY.fullmatch(card.get("summary") or "")
            if previous:
                card["title"] = MOCK_EXCERPT_TITLE
                card["summary"] = mock_excerpt_summary(int(previous[1]), bool(previous[2]))
        elif card.get("title") == MOCK_EXCERPT_TITLE:
            previous = VERBOSE_EXCERPT_SUMMARY.fullmatch(card.get("summary") or "")
            if previous:
                card["summary"] = mock_excerpt_summary(int(previous[1]), bool(previous[2]))
        for field in card.get("fields", []):
            app, separator, kind = field["label"].rpartition(" · ")
            refs = field.get("source_refs") or []
            if len(refs) != 1:
                continue
            sid, _, bid = refs[0].partition(":")
            old_kind = bool(separator) and kind in BLOCK_NAMES
            raw_detail = ((bid == "metadata" and field["value"].startswith("用户确认的来源渠道")) or
                          (bid.rstrip("_") == "capture_event_time" and field["value"].startswith("用户核对的事项发生时间")))
            if not (old_kind or raw_detail or app == PHONE_APP):
                continue
            generated = field["id"] == excerpt_id(refs[0])
            if not generated and (not raw_detail or field.get("origin") == "user"):
                continue
            if sid not in screens:
                screens[sid] = get_screen(sid)
            screen = screens[sid]
            if not screen:
                if not generated:
                    continue
                if old_kind:
                    field["label"] = (app + " · " + BLOCK_NAMES[kind])[:100]
                if app == PHONE_APP or (raw_detail and bid.rstrip("_") == "capture_event_time"):
                    label, value = phone_excerpt(app, bid, field["value"], field["label"])
                    field["label"] = label
                    if field.get("origin") != "user":
                        field["value"] = value
                continue
            block = next((entry for entry in screen["blocks"] if entry["id"] == bid), None)
            if not block:
                continue
            if generated and old_kind and screen["app_name"] == app and block["kind"] == kind and not block.get("speaker"):
                field["label"] = excerpt_label(app, block)
            if is_phone_source(screen) and (app == PHONE_APP or raw_detail):
                label, value = phone_excerpt(screen["app_name"], bid, block["text"], field["label"])
                if generated:
                    field["label"] = label
                if field.get("origin") != "user" and field["value"] == block["text"][:1000]:
                    field["value"] = value[:1000]
