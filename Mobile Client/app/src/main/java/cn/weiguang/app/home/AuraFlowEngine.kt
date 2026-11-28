package cn.weiguang.app.home

import cn.weiguang.app.data.LocalDecision
import cn.weiguang.app.data.LocalEvent
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

data class LocalAuraMutationState(
    val dayModeOverride: String = "AUTO",
    val weatherMode: String = "sunny",
    val exploreIndex: Int = 0,
    val showcaseMode: Boolean = false,
    val adoptedEvents: List<AuraCard> = emptyList(),
    val dismissedIds: Set<String> = emptySet(),
    val completedAt: Map<String, String> = emptyMap(),
    val confirmedIds: Set<String> = emptySet(),
    val selectedPlanByCard: Map<String, Int> = emptyMap(),
    val dismissedReminders: Set<String> = emptySet(),
    val resolvedConflicts: Set<String> = emptySet(),
    val shiftedTimes: Map<String, String> = emptyMap(),
    val checklistDone: Map<String, Map<Int, Boolean>> = emptyMap(),
    val notesDone: Map<String, Map<Int, Boolean>> = emptyMap(),
    val simulatedJoke: Boolean = false,
    val simulatedAlertCardId: String? = null,
    val simulatedSmsTicket: Boolean = false,
    val appliedPlanB: String? = null,
    val negativeFeedbackCount: Int = 0
)

object AuraFlowEngine {

    private val travelRegex = Regex("机票|航班|飞|高铁|火车|动车|车票|出差|差旅|机场|航站楼|登机|值机|返程|火车站|MU\\d+|CA\\d+|G\\d+")
    private val diningRegex = Regex("聚餐|聚会|晚宴|吃饭|餐厅|火锅|烧烤|包厢|订座|排号|约饭|生日|咖啡|茶室|饭局")
    private val shoppingRegex = Regex("买|购物|商场|伴手礼|礼盒|香氛|领券|满减|比价|下单|现货|恒隆|万象|百货|免税|逛街")
    private val exploreRegex = Regex("逛逛|citywalk|散步|看展|美术馆|公园|落日|漫游|博物馆|书店|打卡|徒步", RegexOption.IGNORE_CASE)
    private val confirmedRegex = Regex("已出票|出票成功|航旅纵横|登机口|座位|已订座|包厢号|已预订|已确认|已领券|已预约|已购票|\\d+车\\s*\\d+[A-F]|GATE|SEAT", RegexOption.IGNORE_CASE)
    private val leadingCancellation = Regex("""(?:^取消(?:今天|明天|后天|周[一二三四五六日天])|(?<!不)(?<!别)(?<!能)(?:已经取消|已取消|先取消|取消了|取消吧|取消掉)[哈吧了哦呀]?(?=[\s。！!，,；;]|$))""")
    private val alertRegex = Regex("延误|暴雨|台风|停运|取消|闭店|歇业|雷雨|改签|预警")
    private val jokeRegex = Regex("巴黎.*咖啡|去火星|去月球|开玩笑|哈哈|飞.*喝咖啡|说走就走.*南极")
    private val returnLeg = Regex("""((?:(?:下|本|这)?周[一二三四五六日天]|明天|后天)(?:\s*\d{1,2}:\d{2})?)\s*(?:回|返)""")
    private val serviceCode = Regex("""(?<![A-Z0-9])(?:[A-Z]{2}|[GDC])\d{2,5}(?![A-Z0-9])""", RegexOption.IGNORE_CASE)
    private val ticketText = Regex("已出票|出票成功|已购票")
    private val scheduleWords = Regex("会议|开会|晨会|面试|评审|上课|讨论|提醒|预约|截止|待办")
    private val timeRegex = Regex("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)")
    private val cnTimeRegex = Regex("(上午|下午|晚上|明早|今晚)?(\\d{1,2})点(?:(\\d{1,2})分|半)?")

    private fun startsWithCancellation(text: String): Boolean {
        val lines = text.lineSequence().take(2).map(String::trim).toList()
        val first = lines.firstOrNull().orEmpty()
        val next = lines.getOrNull(1).orEmpty()
        return leadingCancellation.containsMatchIn(first) ||
            (first.endsWith("先") && next.startsWith("取消") && leadingCancellation.containsMatchIn(first + next))
    }

