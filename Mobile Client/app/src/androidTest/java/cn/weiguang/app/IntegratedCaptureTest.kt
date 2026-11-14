package cn.weiguang.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.weiguang.app.capture.ChronoCapture
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

/** Run with scripts/test-integration.py: it removes/restores adb forwarding on the two status markers. */
@RunWith(AndroidJUnit4::class)
class IntegratedCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun realOcrOfflineOutboxAndConfirmedMemory() {
        org.junit.Assume.assumeTrue("Use the managed outage runner", InstrumentationRegistry.getArguments().getString("managedCaptureTest") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = compose.activity.application as WeiguangApp
        val repo = app.repository
        val work = runBlocking {
            repo.logout()
            repo.setEndpoint("http://127.0.0.1:8765")
            repo.authenticate("capture_" + System.nanoTime(), "Capture-test-123", true)
            assertEquals("mock", repo.agentSnapshot(null,null).getJSONObject("health").getString("provider"))
            repo.agentMutation("works", JSONObject().put("event_id", UUID.randomUUID().toString())
                .put("title", "模拟器三端联调").put("subagent_id", "information")).getJSONObject("work")
        }
        val wid = work.getString("id")
        val account = requireNotNull(repo.session.value).accountKey
        val bitmap = Bitmap.createBitmap(1080,1920,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=56f }
        canvas.drawText("12:00",60f,70f,paint)
        canvas.drawText("小林",220f,225f,paint)
        paint.textSize=42f
        canvas.drawText("12:00",440f,410f,paint)
        paint.textSize=56f
        canvas.drawText("周五在A302讨论项目",110f,610f,paint)
        canvas.drawText("记得带电脑",110f,800f,paint)
        val screenshots = File(app.getExternalFilesDir(null),"screenshots").apply { mkdirs() }
        File(screenshots,"integrated-input.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        val payload = runBlocking { ChronoCapture.recognize(app,bitmap,"com.tencent.mm","微信") }
        bitmap.recycle()
        assertTrue(payload.blocks.toString(),payload.blocks.any { it.text.contains("A302") })
        assertTrue(payload.blocks.all { it.bbox != null && it.speaker != null })
        assertTrue(payload.blocks.any { it.messageAt != null })
        runBlocking { repo.setCaptureTarget(wid,true) }

        // The host runner closes the actual TCP forward, not a fake transport or a synthetic error.
        instrumentation.sendStatus(2,Bundle().apply { putString("integration_stage","disconnect") })
        Thread.sleep(1500)
        val event = runBlocking {
            requireNotNull(ChronoCapture.save(app,payload,account,wid,automatic=true))
        }
        assertNull(runBlocking { ChronoCapture.save(app,payload,account,wid,automatic=true) })
        try { runBlocking { repo.sync() }; fail("Expected real offline request failure") }
        catch (_: IOException) { /* Durable evidence and stable task request must survive. */ }
        val pending = runBlocking { app.database.events().get(account,event.eventId)!! }
        assertNotNull(pending.screenJson)
        assertNotNull(pending.agentRequestId)
        assertNull(pending.agentTaskId)
        assertEquals("failed",pending.syncState)
        instrumentation.sendStatus(2,Bundle().apply { putString("integration_stage","reconnect") })
        Thread.sleep(1500)
        runBlocking { repo.sync() }
        val sent = runBlocking { app.database.events().get(account,event.eventId)!! }
        assertEquals(pending.agentRequestId,sent.agentRequestId)
        assertEquals("synced",sent.syncState)
        val tid = requireNotNull(sent.agentTaskId)
        // Repeated sync is idempotent even after the model task has been accepted.
        runBlocking { repo.sync() }
        assertEquals(tid,runBlocking { app.database.events().get(account,event.eventId)!!.agentTaskId })
        var snapshot = runBlocking { repo.agentSnapshot(tid,wid) }
        val deadline = System.currentTimeMillis()+20000
        while (snapshot.getJSONObject("task").optString("status") in listOf("queued","running") && System.currentTimeMillis()<deadline) {
            Thread.sleep(200)
            snapshot = runBlocking { repo.agentSnapshot(tid,wid) }
        }
        assertEquals(1,snapshot.getJSONArray("tasks").length())
        val screen = snapshot.getJSONObject("task").getJSONObject("memory_context").getJSONArray("screens").getJSONObject(0)
        assertEquals("com.tencent.mm",screen.getString("app_package"))
        val block = screen.getJSONArray("blocks").getJSONObject(0)
        assertEquals(payload.blocks[0].speaker,block.getString("speaker"))
        assertEquals(payload.blocks[0].messageAt,block.getString("message_at"))
        assertEquals(4,block.getJSONArray("bbox").length())
        assertEquals(0,snapshot.getJSONObject("work").getInt("memory_version"))

        compose.waitUntil(30000) { compose.onAllNodesWithTag("open-agent").fetchSemanticsNodes().isNotEmpty() }
        ready()
        compose.onNodeWithTag("open-agent").performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("模拟器三端联调").fetchSemanticsNodes().isNotEmpty() }
        ready()
        compose.onNodeWithText("模拟器三端联调").performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithTag("agent-task-$tid").fetchSemanticsNodes().isNotEmpty() }
        ready()
        compose.onNodeWithTag("agent-task-$tid").performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithTag("agent-accept-state").fetchSemanticsNodes().isNotEmpty() }
        ready()
        compose.onNodeWithTag("agent-accept-state").performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("已确认状态 v1",substring=true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1,runBlocking { repo.agentSnapshot(null,wid) }.getJSONObject("work").getInt("memory_version"))
        compose.onNodeWithTag("capture-toggle").performScrollTo().performClick()
        ready()
        assertNull(runBlocking { repo.captureTarget(account) })
        compose.onNodeWithTag("agent-state-version").performScrollTo()
        instrumentation.uiAutomation.takeScreenshot().let { image ->
            File(screenshots,"integrated-confirmed.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            image.recycle()
        }
        instrumentation.sendStatus(2,Bundle().apply { putString("integration_stage","confirmed") })
    }

    private fun ready() { compose.waitUntil(30000) { compose.onAllNodesWithTag("operation-progress").fetchSemanticsNodes().isEmpty() } }
}
