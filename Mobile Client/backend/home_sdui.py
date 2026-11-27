"""Backend AuraFlow SDUI home timeline generator and state manager.

Implements the card generation decision tree from:
- 关于首页卡片生成逻辑的一些想法.md
- auraflow_sdui.html

Strictly scoped to the Home module (SDUI v2.4.0) while integrating with real
user events (OCR/manual/calendar/location), decisions, and Cloud Agent works.
"""
from __future__ import annotations

import copy
from contextlib import closing
import json
import re
import sqlite3
import time
from datetime import datetime, timedelta, timezone
from typing import Any, Literal
from uuid import uuid4

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field

from .home_timing import (
    ZONE, calendar_range_end, clock, duration_label, event_bounds, event_day, gap_duration_minutes, gap_subtitle,
    shift_schedule, timestamp,
)


PROTOCOL_VERSION = "2.4.0"

THEMES: dict[str, dict[str, Any]] = {
    "travel": {"hex": "#00E5FF", "alt": "#3B82F6", "rgb": "0, 229, 255", "label": "出行订票"},
    "dining": {"hex": "#FF914D", "alt": "#F43F5E", "rgb": "255, 145, 77", "label": "聚餐社交"},
    "shopping": {"hex": "#00E676", "alt": "#14B8A6", "rgb": "0, 230, 118", "label": "线下购物"},
    "explore": {"hex": "#C084FC", "alt": "#FBBF24", "rgb": "192, 132, 252", "label": "城市漫游"},
    "office": {"hex": "#818CF8", "alt": "#6366F1", "rgb": "129, 140, 248", "label": "事务日程"},
    "rest": {"hex": "#94A3B8", "alt": "#818CF8", "rgb": "148, 163, 184", "label": "静默放空"},
}

EXPLORE_PRESETS: dict[str, list[dict[str, Any]]] = {
    "sunny": [
        {
            "id": "sun_1",
            "title": "西岸滨江 · 落日微风 Citywalk",
            "duration": "2.5 小时 · 步行 3.2 km",
            "tag": "绝佳江景日落",
            "stops": [
                {"time": "14:30", "name": "龙美术馆江畔草坪", "dist": "起点", "note": "秋日微风草坪小憩"},
                {"time": "15:30", "name": "滑板公园沿江步道", "dist": "1.4 km", "note": "黄金光线拍照打卡"},
                {"time": "16:45", "name": "油罐艺术中心 · 落日露台", "dist": "1.8 km", "note": "看 17:12 橘色江面日落"},
            ],
        },
        {
            "id": "sun_2",
            "title": "衡复风貌区 · 梧桐光影漫游",
            "duration": "2.0 小时 · 步行 2.6 km",
            "tag": "文艺街区摄影",
            "stops": [
                {"time": "14:00", "name": "安福路独立买手街区", "dist": "起点", "note": "逛独立设计小店"},
                {"time": "15:00", "name": "武康路老洋房长廊", "dist": "1.1 km", "note": "梧桐斑驳光影散步"},
                {"time": "16:00", "name": "复兴公园 · 露天手冲摊", "dist": "1.5 km", "note": "听黑胶喝冰美式"},
            ],
        },
        {
            "id": "sun_3",
            "title": "苏河湾 · 工业遗迹与公园放空",
            "duration": "2.5 小时 · 步行 2.9 km",
            "tag": "人少静谧路线",
            "stops": [
                {"time": "14:30", "name": "慎余里红砖里弄", "dist": "起点", "note": "老建筑改造展厅"},
                {"time": "15:30", "name": "苏河湾万象天地绿地", "dist": "1.2 km", "note": "晒太阳放空 40 分钟"},
                {"time": "16:40", "name": "邮政博物馆河岸桥景", "dist": "1.7 km", "note": "远眺陆家嘴天际线"},
            ],
        },
    ],
    "rainy": [
        {
            "id": "rain_1",
            "title": "西岸美术馆特展 · 听雨手冲微行程",
            "duration": "2.5 小时 · 室内连廊直达",
            "tag": "全程不淋雨",
            "stops": [
                {"time": "14:00", "name": "西岸美术馆 · 蓬皮杜特展", "dist": "室内", "note": "免排队电子票直入"},
                {"time": "15:45", "name": "滨江玻璃房手冲咖啡馆", "dist": "步行 180m", "note": "窗边听雨品埃塞瑰夏"},
                {"time": "16:45", "name": "设计书店黑胶试听角", "dist": "同建筑", "note": "挑一本建筑画册"},
            ],
        },
        {
            "id": "rain_2",
            "title": "上生新所 · 茑屋书店与暖冬茶席",
            "duration": "2.2 小时 · 历史室内空间",
            "tag": "高分静谧治愈",
            "stops": [
                {"time": "14:30", "name": "哥伦比亚俱乐部 · 茑屋书店", "dist": "室内", "note": "百年壁炉旁阅读"},
                {"time": "15:40", "name": "中式现代茶室 · 碳焙乌龙", "dist": "步行 90m", "note": "雨天热茶配手作茶点"},
                {"time": "16:50", "name": "地下爵士黑胶体验廊", "dist": "室内", "note": "沉浸式试听 30 分钟"},
            ],
        },
        {
            "id": "rain_3",
            "title": "当代艺术博物馆 · 咖啡与胶片展",
            "duration": "2.5 小时 · 地铁直达",
            "tag": "小众灵感补给",
            "stops": [
                {"time": "14:00", "name": "PSA 当代艺术双年展", "dist": "室内", "note": "沉浸装置艺术空间"},
                {"time": "15:50", "name": "顶楼江景玻璃咖啡厅", "dist": "电梯直达", "note": "俯瞰雨雾黄浦江"},
                {"time": "16:50", "name": "艺术商店胶片周边区", "dist": "1F", "note": "挑一张限量版画"},
            ],
        },
    ],
}

DINING_FRIENDS = [
    {"name": "你·虹桥机场", "short": "你", "x": 40, "y": 96},
    {"name": "林溪·静安寺", "short": "林", "x": 142, "y": 40},
    {"name": "周明·陆家嘴", "short": "周", "x": 294, "y": 58},
    {"name": "陈默·徐家汇", "short": "陈", "x": 132, "y": 152},
]

DEFAULT_GAP_SPOTS = [
    {
        "time": "12:00",
        "title": "西岸美术馆特展",
        "subtitle": "步行 8m · 沉浸看展 1.5h",
        "durationMinutes": 90,
        "detail": "12:00 · 距离 650m · 蓬皮杜当代艺术展",
    },
    {
        "time": "12:15",
        "title": "独立手冲咖啡馆",
        "subtitle": "静谧办公位 · 距出发顺路",
        "detail": "12:15 · 距离 320m · 埃塞单品 + 安静电源位",
    },
]

HOME_SDUI_SCHEMA = """
CREATE TABLE IF NOT EXISTS home_sdui_state (
 user_id TEXT PRIMARY KEY REFERENCES users(id),
 body TEXT NOT NULL,
 updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS home_feedback (
 id TEXT PRIMARY KEY,
 user_id TEXT NOT NULL REFERENCES users(id),
 card_id TEXT NOT NULL,
 source_event_id TEXT,
 action TEXT NOT NULL,
 reason TEXT NOT NULL,
 original_text TEXT NOT NULL,
 created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS home_confirmations (
 user_id TEXT NOT NULL REFERENCES users(id), scope TEXT NOT NULL, key TEXT NOT NULL,
 fingerprint TEXT NOT NULL, reply TEXT NOT NULL, PRIMARY KEY(user_id,scope,key)
);
"""