    fun defaultExplorePresets(): Map<String, List<ExplorePreset>> = mapOf(
        "sunny" to listOf(
            ExplorePreset(
                id = "sun_1",
                title = "西岸滨江 · 落日微风 Citywalk",
                duration = "2.5 小时 · 步行 3.2 km",
                tag = "绝佳江景日落",
                stops = listOf(
                    ExploreStop("14:30", "龙美术馆江畔草坪", "起点", "秋日微风草坪小憩"),
                    ExploreStop("15:30", "滑板公园沿江步道", "1.4 km", "黄金光线拍照打卡"),
                    ExploreStop("16:45", "油罐艺术中心 · 落日露台", "1.8 km", "看 17:12 橘色江面日落")
                )
            ),
            ExplorePreset(
                id = "sun_2",
                title = "衡复风貌区 · 梧桐光影漫游",
                duration = "2.0 小时 · 步行 2.6 km",
                tag = "文艺街区摄影",
                stops = listOf(
                    ExploreStop("14:00", "安福路独立买手街区", "起点", "逛独立设计小店"),
                    ExploreStop("15:00", "武康路老洋房长廊", "1.1 km", "梧桐斑驳光影散步"),
                    ExploreStop("16:00", "复兴公园 · 露天手冲摊", "1.5 km", "听黑胶喝冰美式")
                )
            ),
            ExplorePreset(
                id = "sun_3",
                title = "苏河湾 · 工业遗迹与公园放空",
                duration = "2.5 小时 · 步行 2.9 km",
                tag = "人少静谧路线",
                stops = listOf(
                    ExploreStop("14:30", "慎余里红砖里弄", "起点", "老建筑改造展厅"),
                    ExploreStop("15:30", "苏河湾万象天地绿地", "1.2 km", "晒太阳放空 40 分钟"),
                    ExploreStop("16:40", "邮政博物馆河岸桥景", "1.7 km", "远眺陆家嘴天际线")
                )
            )
        ),
        "rainy" to listOf(
            ExplorePreset(
                id = "rain_1",
                title = "西岸美术馆特展 · 听雨手冲微行程",
                duration = "2.5 小时 · 室内连廊直达",
                tag = "全程不淋雨",
                stops = listOf(
                    ExploreStop("14:00", "西岸美术馆 · 蓬皮杜特展", "室内", "免排队电子票直入"),
                    ExploreStop("15:45", "滨江玻璃房手冲咖啡馆", "步行 180m", "窗边听雨品埃塞瑰夏"),
                    ExploreStop("16:45", "设计书店黑胶试听角", "同建筑", "挑一本建筑画册")
                )
            ),
            ExplorePreset(
                id = "rain_2",
                title = "上生新所 · 茑屋书店与暖冬茶席",
                duration = "2.2 小时 · 历史室内空间",
                tag = "高分静谧治愈",
                stops = listOf(
                    ExploreStop("14:30", "哥伦比亚俱乐部 · 茑屋书店", "室内", "百年壁炉旁阅读"),
                    ExploreStop("15:40", "中式现代茶室 · 碳焙乌龙", "步行 90m", "雨天热茶配手作茶点"),
                    ExploreStop("16:50", "地下爵士黑胶体验廊", "室内", "沉浸式试听 30 分钟")
                )
            ),
            ExplorePreset(
                id = "rain_3",
                title = "当代艺术博物馆 · 咖啡与胶片展",
                duration = "2.5 小时 · 地铁直达",
                tag = "小众灵感补给",
                stops = listOf(
                    ExploreStop("14:00", "PSA 当代艺术双年展", "室内", "沉浸装置艺术空间"),
                    ExploreStop("15:50", "顶楼江景玻璃咖啡厅", "电梯直达", "俯瞰雨雾黄浦江"),
                    ExploreStop("16:50", "艺术商店胶片周边区", "1F", "挑一张限量版画")
                )
            )
        )
    )

    fun defaultDiningFriends(): List<DiningFriend> = listOf(
        DiningFriend("你·虹桥机场", "你", 40f, 96f),
        DiningFriend("林溪·静安寺", "林", 142f, 40f),
        DiningFriend("周明·陆家嘴", "周", 294f, 58f),
        DiningFriend("陈默·徐家汇", "陈", 132f, 152f)
    )

    fun defaultGapSpots(): List<GapSpot> = listOf(
        GapSpot("12:00", "西岸美术馆特展", "步行 8m · 沉浸看展 1.5h", "12:00 · 距离 650m · 蓬皮杜当代艺术展", durationMinutes = 90),
        GapSpot("12:15", "独立手冲咖啡馆", "静谧办公位 · 距出发顺路", "12:15 · 距离 320m · 埃塞单品 + 安静电源位")
    )

