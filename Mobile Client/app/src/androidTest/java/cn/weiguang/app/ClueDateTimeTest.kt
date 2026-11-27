package cn.weiguang.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chronocapsule.contract.ScreenContract
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ClueDateTimeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val repo get() = (compose.activity.application as WeiguangApp).repository

    private fun payload(texts: List<String>) = ScreenContract.Payload(
        UUID.randomUUID().toString(), "time-check", "example.chat", "测试聊天", "工作讨论", Instant.now().toString(),
        texts.mapIndexed { index, text -> ScreenContract.Block("bubble-$index", "message", "我", text, null, listOf(0.1f, 0.2f, 0.8f, 0.3f)) },
    )

    @Test fun calendarReopensAtTheSavedTimeAndChangingOnlyTheDatePreservesTheScreenshot() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("dedicatedDateTest") == "true")
        val body = "周日带上讨论稿"
        val instant = LocalDateTime.of(2028, 9, 17, 19, 25, 42).atZone(ZoneId.systemDefault()).toInstant().toString()
        val original = runBlocking {
            repo.enterOffline()
            repo.saveCapture(payload(listOf(body)), requireNotNull(repo.session.value).accountKey, "private-work", occurredAt = instant)
        }
        for (theme in listOf("light", "dark")) {
            runBlocking { repo.setTheme(theme) }
            compose.onNodeWithText(body).assertIsDisplayed().performClick()
            click("发生于 2028年9月17日 19:25:42")
            compose.onNodeWithText("2028年9月17日").assertIsDisplayed()
            compose.onAllNodesWithText("September 2028").assertCountEquals(0)
            capture("date-$theme")
            click("下一步")
            compose.onNodeWithText("选择时间").assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("选择时间").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("Hour").assertCountEquals(0)
            capture("time-$theme")
            click("上一步")
            compose.onNodeWithText("2028年9月17日").assertIsDisplayed()
            click("下一步")
            click("确定")
            click("完成")
            compose.onNodeWithText(body).assertIsDisplayed()
            val saved = runBlocking { repo.database.events().get(original.accountKey, original.eventId) }
            assertEquals(instant, saved?.occurredAt)
            assertEquals(original.screenJson, saved?.screenJson)
            assertEquals(original.agentRequestId, saved?.agentRequestId)
        }
        compose.onNodeWithText(body).performClick()
        click("移除时间")
        compose.onNodeWithText("补充确定的日期与时间").assertExists()
        click("确认并保存")
        compose.onNodeWithText(body).assertIsDisplayed()
        val saved = runBlocking { requireNotNull(repo.database.events().get(original.accountKey, original.eventId)) }
        assertNull(saved.occurredAt)
        assertEquals(original.screenJson, saved.screenJson)
        assertEquals("private-work", saved.agentWorkId)
        runBlocking { repo.setTheme("light") }
    }

    @Test fun aLongOcrTranscriptCanKeepItsLayoutAndAnEditCannotCrossAccounts() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("dedicatedDateTest") == "true")
        runBlocking {
            repo.enterOffline()
            val account = requireNotNull(repo.session.value).accountKey
            val original = repo.saveCapture(payload(List(4) { "字".repeat(3_500) }), account, "private-work")
            val time = "2028-09-17T11:25:00Z"
            val updated = repo.saveEvent(original.text, original.sourceKind, original.eventType, false, original.eventId, time, expectedAccount = account)
            assertEquals(original.screenJson, updated.screenJson)
            assertEquals(time, updated.occurredAt)
            assertEquals(original.agentRequestId, updated.agentRequestId)
            val failure = runCatching { repo.saveEvent("来自别的账号", "manual", "note", false, original.eventId, expectedAccount = "another-account") }
            assertTrue(failure.exceptionOrNull() is IllegalStateException)
            assertEquals(updated, repo.database.events().get(account, original.eventId))
            val plain = repo.saveEvent("已核对的新文字", original.sourceKind, original.eventType, false, original.eventId, expectedAccount = account)
            assertNull(plain.screenJson)
            assertNull(plain.agentWorkId)
            assertEquals(time, plain.occurredAt)
        }
    }

    private fun click(label: String) = compose.onNodeWithText(label).performClick()

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(600, 5_000)
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val dir = File(compose.activity.getExternalFilesDir(null), "date-time-screens").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { image.recycle() }
    }
}