def _default_travel_plans(direction: str = "outbound") -> list[dict[str, Any]]:
    if direction == "return":
        return [
            {
                "id": "mu5116", "carrier": "东航 MU5116", "mode": "flight", "modeText": "直飞 2h15m",
                "price": 960, "punctuality": 95, "dep": "12:40", "arr": "14:55",
                "depHub": "虹桥 T2", "arrHub": "首都 T2", "seat": "14A 靠窗", "gate": "C18",
                "note": "15:00前落地首都，从容衔接周日晚间休息",
            },
            {
                "id": "g18", "carrier": "复兴号 G18", "mode": "rail", "modeText": "高铁 4h22m",
                "price": 662, "punctuality": 99, "dep": "10:30", "arr": "14:52",
                "depHub": "上海虹桥", "arrHub": "北京南站", "seat": "06车 06F", "gate": "11B",
                "note": "高铁准点不惧晚秋华北阵风",
            },
            {
                "id": "ca1502", "carrier": "国航 CA1502", "mode": "flight", "modeText": "宽体 2h20m",
                "price": 1080, "punctuality": 92, "dep": "13:15", "arr": "15:35",
                "depHub": "虹桥 T2", "arrHub": "首都 T3", "seat": "35A 靠窗", "gate": "B09",
                "note": "午后从容退房前往机场",
            },
        ]
    return [
        {
            "id": "mu5102", "carrier": "东航 MU5102", "mode": "flight", "modeText": "直飞 2h15m",
            "price": 980, "punctuality": 96, "dep": "14:30", "arr": "16:45",
            "depHub": "首都 T2", "arrHub": "虹桥 T2", "seat": "14A 靠窗", "gate": "B24",
            "note": "降落虹桥距市区最近，AI 匹配靠窗静谧位",
        },
        {
            "id": "g14", "carrier": "复兴号 G14", "mode": "rail", "modeText": "高铁 4h18m",
            "price": 662, "punctuality": 99, "dep": "12:00", "arr": "16:18",
            "depHub": "北京南站", "arrHub": "上海虹桥", "seat": "05车 08F", "gate": "14A",
            "note": "准点率 99%，全程不断网适合途中处理邮件",
        },
        {
            "id": "ca1519", "carrier": "国航 CA1519", "mode": "flight", "modeText": "宽体 2h20m",
            "price": 1120, "punctuality": 93, "dep": "14:00", "arr": "16:20",
            "depHub": "首都 T3", "arrHub": "虹桥 T2", "seat": "32A 靠窗", "gate": "C11",
            "note": "空客 A350 宽体客机，落地时间更从容",
        },
    ]


def _default_dining_plans() -> list[dict[str, Any]]:
    return [
        {
            "id": "luyuan", "name": "鹿园 · 外滩店", "match": 96, "price": 320,
            "queue": "包厢免排队", "room": "B08 江景包厢", "address": "黄浦区中山东一路 18 号",
            "avgCommute": 26, "target": {"x": 244, "y": 84}, "commutes": [38, 19, 14, 33],
            "note": "四方地理重心最优解，已预留江景包厢",
        },
        {
            "id": "yongfu", "name": "甬府小鲜 · 静安", "match": 92, "price": 260,
            "queue": "需等 15m", "room": "A04 雅致卡座", "address": "静安区南京西路 1266 号",
            "avgCommute": 24, "target": {"x": 164, "y": 78}, "commutes": [28, 8, 30, 29],
            "note": "距恒隆广场步行 5 分钟，顺路后续采购",
        },
        {
            "id": "fuhehui", "name": "福和慧 · 愚园路", "match": 89, "price": 480,
            "queue": "米其林私密", "room": "禅意 3 号厅", "address": "长宁区愚园路 1037 号",
            "avgCommute": 29, "target": {"x": 118, "y": 92}, "commutes": [22, 16, 46, 31],
            "note": "离虹桥机场更近，商务宴请静谧度满分",
        },
    ]


def _default_shopping_price_history() -> list[dict[str, Any]]:
    return [
        {"date": "10/28", "price": 380, "tag": "日常原价"},
        {"date": "11/03", "price": 368, "tag": "小幅回落"},
        {"date": "11/11", "price": 315, "tag": "双11史低"},
        {"date": "11/16", "price": 352, "tag": "节后回调"},
        {"date": "今日", "price": 328, "tag": "叠券到手 · 推荐入手"},
    ]


def _default_shopping_checklist() -> list[dict[str, Any]]:
    return [
        {"id": "c1", "floor": "B1", "name": "B1 焦糖蝴蝶酥礼盒", "price": "¥268", "save": "省¥40", "done": False},
        {"id": "c2", "floor": "L2", "name": "L2 乌木雪松香氛", "price": "¥980", "save": "省¥220", "done": False},
        {"id": "c3", "floor": "L3", "name": "L3 限定纪念随行杯", "price": "¥320", "save": "省¥80", "done": False},
    ]


def showcase_template() -> dict[str, Any]:
    """Full AuraFlow multi-scenario showcase matching auraflow_sdui.html."""
    return {
        "archivedEvents": [
            {
                "id": "evt_arch_01",
                "time": "09:00",
                "endTime": "09:45",
                "title": "项目晨会同步",
                "lifecycleStage": "ARCHIVED",
                "summary": "已确认 Q4 发布节点与设计评审清单，纪要已自动归档至云端。",
            }
        ],
        "events": [
            {
                "id": "evt_morning_prep",
                "category": "OFFICE",
                "themeKey": "office",
                "lifecycleStage": "CONFIRMED",
                "time": "10:30",
                "durationMinutes": 30,
                "title": "行前简报 · 客户端需求对齐",
                "subtitle": "线上腾讯会议 · 30m 速会",
                "badge": "已就绪",
                "reminderStrip": {
                    "text": "带好演示稿与 Type-C 转接头 · 距开会 15m",
                    "dismissed": False,
                },
                "connectorToNext": {
                    "connectorState": "GAP_SUGGESTION",
                    "gapHours": "3.5h",
                    "label": "空档 3.5h · 附近补白 ✦",
                    "gapSpots": copy.deepcopy(DEFAULT_GAP_SPOTS),
                },
                "entities": ["10:30 速会", "30分钟", "已同步会议纪要模板"],
                "detailBlocks": ["SUMMARY_CARD", "MEETING_CHECKLIST"],
                "notes": [
                    {"text": "核对 SDUI 原子组件 JSON 规范", "done": True},
                    {"text": "同步上海客户演示环境地址", "done": False},
                ],
            },
            {
                "id": "evt_travel_outbound",
                "category": "TRAVEL",
                "themeKey": "travel",
                "direction": "outbound",
                "lifecycleStage": "PROPOSAL",
                "time": "14:30",
                "title": "北京 → 上海 · 差旅出行",
                "subtitle": "待拍板 · 推荐 3 套出行方案",
                "badge": "待拍板",
                "reminderStrip": {
                    "text": "上海小雨 14°C · 建议带伞 / 身份证 · 距值机截止 1h",
                    "dismissed": False,
                },
                "connectorToNext": {
                    "connectorState": "CONFLICT_DASHED",
                    "label": "打车约 55m · 衔接紧张",
                    "actionLabel": "一键顺延 30m",
                },
                "entities": ["北京→上海", "今日 14:30", "靠窗偏好", "准点率优先"],
                "selectedPlanIdx": 0,
                "plans": _default_travel_plans("outbound"),
                "detailBlocks": ["MAP_D3_CORRIDOR", "PLAN_DECK_SELECTOR", "ACTION_SLIDE_BAR"],
            },
            {
                "id": "evt_dining_bund",
                "category": "DINING",
                "themeKey": "dining",
                "lifecycleStage": "PROPOSAL",
                "time": "18:00",
                "title": "外滩 · 鹿园晚宴（4人）",
                "subtitle": "群聊捕获 · 4人等时圈最优解",
                "badge": "待拍板",
                "connectorToNext": {
                    "connectorState": "NORMAL_SOLID",
                    "label": "行程充裕",
                },
                "entities": ["4人聚餐", "分散在4个区", "静谧包厢", "免长排队"],
                "selectedPlanIdx": 0,
                "plans": _default_dining_plans(),
                "detailBlocks": ["MAP_DINING_ISOCHRONE", "PLAN_DECK_SELECTOR", "ACTION_SLIDE_BAR"],
            },
            {
                "id": "evt_shopping_plaza66",
                "category": "SHOPPING",
                "themeKey": "shopping",
                "lifecycleStage": "PROPOSAL",
                "time": "20:00",
                "title": "恒隆广场 · 伴手礼采购",
                "subtitle": "已比价省 ¥340 · 3件现货清单",
                "badge": "0/3",
                "connectorToNext": None,
                "entities": ["恒隆广场", "3件现货", "已寻2张满减券", "当前近史低"],
                "priceHistory": _default_shopping_price_history(),
                "checklist": _default_shopping_checklist(),
                "detailBlocks": ["CHART_PRICE_TREND", "MAP_INDOOR_ROUTE", "INTERACTIVE_CHECKLIST", "ACTION_SLIDE_BAR"],
            },
        ],
        "crossDayRelay": {
            "id": "evt_travel_return",
            "category": "TRAVEL",
            "themeKey": "travel",
            "direction": "return",
            "lifecycleStage": "PROPOSAL",
            "time": "周日 15:00",
            "relativeDayHint": "周日",
            "title": "上海 → 北京 · 返程提案",
            "subtitle": "未订票（提前48h唤醒）",
            "badge": "未订票",
            "entities": ["周日返程", "上海→北京", "16点前抵京", "往返联动"],
            "selectedPlanIdx": 0,
            "plans": _default_travel_plans("return"),
            "detailBlocks": ["MAP_D3_CORRIDOR", "PLAN_DECK_SELECTOR", "ACTION_SLIDE_BAR"],
        },
    }


