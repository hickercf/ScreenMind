package cn.weiguang.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.weiguang.app.capture.CaptureService
import cn.weiguang.app.data.LocalEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeviceFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun offlineEntryManualRecordAndTheme() {
        val repository = (compose.activity.application as WeiguangApp).repository
        runBlocking { repository.logout() }
        compose.waitUntil(15000) { compose.onAllNodesWithText("先离线体验 OCR 与页面").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("先离线体验 OCR 与页面").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("把线索，变成下一步。").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("线索", useUnmergedTree = true).performClick()
        compose.onNodeWithText("手动记录").performClick()
        compose.onNodeWithText("识别或记录内容").performTextInput("UI 自动化记录：周五在 A302 讨论")
        compose.onNodeWithText("确认并保存").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("UI 自动化记录：周五在 A302 讨论").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("我的", useUnmergedTree = true).performClick()
        compose.onNodeWithText("深色").performScrollTo().performClick()
        waitReady()
        compose.waitUntil(15000) { compose.onAllNodes(hasText("深色") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("我的空间").assertExists()
    }

    @Test fun bundledChineseOcrWorksOnDevice() = runBlocking {
        val bitmap = Bitmap.createBitmap(1200, 420, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 60f }
        canvas.drawText("明天 18:30 在 A302 讨论项目", 36f, 140f, paint)
        canvas.drawText("请带上电脑", 36f, 260f, paint)
        val file = File(compose.activity.cacheDir, "ocr-device-test.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val text = CaptureService(compose.activity).recognize(Uri.fromFile(file))
        assertTrue(text, text.contains("A302"))
        assertTrue(text, text.contains("讨论") || text.contains("项目"))
    }

    @Test fun accountIsolationSurvivesRoomAndSessionSwitch() = runBlocking {
        val app = compose.activity.application as WeiguangApp
        val first = LocalEvent("test-server|alice", text = "Alice 的私有记录")
        val second = LocalEvent("test-server|bob", text = "Bob 的私有记录")
        app.database.events().save(first)
        app.database.events().save(second)
        assertNull(app.database.events().get("test-server|bob", first.eventId))
        assertEquals("Alice 的私有记录", app.database.events().get("test-server|alice", first.eventId)?.text)
        app.database.events().clear("test-server|alice")
        app.database.events().clear("test-server|bob")
    }

    @Test fun cloudRegistrationGroupAndDecisionThroughRealScreens() {
        val repository = (compose.activity.application as WeiguangApp).repository
        runBlocking { repository.logout(); repository.setEndpoint("http://127.0.0.1:8765"); repository.setTheme("light") }
        compose.waitUntil(15000) { compose.onAllNodesWithText("创建测试账号").fetchSemanticsNodes().isNotEmpty() }
        screenshot("login")
        compose.onNodeWithText("创建测试账号").performScrollTo().performClick()
        compose.onNodeWithText("账号").performTextInput("android_" + System.nanoTime())
        compose.onNodeWithText("密码", substring = false).performTextInput("Android-test-123")
        compose.onNodeWithText("确认密码", substring = false).performScrollTo().performTextInput("Android-test-123")
        compose.onNodeWithText("注册并登录").performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("把线索，变成下一步。").fetchSemanticsNodes().isNotEmpty() }
        waitReady()
        compose.onNodeWithText("我的", useUnmergedTree = true).performClick()
        compose.onNodeWithText("创建、加入与管理").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("新建组名称").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("新建组名称").performScrollTo().performTextInput("安卓联调组")
        compose.onNodeWithText("创建组", substring = false).performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("安卓联调组").fetchSemanticsNodes().isNotEmpty() }
        waitReady()
        compose.onNodeWithText("查看成员与共享").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("生成限时邀请码").fetchSemanticsNodes().isNotEmpty() }
        waitReady()
        compose.onNodeWithText("生成限时邀请码").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("复制邀请码").fetchSemanticsNodes().isNotEmpty() }
        waitReady()
        compose.onNodeWithText("复制邀请码").performScrollTo()
        screenshot("groups")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("线索", useUnmergedTree = true).performClick()
        compose.onNodeWithText("手动记录").performClick()
        compose.onNodeWithText("识别或记录内容").performTextInput("明天18:30在A302讨论项目")
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("确认并保存").performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("已同步", substring = true).fetchSemanticsNodes().isNotEmpty() }
        waitReady()
        screenshot("clues")
        compose.onNodeWithText("首页", useUnmergedTree = true).performClick()
        compose.onNodeWithText("用已同步线索生成联调建议").performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("先确认线索中的具体时间").fetchSemanticsNodes().isNotEmpty() }
        waitReady()
        compose.onNodeWithText("把线索，变成下一步。").performScrollTo()
        screenshot("home")
        compose.onNodeWithText("采纳", substring = false).performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("已采纳 · 反馈已记录").fetchSemanticsNodes().isNotEmpty() }
        waitReady()
        compose.onNodeWithText("设备", useUnmergedTree = true).performClick()
        compose.onNodeWithText("先验证腕上提醒").assertExists()
        screenshot("devices")
        compose.onNodeWithText("我的", useUnmergedTree = true).performClick()
        compose.onNodeWithText("深色").performScrollTo().performClick()
        waitReady()
        compose.waitUntil(15000) { compose.onAllNodes(hasText("深色") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        screenshot("profile-dark")
    }

    private fun screenshot(name: String) {
        compose.runOnUiThread {
            compose.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
        // System screenshots capture compositor frames, which can lag Compose semantics.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(500)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = File(compose.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private fun waitReady() {
        compose.waitUntil(30000) { compose.onAllNodesWithTag("operation-progress").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
    }
}
