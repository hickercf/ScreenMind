package cn.weiguang.app

import cn.weiguang.app.data.LocalEvent
import cn.weiguang.app.data.Validation
import org.junit.Assert.*
import org.junit.Test

class ContractTest {
    @Test fun eventSeparatesSourceAndTypeAndHasNoClientOwnerClaim() {
        val event = LocalEvent(accountKey = "server|user", text = "明天讨论", sourceKind = "screenshot_ocr", eventType = "message")
        val payload = event.toPayload()
        assertEquals("message", payload.getString("event_type"))
        assertEquals("screenshot_ocr", payload.getString("source_kind"))
        assertTrue(payload.isNull("occurred_at"))
        assertFalse(payload.has("user_id"))
        assertFalse(payload.has("accountKey"))
        assertEquals("明天讨论", payload.getJSONObject("payload").getString("text"))
    }
    @Test fun invalidCredentialsAndBlankPayloadAreRejected() {
        assertNotNull(Validation.credentials("ab", "12345678"))
        assertNotNull(Validation.credentials("test_user", "123"))
        assertNotNull(Validation.eventText("   "))
        assertNotNull(Validation.eventText("字".repeat(10001)))
        assertNull(Validation.credentials("test_user", "12345678"))
        assertNull(Validation.eventText("确认过的文字"))
    }
    @Test fun occurrenceTimeAndCaptureTimeRemainSeparate() {
        val payload = LocalEvent(accountKey = "server|user", text = "上周的消息", capturedAt = "2026-11-08T10:00:00Z", occurredAt = "2026-11-01T10:00:00Z").toPayload()
        assertNotEquals(payload.getString("captured_at"), payload.getString("occurred_at"))
    }
}