    fun defaultTravelPlans(direction: String = "outbound"): List<TravelPlan> = if (direction == "return") {
        listOf(
            TravelPlan("mu5116", "东航 MU5116", "flight", "直飞 2h15m", 960, 95, "12:40", "14:55", "虹桥 T2", "首都 T2", "14A 靠窗", "C18", "15:00前落地首都，从容衔接周日晚间休息"),
            TravelPlan("g18", "复兴号 G18", "rail", "高铁 4h22m", 662, 99, "10:30", "14:52", "上海虹桥", "北京南站", "06车 06F", "11B", "高铁准点不惧晚秋华北阵风"),
            TravelPlan("ca1502", "国航 CA1502", "flight", "宽体 2h20m", 1080, 92, "13:15", "15:35", "虹桥 T2", "首都 T3", "35A 靠窗", "B09", "午后从容退房前往机场")
        )
    } else {
        listOf(
            TravelPlan("mu5102", "东航 MU5102", "flight", "直飞 2h15m", 980, 96, "14:30", "16:45", "首都 T2", "虹桥 T2", "14A 靠窗", "B24", "降落虹桥距市区最近，AI 匹配靠窗静谧位"),
            TravelPlan("g14", "复兴号 G14", "rail", "高铁 4h18m", 662, 99, "12:00", "16:18", "北京南站", "上海虹桥", "05车 08F", "14A", "准点率 99%，全程不断网适合途中处理邮件"),
            TravelPlan("ca1519", "国航 CA1519", "flight", "宽体 2h20m", 1120, 93, "14:00", "16:20", "首都 T3", "虹桥 T2", "32A 靠窗", "C11", "空客 A350 宽体客机，落地时间更从容")
        )
    }

    fun defaultDiningPlans(): List<DiningPlan> = listOf(
        DiningPlan("luyuan", "鹿园 · 外滩店", 96, 320, "包厢免排队", "B08 江景包厢", "黄浦区中山东一路 18 号", 26, 244f, 84f, listOf(38, 19, 14, 33), "四方地理重心最优解，已预留江景包厢"),
        DiningPlan("yongfu", "甬府小鲜 · 静安", 92, 260, "需等 15m", "A04 雅致卡座", "静安区南京西路 1266 号", 24, 164f, 78f, listOf(28, 8, 30, 29), "距恒隆广场步行 5 分钟，顺路后续采购"),
        DiningPlan("fuhehui", "福和慧 · 愚园路", 89, 480, "米其林私密", "禅意 3 号厅", "长宁区愚园路 1037 号", 29, 118f, 92f, listOf(22, 16, 46, 31), "离虹桥机场更近，商务宴请静谧度满分")
    )

    fun defaultPriceHistory(): List<PricePoint> = listOf(
        PricePoint("10/28", 380, "日常原价"),
        PricePoint("11/03", 368, "小幅回落"),
        PricePoint("11/11", 315, "双11史低"),
        PricePoint("11/16", 352, "节后回调"),
        PricePoint("今日", 328, "叠券到手 · 推荐入手")
    )

    fun defaultChecklist(): List<ChecklistItem> = listOf(
        ChecklistItem("c1", "B1", "B1 焦糖蝴蝶酥礼盒", "¥268", "省¥40", false),
        ChecklistItem("c2", "L2", "L2 乌木雪松香氛", "¥980", "省¥220", false),
        ChecklistItem("c3", "L3", "L3 限定纪念随行杯", "¥320", "省¥80", false)
    )

    fun defaultCrossDayRelay(): AuraCard = AuraCard(
        id = "evt_travel_return",
        category = AuraCategory.TRAVEL,
        themeKey = "travel",
        direction = "return",
        lifecycleStage = LifecycleStage.PROPOSAL,
        time = "周日 15:00",
        title = "上海 → 北京 · 返程提案",
        subtitle = "未订票（提前48h唤醒）",
        badge = "未订票",
        entities = listOf("周日返程", "上海→北京", "16点前抵京", "往返联动"),
        selectedPlanIdx = 0,
        travelPlans = defaultTravelPlans("return"),
        detailBlocks = listOf("MAP_D3_CORRIDOR", "PLAN_DECK_SELECTOR", "ACTION_SLIDE_BAR")
    )

    fun showcaseArchived(): List<ArchivedCard> = listOf(
        ArchivedCard(
            id = "evt_arch_01",
            time = "09:00",
            endTime = "09:45",
            title = "项目晨会同步",
            summary = "已确认 Q4 发布节点与设计评审清单，纪要已自动归档至云端。"
        )
    )