def default_user_state() -> dict[str, Any]:
    return {
        "dayModeOverride": "AUTO",  # AUTO | ACTIVE_TIMELINE | EMPTY_EXPLORE | REST_MODE
        "weatherMode": "sunny",     # sunny | rainy
        "exploreIndex": 0,
        "showcaseMode": False,
        "adoptedEvents": [],
        "cardOverrides": {},
        "dismissedIds": [],
        "simulatedJoke": False,
        "simulatedAlertCardId": None,
        "simulatedSmsTicket": False,
    }


def ensure_schema(conn) -> None:
    conn.executescript(HOME_SDUI_SCHEMA)
    if "source_chat_id" not in {r[1] for r in conn.execute("PRAGMA table_info(home_feedback)")}:
        conn.execute("ALTER TABLE home_feedback ADD COLUMN source_chat_id TEXT")


def load_user_state(conn, user_id: str) -> dict[str, Any]:
    row = conn.execute("SELECT body FROM home_sdui_state WHERE user_id=?", (user_id,)).fetchone()
    base = default_user_state()
    if row:
        try:
            saved = json.loads(row["body"])
            if isinstance(saved, dict):
                base.update(saved)
        except ValueError:
            pass
    return base


def save_user_state(conn, user_id: str, state: dict[str, Any]) -> None:
    conn.execute(
        "INSERT OR REPLACE INTO home_sdui_state VALUES (?,?,?)",
        (user_id, json.dumps(state, ensure_ascii=False), int(time.time())),
    )


# =============================================================================
# DETERMINISTIC DECISION TREE: 事件 = 类别 + 状态
# =============================================================================

_TRAVEL_RE = re.compile(r"机票|航班|飞|高铁|火车|动车|车票|出差|差旅|机场|航站楼|登机|值机|返程|火车站|MU\d+|CA\d+|G\d+")
_DINING_RE = re.compile(r"聚餐|聚会|晚宴|吃饭|餐厅|火锅|烧烤|包厢|订座|排号|约饭|生日|咖啡|茶室|饭局|干饭")
_SHOPPING_RE = re.compile(r"买|购物|商场|伴手礼|礼盒|香氛|领券|满减|比价|下单|现货|恒隆|万象|百货|免税|逛街")
_EXPLORE_RE = re.compile(r"逛逛|citywalk|散步|看展|美术馆|公园|落日|漫游|博物馆|书店|打卡|徒步", re.IGNORECASE)
_CONFIRMED_RE = re.compile(r"已出票|出票成功|航旅纵横|登机口|座位|已订座|包厢号|已预订|已确认|已领券|已预约|已购票|\d+车\s*\d+[A-F]|GATE|SEAT", re.IGNORECASE)
_ALERT_RE = re.compile(r"延误|暴雨|台风|停运|取消|闭店|歇业|雷雨|改签|预警")
_JOKE_RE = re.compile(r"巴黎.*咖啡|去火星|去月球|开玩笑|哈哈|飞.*喝咖啡|说走就走.*南极")
_RETURN_RE = re.compile(r"(周[一二三四五六日天]|明天|后天).*回|返程|往返")
_TIME_RE = re.compile(r"(?<!\d)([01]?\d|2[0-3]):([0-5]\d)(?!\d)")
_CN_TIME_RE = re.compile(r"(上午|下午|晚上|明早|今晚)?(\d{1,2})点(?:(\d{1,2})分|半)?")


def classify_category(text: str, subagent_id: str | None = None) -> tuple[str, str]:
    if subagent_id == "party":
        return "DINING", "dining"
    if _TRAVEL_RE.search(text) or _RETURN_LEG.search(text):
        return "TRAVEL", "travel"
    if _DINING_RE.search(text):
        return "DINING", "dining"
    if _SHOPPING_RE.search(text):
        return "SHOPPING", "shopping"
    if _EXPLORE_RE.search(text):
        return "EXPLORE", "explore"
    return "OFFICE", "office"


def extract_time_minutes(text: str, iso_time: str | None = None, default_minutes: int = 14 * 60) -> tuple[str, int]:
    if stamp := timestamp(iso_time):
        local = stamp.astimezone(ZONE)
        return local.strftime("%H:%M"), local.hour * 60 + local.minute
    m = _TIME_RE.search(text)
    if m:
        h, mi = int(m.group(1)), int(m.group(2))
        return f"{h:02d}:{mi:02d}", h * 60 + mi
    m2 = _CN_TIME_RE.search(text)
    if m2:
        period, h_str, min_str = m2.group(1), m2.group(2), m2.group(3)
        h = int(h_str)
        if period in ("下午", "晚上", "今晚") and h < 12:
            h += 12
        mi = 30 if "半" in m2.group(0) else (int(min_str) if min_str else 0)
        if 0 <= h <= 23 and 0 <= mi <= 59:
            return f"{h:02d}:{mi:02d}", h * 60 + mi
    return f"{default_minutes // 60:02d}:{default_minutes % 60:02d}", default_minutes


def _short_title(text: str, category: str) -> str:
    first_line = next((line.strip() for line in text.splitlines() if line.strip()), text.strip())
    cleaned = re.sub(r"^\{.*\}$", "", first_line).strip()
    if not cleaned:
        return {"TRAVEL": "差旅出行", "DINING": "聚餐安排", "SHOPPING": "购物清单", "EXPLORE": "城市漫游"}.get(category, "待办日程")
    return cleaned if len(cleaned) <= 22 else cleaned[:21] + "…"


_SERVICE_RE = re.compile(r"(?<![A-Z0-9])(?:[A-Z]{2}|[GDC])\d{2,5}(?![A-Z0-9])", re.I)
_TICKET_RE = re.compile(r"已出票|出票成功|已购票")
_SCHEDULE_RE = re.compile(r"会议|开会|晨会|面试|评审|上课|讨论|提醒|预约|截止|待办")
_RETURN_LEG = re.compile(r"((?:(?:下|本|这)?周[一二三四五六日天]|明天|后天)(?:\s*\d{1,2}:\d{2})?)\s*(?:回|返)")


