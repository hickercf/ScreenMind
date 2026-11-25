package cn.weiguang.app.home

import org.json.JSONArray
import org.json.JSONObject

enum class DayMode {
    ACTIVE_TIMELINE,
    EMPTY_EXPLORE,
    REST_MODE;

    companion object {
        fun parse(value: String?): DayMode = when (value) {
            "EMPTY_EXPLORE" -> EMPTY_EXPLORE
            "REST_MODE" -> REST_MODE
            else -> ACTIVE_TIMELINE
        }
    }
}

enum class AuraCategory {
    TRAVEL,
    DINING,
    SHOPPING,
    EXPLORE,
    OFFICE;

    val themeKey: String
        get() = when (this) {
            TRAVEL -> "travel"
            DINING -> "dining"
            SHOPPING -> "shopping"
            EXPLORE -> "explore"
            OFFICE -> "office"
        }

    companion object {
        fun parse(value: String?): AuraCategory = when (value) {
            "TRAVEL" -> TRAVEL
            "DINING" -> DINING
            "SHOPPING" -> SHOPPING
            "EXPLORE" -> EXPLORE
            else -> OFFICE
        }
    }
}

enum class LifecycleStage {
    PROPOSAL,
    CONFIRMED,
    ARCHIVED;

    companion object {
        fun parse(value: String?): LifecycleStage = when (value) {
            "CONFIRMED" -> CONFIRMED
            "ARCHIVED" -> ARCHIVED
            else -> PROPOSAL
        }
    }
}

enum class ConnectorState {
    NORMAL_SOLID,
    GAP_SUGGESTION,
    CONFLICT_DASHED,
    ALERT_PULSE;

    companion object {
        fun parse(value: String?): ConnectorState = when (value) {
            "GAP_SUGGESTION" -> GAP_SUGGESTION
            "CONFLICT_DASHED" -> CONFLICT_DASHED
            "ALERT_PULSE" -> ALERT_PULSE
            else -> NORMAL_SOLID
        }
    }
}

data class WeatherContext(
    val mode: String = "sunny",
    val label: String = "晴朗微风 21°C",
    val city: String = "上海"
) {
    fun toJson(): JSONObject = JSONObject()
        .put("mode", mode)
        .put("label", label)
        .put("city", city)

    companion object {
        fun fromJson(obj: JSONObject?): WeatherContext = if (obj == null) WeatherContext() else WeatherContext(
            mode = obj.optString("mode", "sunny"),
            label = obj.optString("label", "晴朗微风 21°C"),
            city = obj.optString("city", "上海")
        )
    }
}

data class ExploreStop(
    val time: String,
    val name: String,
    val dist: String,
    val note: String
) {
    companion object {
        fun fromJson(obj: JSONObject) = ExploreStop(
            time = obj.optString("time"),
            name = obj.optString("name"),
            dist = obj.optString("dist"),
            note = obj.optString("note")
        )
    }
}

data class ExplorePreset(
    val id: String,
    val title: String,
    val duration: String,
    val tag: String,
    val stops: List<ExploreStop>
) {
    companion object {
        fun fromJson(obj: JSONObject) = ExplorePreset(
            id = obj.optString("id"),
            title = obj.optString("title"),
            duration = obj.optString("duration"),
            tag = obj.optString("tag"),
            stops = obj.optJSONArray("stops").toList { ExploreStop.fromJson(it) }
        )
    }
}

data class ExploreMeta(
    val tripTitle: String,
    val duration: String,
    val note: String,
    val stops: List<ExploreStop>,
    val currentStopIdx: Int = 0
) {
    companion object {
        fun fromJson(obj: JSONObject?) = obj?.let {
            ExploreMeta(
                tripTitle = it.optString("tripTitle"),
                duration = it.optString("duration"),
                note = it.optString("note"),
                stops = it.optJSONArray("stops").toList { s -> ExploreStop.fromJson(s) },
                currentStopIdx = it.optInt("currentStopIdx", 0)
            )
        }
    }
}

data class ReminderStrip(
    val text: String,
    val dismissed: Boolean = false
) {
    companion object {
        fun fromJson(obj: JSONObject?) = obj?.let {
            ReminderStrip(text = it.optString("text"), dismissed = it.optBoolean("dismissed", false))
        }
    }
}

