"""Synthetic input scripts for the mobile gateway. Inputs are built once per run and replayed verbatim."""
from datetime import datetime, timedelta, timezone
from uuid import uuid4

EVENTS = "/v1/events"
HOME = "/v1/home/sdui"
ACTIONS = HOME + "/actions"
TRIP = "evt_travel_outbound"
DINNER = "evt_dining_bund"
SHOP = "evt_shopping_plaza66"
MEETING = "evt_morning_prep"


def step(title, rule, path, body):
    return {"title": title, "rule": rule, "path": path, "body": body}


def action(label, rule, name, **args):
    return step(label, rule, ACTIONS, {"action": name, **args})


def scenario(title, rule, name):
    return action(title, rule, "simulate_scenario", scenario=name)


START = scenario("载入场景", "读取示例卡片模板、已归档晨会和跨日返程。交通方案、价格、天气来自夹具。", "load_showcase")
LIVE = scenario("读取账号首页", "退出示例模板，按当前账号的线索和云端事项生成首页。", "exit_showcase")
ALERT = scenario("接收延误预警", "将去程的连接线切换为 ALERT_PULSE，提供备选方案。", "emergency_alert")
def confirm(card):
    return action("确认所选方案", "读取 selectedPlanIdx 指向的方案，将卡片更新为 CONFIRMED。", "confirm_card", card_id=card)


def mode_step(mode):
    return action("切换首页模式", "由 day_mode 选择日程轴、漫游或休息视图。", "set_day_mode", day_mode=mode)

# Labels are also the navigation order in the desktop inspector.
CATALOG = {
    "day": ("线索生成", "一日行程", "日历、OCR 与手动线索按类别和时间组成日程。"),
    "roundtrip": ("线索生成", "往返与出票", "用真实线索格式匹配班次，生成去程与跨日返程。"),
    "feedback": ("线索生成", "误判与负反馈", "识别聊天提案，移除卡片并保留源线索。"),
    "filtered": ("线索生成", "定位与普通笔记", "核对已接收但未生成日程卡的输入。"),
    "agent": ("线索生成", "云端日程确认", "已同步的线索进入日程 Agent，确认候选后生成正式事项。"),
    "overview": ("卡片生命周期", "全场景时间轴", "查看每张卡、归档晨会、返程与连接线。"),
    "contract": ("卡片生命周期", "1.2 详情与回写", "调整预算、确认同一方案两次，再检查清单进度的幂等回写。"),
    "home_contract": ("卡片生命周期", "1.2 首页交互", "顺延、关闭提醒、移除提案、切换放空、换路线并采纳。"),
    "office": ("卡片生命周期", "会议与归档", "勾选会议准备项、收起提醒、完成并归档。"),
    "travel": ("卡片生命周期", "出行方案", "选择车次并确认，查看凭证字段。"),
    "dining": ("卡片生命周期", "聚餐订座", "选择餐厅并查看模拟订座结果。"),
    "shopping": ("卡片生命周期", "购物清单", "模拟领券确认并更新清单进度。"),
    "relay": ("卡片生命周期", "跨日返程", "对返程卡单独选票、确认。"),
    "sms": ("卡片生命周期", "短信自动核销", "模拟出票回执，观察提案变为凭证，再重置。"),
    "gap": ("日程调度", "空档补白", "在两段日程之间插入新的漫游卡片。"),
    "conflict": ("日程调度", "通勤冲突", "调整相邻安排时间并观察连接线变化。"),
    "reroute": ("日程调度", "延误后改乘高铁", "接收预警并选择替代班次。"),
    "postpone": ("日程调度", "延误后顺延晚餐", "接收预警并调整关联聚餐。"),
    "explore": ("日程调度", "雨天漫游与采纳", "切换天气、替换路线、将停靠点写入时间轴。"),
    "rest": ("日程调度", "休息与唤醒", "观察 REST_MODE、EMPTY_EXPLORE 和 ACTIVE_TIMELINE 的切换。"),
    "joke": ("日程调度", "玩笑提案", "插入和移除低置信度卡片，再恢复示例状态。"),
}