    fun showcaseCards(): List<AuraCard> = listOf(
        AuraCard(
            id = "evt_morning_prep",
            category = AuraCategory.OFFICE,
            themeKey = "office",
            lifecycleStage = LifecycleStage.CONFIRMED,
            time = "10:30",
            title = "行前简报 · 客户端需求对齐",
            subtitle = "线上腾讯会议 · 30m 速会",
            badge = "已就绪",
            reminderStrip = ReminderStrip("带好演示稿与 Type-C 转接头 · 距开会 15m", false),
            connectorToNext = ConnectorInfo(ConnectorState.GAP_SUGGESTION, "空档 3.5h · 附近补白 ✦", gapHours = "3.5h", gapSpots = defaultGapSpots()),
            entities = listOf("10:30 速会", "30分钟", "已同步会议纪要模板"),
            notes = listOf(
                NoteItem("核对 SDUI 原子组件 JSON 规范", true),
                NoteItem("同步上海客户演示环境地址", false)
            ),
            detailBlocks = listOf("SUMMARY_CARD", "MEETING_CHECKLIST")
        ),
        AuraCard(
            id = "evt_travel_outbound",
            category = AuraCategory.TRAVEL,
            themeKey = "travel",
            direction = "outbound",
            lifecycleStage = LifecycleStage.PROPOSAL,
            time = "14:30",
            title = "北京 → 上海 · 差旅出行",
            subtitle = "待拍板 · 推荐 3 套出行方案",
            badge = "待拍板",
            reminderStrip = ReminderStrip("上海小雨 14°C · 建议带伞 / 身份证 · 距值机截止 1h", false),
            connectorToNext = ConnectorInfo(ConnectorState.CONFLICT_DASHED, "打车约 55m · 衔接紧张", actionLabel = "一键顺延 30m"),
            entities = listOf("北京→上海", "今日 14:30", "靠窗偏好", "准点率优先"),
            selectedPlanIdx = 0,
            travelPlans = defaultTravelPlans("outbound"),
            detailBlocks = listOf("MAP_D3_CORRIDOR", "PLAN_DECK_SELECTOR", "ACTION_SLIDE_BAR")
        ),
        AuraCard(
            id = "evt_dining_bund",
            category = AuraCategory.DINING,
            themeKey = "dining",
            lifecycleStage = LifecycleStage.PROPOSAL,
            time = "18:00",
            title = "外滩 · 鹿园晚宴（4人）",
            subtitle = "群聊捕获 · 4人等时圈最优解",
            badge = "待拍板",
            connectorToNext = ConnectorInfo(ConnectorState.NORMAL_SOLID, "行程充裕"),
            entities = listOf("4人聚餐", "分散在4个区", "静谧包厢", "免长排队"),
            selectedPlanIdx = 0,
            diningPlans = defaultDiningPlans(),
            detailBlocks = listOf("MAP_DINING_ISOCHRONE", "PLAN_DECK_SELECTOR", "ACTION_SLIDE_BAR")
        ),
        AuraCard(
            id = "evt_shopping_plaza66",
            category = AuraCategory.SHOPPING,
            themeKey = "shopping",
            lifecycleStage = LifecycleStage.PROPOSAL,
            time = "20:00",
            title = "恒隆广场 · 伴手礼采购",
            subtitle = "已比价省 ¥340 · 3件现货清单",
            badge = "0/3",
            connectorToNext = null,
            entities = listOf("恒隆广场", "3件现货", "已寻2张满减券", "当前近史低"),
            priceHistory = defaultPriceHistory(),
            checklist = defaultChecklist(),
            detailBlocks = listOf("CHART_PRICE_TREND", "MAP_INDOOR_ROUTE", "INTERACTIVE_CHECKLIST", "ACTION_SLIDE_BAR")
        )
    )

    fun classifyCategory(text: String, subagentId: String? = null): AuraCategory {
        if (subagentId == "party") return AuraCategory.DINING
        return when {
            travelRegex.containsMatchIn(text) || returnLeg.containsMatchIn(text) -> AuraCategory.TRAVEL
            diningRegex.containsMatchIn(text) -> AuraCategory.DINING
            shoppingRegex.containsMatchIn(text) -> AuraCategory.SHOPPING
            exploreRegex.containsMatchIn(text) -> AuraCategory.EXPLORE
            else -> AuraCategory.OFFICE
        }
    }