data class GapSpot(
    val time: String,
    val title: String,
    val subtitle: String,
    val detail: String,
    val durationMinutes: Int? = null
) {
    companion object {
        fun fromJson(obj: JSONObject) = GapSpot(
            time = obj.optString("time", "12:00"),
            title = obj.optString("title"),
            subtitle = obj.optString("subtitle"),
            detail = obj.optString("detail"),
            durationMinutes = obj.optInt("durationMinutes").takeIf { it > 0 }
        )
    }
}

data class ConnectorInfo(
    val connectorState: ConnectorState,
    val label: String,
    val actionLabel: String? = null,
    val gapHours: String? = null,
    val gapSpots: List<GapSpot> = emptyList()
) {
    companion object {
        fun fromJson(obj: JSONObject?) = obj?.let {
            ConnectorInfo(
                connectorState = ConnectorState.parse(it.optString("connectorState")),
                label = it.optString("label"),
                actionLabel = it.optString("actionLabel").takeIf { s -> s.isNotBlank() },
                gapHours = it.optString("gapHours").takeIf { s -> s.isNotBlank() },
                gapSpots = it.optJSONArray("gapSpots").toList { g -> GapSpot.fromJson(g) }
            )
        }
    }
}

data class TravelPlan(
    val id: String,
    val carrier: String,
    val mode: String,
    val modeText: String,
    val price: Int,
    val punctuality: Int,
    val dep: String,
    val arr: String,
    val depHub: String,
    val arrHub: String,
    val seat: String,
    val gate: String,
    val note: String
) {
    companion object {
        fun fromJson(obj: JSONObject) = TravelPlan(
            id = obj.optString("id"),
            carrier = obj.optString("carrier"),
            mode = obj.optString("mode", "flight"),
            modeText = obj.optString("modeText"),
            price = obj.optInt("price"),
            punctuality = obj.optInt("punctuality", 95),
            dep = obj.optString("dep"),
            arr = obj.optString("arr"),
            depHub = obj.optString("depHub"),
            arrHub = obj.optString("arrHub"),
            seat = obj.optString("seat"),
            gate = obj.optString("gate"),
            note = obj.optString("note")
        )
    }
}

data class DiningFriend(
    val name: String,
    val short: String,
    val x: Float,
    val y: Float
) {
    companion object {
        fun fromJson(obj: JSONObject) = DiningFriend(
            name = obj.optString("name"),
            short = obj.optString("short"),
            x = obj.optDouble("x", 0.0).toFloat(),
            y = obj.optDouble("y", 0.0).toFloat()
        )
    }
}

data class DiningPlan(
    val id: String,
    val name: String,
    val match: Int,
    val price: Int,
    val queue: String,
    val room: String,
    val address: String,
    val avgCommute: Int,
    val targetX: Float,
    val targetY: Float,
    val commutes: List<Int>,
    val note: String
) {
    companion object {
        fun fromJson(obj: JSONObject): DiningPlan {
            val target = obj.optJSONObject("target")
            val commArr = obj.optJSONArray("commutes")
            val comm = if (commArr != null) List(commArr.length()) { commArr.optInt(it, 25) } else listOf(30, 20, 15, 28)
            return DiningPlan(
                id = obj.optString("id"),
                name = obj.optString("name"),
                match = obj.optInt("match", 92),
                price = obj.optInt("price", 300),
                queue = obj.optString("queue"),
                room = obj.optString("room"),
                address = obj.optString("address"),
                avgCommute = obj.optInt("avgCommute", 25),
                targetX = target?.optDouble("x", 180.0)?.toFloat() ?: 180f,
                targetY = target?.optDouble("y", 90.0)?.toFloat() ?: 90f,
                commutes = comm,
                note = obj.optString("note")
            )
        }
    }
}

data class PricePoint(
    val date: String,
    val price: Int,
    val tag: String
) {
    companion object {
        fun fromJson(obj: JSONObject) = PricePoint(
            date = obj.optString("date"),
            price = obj.optInt("price"),
            tag = obj.optString("tag")
        )
    }
}

