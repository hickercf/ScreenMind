package cn.weiguang.app

import android.graphics.Bitmap
import android.graphics.Color
import android.app.UiAutomation
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WindowThemeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun darkWindowAfterRecreation() = verify("dark")
    @Test fun lightWindowAfterRecreation() = verify("light")

    private fun verify(theme: String) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("dedicatedWindowTest") == "true")
        val repository = (compose.activity.application as WeiguangApp).repository
        runBlocking {
            repository.enterOffline()
            repository.setTheme(theme)
            repository.saveEvent("周五在图书馆碰面，一起参加 Reading Circle", "manual", "note", false)
        }
        compose.waitUntil(10_000) { compose.onNodeWithText("你的工作台").isDisplayed() }
        compose.onNodeWithText("线索").performClick()
        val search = compose.onNodeWithTag("clue-search")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = instrumentation.uiAutomation
        search.performClick()
        waitForKeyboard(device, true)
        instrumentation.sendStringSync("read")
        search.assertTextContains("read")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("周五在图书馆碰面，一起参加 Reading Circle").onFirst().isDisplayed() }
        compose.onNodeWithContentDescription("清除搜索").performClick()
        search.assertIsFocused()
        instrumentation.sendStringSync("read")
        search.assertTextContains("read")
        search.performImeAction()
        waitForKeyboard(device, false)
        search.assertTextContains("read")
        compose.activityRule.scenario.recreate()
        search.assertTextContains("read")
        compose.onNodeWithContentDescription("清除搜索").performClick()
        search.performClick()
        waitForKeyboard(device, true)
        instrumentation.sendStringSync("reader")
        search.assertTextContains("reader")
        compose.waitUntil(10_000) { compose.onNodeWithText("没有找到匹配的线索").isDisplayed() }
        search.performImeAction()
        val window = compose.activity.window
        compose.waitUntil(10_000) {
            ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false
        }
        compose.waitForIdle()
        waitForKeyboard(device, false)
        device.waitForIdle(500, 5_000)
        val frame = device.takeScreenshot()
        try {
            val dir = File(compose.activity.getExternalFilesDir(null), "window-theme").apply { mkdirs() }
            File(dir, "$theme.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val inset = (6 * window.decorView.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            fun luminanceAt(y: Int): Float =
                (1..9).map { Color.luminance(frame.getPixel(frame.width * it / 10, y)) }.sorted()[4]
            val top = luminanceAt(inset)
            val bottom = luminanceAt(frame.height - inset - 1)
            File(dir, "$theme.json").writeText(JSONObject().put("status_bar_luminance", top.toDouble())
                .put("navigation_bar_luminance", bottom.toDouble()).toString(2))
            val readable = if (theme == "dark") top < 0.18f && bottom < 0.18f else top > 0.65f && bottom > 0.65f
            assertTrue("The $theme window has mismatched system bars after recreation: $top, $bottom", readable)
        } finally {
            frame.recycle()
        }
    }

    private fun waitForKeyboard(device: UiAutomation, visible: Boolean) {
        compose.waitUntil(15_000) { keyboardOnScreen(device) == visible }
    }
}