    fun extractTimeMinutes(text: String, isoTime: String? = null, defaultMinutes: Int = 14 * 60): Pair<String, Int> {
        if (!isoTime.isNullOrBlank()) {
            try {
                val zdt = ZonedDateTime.ofInstant(Instant.parse(isoTime), ZoneId.systemDefault())
                val mins = zdt.hour * 60 + zdt.minute
                return String.format(Locale.US, "%02d:%02d", zdt.hour, zdt.minute) to mins
            } catch (_: Exception) {
            }
        }
        timeRegex.find(text)?.let { m ->
            val h = m.groupValues[1].toInt()
            val mi = m.groupValues[2].toInt()
            return String.format(Locale.US, "%02d:%02d", h, mi) to (h * 60 + mi)
        }
        cnTimeRegex.find(text)?.let { m ->
            val period = m.groupValues[1]
            var h = m.groupValues[2].toInt()
            if (period in listOf("下午", "晚上", "今晚") && h < 12) h += 12
            val mi = if (m.value.contains("半")) 30 else m.groupValues[3].toIntOrNull() ?: 0
            if (h in 0..23 && mi in 0..59) {
                return String.format(Locale.US, "%02d:%02d", h, mi) to (h * 60 + mi)
            }
        }
        return String.format(Locale.US, "%02d:%02d", defaultMinutes / 60, defaultMinutes % 60) to defaultMinutes
    }

    fun shiftTime(timeStr: String, deltaMinutes: Int): String {
        val m = timeRegex.find(timeStr) ?: return timeStr
        val total = (m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt() + deltaMinutes).mod(24 * 60)
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
    }

    private fun shortTitle(text: String, category: AuraCategory): String {
        val firstLine = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val cleaned = if (firstLine.startsWith("{") && firstLine.endsWith("}")) "" else firstLine
        if (cleaned.isEmpty()) {
            return when (category) {
                AuraCategory.TRAVEL -> "差旅出行"
                AuraCategory.DINING -> "聚餐安排"
                AuraCategory.SHOPPING -> "购物清单"
                AuraCategory.EXPLORE -> "城市漫游"
                AuraCategory.OFFICE -> "待办日程"
            }
        }
        return if (cleaned.length <= 22) cleaned else cleaned.take(21) + "…"
    }