def steps_for(case):
    now = datetime.now(timezone(timedelta(hours=8))).replace(microsecond=0)
    def clue(title, rule, text, source="screenshot_ocr", kind="message", at=None):
        body = {"schema_version": 1, "event_id": str(uuid4()), "revision": 1, "event_type": kind,
                "source_kind": source, "captured_at": now.isoformat(), "timezone": "Asia/Shanghai",
                "review_status": "confirmed", "payload": {"text": text}}
        if at:
            body["occurred_at"] = at.isoformat()
        return step(title, rule, EVENTS, body)

    trip = clue("收到出行意图", "从 OCR 文本提取班次和时间；识别「周日回」并生成关联返程卡。",
                "今天14:30乘 MU5102 从北京飞上海出差，周日回")
    if case == "contract":
        event = "/api/v1/events/" + TRIP
        confirmation = {"actionId": "BOOK_FLIGHT_TICKET", "selectedPlanId": "g14", "idempotencyKey": str(uuid4())}
        check = dict(step("勾选购物项", "按 itemId 设置 checked，重复发送保持勾选状态。", "/api/v1/events/" + SHOP + "/checklist/c1", {"checked": True}), method="PATCH")
        return [START, dict(step("读取出行详情", "按顺序下发地图、方案和确认操作。", event+"/detail", {}), method="GET"),
                step("预算改为 700 元", "用预算筛选现有候选与地图线路。", event+"/recalculate", {"entityKey":"budget","newValue":700}),
                step("确认高铁方案", "使用当前方案和幂等键生成模拟凭证及首页补丁。", event+"/confirm", confirmation),
                step("重试同次确认", "相同请求返回已存响应，不重复履约。", event+"/confirm", confirmation),
                confirm(SHOP), check, check]
    if case == "home_contract":
        base = "/api/v1/home"
        day = now.date().isoformat()
        return [START, step("顺延晚餐", "按目标事件 ID 调整时间。", base+"/actions/execute",
                            {"actionId":"SHIFT_EVENT_TIME","payload":{"targetEventId":DINNER,"shiftMinutes":30}}),
                step("关闭会前提醒", "再次拉取时隐藏该提醒。", base+"/reminders/rem_"+MEETING+"/ack", {}),
                step("移除晚餐提案", "只移除该账号的指定提案。", base+"/events/"+DINNER+"/dismiss", {"reason":"USER_SWIPE_DISMISS"}),
                step("今日放空", "仅覆盖所选日期。", base+"/day-mode", {"date":day,"mode":"REST_MODE"}),
                step("查看漫游", "下发当前漫游计划。", base+"/day-mode", {"date":day,"mode":"EMPTY_EXPLORE"}),
                step("排除已看路线", "从当天天气候选中排除已看 planId。", base+"/explore/refresh", {"date":day,"excludePlanIds":["sun_1"]}),
                step("采纳漫游", "将选定路线各站加入首页。", base+"/actions/execute", {"actionId":"ADOPT_EXPLORE_PLAN","payload":{"planId":"sun_2"}})]
    if case == "day":
        return [LIVE, clue("导入会议", "日历来源确认事件，occurred_at 提供开始时间。", "项目需求评审", "calendar", "schedule", now.replace(hour=9, minute=30)),
                trip, clue("识别聚餐", "聚餐及人数形成待确认的 DINING 卡。", "今天18:00 外滩聚餐，4人，地点：鹿园"),
                clue("保存购物清单", "从手动记录识别购物和预算，生成待确认卡。", "今天20:00 购物清单：茶叶、点心，预算300元", "manual", "note"),
                clue("记录散步", "散步意图形成 EXPLORE 卡，与附近的安排按时间衔接。", "今天21:00 到公园散步")]
    if case == "roundtrip":
        return [LIVE, trip, clue("收到出票短信", "同一采集日匹配 MU5102 和出票证据，更新已有去程。", "MU5102 已出票，登机口 B24，座位 14A")]
    if case == "feedback":
        joke = clue("捕获玩笑", "文本命中玩笑特征，保留提案供用户核对。", "走，明天09:00飞巴黎喝咖啡！")
        return [LIVE, joke, action("用户移除", "记录 source_event_id 和负反馈原因，后续首页排除该卡片。", "dismiss_misjudgment",
                                   card_id="evt_" + joke["body"]["event_id"], reason="group_chat_joke")]
    if case == "filtered":
        return [LIVE, clue("收到定位", "定位采样没有安排意图，保存为线索。", "位置 31.23,121.47", "location", "location"),
                clue("收到普通笔记", "没有分类、时刻或待办词的普通笔记不生成日程卡。", "这本书的封面很好看", "manual", "note")]
    if case == "agent":
        # Schedule input has the same text format used by the existing deterministic Agent provider.
        import json
        stamp = (now + timedelta(hours=1)).isoformat()
        ev = clue("同步已授权线索", "把带明确时间的日程变更作为测试线索，同步后才可提交给 Agent。",
                  json.dumps({"schedule_changes": [{"id": "review", "title": "联调方案评审", "at": stamp}]}, ensure_ascii=False), "manual", "schedule")
        return [LIVE, ev, step("创建云端事项", "以 schedule 插件创建独立事项。", "/v1/agent/works",
                               {"event_id": str(uuid4()), "title": "首页推演 · 方案评审", "subagent_id": "schedule"}),
                step("提交日程任务", "绑定已同步线索版本，等待 mock Agent 生成 waiting_input 候选。", "/v1/agent/tasks",
                     {"request_id": str(uuid4()), "work_id": "$work", "instruction": "整理评审日程", "events": [{"event_id": ev["body"]["event_id"], "revision": 1}]}),
                step("确认候选方案", "提交候选的实际版本和 proposal_id，等待正式事项版本递增。",
                     "/v1/agent/tasks/$task/responses", {"event_id": str(uuid4()), "kind": "accept_state", "expected_revision": "$revision",
                                                         "output_version": "$version", "proposal_id": "$proposal"})]
    recipes = {
        "overview": [],
        "office": [action("勾选准备项", "翻转 notes 中第二项的完成值。", "toggle_note", card_id=MEETING, item_idx=1),
                   action("收起提醒", "将提醒的 dismissed 字段置为 true。", "dismiss_reminder", card_id=MEETING),
                   action("完成会议", "记录完成时间并归入今日回顾。", "complete_card", card_id=MEETING)],
        "travel": [action("选择高铁", "将第二个候选设为当前方案。", "select_plan", card_id=TRIP, plan_idx=1), confirm(TRIP)],
        "dining": [action("更换餐厅", "将第二家餐厅设为当前方案。", "select_plan", card_id=DINNER, plan_idx=1), confirm(DINNER)],
        "shopping": [confirm(SHOP), action("勾选商品", "翻转第一件商品的完成值，重新统计 badge。", "toggle_checklist", card_id=SHOP, item_idx=0)],
        "relay": [action("选择返程", "独立更新 crossDayRelay 的选中方案。", "select_plan", card_id="evt_travel_return", plan_idx=1), confirm("evt_travel_return")],
        "sms": [scenario("收到出票短信", "模拟出票信号将去程由 PROPOSAL 改为 CONFIRMED。", "sms_ticket"),
                scenario("恢复未出票状态", "移除去程回执和覆盖值，恢复初始提案。", "reset_flight_proposal")],
        "gap": [action("插入空档安排", "在会议之后加入独立 EXPLORE 节点。", "insert_gap_spot", card_id=MEETING, time="12:15", title="午间书店", subtitle="空档内停留 30 分钟")],
        "conflict": [action("顺延下一项", "找到出行的下一项，增加 30 分钟并更新连接状态。", "resolve_conflict", card_id=TRIP, shift_minutes=30)],
        "reroute": [ALERT, action("选择高铁备选", "切换至 G14 并解除去程预警。", "apply_plan_b", card_id=TRIP, choice="switch_hsr")],
        "postpone": [ALERT, action("顺延晚餐", "清除预警并将聚餐改为 19:30。", "apply_plan_b", card_id=TRIP, choice="shift_dinner")],
        "explore": [mode_step("EMPTY_EXPLORE"), action("切换雨天", "选用室内漫游预设并重置路线索引。", "set_weather", weather="rainy"),
                    action("换一条路线", "推进 exploreIndex。", "cycle_explore"), action("采纳路线", "按停靠点生成已确认卡，返回时间轴。", "adopt_explore")],
        "rest": [mode_step("REST_MODE"), mode_step("EMPTY_EXPLORE"), mode_step("ACTIVE_TIMELINE")],
        "joke": [scenario("插入玩笑卡", "添加可移除的低置信度出行提案。", "joke_misjudgment"),
                 action("移除提案", "通过 card_id 隐藏本次示例提案。", "dismiss_misjudgment", card_id="evt_joke_paris"),
                 scenario("重置示例", "还原示例状态，保留账号的真实首页。", "reset_showcase")],
    }
    return [START, *recipes[case]]
