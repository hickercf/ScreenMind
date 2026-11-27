package cn.weiguang.app

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WorkspaceSurfaceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun everyScreenUsesTheWorkspacePaletteInBothAppearances() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("dedicatedSurfaceTest") == "true")
        val username = requireNotNull(args.getString("qaUsername"))
        val password = requireNotNull(args.getString("qaPassword"))
        check(username.startsWith("partyqa_")) { "Use an isolated synthetic account" }
        val repo = (compose.activity.application as WeiguangApp).repository
        runBlocking {
            repo.logout()
            repo.setTheme("light")
        }
        visible("登录 ScreenMind")
        compose.onNodeWithText("先离线使用").assertIsDisplayed()
        capture("login-light", light)
        click("创建测试账号")
        visible("注册 ScreenMind")
        capture("register-light", light)
        click("已有账号，去登录")
        compose.onNodeWithText("账号", substring = false).performScrollTo().performTextInput(username)
        compose.onNodeWithText("密码", substring = false).performScrollTo().performTextInput(password)
        click("登录")
        visible("你的工作台")
        ready()
        compose.waitUntil(10_000) {
            compose.runOnUiThread {
                ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == false
            }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("同步完成").fetchSemanticsNodes().isEmpty() }

        try {
            runBlocking {
                repo.saveEvent(
                    """{"schedule_changes":[{"id":"meeting","title":"组会","at":"2026-12-10T18:00:00+08:00"}]}""",
                    "manual", "schedule", false,
                )
            }
            exercisePages("light", light)
            tab("我的")
            ready()
            compose.onNodeWithText("深色").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("深色") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
            exercisePages("dark", dark)
        } finally {
            runBlocking { repo.logout() }
        }
        visible("登录 ScreenMind")
        capture("login-dark", dark)
        click("创建测试账号")
        visible("注册 ScreenMind")
        capture("register-dark", dark)
        click("已有账号，去登录")
        runBlocking { repo.setTheme("light") }
    }

    private fun exercisePages(suffix: String, color: Int) {
        val clue = "组会，${cn.weiguang.app.ui.ReadableText.scheduleTime("2026-12-10T18:00:00+08:00")}"
        tab("首页")
        visible("你的工作台")
        val recent = compose.onNodeWithText(clue)
        recent.assertIsDisplayed().assertHasClickAction()
        assertTrue("The first clue must fit above the navigation bar without scrolling",
            recent.getUnclippedBoundsInRoot().bottom <= compose.onNodeWithText("首页").getUnclippedBoundsInRoot().top)
        capture("home-$suffix", color)
        recent.performClick()
        compose.onNodeWithTag("clue-editor-text").assertTextContains(clue)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("clue-editor-text").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("open-agent").performScrollTo().performClick()
        visible("云端事项")
        visible("创建事项")
        ready()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("agent-plugin-information").fetchSemanticsNodes().isNotEmpty() }
        capture("agent-$suffix", color)
        back()

        tab("线索")
        visible("你的线索")
        capture("clues-$suffix", color)
        compose.onNodeWithText(clue).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("schedule_changes", substring = true).assertCountEquals(0)
        click("纠错")
        compose.onNodeWithTag("clue-editor-text").assertTextContains(clue)
        capture("clue-editor-$suffix")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("clue-editor-text").fetchSemanticsNodes().isEmpty() }

        tab("设备")
        visible("设备与连接")
        capture("devices-$suffix", color)
        click("查看手表提醒")
        visible("手表提醒预览")
        capture("watch-$suffix", color)
        back()

        tab("我的")
        visible("账户与设置")
        capture("profile-$suffix", color)
        click("测试组")
        visible("测试组")
        ready()
        capture("groups-$suffix", color)
        back()

        compose.onNode(hasText("更新") or hasText("发现更新")).performClick()
        visible("应用更新")
        visible("当前版本 ${BuildConfig.VERSION_NAME}")
        click("检查更新")
        visible("暂无可用更新。")
        capture("updates-$suffix", color)
        back()
    }

    private fun click(label: String) = compose.onNodeWithText(label).performScrollTo().performClick()
    private fun tab(label: String) = compose.onNodeWithText(label, useUnmergedTree = true).performClick()
    private fun back() = compose.onNodeWithContentDescription("返回").performClick()

    private fun visible(label: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label).assertIsDisplayed()
    }

    private fun ready() = compose.waitUntil(20_000) {
        compose.onAllNodesWithTag("operation-progress").fetchSemanticsNodes().isEmpty()
    }

    private fun capture(name: String, background: Int? = null) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val dir = File(compose.activity.getExternalFilesDir(null), "workspace-screens").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (background != null) {
                assertEquals("$name page background", background, image.getPixel(image.width / 100, image.height / 2))
            }
        } finally {
            image.recycle()
        }
    }

    private val light = Color.rgb(245, 247, 251)
    private val dark = Color.rgb(14, 23, 39)
}