    fun synthesize(
        localEvents: List<LocalEvent>,
        localDecisions: List<LocalDecision>,
        agentWorks: List<JSONObject>,
        mutation: LocalAuraMutationState
    ): AuraFlowState {
        val weather = if (mutation.weatherMode == "rainy") {
            WeatherContext("rainy", "细雨微凉 15°C", "上海")
        } else {
            WeatherContext("sunny", "晴朗微风 21°C", "上海")
        }

        val archived = mutableListOf<ArchivedCard>()
        val rawCards = mutableListOf<Pair<Int, AuraCard>>()
        var relay: AuraCard? = null

        if (mutation.showcaseMode) {
            archived.addAll(showcaseArchived())
            showcaseCards().forEachIndexed { idx, card ->
                var c = card
                if (mutation.simulatedSmsTicket && c.id == "evt_travel_outbound") {
                    val p = c.travelPlans.first()
                    c = c.copy(
                        lifecycleStage = LifecycleStage.CONFIRMED,
                        title = "北京 → 上海 · ${p.carrier.substringAfter(' ')}",
                        subtitle = "${p.depHub} · ${p.seat}",
                        badge = "已出票"
                    )
                }
                if (mutation.simulatedAlertCardId == c.id) {
                    c = c.copy(
                        connectorToNext = ConnectorInfo(
                            ConnectorState.ALERT_PULSE,
                            "MU5102 预计延误 75m · 点击查看 B 计划"
                        )
                    )
                }
                rawCards.add((idx * 100) to c)
            }
            relay = defaultCrossDayRelay()
        } else {
            // 1. Agent works -> confirmed schedule / party cards
            for (work in agentWorks) {
                val wid = work.optString("id")
                val workTitle = work.optString("title", "云端事项")
                val version = work.optInt("memory_version", 0)
                val state = work.optJSONObject("state") ?: continue
                val items = state.optJSONObject("items")
                if (items != null) {
                    val keys = items.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val item = items.getJSONObject(key)
                        val title = item.optString("title", workTitle)
                        val (timeStr, sortMin) = extractTimeMinutes(title, item.optString("at").takeIf { it.isNotBlank() }, 15 * 60)
                        if (item.optString("status") == "completed") {
                            archived.add(ArchivedCard("work_${wid}_item_$key", timeStr, "", title, "来自云端事项「$workTitle」· 已完成归档。", wid, item.optString("at")))
                        } else {
                            val cat = classifyCategory(title, work.optString("subagent_id"))
                            rawCards.add(
                                sortMin to AuraCard(
                                    id = "work_${wid}_item_$key",
                                    scheduledAt = item.optString("at"),
                                    sourceWorkId = wid,
                                    category = cat,
                                    themeKey = cat.themeKey,
                                    lifecycleStage = LifecycleStage.CONFIRMED,
                                    time = timeStr,
                                    title = title,
                                    subtitle = "云端事项「$workTitle」· v$version 已确认",
                                    badge = "已确认",
                                    entities = listOf("云端日程", "v$version", timeStr),
                                    reminderStrip = ReminderStrip("已同步云端事项 v$version · 可在云端事项导出日历"),
                                    notes = listOf(NoteItem("云端事项：$workTitle", true), NoteItem("安排时间：$timeStr", false)),
                                    detailBlocks = listOf("SUMMARY_CARD", "MEETING_CHECKLIST")
                                )
                            )
                        }
                    }
                }
                val plan = state.optJSONObject("plan")
                if (plan != null) {
                    val scheduledAt = sequenceOf(state.optString("scheduled_at"), plan.optString("time"))
                        .mapNotNull { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
                        .firstOrNull()?.toInstant()?.toString()
                    val (timeStr, sortMin) = if (scheduledAt != null) extractTimeMinutes("", scheduledAt)
                        else "日期待确认" to 24 * 60
                    rawCards.add(
                        sortMin to AuraCard(
                            id = "work_${wid}_party",
                            sourceWorkId = wid,
                            category = AuraCategory.DINING,
                            themeKey = "dining",
                            lifecycleStage = LifecycleStage.CONFIRMED,
                            time = timeStr,
                            title = plan.optString("title", workTitle),
                            subtitle = "${plan.optString("location", "待确认")} · 云端聚会 v$version",
                            badge = "已确认",
                            entities = listOf("云端聚会", plan.optString("location", "待确认"), "v$version"),
                            scheduledAt = scheduledAt,
                            detailBlocks = listOf("SOURCE_DETAIL")
                        )
                    )
                }
            }

            // 2. Real LocalEvents (OCR / manual / calendar / location)
            val tickets = localEvents.filter { ticketText.containsMatchIn(it.text) }.flatMap { event ->
                serviceCode.findAll(event.text).map { it.value.uppercase() to event.capturedAt.take(10) }.toList()
            }.toSet()
            for (event in localEvents) {
                val text = event.text.trim()
                if (event.sourceKind == "screenshot_ocr" && event.eventType == "message" &&
                    event.occurredAt == null && startsWithCancellation(text)
                ) continue
                val category = classifyCategory(text)
                val hasTime = timeRegex.containsMatchIn(text) || cnTimeRegex.containsMatchIn(text) || event.occurredAt != null
                if (event.sourceKind == "location" || category == AuraCategory.OFFICE && !hasTime &&
                    event.eventType != "schedule" && !scheduleWords.containsMatchIn(text)) continue
                val (time, minutes) = if (hasTime) extractTimeMinutes(text, event.occurredAt) else "待定" to 1440
                val joke = jokeRegex.containsMatchIn(text)
                val ticket = category == AuraCategory.TRAVEL && serviceCode.findAll(text).any {
                    (it.value.uppercase() to event.capturedAt.take(10)) in tickets
                }
                val confirmed = !joke && (confirmedRegex.containsMatchIn(text) || ticket || event.sourceKind == "calendar" ||
                    (event.eventType == "schedule" && event.occurredAt != null))
                val source = when (event.sourceKind) {
                    "screenshot_ocr" -> "截图识别"
                    "calendar" -> "系统日历"
                    else -> "手动记录"
                }
                val card = AuraCard(
                    id = "evt_${event.eventId}", sourceEventId = event.eventId,
                    sourceWorkId = event.agentWorkId, sourceTaskId = event.agentTaskId,
                    scheduledAt = event.occurredAt, capturedAt = event.capturedAt,
                    category = category, themeKey = category.themeKey, time = time,
                    lifecycleStage = if (confirmed) LifecycleStage.CONFIRMED else LifecycleStage.PROPOSAL,
                    title = shortTitle(text, category), subtitle = source, originalText = text,
                    badge = when {
                        confirmed && (ticket || ticketText.containsMatchIn(text)) -> "已出票"
                        confirmed -> "已确认"
                        else -> "待确认"
                    },
                    isMisjudgment = joke, entities = listOf(source), detailBlocks = listOf("SOURCE_DETAIL"),
                    reminderStrip = if (category == AuraCategory.TRAVEL) ReminderStrip("出发前核对证件与车次") else null
                )
                rawCards.add(minutes to card)
                val leg = returnLeg.find(text)
                if (relay == null && category == AuraCategory.TRAVEL && leg != null && card.id !in mutation.dismissedIds) {
                    relay = card.copy(id = card.id + "_return", title = "返程安排", direction = "return",
                        scheduledAt = null, time = leg.groupValues[1], lifecycleStage = LifecycleStage.PROPOSAL, badge = "待确认")
                }
            }
        }

        // Merge adopted explore / gap cards
        mutation.adoptedEvents.forEach { adopted ->
            val (_, m) = extractTimeMinutes(adopted.time)
            rawCards.add(m to adopted)
        }

        // Optional simulated joke card
        if (mutation.simulatedJoke && "evt_joke_paris" !in mutation.dismissedIds) {
            rawCards.add(
                0,
                -1 to AuraCard(
                    id = "evt_joke_paris",
                    category = AuraCategory.TRAVEL,
                    themeKey = "explore",
                    lifecycleStage = LifecycleStage.PROPOSAL,
                    isMisjudgment = true,
                    confidence = 0.42f,
                    time = "明日 09:00",
                    title = "北京 → 巴黎 · 喝咖啡（疑似玩笑）",
                    subtitle = "← 左滑雾化移除 · 纠正 AI 闲聊语境",
                    badge = "待拍板",
                    connectorToNext = ConnectorInfo(ConnectorState.NORMAL_SOLID, "待确认跨国意图"),
                    entities = listOf("群聊捕获", "置信度 42%", "支持左滑雾化"),
                    originalText = "走，明天飞巴黎喝咖啡！",
                    detailBlocks = listOf("SUMMARY_CARD")
                )
            )
        }

        // Filter dismissed & sort non-showcase
        val filtered = rawCards.filter { it.second.id !in mutation.dismissedIds }
        val ordered = if (mutation.showcaseMode) filtered else filtered.sortedBy { it.first }

        // Apply local interactive mutations
        val mutatedCards = ordered.mapIndexed { idx, (sortMin, base) ->
            var c = base
            val planIdx = mutation.selectedPlanByCard[c.id] ?: c.selectedPlanIdx
            c = c.copy(selectedPlanIdx = planIdx)

            if (c.id in mutation.confirmedIds && c.lifecycleStage != LifecycleStage.CONFIRMED) {
                c = applyConfirmTransform(c)
            }
            if (c.id in mutation.dismissedReminders && c.reminderStrip != null) {
                c = c.copy(reminderStrip = c.reminderStrip?.copy(dismissed = true))
            }
            mutation.shiftedTimes[c.id]?.let { newTime -> c = c.copy(time = newTime) }
            mutation.checklistDone[c.id]?.let { doneMap ->
                val updatedList = c.checklist.mapIndexed { i, item -> item.copy(done = doneMap[i] ?: item.done) }
                c = c.copy(checklist = updatedList, badge = "${updatedList.count { it.done }}/${updatedList.size}")
            }
            mutation.notesDone[c.id]?.let { doneMap ->
                val updatedNotes = c.notes.mapIndexed { i, item -> item.copy(done = doneMap[i] ?: item.done) }
                c = c.copy(notes = updatedNotes)
            }
            if (mutation.appliedPlanB == "switch_hsr" && c.id == "evt_travel_outbound") {
                c = c.copy(
                    selectedPlanIdx = 1,
                    lifecycleStage = LifecycleStage.CONFIRMED,
                    time = "12:00",
                    title = "北京 → 上海 · 复兴号 G14",
                    subtitle = "北京南站 · 05车 08F · 准点 99%",
                    badge = "已改签",
                    connectorToNext = ConnectorInfo(ConnectorState.NORMAL_SOLID, "高铁准点抵沪 · 赴宴充裕")
                )
            } else if (mutation.appliedPlanB == "shift_dinner") {
                if (c.id == "evt_travel_outbound") {
                    c = c.copy(connectorToNext = ConnectorInfo(ConnectorState.NORMAL_SOLID, "B 计划已生效 · 晚宴延至 19:30"))
                } else if (c.id == "evt_dining_bund") {
                    c = c.copy(time = "19:30", subtitle = "已自动顺延 · 包厢已留座至 19:45")
                }
            }
            if (c.id in mutation.resolvedConflicts) {
                c = c.copy(connectorToNext = ConnectorInfo(ConnectorState.NORMAL_SOLID, "已顺延 · 预留 25m 缓冲"))
            }

            // Compute connectors for non-showcase mode
            if (!mutation.showcaseMode) {
                if (idx == ordered.lastIndex) {
                    c = c.copy(connectorToNext = null)
                } else if (c.id !in mutation.resolvedConflicts) {
                    val hasAlert = alertRegex.containsMatchIn(c.originalText) || mutation.simulatedAlertCardId == c.id
                    val nextMin = ordered[idx + 1].first
                    val gap = nextMin - sortMin
                    c = c.copy(
                        connectorToNext = when {
                            hasAlert -> ConnectorInfo(ConnectorState.ALERT_PULSE, "${c.title.take(12)} 监测到异常变动 · 点击查看 B 计划")
                            sortMin >= 0 && nextMin >= 0 && gap < 50 -> ConnectorInfo(ConnectorState.CONFLICT_DASHED, "间隔仅 ${gap.coerceAtLeast(5)}m · 衔接紧张", actionLabel = "一键顺延 30m")
                            sortMin >= 0 && nextMin >= 0 && gap > 180 -> {
                                val hrs = String.format(Locale.US, "%.1fh", gap / 60.0)
                                ConnectorInfo(ConnectorState.GAP_SUGGESTION, "空档 $hrs · 附近补白 ✦", gapHours = hrs, gapSpots = defaultGapSpots())
                            }
                            else -> ConnectorInfo(ConnectorState.NORMAL_SOLID, "行程充裕")
                        }
                    )
                }
            }
            c
        }

        val finalRelay = relay?.takeUnless { it.id in mutation.dismissedIds }?.let { r ->
            var rr = r.copy(selectedPlanIdx = mutation.selectedPlanByCard[r.id] ?: r.selectedPlanIdx)
            if (rr.id in mutation.confirmedIds) rr = applyConfirmTransform(rr)
            rr
        }

        val dayMode = when (mutation.dayModeOverride) {
            "EMPTY_EXPLORE" -> DayMode.EMPTY_EXPLORE
            "REST_MODE" -> DayMode.REST_MODE
            "ACTIVE_TIMELINE" -> if (mutatedCards.isNotEmpty()) DayMode.ACTIVE_TIMELINE else DayMode.EMPTY_EXPLORE
            else -> if (mutatedCards.isNotEmpty()) DayMode.ACTIVE_TIMELINE else DayMode.EMPTY_EXPLORE
        }

        val activeTheme = when (dayMode) {
            DayMode.EMPTY_EXPLORE -> "explore"
            DayMode.REST_MODE -> "rest"
            DayMode.ACTIVE_TIMELINE -> mutatedCards.find { it.id == "evt_travel_outbound" }?.themeKey
                ?: mutatedCards.firstOrNull()?.themeKey ?: "travel"
        }

        return AuraFlowState(
            protocolVersion = "2.4.0",
            dayMode = dayMode,
            dayModeOverride = mutation.dayModeOverride,
            showcaseMode = mutation.showcaseMode,
            activeTheme = activeTheme,
            weatherContext = weather,
            exploreIndex = mutation.exploreIndex,
            explorePresets = defaultExplorePresets(),
            diningFriends = defaultDiningFriends(),
            archivedEvents = archived,
            events = mutatedCards,
            crossDayRelay = finalRelay,
            negativeFeedbackCount = mutation.negativeFeedbackCount,
            realEventCount = localEvents.size + localDecisions.size,
            dailyFootprint = if (archived.isNotEmpty()) "今日已沉降 ${archived.size} 项日程 · 已记录 ${localEvents.size} 条线索" else null,
            fromServer = false
        )
    }

