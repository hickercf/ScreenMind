package cn.weiguang.app

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.withTransaction
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.weiguang.app.data.LocalEvent
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ClueLibraryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as WeiguangApp
    private val files get() = File(compose.activity.getExternalFilesDir(null), "library-test").apply { mkdirs() }
    private val search get() = compose.onNodeWithTag("clue-search")
    private lateinit var account: String
    private val rehearsal = """{"schedule_changes":[{"id":"rehearsal","title":"合唱排练","at":"2026-12-15T18:00:00+08:00"}]}"""

    @Before fun loadLibrary() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("dedicatedLibraryTest") == "true")
        runBlocking {
            app.repository.enterOffline()
            account = requireNotNull(app.repository.session.value).accountKey
            val start = Instant.parse("2026-10-01T04:00:00Z")
            val events = app.database.events()
            app.database.withTransaction {
                events.clear(account)
                for (index in 0 until 120) {
                    val item = LocalEvent(accountKey = account, eventId = "library-$index",
                        text = when (index) {
                            0 -> rehearsal
                            1 -> "把书房的备用钥匙放在前台"
                            else -> listOf("整理读书笔记", "把借的书带去图书馆", "晚饭后和同学交流")[index % 3]
                        }, capturedAt = start.plusSeconds(index * 3600L).toString(),
                        syncState = if (index == 1) "local_copy" else "local")
                    events.save(item)
                }
                events.save(LocalEvent(accountKey = "library-fixture-other", eventId = "private", text = "另一位读者的私房书单"))
            }
        }
        visible("你的工作台")
    }

    @Test fun findsAnOlderStructuredNoteWithoutRenderingTheWholeLibrary() {
        val started = SystemClock.elapsedRealtime()
        compose.onNodeWithText("线索").performClick()
        visible("你的线索")
        val elapsed = SystemClock.elapsedRealtime() - started
        val rows = compose.onAllNodesWithText("纠错").fetchSemanticsNodes().size
        val hasSearch = compose.onAllNodesWithTag("clue-search").fetchSemanticsNodes().isNotEmpty()
        val report = JSONObject().put("saved_clues", 120).put("edit_buttons_in_semantics", rows)
            .put("has_search", hasSearch).put("open_elapsed_ms", elapsed)
        File(files,"initial.json").writeText(report.toString(2))
        capture("library")
        assertTrue("There is no search field for the saved clues", hasSearch)
        assertTrue("Off-screen rows were composed: $rows", rows in 1..15)
        search.performTextReplacement("18:00 排练")
        visible("找到 1 条线索")
        compose.onNodeWithText("合唱排练", substring = true).assertExists()
        compose.onAllNodesWithText("schedule_changes", substring = true).assertCountEquals(0)
        search.performImeAction()
        capture("result", keyboardVisible = false)
        compose.onNodeWithText("纠错").performScrollTo().performClick()
        compose.onNodeWithText("核对这条线索").assertIsDisplayed()
        compose.onNodeWithText("完成").performScrollTo().performClick()
        search.assertTextContains("18:00 排练")
        compose.onNodeWithText("首页").performClick()
        visible("你的工作台")
        compose.onNodeWithText("线索").performClick()
        search.assertTextContains("18:00 排练")
        compose.activityRule.scenario.recreate()
        search.assertTextContains("18:00 排练")
        visible("找到 1 条线索")

        search.performTextReplacement("本机副本")
        visible("把书房的备用钥匙放在前台")
        search.performTextReplacement("私房书单")
        visible("没有找到匹配的线索")
        compose.onAllNodesWithText("另一位读者的私房书单").assertCountEquals(0)
        search.performImeAction()
        capture("empty-search", keyboardVisible = false)
        compose.onNodeWithContentDescription("清除搜索").performClick()
        visible("你的线索")
        compose.onAllNodesWithText("没有找到匹配的线索").assertCountEquals(0)
    }

    @Test fun incomingChangesUpdateActiveResultsAndSigningOutClearsSearch() {
        compose.onNodeWithText("线索").performClick()
        search.performTextReplacement("备用钥匙")
        val decor = compose.activity.window.decorView
        val ime = WindowInsetsCompat.Type.ime()
        compose.waitUntil(10_000) { ViewCompat.getRootWindowInsets(decor)?.isVisible(ime) == true }
        visible("把书房的备用钥匙放在前台")
        val keyboardTop = (decor.height - requireNotNull(ViewCompat.getRootWindowInsets(decor)).getInsets(ime).bottom) / decor.resources.displayMetrics.density
        assertTrue("The first search result must fit above the keyboard",
            compose.onNodeWithText("把书房的备用钥匙放在前台").getUnclippedBoundsInRoot().bottom.value <= keyboardTop)
        capture("typing", keyboardVisible = true)
        runBlocking {
            val events = app.database.events()
            val saved = requireNotNull(events.get(account,"library-1"))
            events.save(saved.copy(text = "改从侧门进去"))
        }
        visible("没有找到匹配的线索")
        search.performImeAction()
        runBlocking { app.repository.logout() }
        visible("先离线使用")
        compose.onNodeWithText("先离线使用").performClick()
        visible("你的工作台")
        compose.onNodeWithText("线索").performClick()
        assertEquals("", search.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onAllNodesWithText("没有找到匹配的线索").assertCountEquals(0)
    }

    private fun visible(text: String) {
        val node = compose.onNodeWithText(text)
        try {
            compose.waitUntil(20_000) { node.isDisplayed() }
        } catch (timeout: ComposeTimeoutException) {
            runCatching { capture("visibility-failure") }
            throw timeout
        }
        node.assertIsDisplayed()
    }

    private fun capture(name: String, keyboardVisible: Boolean? = null) {
        compose.waitForIdle()
        val device = InstrumentationRegistry.getInstrumentation()
        if (keyboardVisible != null) {
            val decor = compose.activity.window.decorView
            compose.waitUntil(10_000) {
                ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) == keyboardVisible
            }
        }
        device.waitForIdleSync()
        device.uiAutomation.waitForIdle(400, 5_000)
        val image = device.uiAutomation.takeScreenshot()
        try { File(files,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } }
        finally { image.recycle() }
    }
}