data class ChecklistItem(
    val id: String,
    val floor: String,
    val name: String,
    val price: String,
    val save: String,
    val done: Boolean = false
) {
    companion object {
        fun fromJson(obj: JSONObject) = ChecklistItem(
            id = obj.optString("id"),
            floor = obj.optString("floor"),
            name = obj.optString("name"),
            price = obj.optString("price"),
            save = obj.optString("save"),
            done = obj.optBoolean("done", false)
        )
    }
}

data class NoteItem(
    val text: String,
    val done: Boolean = false
) {
    companion object {
        fun fromJson(obj: JSONObject) = NoteItem(
            text = obj.optString("text"),
            done = obj.optBoolean("done", false)
        )
    }
}

data class ArchivedCard(
    val id: String,
    val time: String,
    val endTime: String,
    val title: String,
    val summary: String,
    val sourceWorkId: String? = null,
    val scheduledAt: String? = null
) {
    companion object {
        fun fromJson(obj: JSONObject) = ArchivedCard(
            id = obj.optString("id"),
            time = obj.optString("time"),
            endTime = obj.optString("endTime"),
            title = obj.optString("title"),
            summary = obj.optString("summary"),
            sourceWorkId = obj.optionalText("sourceWorkId"),
            scheduledAt = obj.optionalText("scheduledAt")
        )
    }
}

data class AuraCard(
    val id: String,
    val category: AuraCategory,
    val themeKey: String,
    val lifecycleStage: LifecycleStage,
    val time: String,
    val title: String,
    val subtitle: String,
    val badge: String,
    val direction: String = "outbound",
    val isMisjudgment: Boolean = false,
    val confidence: Float = 0.95f,
    val originalText: String = "",
    val sourceEventId: String? = null,
    val sourceWorkId: String? = null,
    val sourceTaskId: String? = null,
    val scheduledAt: String? = null,
    val capturedAt: String? = null,
    val completedAt: String? = null,
    val reminderStrip: ReminderStrip? = null,
    val connectorToNext: ConnectorInfo? = null,
    val entities: List<String> = emptyList(),
    val selectedPlanIdx: Int = 0,
    val travelPlans: List<TravelPlan> = emptyList(),
    val diningPlans: List<DiningPlan> = emptyList(),
    val priceHistory: List<PricePoint> = emptyList(),
    val checklist: List<ChecklistItem> = emptyList(),
    val notes: List<NoteItem> = emptyList(),
    val exploreMeta: ExploreMeta? = null,
    val detailBlocks: List<String> = emptyList(),
    val endsAt: String? = null,
    val allDay: Boolean = false
) {
    fun toSummaryJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("time", time)
        .put("category", category.name)
        .put("lifecycleStage", lifecycleStage.name)
        .put("connectorState", connectorToNext?.connectorState?.name ?: "END")
        .put("hasReminderStrip", reminderStrip != null && !reminderStrip.dismissed)
        .put("swipeDismissible", isMisjudgment || lifecycleStage == LifecycleStage.PROPOSAL)

    companion object {
        fun fromJson(obj: JSONObject): AuraCard {
            val category = AuraCategory.parse(obj.optString("category"))
            val timing = obj.optJSONObject("timing")
            val plansArr = obj.optJSONArray("plans")
            val travelPlans = if (category == AuraCategory.TRAVEL) plansArr.toList { TravelPlan.fromJson(it) } else emptyList()
            val diningPlans = if (category == AuraCategory.DINING) plansArr.toList { DiningPlan.fromJson(it) } else emptyList()
            return AuraCard(
                id = obj.optString("id"),
                category = category,
                themeKey = obj.optString("themeKey", category.themeKey),
                lifecycleStage = LifecycleStage.parse(obj.optString("lifecycleStage")),
                time = obj.optString("time"),
                title = obj.optString("title"),
                subtitle = obj.optString("subtitle"),
                badge = obj.optString("badge"),
                direction = obj.optString("direction", "outbound"),
                isMisjudgment = obj.optBoolean("isMisjudgment", false),
                confidence = obj.optDouble("confidence", 0.95).toFloat(),
                originalText = obj.optString("originalText"),
                sourceEventId = obj.optionalText("sourceEventId"),
                sourceWorkId = obj.optionalText("sourceWorkId"),
                sourceTaskId = obj.optionalText("sourceTaskId"),
                scheduledAt = timing?.optionalText("startAt") ?: obj.optionalText("scheduledAt"),
                capturedAt = obj.optionalText("capturedAt"),
                completedAt = obj.optionalText("completedAt"),
                reminderStrip = ReminderStrip.fromJson(obj.optJSONObject("reminderStrip")),
                connectorToNext = ConnectorInfo.fromJson(obj.optJSONObject("connectorToNext")),
                entities = obj.optJSONArray("entities").toStringList(),
                selectedPlanIdx = obj.optInt("selectedPlanIdx", 0),
                travelPlans = travelPlans,
                diningPlans = diningPlans,
                priceHistory = obj.optJSONArray("priceHistory").toList { PricePoint.fromJson(it) },
                checklist = obj.optJSONArray("checklist").toList { ChecklistItem.fromJson(it) },
                notes = obj.optJSONArray("notes").toList { NoteItem.fromJson(it) },
                exploreMeta = ExploreMeta.fromJson(obj.optJSONObject("exploreMeta")),
                detailBlocks = obj.optJSONArray("detailBlocks").toStringList(),
                endsAt = timing?.optionalText("endAt") ?: obj.optionalText("endsAt"),
                allDay = timing?.optBoolean("allDay") ?: obj.optBoolean("allDay")
            )
        }
    }
}