    fun applyConfirmTransform(card: AuraCard): AuraCard = if (card.sourceEventId != null || card.sourceWorkId != null) {
        card.copy(lifecycleStage = LifecycleStage.CONFIRMED, badge = "已确认")
    } else when (card.category) {
        AuraCategory.TRAVEL -> {
            val plan = card.travelPlans.getOrNull(card.selectedPlanIdx)
            if (plan != null) {
                card.copy(
                    lifecycleStage = LifecycleStage.CONFIRMED,
                    time = plan.dep,
                    title = "${if (card.direction == "return") "上海 → 北京" else "北京 → 上海"} · ${plan.carrier.substringAfter(' ')}",
                    subtitle = "${plan.depHub} · ${plan.seat}",
                    badge = "已出票"
                )
            } else card.copy(lifecycleStage = LifecycleStage.CONFIRMED, badge = "已出票")
        }
        AuraCategory.DINING -> {
            val plan = card.diningPlans.getOrNull(card.selectedPlanIdx)
            if (plan != null) {
                val parts = plan.name.split("·").map { it.trim() }
                card.copy(
                    lifecycleStage = LifecycleStage.CONFIRMED,
                    title = "${parts.getOrElse(1) { "外滩" }} · ${parts.first()}（4人）",
                    subtitle = "${plan.room} · 已同步群聊",
                    badge = "已订座"
                )
            } else card.copy(lifecycleStage = LifecycleStage.CONFIRMED, badge = "已订座")
        }
        AuraCategory.SHOPPING -> card.copy(
            lifecycleStage = LifecycleStage.CONFIRMED,
            subtitle = "已领 2 张满减券 · ${card.checklist.size}件现货清单"
        )
        else -> card.copy(lifecycleStage = LifecycleStage.CONFIRMED, badge = "已确认")
    }
}
