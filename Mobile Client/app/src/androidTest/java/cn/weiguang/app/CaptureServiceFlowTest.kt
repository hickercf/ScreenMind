package cn.weiguang.app

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.weiguang.app.capture.CaptureRuntime
import cn.weiguang.app.data.LocalEvent
import com.chronocapsule.contract.ScreenContract
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CaptureServiceFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as WeiguangApp
    private val repo get() = app.repository
    private lateinit var ui: UiAutomation
    private val stages = mutableListOf<String>()
    private val observedEvents = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Test fun realServiceEventsOverlayPauseAndAgentDelivery() {
        org.junit.Assume.assumeTrue("Dedicated emulator runner required",
            InstrumentationRegistry.getArguments().getString("managedServiceCaptureTest") == "true")
        ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ui.serviceInfo = ui.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        ui.setOnAccessibilityEventListener { event ->
            if (event.packageName?.toString() == "cn.weiguang.capturefixture" && observedEvents.size < 240) {
                observedEvents.add("type=${event.eventType} window=${event.windowId} class=${event.className}")
            }
        }
        val out = File(app.getExternalFilesDir(null), "capture-service").apply { mkdirs() }
        val started = System.currentTimeMillis()
        var passed = false
        try {
            val work = runBlocking {
                repo.logout(); repo.setEndpoint("http://127.0.0.1:8765")
                repo.authenticate("service_" + System.nanoTime(), "Capture-test-123", true)
                assertEquals("mock", repo.agentSnapshot(null,null).getJSONObject("health").getString("provider"))
                repo.agentMutation("works", JSONObject().put("event_id", UUID.randomUUID().toString())
                    .put("title", "悬浮窗采集验证").put("subagent_id", "information")).getJSONObject("work")
            }
            val wid = work.getString("id")
            val account = requireNotNull(repo.session.value).accountKey
            runBlocking { repo.setCaptureTarget(wid, true) }
            instrumentation.sendStatus(2, android.os.Bundle().apply { putString("capture_stage", "enable_service") })
            waitFor("service connected") { CaptureRuntime.status.value.connected }
            launchFixture("CaptureFixtureActivity")
            waitFor("actual service screenshot and OCR", 60_000) { events(account).any { it.text.contains("A302") } }
            assertTrue(CaptureRuntime.status.value.screenshots > 0)
            assertTrue(events(account).all { !it.text.contains("ScreenMind", ignoreCase = true) && !it.text.contains("暂停") })
            stages += "actual_window_screenshot_and_ppocr_without_overlay_text"
            screenshot(out, "overlay-running")

            clickDescription("移动或收起采集悬浮窗")
            waitFor("overlay collapsed") { find(null,"暂停或继续屏幕采集") == null }
            clickDescription("移动或收起采集悬浮窗")
            waitFor("overlay expanded") { find(null,"暂停或继续屏幕采集") != null }
            stages += "overlay_collapse_and_expand"

            val firstCount = events(account).size
            val firstDuplicates = CaptureRuntime.status.value.duplicates
            click("重复")
            waitFor("unchanged screen deduplicated", 30_000) { CaptureRuntime.status.value.duplicates > firstDuplicates }
            assertEquals(firstCount, events(account).size)
            stages += "unchanged_content_deduplicated"

            click("新消息")
            waitFor("content-change captures new message", 30_000) { events(account).any { it.text.contains("不来了") } }
            assertEquals(firstCount + 1, events(account).size)
            val oldGrant = runBlocking { requireNotNull(repo.captureGrant(account)) }
            val payload = ScreenContract.Payload.fromJson(JSONObject(requireNotNull(events(account).first().screenJson)))
            stages += "content_changed_event_captured_without_switching_window"

            clickDescription("暂停或继续屏幕采集")
            waitFor("overlay pause persisted") { runBlocking { repo.captureGrant(account)?.paused == true } }
            val pausedRequests = CaptureRuntime.status.value.requests
            val pausedCount = events(account).size
            click("滚动")
            Thread.sleep(2300)
            assertEquals(pausedRequests, CaptureRuntime.status.value.requests)
            assertEquals(pausedCount, events(account).size)
            screenshot(out, "overlay-paused")
            stages += "overlay_pause_blocks_screenshots_and_new_evidence"

            clickDescription("暂停或继续屏幕采集")
            waitFor("resume captures latest visible content", 30_000) { events(account).any { it.text.contains("B201") } }
            assertNotEquals(oldGrant.token, runBlocking { repo.captureGrant(account)?.token })
            try {
                runBlocking { repo.saveCapture(payload, account, wid, upload=true, requireTarget=true, expectedCaptureToken=oldGrant.token) }
                fail("Old in-flight authorization must be rejected even after resuming the same work")
            } catch (_: IllegalStateException) { }
            stages += "resume_latest_frame_and_reject_old_authorization"

            // Reset the synthetic screen and scroll while recording, proving the scroll trigger itself.
            launchFixture("CaptureFixtureActivity")
            Thread.sleep(1800)
            val scrollScreens = CaptureRuntime.status.value.screenshots
            click("滚动")
            waitFor("scroll event screenshot", 30_000) { CaptureRuntime.status.value.screenshots > scrollScreens }
            stages += "view_scrolled_event_triggers_screenshot"

            click("切聊天")
            waitFor("same-window unrelated chat processed locally", 30_000) { events(account).any { it.text.contains("C909") } }
            runBlocking { repo.sync() }
            assertTrue(events(account).all { it.syncState == "local" && !it.consentToUpload && it.agentWorkId == null })
            val emptySnapshot = runBlocking { repo.agentSnapshot(null, wid) }
            assertEquals(0, emptySnapshot.getJSONArray("tasks").length())
            stages += "different_conversation_in_same_window_stays_local_and_unassigned"
            click("切聊天")
            val blockedCount = events(account).size
            launchFixture("BlockedBrowserActivity")
            Thread.sleep(2200)
            // A callback from the preceding chat may finish during the transition; sample after it settles.
            val beforeBlocked = CaptureRuntime.status.value.requests
            Thread.sleep(1800)
            assertEquals(beforeBlocked, CaptureRuntime.status.value.requests)
            assertEquals(blockedCount, events(account).size)
            assertTrue(events(account).none { it.text.contains("合成网页") })
            stages += "non_chat_window_excluded"

            val beforeReturn = CaptureRuntime.status.value.duplicates
            launchFixture("CaptureFixtureActivity")
            waitFor("chat becomes active again", 30_000) { CaptureRuntime.status.value.duplicates > beforeReturn }
            clickDescription("暂停或继续屏幕采集")
            waitFor("pause before changing pages") { runBlocking { repo.captureGrant(account)?.paused == true } }
            launchFixture("BlockedBrowserActivity")
            Thread.sleep(1200)
            val pausedBrowserRequests = CaptureRuntime.status.value.requests
            clickDescription("暂停或继续屏幕采集")
            waitFor("resume while non-chat page visible") { runBlocking { repo.captureGrant(account)?.paused == false } }
            Thread.sleep(1800)
            assertEquals(pausedBrowserRequests, CaptureRuntime.status.value.requests)
            assertEquals(blockedCount, events(account).size)
            stages += "resume_after_paused_page_change_keeps_non_chat_excluded"

            val beforeSecureReturn = CaptureRuntime.status.value.duplicates
            launchFixture("CaptureFixtureActivity")
            waitFor("return from paused non-chat page", 30_000) { CaptureRuntime.status.value.duplicates > beforeSecureReturn }
            val secureCount = events(account).size
            click("安全页")
            waitFor("secure window refuses screenshot", 30_000) { CaptureRuntime.status.value.detail.contains("未采集成功") }
            val secureCallbacks = CaptureRuntime.status.value.screenshots
            click("重复")
            Thread.sleep(1800)
            assertEquals(secureCallbacks, CaptureRuntime.status.value.screenshots)
            assertEquals(secureCount, events(account).size)
            stages += "secure_window_not_captured_or_uploaded"

            val approved = events(account).first { it.text.contains("A302") }
            runBlocking { repo.uploadEvent(approved.eventId) }
            waitFor("approved event synced", 30_000) {
                runBlocking { repo.sync() }
                events(account).any { it.eventId == approved.eventId && it.syncState == "synced" }
            }
            val synced = events(account).first { it.eventId == approved.eventId }
            val agentTask = runBlocking { repo.agentMutation("tasks", JSONObject()
                .put("request_id", UUID.randomUUID().toString()).put("work_id", wid).put("instruction", "整理所选测试线索")
                .put("events", org.json.JSONArray().put(JSONObject().put("event_id", synced.eventId)
                    .put("revision", synced.revision)))) }.getJSONObject("task")
            val snapshot = runBlocking { repo.agentSnapshot(agentTask.getString("id"), wid) }
            assertEquals(1, snapshot.getJSONArray("tasks").length())
            assertTrue(events(account).filter { it.eventId != synced.eventId }.all { it.syncState == "local" })
            assertEquals(0, snapshot.getJSONObject("work").getInt("memory_version"))
            stages += "only_explicitly_approved_evidence_synced_and_selected"

            clickDescription("结束屏幕采集")
            waitFor("stop removes authorization") { runBlocking { repo.captureGrant(account) == null } }
            waitFor("overlay removed") { find(null, "结束屏幕采集") == null }
            stages += "overlay_stop_revokes_capture_and_removes_window"
            passed = true
        } finally {
            if (!passed) screenshot(out, "failure")
            val status = CaptureRuntime.status.value
            File(out,"accessibility-events.json").writeText(org.json.JSONArray(observedEvents.toList()).toString(2))
            File(out, "report.json").writeText(JSONObject().put("passed", passed).put("synthetic", true)
                .put("provider", "mock").put("stages", org.json.JSONArray(stages))
                .put("requests_started", status.requests)
                .put("screenshots_taken", status.screenshots).put("saved", status.saved).put("duplicates", status.duplicates)
                .put("last_status", status.detail)
                .put("windows", org.json.JSONArray(ui.windows.map { window -> JSONObject()
                    .put("id",window.id).put("type",window.type).put("active",window.isActive).put("focused",window.isFocused)
                    .put("package",window.root?.packageName?.toString()) }))
                .put("seconds", (System.currentTimeMillis()-started)/1000.0).toString(2))
            runBlocking { if (repo.session.value != null) repo.setCaptureTarget(null, false) }
        }
    }

    private fun events(account: String): List<LocalEvent> = runBlocking { app.database.events().all(account) }
    private fun launchFixture(activity: String) {
        app.startActivity(Intent().setClassName("cn.weiguang.capturefixture", "cn.weiguang.capturefixture.$activity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }
    private fun click(text: String) { waitFor("button $text") { find(text,null)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true } }
    private fun clickDescription(description: String) { waitFor("control $description") { find(null,description)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true } }
    private fun find(text: String?, description: String?): AccessibilityNodeInfo? {
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        ui.windows.mapNotNull { it.root }.forEach { nodes.addLast(it) }
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            if ((text != null && node.text?.toString() == text) ||
                (description != null && node.contentDescription?.toString() == description)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { nodes.addLast(it) }
        }
        return null
    }
    private fun waitFor(name: String, timeout: Long = 15_000, check: () -> Boolean) {
        val until = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < until) { if (check()) return; Thread.sleep(150) }
        fail("Timed out: $name; service=${CaptureRuntime.status.value}")
    }
    private fun screenshot(out: File, name: String) {
        val image = ui.takeScreenshot() ?: return
        File(out,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        image.recycle()
    }
}