data class AuraFlowState(
    val protocolVersion: String = "2.4.0",
    val dayMode: DayMode = DayMode.EMPTY_EXPLORE,
    val dayModeOverride: String = "AUTO",
    val showcaseMode: Boolean = false,
    val activeTheme: String = "explore",
    val weatherContext: WeatherContext = WeatherContext(),
    val exploreIndex: Int = 0,
    val explorePresets: Map<String, List<ExplorePreset>> = AuraFlowEngine.defaultExplorePresets(),
    val diningFriends: List<DiningFriend> = AuraFlowEngine.defaultDiningFriends(),
    val archivedEvents: List<ArchivedCard> = emptyList(),
    val events: List<AuraCard> = emptyList(),
    val crossDayRelay: AuraCard? = null,
    val negativeFeedbackCount: Int = 0,
    val realEventCount: Int = 0,
    val dailyFootprint: String? = null,
    val dismissedIds: Set<String> = emptySet(),
    val wireJson: String? = null,
    val timelineIds: List<String>? = null,
    val focusId: String? = null,
    val focusAt: String? = null,
    val focusInProgress: Boolean = false,
    val localContext: HomeLocalContext? = null,
    val simulatedAt: String? = null,
    val focusProgress: Float = 0f,
    val fromServer: Boolean = false
) {
    val activeExplorePreset: ExplorePreset
        get() {
            val list = explorePresets[weatherContext.mode].orEmpty().ifEmpty { AuraFlowEngine.defaultExplorePresets()["sunny"]!! }
            return list[exploreIndex.mod(list.size)]
        }

    fun findCard(id: String?): AuraCard? {
        if (id == null) return null
        if (crossDayRelay?.id == id) return crossDayRelay
        return events.find { it.id == id }
    }

    fun topConnectorState(): String {
        if (dayMode != DayMode.ACTIVE_TIMELINE) return "IDLE"
        val states = events.mapNotNull { it.connectorToNext?.connectorState }
        return when {
            ConnectorState.ALERT_PULSE in states -> "ALERT_PULSE"
            ConnectorState.CONFLICT_DASHED in states -> "CONFLICT_DASHED"
            ConnectorState.GAP_SUGGESTION in states -> "GAP_SUGGESTION"
            else -> "NORMAL_SOLID"
        }
    }

    fun toProtocolJson(activeDetailId: String? = null): String {
        val activeCard = findCard(activeDetailId)
        val root = JSONObject()
        if (activeCard != null) {
            root.put("protocolVersion", protocolVersion)
                .put("dayMode", dayMode.name)
                .put("activeView", "L1_DETAIL_SHEET")
                .put("eventId", activeCard.id)
                .put("category", activeCard.category.name)
                .put("lifecycleStage", activeCard.lifecycleStage.name)
                .put("themeKey", activeCard.themeKey)
                .put("connectorState", activeCard.connectorToNext?.connectorState?.name ?: "NONE")
                .put("detailBlocks", JSONArray(activeCard.detailBlocks))
            if (activeCard.checklist.isNotEmpty()) {
                val done = activeCard.checklist.count { it.done }
                root.put("checklistProgress", "$done/${activeCard.checklist.size}")
            }
            return root.toString(2)
        }
        root.put("protocolVersion", protocolVersion)
            .put("source", if (fromServer) "SERVER_SDUI" else "LOCAL_ENGINE")
            .put("dayMode", dayMode.name)
            .put("showcaseMode", showcaseMode)
            .put("weatherContext", weatherContext.toJson())
            .put("archivedCount", archivedEvents.size)
            .put("negativeFeedbackCount", negativeFeedbackCount)
            .put("timelineNodes", JSONArray(events.map { it.toSummaryJson() }))
        crossDayRelay?.let { relay ->
            root.put(
                "crossDayRelay",
                JSONObject()
                    .put("id", relay.id)
                    .put("lifecycleStage", relay.lifecycleStage.name)
                    .put("wakeWindow", "48h_BEFORE_RETURN")
            )
        }
        return root.toString(2)
    }

    companion object {
        fun fromJson(obj: JSONObject): AuraFlowState {
            obj.optJSONObject("nativeState")?.let { native ->
                val base = fromJson(native)
                val focus = obj.optJSONObject("nowFocusCard")
                val wire = JSONObject(obj.toString()).apply { remove("nativeState") }
                return base.copy(dayMode = DayMode.parse(obj.optString("dayMode")),
                    timelineIds = obj.optJSONArray("timelineEvents").toList { it.getString("eventId") },
                    focusId = focus?.optionalText("eventId"), focusAt = focus?.optionalText("countdownTargetIso"),
                    focusInProgress = focus?.optString("timeStatus") == "IN_PROGRESS",
                    localContext = obj.optJSONObject("homeContext")?.let(HomeLocalContext::fromJson),
                    simulatedAt = obj.optionalText("simulatedAt"),
                    focusProgress = focus?.optDouble("progressRatio", 0.0)?.toFloat() ?: 0f, wireJson = wire.toString(2))
            }
            val presetsObj = obj.optJSONObject("explorePresets")
            val presetsMap = mutableMapOf<String, List<ExplorePreset>>()
            if (presetsObj != null) {
                for (key in listOf("sunny", "rainy")) {
                    val arr = presetsObj.optJSONArray(key)
                    if (arr != null) presetsMap[key] = arr.toList { ExplorePreset.fromJson(it) }
                }
            }
            val friends = obj.optJSONArray("diningFriends").toList { DiningFriend.fromJson(it) }
                .ifEmpty { AuraFlowEngine.defaultDiningFriends() }
            val stats = obj.optJSONObject("stats")
            return AuraFlowState(
                protocolVersion = obj.optString("protocolVersion", "2.4.0"),
                dayMode = DayMode.parse(obj.optString("dayMode")),
                dayModeOverride = obj.optString("dayModeOverride", "AUTO"),
                showcaseMode = obj.optBoolean("showcaseMode", false),
                activeTheme = obj.optString("activeTheme", "travel"),
                weatherContext = WeatherContext.fromJson(obj.optJSONObject("weatherContext")),
                exploreIndex = obj.optInt("exploreIndex", 0),
                explorePresets = presetsMap.ifEmpty { AuraFlowEngine.defaultExplorePresets() },
                diningFriends = friends,
                archivedEvents = obj.optJSONArray("archivedEvents").toList { ArchivedCard.fromJson(it) },
                events = obj.optJSONArray("events").toList { AuraCard.fromJson(it) },
                crossDayRelay = obj.optJSONObject("crossDayRelay")?.let { AuraCard.fromJson(it) },
                negativeFeedbackCount = stats?.optInt("negativeFeedbackCount", 0) ?: 0,
                realEventCount = stats?.optInt("realEventCount", 0) ?: 0,
                dailyFootprint = obj.optString("dailyFootprint").takeIf { it.isNotBlank() && !obj.isNull("dailyFootprint") },
                dismissedIds = obj.optJSONArray("dismissedIds").toStringList().toSet(),
                wireJson = if (obj.optBoolean("showcaseMode")) obj.toString(2) else null,
                fromServer = true
            )
        }
    }
}

private fun <T> JSONArray?.toList(mapper: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return List(length()) { mapper(getJSONObject(it)) }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return List(length()) { optString(it) }
}

private fun JSONObject.optionalText(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
