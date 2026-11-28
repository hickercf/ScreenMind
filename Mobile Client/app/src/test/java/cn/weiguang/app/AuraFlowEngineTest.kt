package cn.weiguang.app

import cn.weiguang.app.data.LocalEvent
import cn.weiguang.app.home.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class AuraFlowEngineTest {
    @Test fun partyUsesConfirmedDateAndKeepsUnresolvedDateUnscheduled() {
        val thursday = ZonedDateTime.parse("2026-11-26T16:30:00+08:00")
        fun agenda(scheduledAt: String?): HomeAgenda {
            val plan = JSONObject().put("title", "聚会").put("time", "周六 18:30")
            val state = JSONObject().put("plan", plan).put("scheduled_at", scheduledAt ?: JSONObject.NULL)
            val work = JSONObject().put("id", "party").put("state", state)
            return AuraFlowEngine.synthesize(emptyList(), emptyList(), listOf(work), LocalAuraMutationState()).agenda(thursday)
        }
        val dated = agenda("2026-11-28T18:30:00+08:00")
        assertTrue(dated.today.isEmpty())
        assertEquals("2026-11-28", dated.later.single().date.toString())
        assertEquals("18:30", dated.later.single().card.time)
        val undated = agenda(null)
        assertTrue(undated.today.isEmpty())
        assertEquals("日期待确认", undated.unscheduled.single().card.time)
        assertNull(undated.unscheduled.single().start(thursday.zone))
    }

    @Test fun cancelledChatDoesNotBorrowTheReplacementTime() {
        val cancelled = LocalEvent("test", eventId = "cancelled", sourceKind = "screenshot_ocr",
            eventType = "message", text = "周日那顿先取消哈\n我周一19:15可以")
        val unchanged = cancelled.copy(eventId = "unchanged", text = "周日18:30的聚会不能取消了")
        val explicitSchedule = cancelled.copy(eventId = "rescheduled", eventType = "schedule",
            occurredAt = "2026-11-30T11:15:00Z")
        val state = AuraFlowEngine.synthesize(listOf(cancelled, unchanged, explicitSchedule),
            emptyList(), emptyList(), LocalAuraMutationState())
        assertEquals(setOf("evt_unchanged", "evt_rescheduled"), state.events.map { it.id }.toSet())
    }

    @Test fun emptyDayDispatchesExploreAndAdoptsToTimeline() {
        val state = AuraFlowEngine.synthesize(emptyList(), emptyList(), emptyList(), LocalAuraMutationState())
        assertEquals(DayMode.EMPTY_EXPLORE, state.dayMode)
        assertEquals("sunny", state.weatherContext.mode)
        assertEquals(3, state.activeExplorePreset.stops.size)
    }

    @Test fun realCluesClassifyConnectorsAutoVerifyAndRelay() {
        val events = listOf(
            LocalEvent("srv|u1", eventId = "e1", sourceKind = "calendar", eventType = "schedule", text = "10:00 项目晨会需求对齐"),
            LocalEvent("srv|u1", eventId = "e2", sourceKind = "screenshot_ocr", eventType = "message", text = "周五14:30飞上海出差，周日回"),
            LocalEvent("srv|u1", eventId = "e3", sourceKind = "screenshot_ocr", eventType = "message", text = "15:00 外滩鹿园4人聚餐"),
            LocalEvent("srv|u1", eventId = "e4", sourceKind = "screenshot_ocr", eventType = "message", text = "20:00 恒隆广场买伴手礼领券")
        )
        val state = AuraFlowEngine.synthesize(events, emptyList(), emptyList(), LocalAuraMutationState())
        assertEquals(DayMode.ACTIVE_TIMELINE, state.dayMode)
        assertEquals(listOf(AuraCategory.OFFICE, AuraCategory.TRAVEL, AuraCategory.DINING, AuraCategory.SHOPPING), state.events.map { it.category })
        assertEquals(ConnectorState.GAP_SUGGESTION, state.events[0].connectorToNext?.connectorState)
        assertEquals(ConnectorState.CONFLICT_DASHED, state.events[1].connectorToNext?.connectorState)
        assertNotNull(state.crossDayRelay)
        assertEquals("return", state.crossDayRelay?.direction)
    }

    @Test fun jokeMisjudgmentAndZeroManualTicketConfirm() {
        val events = listOf(
            LocalEvent("srv|u1", eventId = "e1", sourceKind = "screenshot_ocr", eventType = "message", text = "走，明天09:00飞巴黎喝咖啡！"),
            LocalEvent("srv|u1", eventId = "e2", sourceKind = "screenshot_ocr", eventType = "message", text = "14:30 乘 MU5102 飞上海出差"),
            LocalEvent("srv|u1", eventId = "e3", sourceKind = "screenshot_ocr", eventType = "message", text = "航旅纵横：MU5102 已出票，登机口 B24")
        )
        val state = AuraFlowEngine.synthesize(events, emptyList(), emptyList(), LocalAuraMutationState())
        val joke = state.events.find { it.id == "evt_e1" }
        assertNotNull(joke)
        assertTrue(joke!!.isMisjudgment)

        val travel = state.events.find { it.id == "evt_e2" }
        assertNotNull(travel)
        assertEquals(LifecycleStage.CONFIRMED, travel!!.lifecycleStage)
        assertEquals("已出票", travel.badge)
    }

    @Test fun agendaUsesTheOccurrenceDateAndCaptureDayForRelativePlans() {
        val now = java.time.ZonedDateTime.parse("2026-11-23T14:00:00+08:00[Asia/Shanghai]")
        fun event(id: String, text: String, at: String? = null) = LocalEvent("test", eventId = id,
            text = text, occurredAt = at, capturedAt = "2026-11-23T01:00:00Z")
        val source = listOf(event("am", "上午10点开会"), event("pm", "项目评审", "2026-11-23T07:00:00Z"),
            event("trip", "下周五去成都，周日回"), event("unknown", "聚餐待定"),
            event("other", "今天 16:00 乘 CA1234 出差"), event("ticket", "MU5102 已出票"))
        val state = AuraFlowEngine.synthesize(source, emptyList(), emptyList(), LocalAuraMutationState())
        val day = state.agenda(now)
        assertEquals(listOf("evt_am"), day.past.map { it.id })
        assertEquals("15:00", day.today.first().card.time)
        assertEquals(listOf("2026-12-04", "2026-12-06"), day.later.map { it.date.toString() })
        assertTrue(day.unscheduled.any { it.card.id == "evt_unknown" && it.clock == null })
        assertEquals(LifecycleStage.PROPOSAL, day.today.last().card.lifecycleStage)
        assertTrue(day.today.last().card.travelPlans.isEmpty())
        assertTrue(state.agenda(now.plusDays(1)).today.isEmpty())
    }

    @Test fun calendarMeetingStaysInFocusUntilItsEnd() {
        val now = java.time.ZonedDateTime.parse("2026-11-23T14:30:00+08:00[Asia/Shanghai]")
        val event = LocalEvent("test", text = "团队会议\n11-23 14:00 — 11-23 15:00", sourceKind = "calendar",
            occurredAt = "2026-11-23T06:00:00Z")
        val state = AuraFlowEngine.synthesize(listOf(event), emptyList(), emptyList(), LocalAuraMutationState())
        assertEquals(15, state.agenda(now).today.single().end?.hour)
        assertEquals(1, state.agenda(now.plusMinutes(30)).past.size)
    }

    @Test fun syncedAgendaUsesStructuredEndAndAllDayDates() {
        val now = java.time.ZonedDateTime.parse("2026-11-23T14:30:00+08:00[Asia/Shanghai]")
        val state = AuraFlowState.fromJson(JSONObject(
            """
            {"events": [
              {"id":"meeting","category":"OFFICE","lifecycleStage":"CONFIRMED","title":"评审会议",
               "timing":{"startAt":"2026-11-23T06:00:00Z","endAt":"2026-11-23T07:00:00Z","allDay":false}},
              {"id":"all-day","category":"OFFICE","lifecycleStage":"CONFIRMED","title":"展览",
               "timing":{"startAt":"2026-11-23T00:00:00+09:00","endAt":"2026-11-24T00:00:00+09:00","allDay":true}}
            ]}
            """.trimIndent()
        ))
        val today = state.agenda(now).today.associateBy { it.card.id }
        assertEquals(15, today.getValue("meeting").end?.hour)
        assertEquals("全天", today.getValue("all-day").card.time)
        assertEquals(null, today.getValue("all-day").start(now.zone))
        assertTrue(state.agenda(now.plusDays(1)).today.isEmpty())
    }

    @Test fun serverSduiJsonRoundTripParsesCleanly() {
        val raw = JSONObject(
            """
            {
              "protocolVersion": "2.4.0",
              "dayMode": "ACTIVE_TIMELINE",
              "showcaseMode": true,
              "activeTheme": "travel",
              "weatherContext": {"mode": "rainy", "label": "细雨微凉 15°C", "city": "上海"},
              "exploreIndex": 1,
              "archivedEvents": [{"id": "a1", "time": "09:00", "endTime": "09:45", "title": "晨会", "summary": "已完成"}],
              "events": [
                {
                  "id": "evt_1",
                  "category": "TRAVEL",
                  "themeKey": "travel",
                  "lifecycleStage": "PROPOSAL",
                  "time": "14:30",
                  "title": "北京 → 上海",
                  "subtitle": "待拍板",
                  "badge": "待拍板",
                  "connectorToNext": {"connectorState": "CONFLICT_DASHED", "label": "衔接紧张", "actionLabel": "一键顺延 30m"}
                }
              ],
              "stats": {"negativeFeedbackCount": 2, "realEventCount": 3}
            }
            """.trimIndent()
        )
        val parsed = AuraFlowState.fromJson(raw)
        assertTrue(parsed.fromServer)
        assertEquals(DayMode.ACTIVE_TIMELINE, parsed.dayMode)
        assertEquals("rainy", parsed.weatherContext.mode)
        assertEquals(1, parsed.archivedEvents.size)
        assertEquals(ConnectorState.CONFLICT_DASHED, parsed.events.first().connectorToNext?.connectorState)
        assertEquals(2, parsed.negativeFeedbackCount)
    }
}