def _build_card_from_event(event: dict[str, Any]) -> dict[str, Any] | None:
    text = event["payload"]["text"].strip()
    source = event.get("source_kind")
    at = event.get("occurred_at")
    category, theme = classify_category(text)
    if source == "location" or (category == "OFFICE" and not at and
            event.get("event_type") != "schedule" and not (_SCHEDULE_RE.search(text) or _TIME_RE.search(text) or _CN_TIME_RE.search(text))):
        return None
    captured = event.get("captured_at")
    end = event.get("ended_at")
    if end is None and source == "calendar":
        end = calendar_range_end(at, text)
    time_str, minutes = extract_time_minutes(text, at)
    all_day = bool(event.get("all_day"))
    if all_day:
        time_str, minutes = "全天", 0
    elif not (at or _TIME_RE.search(text) or _CN_TIME_RE.search(text)):
        time_str, minutes = "待定", 24 * 60
    source_label = {"calendar": "系统日历", "screenshot_ocr": "截图识别"}.get(source, "手动记录")
    joke = bool(_JOKE_RE.search(text))
    confirmed = not joke and (bool(_CONFIRMED_RE.search(text)) or source == "calendar" or (event.get("event_type") == "schedule" and at is not None))
    return {
        "id": f"evt_{event['event_id']}", "sourceEventId": event["event_id"],
        "sourceEventRevision": event["revision"],
        "category": category, "themeKey": theme, "isMisjudgment": joke,
        "lifecycleStage": "CONFIRMED" if confirmed else "PROPOSAL",
        "badge": "已出票" if confirmed and _TICKET_RE.search(text) else "已确认" if confirmed else "待确认",
        "time": time_str, "_sortMin": minutes, "scheduledAt": at, "endsAt": end,
        "allDay": all_day, "capturedAt": captured,
        "title": _short_title(text, category), "originalText": text, "subtitle": source_label,
        "entities": [source_label], "detailBlocks": ["SOURCE_DETAIL"],
        "hasAlert": bool(_ALERT_RE.search(text)),
        "reminderStrip": {"text": "出发前核对证件与车次", "dismissed": False} if category == "TRAVEL" else None,
    }


def _return_leg(card: dict[str, Any]) -> dict[str, Any] | None:
    match = _RETURN_LEG.search(card["originalText"])
    if card["category"] != "TRAVEL" or not match:
        return None
    return {**card, "id": card["id"] + "_return", "direction": "return",
            "title": "返程安排", "time": match[1], "relativeDayHint": match[1],
            "scheduledAt": None, "endsAt": None, "allDay": False, "ticketSourceEventId": None,
            "lifecycleStage": "PROPOSAL", "badge": "待确认", "detailBlocks": ["SOURCE_DETAIL"]}


def _link_ticket_receipts(cards):
    itineraries = [c for c in cards if c["category"] == "TRAVEL"
                   and c["badge"] != "已出票" and not c["isMisjudgment"]]
    linked = set()
    today = datetime.now(ZONE).date()
    receipts = [c for c in cards if c["category"] == "TRAVEL" and c["badge"] == "已出票"]
    for receipt in receipts:
        services = {number.upper() for number in _SERVICE_RE.findall(receipt["originalText"])}
        captured = timestamp(receipt.get("capturedAt"))
        if not services or captured is None:
            continue
        receipt_day = captured.astimezone(ZONE).date()
        candidates = [c for c in itineraries if (stamp := timestamp(c.get("capturedAt")))
                      and stamp.astimezone(ZONE).date() == receipt_day
                      and services.intersection(n.upper() for n in _SERVICE_RE.findall(c["originalText"]))]
        if receipt.get("scheduledAt"):
            candidates = [c for c in candidates if event_day(c, today) == event_day(receipt, today)]
        if len(candidates) == 1:
            target = candidates[0]
            target.update(lifecycleStage="CONFIRMED", badge="已出票")
            target.setdefault("ticketSourceEventId", receipt["sourceEventId"])
            linked.add(receipt["id"])
    return [c for c in cards if c["id"] not in linked]


def _collect_agent_cards(agent_hub, user_id: str) -> tuple[list[dict[str, Any]], list[dict[str, Any]], set[tuple[str, int]]]:
    """Read confirmed and waiting_input cards from user's isolated Cloud Agent store if initialized."""
    active_cards: list[dict[str, Any]] = []
    archived_cards: list[dict[str, Any]] = []
    party_sources: set[tuple[str, int]] = set()
    if agent_hub is None or user_id not in getattr(agent_hub, "entries", {}):
        return active_cards, archived_cards, party_sources
    store = agent_hub.entries[user_id].app.state.store
    try:
        works = store.list_works()
    except Exception:
        return active_cards, archived_cards, party_sources

    for work in works:
        wid = work["id"]
        subagent_id = work.get("subagent_id", "information")
        state = work.get("state") or {}
        version = work.get("memory_version", 0)

        # Schedule items from confirmed state
        items = state.get("items")
        if isinstance(items, dict):
            for key, item in items.items():
                title = item.get("title") or work["title"]
                at_iso = item.get("at")
                time_str, sort_min = extract_time_minutes(title, at_iso, default_minutes=15 * 60)
                if item.get("status") == "completed":
                    archived_cards.append({
                        "id": f"work_{wid}_item_{key}",
                        "sourceWorkId": wid,
                        "time": time_str,
                        "scheduledAt": at_iso,
                        "endTime": "",
                        "title": title,
                        "lifecycleStage": "ARCHIVED",
                        "summary": f"来自云端事项「{work['title']}」· 已标记完成并归档。",
                    })
                else:
                    cat, theme_key = classify_category(title, subagent_id)
                    active_cards.append({
                        "id": f"work_{wid}_item_{key}",
                        "sourceWorkId": wid,
                        "scheduledAt": at_iso,
                        "category": cat,
                        "themeKey": theme_key,
                        "lifecycleStage": "CONFIRMED",
                        "time": time_str,
                        "_sortMin": sort_min,
                        "title": title,
                        "subtitle": f"云端事项「{work['title']}」· v{version} 已确认",
                        "badge": "已确认",
                        "entities": ["云端日程", f"v{version}", time_str],
                        "detailBlocks": ["SUMMARY_CARD", "MEETING_CHECKLIST"],
                        "notes": [
                            {"text": f"云端事项：{work['title']}", "done": True},
                            {"text": f"安排时间：{at_iso or time_str}", "done": False},
                        ],
                        "reminderStrip": {
                            "text": f"已同步云端事项 v{version} · 可前往云端事项导出日历",
                            "dismissed": False,
                        },
                        "hasAlert": False,
                    })

        # Party plan from confirmed state
        plan = state.get("plan")
        if isinstance(plan, dict):
            from screenmind.agents.party import party_start

            participants = plan.get("participants") or []
            attending = [p for p in participants if p.get("status") == "attending"]
            declined = [p for p in participants if p.get("status") == "declined"]
            screens = {ref.partition(":")[0] for ref in state.get("processed_sources", [])}
            if screens:
                rows = store.db.execute(
                    "SELECT event_id, revision, screen_id FROM mobile_evidence WHERE work_id=?",
                    (wid,),
                )
                party_sources.update((row["event_id"], row["revision"]) for row in rows if row["screen_id"] in screens)
            start = timestamp(state.get("scheduled_at")) or party_start(plan.get("time"))
            at_iso = start.isoformat() if start else None
            time_str, sort_min = extract_time_minutes("", at_iso) if at_iso else ("日期待确认", 24 * 60)
            party_title = re.sub(r"\s*[（(](?:标题|名称)?待确认[）)]\s*$", "", plan.get("title") or "").strip()
            if not party_title or party_title in ("待确认", "标题待确认", "名称待确认"):
                party_title = work["title"]
            active_cards.append({
                "id": f"work_{wid}_party",
                "sourceWorkId": wid,
                "category": "DINING",
                "themeKey": "dining",
                "lifecycleStage": "CONFIRMED",
                "time": time_str,
                "_sortMin": sort_min,
                "title": f"{party_title}（{len(attending)}人）",
                "subtitle": f"{plan.get('location', '待确认地点')} · 云端聚会 v{version}",
                "badge": "已确认",
                "entities": [f"确定 {len(attending)} 人", plan.get("location", "待确认"), f"v{version}"],
                "selectedPlanIdx": 0,
                "scheduledAt": at_iso,
                "detailBlocks": ["SOURCE_DETAIL"],
                "reminderStrip": {
                    "text": f"{len(declined)} 人退出 · 请核对包厢与人均费用" if declined else f"已同步 {len(attending)} 位参与者",
                    "dismissed": False,
                },
                "hasAlert": bool(declined),
            })

        # Pending waiting_input task proposal -> PROPOSAL card
        try:
            pending_rows = store.db.execute(
                "SELECT id FROM tasks WHERE owner=? AND work_id=? AND status='waiting_input' ORDER BY updated_at DESC LIMIT 1",
                (store.owner, wid),
            ).fetchall()
        except Exception:
            pending_rows = []
        for row in pending_rows:
            task = store.task(row["id"])
            card_data = task.get("card") or {}
            cat, theme_key = classify_category(work["title"] + " " + card_data.get("summary", ""), subagent_id)
            active_cards.append({
                "id": f"task_{task['id']}",
                "sourceWorkId": wid,
                "sourceTaskId": task["id"],
                "category": cat,
                "themeKey": theme_key,
                "lifecycleStage": "PROPOSAL",
                "time": "待确认",
                "_sortMin": 16 * 60,
                "title": card_data.get("title") or work["title"],
                "subtitle": card_data.get("summary") or "云端候选方案等待确认",
                "badge": "待拍板",
                "entities": ["云端候选", subagent_id, "需确认"],
                "selectedPlanIdx": 0,
                "detailBlocks": ["SOURCE_DETAIL"],
                "notes": [{"text": f"{f.get('label')}: {f.get('value')}", "done": False} for f in card_data.get("fields", [])[:4]],
                "hasAlert": False,
            })

    return active_cards, archived_cards, party_sources


