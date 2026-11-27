package cn.weiguang.app

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.weiguang.app.ui.PlanDeck
import cn.weiguang.app.ui.WorkspaceTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlanSheetThemeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun anOpenPlanDetailFollowsTheAppAppearance() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("dedicatedSheetTest") == "true")
        val window = compose.activity.window
        val mode = mutableStateOf("dark")
        val card = JSONObject().put("id", "place").put("title", "见面地点").put("value", "图书馆南门")
        val plan = JSONObject().put("work_id", "reading").put("title", "周末读书会")
            .put("version", 1).put("confirmed", JSONArray().put(card))
        compose.setContent {
            WorkspaceTheme(mode.value, window) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.windowInsetsPadding(WindowInsets.systemBars)) {
                        PlanDeck(plan, onOpinion = {}) {}
                    }
                }
            }
        }
        compose.onNodeWithTag("plan-card-place").performClick()
        val device = InstrumentationRegistry.getInstrumentation().uiAutomation
        val folder = File(compose.activity.getExternalFilesDir(null), "plan-sheet-theme").apply { mkdirs() }
        for (theme in listOf("dark", "light")) {
            compose.runOnIdle { mode.value = theme }
            compose.onNodeWithTag("plan-opinion-text").assertIsDisplayed()
            compose.waitForIdle()
            device.waitForIdle(600, 5_000)
            val barHeight = requireNotNull(ViewCompat.getRootWindowInsets(window.decorView))
                .getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()).top
            // System UI animates its own tint after Compose and accessibility are already idle.
            val deadline = SystemClock.uptimeMillis() + 4_000
            while (true) {
                val frame = checkNotNull(device.takeScreenshot())
                try {
                    val left = frame.width * 4 / 5
                    val width = frame.width - left
                    val pixels = IntArray(width * barHeight)
                    frame.getPixels(pixels, 0, width, left, 0, width, barHeight)
                    val colors = pixels.asIterable().groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
                    val background = Color.luminance(colors[0].key)
                    val ink = Color.luminance(colors.getOrElse(1) { colors[0] }.key)
                    val contrast = (maxOf(ink, background) + .05f) / (minOf(ink, background) + .05f)
                    val matches = (ink > background) == (theme == "dark") && contrast >= 3
                    if (matches || SystemClock.uptimeMillis() >= deadline) {
                        File(folder, "$theme.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        File(folder, "$theme.json").writeText(JSONObject().put("status_contrast", contrast.toDouble()).toString())
                        assertEquals("Status icons should follow the open sheet", theme == "dark", ink > background)
                        assertTrue("Status icon contrast in $theme was $contrast", contrast >= 3)
                        break
                    }
                } finally { frame.recycle() }
                SystemClock.sleep(90)
            }
        }
    }
}