def _compute_connectors(events: list[dict[str, Any]], day, alert_card_id: str | None) -> None:
    """Apply the 4 dynamic connector semantics between adjacent timeline nodes."""
    for idx, ev in enumerate(events):
        if idx == len(events) - 1:
            ev["connectorToNext"] = None
            continue
        # Respect explicit override already placed on the card
        if ev.get("_connectorLocked"):
            continue
        nxt = events[idx + 1]
        if ev.get("hasAlert") or (alert_card_id and ev["id"] == alert_card_id):
            ev["connectorToNext"] = {
                "connectorState": "ALERT_PULSE", "targetEventId": nxt["id"],
                "label": f"{ev['title'][:12]} 监测到异常变动 · 点击查看 B 计划",
            }
            continue
        cur_start, cur_end = event_bounds(ev, day)
        nxt_start, _ = event_bounds(nxt, day)
        if cur_start and nxt_start and not (ev.get("allDay") or nxt.get("allDay")):
            gap = int((nxt_start - (cur_end or cur_start)).total_seconds() // 60)
            if gap < 50:
                ev["connectorToNext"] = {
                    "connectorState": "CONFLICT_DASHED",
                    "label": f"重叠 {abs(gap)} 分钟" if gap < 0 else f"间隔 {gap} 分钟 · 衔接紧张",
                    "actionLabel": "一键顺延 30m",
                }
            elif gap > 180:
                hours = round(gap / 60.0, 1)
                ev["connectorToNext"] = {
                    "connectorState": "GAP_SUGGESTION",
                    "gapHours": f"{hours}h",
                    "gapMinutes": gap,
                    "label": f"空档 {hours}h · 附近补白 ✦",
                    "gapSpots": copy.deepcopy(DEFAULT_GAP_SPOTS),
                }
            else:
                ev["connectorToNext"] = {
                    "connectorState": "NORMAL_SOLID",
                    "label": "行程充裕",
                }
        else:
            ev["connectorToNext"] = {
                "connectorState": "NORMAL_SOLID",
                "label": "行程充裕",
            }
        ev["connectorToNext"]["targetEventId"] = nxt["id"]


def _apply_overrides(card: dict[str, Any], overrides: dict[str, Any]) -> None:
    ov = overrides.get(card["id"])
    if not isinstance(ov, dict):
        return
    for key in ("lifecycleStage", "completedAt", "selectedPlanIdx", "time", "scheduledAt", "endsAt",
                "endTime", "scheduleDate", "title", "subtitle", "badge"):
        if key in ov:
            card[key] = ov[key]
    if card.get("ticketSourceEventId") and card.get("lifecycleStage") != "ARCHIVED":
        card.update(lifecycleStage="CONFIRMED", badge="已出票")
    if "reminderDismissed" in ov and isinstance(card.get("reminderStrip"), dict):
        card["reminderStrip"]["dismissed"] = bool(ov["reminderDismissed"])
    if "connectorToNext" in ov:
        card["connectorToNext"] = ov["connectorToNext"]
        card["_connectorLocked"] = True
    if "checklistDone" in ov and isinstance(card.get("checklist"), list):
        done_map = ov["checklistDone"]
        for i, item in enumerate(card["checklist"]):
            if str(i) in done_map:
                item["done"] = bool(done_map[str(i)])
        done_cnt = sum(1 for c in card["checklist"] if c.get("done"))
        card["badge"] = f"{done_cnt}/{len(card['checklist'])}"
    if "notesDone" in ov and isinstance(card.get("notes"), list):
        done_map = ov["notesDone"]
        for i, item in enumerate(card["notes"]):
            if str(i) in done_map:
                item["done"] = bool(done_map[str(i)])
    if ov.get("alertResolved"):
        card["hasAlert"] = False


def build_home_sdui(conn, agent_hub, user_id: str, *, live: bool = False) -> dict[str, Any]:
    """Generate the complete AuraFlow SDUI v2.4.0 home payload for the authenticated user."""
    user_state = load_user_state(conn, user_id)
    if live and user_state["showcaseMode"]:
        user_state = user_state.get("liveState") or default_user_state()
    dismissed = set(user_state.get("dismissedIds") or [])
    overrides = user_state.get("cardOverrides") or {}
    weather_mode = user_state.get("weatherMode", "sunny")
    if weather_mode not in ("sunny", "rainy"):
        weather_mode = "sunny"
    weather_ctx = (
        {"mode": "sunny", "label": "晴朗微风 21°C", "city": "上海"}
        if weather_mode == "sunny"
        else {"mode": "rainy", "label": "细雨微凉 15°C", "city": "上海"}
    )

    # Read real user events & decisions
    event_rows = conn.execute(
        "SELECT body FROM events WHERE user_id=? ORDER BY rowid DESC", (user_id,)
    ).fetchall()
    raw_events = [json.loads(r["body"]) for r in event_rows]

    # Collect Agent works & tasks
    agent_active, agent_archived, party_sources = _collect_agent_cards(agent_hub, user_id)

    showcase_mode = bool(user_state.get("showcaseMode"))
    archived_events: list[dict[str, Any]] = []
    timeline_events: list[dict[str, Any]] = []
    cross_day_relay: dict[str, Any] | None = None

    if showcase_mode:
        tpl = showcase_template()
        archived_events = tpl["archivedEvents"]
        timeline_events = tpl["events"]
        cross_day_relay = tpl["crossDayRelay"]
        # Mark connectors from showcase template as locked unless overridden
        for ev in timeline_events:
            ev["_connectorLocked"] = True
        if user_state.get("simulatedSmsTicket"):
            for ev in timeline_events:
                if ev["id"] == "evt_travel_outbound":
                    p = ev["plans"][0]
                    ev.update(lifecycleStage="CONFIRMED", time=p["dep"], endTime=p["arr"])
                    ev["title"] = f"北京 → 上海 · {p['carrier'].split(' ')[1]}"
                    ev["subtitle"] = f"{p['depHub']} · {p['seat']}"
                    ev["badge"] = "已出票"
        if user_state.get("simulatedAlertCardId"):
            for ev in timeline_events:
                if ev["id"] == user_state["simulatedAlertCardId"]:
                    ev["connectorToNext"] = {
                        "connectorState": "ALERT_PULSE",
                        "label": "MU5102 预计延误 75m · 点击查看 B 计划",
                    }
    else:
        archived_events.extend(agent_archived)
        timeline_events.extend(agent_active)
        clue_cards = []
        for event in raw_events:
            card = _build_card_from_event(event)
            if card is None:
                continue
            if (card["category"] == "DINING" and not event.get("occurred_at")
                    and (event["event_id"], event["revision"]) in party_sources):
                continue
            clue_cards.append(card)
        for card in _link_ticket_receipts(clue_cards):
            timeline_events.append(card)
            if cross_day_relay is None and card["id"] not in dismissed:
                cross_day_relay = _return_leg(card)

    # Merge user-adopted explore / gap-filled cards
    for adopted in user_state.get("adoptedEvents") or []:
        if adopted.get("id") not in dismissed:
            card = copy.deepcopy(adopted)
            if card["id"].startswith("evt_gap_"):
                minutes = card.get("durationMinutes") or gap_duration_minutes(card.get("subtitle"))
                card["durationMinutes"] = minutes
                if isinstance(card.get("exploreMeta"), dict):
                    card["exploreMeta"]["duration"] = duration_label(minutes)
                if minutes is not None and isinstance(card.get("subtitle"), str):
                    card["subtitle"] = gap_subtitle(card["subtitle"], minutes)
            timeline_events.append(card)

    # Optional simulated joke misjudgment card
    if user_state.get("simulatedJoke") and "evt_joke_paris" not in dismissed:
        timeline_events.insert(0, {
            "id": "evt_joke_paris",
            "category": "TRAVEL",
            "themeKey": "explore",
            "lifecycleStage": "PROPOSAL",
            "isMisjudgment": True,
            "confidence": 0.42,
            "time": "明日 09:00",
            "_sortMin": 9 * 60,
            "title": "北京 → 巴黎 · 喝咖啡（疑似玩笑）",
            "subtitle": "← 左滑雾化移除 · 纠正 AI 闲聊语境",
            "badge": "待拍板",
            "connectorToNext": {"connectorState": "NORMAL_SOLID", "label": "待确认跨国意图"},
            "entities": ["群聊捕获", "置信度 42%", "支持左滑雾化"],
            "originalText": "走，明天飞巴黎喝咖啡！",
            "detailBlocks": ["SUMMARY_CARD"],
            "_connectorLocked": True,
        })

    # Filter out dismissed cards & apply overrides
    timeline_events = [e for e in timeline_events if e["id"] not in dismissed]
    for ev in timeline_events:
        _apply_overrides(ev, overrides)
    if cross_day_relay and cross_day_relay["id"] in dismissed:
        cross_day_relay = None
    if cross_day_relay is not None:
        _apply_overrides(cross_day_relay, overrides)

    if showcase_mode:
        completed = [card for card in timeline_events if card["lifecycleStage"] == "ARCHIVED"]
        archived_events.extend({**card, "summary": "已标记完成"} for card in completed)
        timeline_events = [card for card in timeline_events if card["lifecycleStage"] != "ARCHIVED"]
        if user_state.get("adoptedEvents"):
            timeline_events.sort(key=lambda card: extract_time_minutes(card["time"], default_minutes=1440)[1])
    else:
        timeline_events.sort(key=lambda x: extract_time_minutes(x["time"], default_minutes=1440)[1])
        by_day = {}
        today = datetime.now(ZONE).date()
        for card in timeline_events:
            if card["lifecycleStage"] != "ARCHIVED":
                by_day.setdefault(event_day(card, today), []).append(card)
        for day, cards in by_day.items():
            _compute_connectors(cards, day, user_state.get("simulatedAlertCardId"))

    # On-device day grouping uses the viewer’s time zone. Live gaps have no venue provider.
    for ev in timeline_events:
        if live and ev.get("connectorToNext"):
            ev["connectorToNext"].pop("gapSpots", None)
        ev.pop("_sortMin", None)
        ev.pop("_connectorLocked", None)
        ev.pop("hasAlert", None)

    # Macro Day Mode Dispatcher
    day_mode_override = user_state.get("dayModeOverride", "AUTO")
    if day_mode_override in ("ACTIVE_TIMELINE", "EMPTY_EXPLORE", "REST_MODE"):
        day_mode = day_mode_override
        if day_mode == "ACTIVE_TIMELINE" and not timeline_events:
            # Auto-show empty explore unless user has cards
            day_mode = "EMPTY_EXPLORE"
    else:
        day_mode = "ACTIVE_TIMELINE" if timeline_events else "EMPTY_EXPLORE"

    explore_list = EXPLORE_PRESETS[weather_mode]
    explore_idx = int(user_state.get("exploreIndex", 0)) % len(explore_list)

    # Active theme derives from macro mode or top focus card
    if day_mode == "EMPTY_EXPLORE":
        active_theme = "explore"
    elif day_mode == "REST_MODE":
        active_theme = "rest"
    else:
        focus = next((e for e in timeline_events if e["id"] == "evt_travel_outbound"), timeline_events[0] if timeline_events else None)
        active_theme = focus["themeKey"] if focus else "travel"

    # Negative feedback count
    fb_row = conn.execute("SELECT COUNT(*) AS cnt FROM home_feedback WHERE user_id=?", (user_id,)).fetchone()
    feedback_count = int(fb_row["cnt"]) if fb_row else 0

    return {
        "protocolVersion": PROTOCOL_VERSION,
        "dismissedIds": sorted(dismissed),
        "dayMode": day_mode,
        "dayModeOverride": day_mode_override,
        "showcaseMode": showcase_mode,
        "activeTheme": active_theme,
        "weatherContext": {"mode": "unknown", "label": "", "city": ""} if live else weather_ctx,
        "exploreIndex": explore_idx,
        "explorePresets": {} if live else EXPLORE_PRESETS,
        "diningFriends": [] if live else DINING_FRIENDS,
        "archivedEvents": archived_events,
        "events": timeline_events,
        "crossDayRelay": cross_day_relay,
        "stats": {
            "totalActive": len(timeline_events),
            "proposalCount": sum(1 for e in timeline_events if e.get("lifecycleStage") == "PROPOSAL"),
            "archivedCount": len(archived_events),
            "negativeFeedbackCount": feedback_count,
            "realEventCount": len(raw_events),
        },
        "dailyFootprint": (
            f"今日已沉降 {len(archived_events)} 项日程 · 已记录 {len(raw_events)} 条线索"
            if archived_events
            else None
        ),
    }


# =============================================================================
# PYDANTIC ACTION INPUT & FASTAPI ROUTES
# =============================================================================

class HomeSduiAction(BaseModel):
    model_config = ConfigDict(extra="forbid")
    action: Literal[
        "set_day_mode",
        "set_weather",
        "cycle_explore",
        "adopt_explore",
        "select_plan",
        "confirm_card",
        "complete_card",
        "dismiss_reminder",
        "resolve_conflict",
        "insert_gap_spot",
        "apply_plan_b",
        "toggle_checklist",
        "toggle_note",
        "dismiss_misjudgment",
        "simulate_scenario",
    ]
    day_mode: Literal["AUTO", "ACTIVE_TIMELINE", "EMPTY_EXPLORE", "REST_MODE"] | None = None
    weather: Literal["sunny", "rainy"] | None = None
    card_id: str | None = Field(default=None, max_length=120)
    plan_idx: int | None = Field(default=None, ge=0, le=10)
    item_idx: int | None = Field(default=None, ge=0, le=50)
    shift_minutes: int | None = Field(default=30, ge=5, le=240)
    time: str | None = Field(default=None, max_length=32)
    duration_minutes: int | None = Field(default=None, strict=True, gt=0, le=1440)
    title: str | None = Field(default=None, max_length=120)
    subtitle: str | None = Field(default=None, max_length=200)
    choice: str | None = Field(default=None, max_length=64)
    reason: str | None = Field(default="", max_length=300)
    scenario: Literal[
        "load_showcase",
        "exit_showcase",
        "sms_ticket",
        "emergency_alert",
        "joke_misjudgment",
        "reset_flight_proposal",
        "reset_showcase",
    ] | None = None


def apply_home_action(conn, agent_hub, user_id: str, req: HomeSduiAction, *, live: bool = False) -> dict[str, Any]:
    state = load_user_state(conn, user_id)
    showcase = state if live and state["showcaseMode"] else None
    if showcase is not None:
        state = showcase.get("liveState") or default_user_state()
    overrides = state.setdefault("cardOverrides", {})
    act = req.action
    day = datetime.now(ZONE).date()
    date_key = day.isoformat()
    daily = state.get("days", {}).get(date_key, {})
    if act in ("set_day_mode", "adopt_explore"):
        daily.pop("mode", None)
    if act in ("set_weather", "cycle_explore"):
        state["exploreIndex"] = daily.pop("exploreIndex", state.get("exploreIndex", 0))
    if live and act not in ("confirm_card", "complete_card", "dismiss_reminder", "dismiss_misjudgment"):
        raise HTTPException(422, "此操作仅适用于示例场景")

    if act in ("confirm_card", "dismiss_misjudgment", "dismiss_reminder", "complete_card"):
        if act == "dismiss_misjudgment" and req.card_id in state["dismissedIds"]:
            return build_home_sdui(conn, agent_hub, user_id, live=live)
        doc = build_home_sdui(conn, agent_hub, user_id, live=live)
        cards = doc["events"] + doc["archivedEvents"] + ([doc["crossDayRelay"]] if doc["crossDayRelay"] else [])
        target = next((card for card in cards if card["id"] == req.card_id), None)
        if target is None:
            raise HTTPException(404, "首页事项已变化，请刷新")
        if act in ("confirm_card", "complete_card") and target.get("sourceWorkId"):
            raise HTTPException(409, "请在关联的云端事项中处理")
        if act == "complete_card" and target["lifecycleStage"] == "ARCHIVED":
            return doc
        if act == "confirm_card" and target["lifecycleStage"] == "ARCHIVED":
            raise HTTPException(409, "已归档事项不能重新确认")
        if act == "dismiss_misjudgment" and target["lifecycleStage"] != "PROPOSAL":
            raise HTTPException(409, "仅可移除待确认提案")
    if act == "complete_card":
        completion = overrides.setdefault(req.card_id, {})
        completion.setdefault("completedAt", datetime.now(timezone.utc).isoformat())
        completion.update(lifecycleStage="ARCHIVED", badge="已完成")
    elif act == "set_day_mode":
        if not req.day_mode:
            raise HTTPException(422, "缺少 day_mode")
        state["dayModeOverride"] = req.day_mode

    elif act == "set_weather":
        if not req.weather:
            raise HTTPException(422, "缺少 weather")
        state["weatherMode"] = req.weather
        state["exploreIndex"] = 0

    elif act == "cycle_explore":
        w = state.get("weatherMode", "sunny")
        state["exploreIndex"] = (int(state.get("exploreIndex", 0)) + 1) % len(EXPLORE_PRESETS[w])

    elif act == "adopt_explore":
        w = state.get("weatherMode", "sunny")
        preset = EXPLORE_PRESETS[w][int(state.get("exploreIndex", 0)) % len(EXPLORE_PRESETS[w])]
        now_ms = int(time.time() * 1000)
        adopted = []
        for idx, stop in enumerate(preset["stops"]):
            adopted.append({
                "id": f"evt_explore_{now_ms}_{idx}",
                "category": "EXPLORE",
                "themeKey": "explore",
                "lifecycleStage": "CONFIRMED",
                "time": stop["time"],
                "_sortMin": extract_time_minutes(stop["time"])[1],
                "title": stop["name"],
                "subtitle": f"{stop['dist']} · {stop['note']}",
                "badge": "漫游已定",
                "connectorToNext": (
                    {"connectorState": "NORMAL_SOLID", "label": "漫步衔接"}
                    if idx < len(preset["stops"]) - 1
                    else None
                ),
                "_connectorLocked": True,
                "entities": [preset["tag"], stop["dist"], "晴朗微风 21°C" if w == "sunny" else "细雨微凉 15°C"],
                "detailBlocks": ["EXPLORE_ROUTE_CARD"],
                "exploreMeta": {
                    "tripTitle": preset["title"],
                    "duration": preset["duration"],
                    "note": stop["note"],
                    "stops": preset["stops"],
                    "currentStopIdx": idx,
                },
            })
        state["adoptedEvents"] = adopted
        state["dayModeOverride"] = "ACTIVE_TIMELINE"

    elif act == "select_plan":
        if not req.card_id or req.plan_idx is None:
            raise HTTPException(422, "缺少 card_id 或 plan_idx")
        ov = overrides.setdefault(req.card_id, {})
        ov["selectedPlanIdx"] = req.plan_idx

    elif act == "confirm_card":
        if not req.card_id:
            raise HTTPException(422, "缺少 card_id")
        ov = overrides.setdefault(req.card_id, {})
        ov["lifecycleStage"] = "CONFIRMED"
        if target:
            cat = target.get("category")
            plans = target.get("plans") or []
            p_idx = int(ov.get("selectedPlanIdx", target.get("selectedPlanIdx", 0)))
            if cat == "TRAVEL" and plans and 0 <= p_idx < len(plans):
                p = plans[p_idx]
                direction = target.get("direction", "outbound")
                ov.update(time=p["dep"], endTime=p["arr"], endsAt=None, scheduledAt=None,
                          scheduleDate=event_day(target, day).isoformat())
                ov["title"] = f"{'上海 → 北京' if direction == 'return' else '北京 → 上海'} · {p['carrier'].split(' ')[-1]}"
                ov["subtitle"] = f"{p['depHub']} · {p['seat']}"
                ov["badge"] = "已出票"
            elif cat == "DINING" and plans and 0 <= p_idx < len(plans):
                r = plans[p_idx]
                parts = [s.strip() for s in r["name"].split("·")]
                ov["title"] = f"{parts[1] if len(parts) > 1 else '外滩'} · {parts[0]}（4人）"
                ov["subtitle"] = f"{r['room']} · 已同步群聊"
                ov["badge"] = "已订座"
            elif cat == "SHOPPING" and state["showcaseMode"]:
                ov["subtitle"] = "已领 2 张满减券 · 3件现货清单"
            else:
                ov["badge"] = "已确认"

    elif act == "dismiss_reminder":
        if not req.card_id:
            raise HTTPException(422, "缺少 card_id")
        ov = overrides.setdefault(req.card_id, {})
        ov["reminderDismissed"] = True

    elif act == "resolve_conflict":
        if not req.card_id:
            raise HTTPException(422, "缺少 card_id")
        current_doc = build_home_sdui(conn, agent_hub, user_id, live=live)
        events = current_doc["events"]
        idx = next((i for i, e in enumerate(events) if e["id"] == req.card_id), None)
        ov = overrides.setdefault(req.card_id, {})
        ov["connectorToNext"] = {"connectorState": "NORMAL_SOLID", "label": "已顺延 · 预留 25m 缓冲"}
        if idx is not None and idx + 1 < len(events):
            nxt = events[idx + 1]
            nxt_ov = overrides.setdefault(nxt["id"], {})
            shifted = shift_schedule(nxt, event_day(nxt, day), req.shift_minutes or 30)
            if not shifted:
                raise HTTPException(409, "事项没有可顺延的开始时间")
            nxt_ov.update(shifted)

    elif act == "insert_gap_spot":
        if not req.card_id or not req.title:
            raise HTTPException(422, "缺少 card_id 或 title")
        ov = overrides.setdefault(req.card_id, {})
        ov["connectorToNext"] = {"connectorState": "NORMAL_SOLID", "label": "顺路衔接"}
        t_str = req.time or "12:00"
        if clock(t_str) is None:
            raise HTTPException(422, "空档安排需提供有效的开始时间")
        suggestion = next((spot for spot in DEFAULT_GAP_SPOTS if spot["title"] == req.title and spot["time"] == t_str), {})
        minutes = req.duration_minutes or gap_duration_minutes(req.subtitle) or suggestion.get("durationMinutes")
        subtitle = gap_subtitle(req.subtitle or "顺路轻去处", minutes)
        new_card = {
            "id": f"evt_gap_{uuid4().hex}",
            "category": "EXPLORE",
            "themeKey": "explore",
            "lifecycleStage": "CONFIRMED",
            "time": t_str,
            "scheduleDate": date_key,
            "durationMinutes": minutes,
            "_sortMin": extract_time_minutes(t_str)[1],
            "title": req.title,
            "subtitle": subtitle,
            "badge": "补白已定",
            "connectorToNext": {"connectorState": "NORMAL_SOLID", "label": "准时衔接下一程"},
            "_connectorLocked": True,
            "entities": ["线间补白", t_str, "顺路推荐"],
            "detailBlocks": ["EXPLORE_ROUTE_CARD"],
            "exploreMeta": {
                "tripTitle": req.title,
                "duration": duration_label(minutes),
                "note": subtitle,
                "stops": [{"time": t_str, "name": req.title, "dist": "当前附近", "note": subtitle}],
                "currentStopIdx": 0,
            },
        }
        adopted = list(state.get("adoptedEvents") or [])
        adopted.append(new_card)
        state["adoptedEvents"] = adopted

    elif act == "apply_plan_b":
        card_id = req.card_id or "evt_travel_outbound"
        ov = overrides.setdefault(card_id, {})
        ov["alertResolved"] = True
        state["simulatedAlertCardId"] = None
        if req.choice == "switch_hsr":
            ov["selectedPlanIdx"] = 1
            ov["lifecycleStage"] = "CONFIRMED"
            backup = _default_travel_plans()[1]
            ov.update(time=backup["dep"], endTime=backup["arr"], endsAt=None, scheduledAt=None, scheduleDate=date_key)
            ov["title"] = "北京 → 上海 · 复兴号 G14"
            ov["subtitle"] = "北京南站 · 05车 08F · 准点 99%"
            ov["badge"] = "已改签"
            ov["connectorToNext"] = {"connectorState": "NORMAL_SOLID", "label": "高铁准点抵沪 · 赴宴充裕"}
        else:
            ov["connectorToNext"] = {"connectorState": "NORMAL_SOLID", "label": "B 计划已生效 · 晚宴延至 19:30"}
            din_ov = overrides.setdefault("evt_dining_bund", {})
            din_ov.update(time="19:30", endsAt=None, scheduledAt=None, scheduleDate=date_key)
            din_ov["subtitle"] = "已自动顺延 · 包厢已留座至 19:45"

    elif act == "toggle_checklist":
        if not req.card_id or req.item_idx is None:
            raise HTTPException(422, "缺少 card_id 或 item_idx")
        ov = overrides.setdefault(req.card_id, {})
        done_map = ov.setdefault("checklistDone", {})
        key = str(req.item_idx)
        done_map[key] = not bool(done_map.get(key, False))

    elif act == "toggle_note":
        if not req.card_id or req.item_idx is None:
            raise HTTPException(422, "缺少 card_id 或 item_idx")
        ov = overrides.setdefault(req.card_id, {})
        done_map = ov.setdefault("notesDone", {})
        key = str(req.item_idx)
        default_val = True if (req.card_id == "evt_morning_prep" and req.item_idx == 0) else False
        done_map[key] = not bool(done_map.get(key, default_val))

    elif act == "dismiss_misjudgment":
        if not req.card_id:
            raise HTTPException(422, "缺少 card_id")
        dismissed = list(state.get("dismissedIds") or [])
        if req.card_id not in dismissed:
            dismissed.append(req.card_id)
        state["dismissedIds"] = dismissed
        if req.card_id == "evt_joke_paris":
            state["simulatedJoke"] = False
        if not state["showcaseMode"]:
            conn.execute(
                "INSERT INTO home_feedback(id,user_id,card_id,source_event_id,action,reason,original_text,created_at) VALUES (?,?,?,?,?,?,?,?)",
                (str(uuid4()), user_id, req.card_id, target.get("sourceEventId"), act,
                 req.reason or "not_my_schedule", "", int(time.time())),
            )

    elif act == "simulate_scenario":
        sc = req.scenario
        if sc == "exit_showcase":
            state = state.get("liveState", state if not state.get("showcaseMode") else default_user_state())
        elif sc in ("load_showcase", "reset_showcase", "sms_ticket", "emergency_alert", "joke_misjudgment", "reset_flight_proposal"):
            saved_live = state.get("liveState") if state.get("showcaseMode") else state
            if not state.get("showcaseMode") or sc in ("load_showcase", "reset_showcase"):
                state = default_user_state()
                state.update(showcaseMode=True, dayModeOverride="ACTIVE_TIMELINE", liveState=saved_live or default_user_state())
                overrides = state["cardOverrides"]
        if sc == "sms_ticket":
            state["showcaseMode"] = True
            state["dayModeOverride"] = "ACTIVE_TIMELINE"
            state["simulatedSmsTicket"] = True
            overrides.pop("evt_travel_outbound", None)
        elif sc == "emergency_alert":
            state["showcaseMode"] = True
            state["dayModeOverride"] = "ACTIVE_TIMELINE"
            state["simulatedAlertCardId"] = "evt_travel_outbound"
            overrides.get("evt_travel_outbound", {}).pop("connectorToNext", None)
        elif sc == "joke_misjudgment":
            state["dayModeOverride"] = "ACTIVE_TIMELINE"
            state["simulatedJoke"] = True
            state["dismissedIds"] = [i for i in state.get("dismissedIds", []) if i != "evt_joke_paris"]
        elif sc == "reset_flight_proposal":
            state["simulatedSmsTicket"] = False
            overrides.pop("evt_travel_outbound", None)
    if showcase is not None:
        showcase["liveState"] = state
    save_user_state(conn, user_id, showcase or state)
    return build_home_sdui(conn, agent_hub, user_id, live=live)


def install_home_sdui(app, main_module) -> None:
    router = APIRouter(prefix="/v1/home", tags=["Home AuraFlow SDUI"])

    def _open_conn():
        conn = sqlite3.connect(main_module.database_path(), timeout=15, check_same_thread=False)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys=ON")
        return conn

    @router.get("/sdui")
    async def get_home_sdui(request: Request, live: bool = False, follow: bool = False, user=Depends(main_module.short_session_user)):
        def current_document(conn, hub):
            use_live = not load_user_state(conn, user["id"])["showcaseMode"] if follow else live
            return build_home_sdui(conn, hub, user["id"], live=use_live)

        hub = getattr(request.app.state, "agent_hub", None)
        if hub is not None:
            async with hub.lock(user["id"]):
                await hub.restore_existing(user["id"])
                with closing(_open_conn()) as conn, conn:
                    return current_document(conn, hub)
        with closing(_open_conn()) as conn, conn:
            return current_document(conn, None)

    @router.post("/sdui/actions")
    async def post_home_sdui_action(
        body: HomeSduiAction,
        request: Request,
        live: bool = False,
        user=Depends(main_module.short_session_user),
    ):
        hub = getattr(request.app.state, "agent_hub", None)
        if hub is not None:
            async with hub.lock(user["id"]):
                await hub.restore_existing(user["id"])
                with closing(_open_conn()) as conn, conn:
                    conn.execute("BEGIN IMMEDIATE")
                    return apply_home_action(conn, hub, user["id"], body, live=live)
        with closing(_open_conn()) as conn, conn:
            conn.execute("BEGIN IMMEDIATE")
            return apply_home_action(conn, None, user["id"], body, live=live)

    @router.get("/sdui/feedback")
    def list_home_feedback(user=Depends(main_module.session_user), conn=Depends(main_module.db)):
        rows = conn.execute(
            "SELECT id, card_id, source_event_id, source_chat_id, action, reason, original_text, created_at FROM home_feedback WHERE user_id=? ORDER BY created_at DESC LIMIT 50",
            (user["id"],),
        ).fetchall()
        return {"items": [dict(r) for r in rows]}

    app.include_router(router)
    from .auraflow_routes import install
    install(app, main_module, _open_conn)
