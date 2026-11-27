package cn.weiguang.app

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SharedScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as WeiguangApp
    private val banner = "收到一张截图"

    @Before fun requireDedicatedDevice() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("dedicatedShareTest") == "true")
    }

    @Test fun interruptedReadCanRetryAndSuccessfulShareIsConsumed() {
        enterOfflineFromLogin()
        val directory = File(app.filesDir, "updates").apply { mkdirs() }
        val fixture = File(directory, "shared-ocr-${System.nanoTime()}.png")
        val uri = FileProvider.getUriForFile(app, app.packageName + ".updates", fixture)
        try {
            share(uri)
            waitFor(banner)
            compose.activityRule.scenario.recreate()
            click("识别并核对")
            waitFor("重新识别")
            takePicture("retry")
            writeScreenshot(fixture)
            click("重新识别")
            compose.waitUntil(40000) { compose.onAllNodesWithTag("clue-editor-text").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("clue-editor-text").assertTextContains("A302", substring = true)
            click("确认并保存")
            compose.activityRule.scenario.recreate()
            waitFor("你的工作台")
            compose.onAllNodesWithText(banner).assertCountEquals(0)
            compose.onNodeWithText("A302", substring = true).assertExists()
            val row = runBlocking { app.database.events().all(requireNotNull(app.repository.session.value).accountKey) }
                .single { it.text.contains("A302") && it.sourceKind == "screenshot_ocr" }
            assertFalse(row.consentToUpload)
            assertTrue(row.screenJson!!.contains("bbox"))
            share(uri)
            waitFor(banner)
            takePicture("received")
            // A second delivery of the same file can arrive while the first OCR job is reading it.
            compose.activityRule.scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[AppViewModel::class.java]
                val first = requireNotNull(model.sharedScreenshot)
                model.recognize(first)
                model.receiveSharedScreenshot(uri)
                assertNotEquals(first.deliveryId, model.sharedScreenshot?.deliveryId)
            }
            compose.waitUntil(40000) { compose.onAllNodesWithTag("operation-progress").fetchSemanticsNodes().isEmpty() }
            compose.onAllNodesWithTag("clue-editor-text").assertCountEquals(0)
            compose.onNodeWithText(banner).assertIsDisplayed()
            compose.onNodeWithContentDescription("忽略这张截图").performScrollTo().performClick()
            compose.activityRule.scenario.recreate()
            waitFor("你的工作台")
            compose.onAllNodesWithText(banner).assertCountEquals(0)
        } finally { fixture.delete() }
    }

    @Test fun pendingShareLeavesWithTheAccount() {
        enterOfflineFromLogin()
        val uri = Uri.parse("content://com.example.synthetic/one-image")
        compose.onNodeWithText("我的", useUnmergedTree = true).performClick()
        waitFor("账户与设置")
        share(uri, clipOnly = true)
        waitFor(banner)
        compose.onNodeWithText("你的工作台").assertExists()
        runBlocking { app.repository.logout() }
        waitFor("先离线使用")
        click("先离线使用")
        waitFor("你的工作台")
        compose.onAllNodesWithText(banner).assertCountEquals(0)
        runBlocking { app.repository.logout() }
        waitFor("先离线使用")
        share(uri, clipOnly = true)
        waitFor("在本机识别")
        takePicture("signed-out")
        click("在本机识别")
        waitFor("重新识别")
        assertTrue(app.repository.session.value?.endpoint == "offline")
    }

    private fun share(uri: Uri, clipOnly: Boolean = false) {
        compose.activityRule.scenario.onActivity { activity ->
            activity.startActivity(Intent(Intent.ACTION_SEND).apply {
                setClass(activity, MainActivity::class.java)
                type = "image/png"
                if (clipOnly) clipData = ClipData.newRawUri("截图", uri)
                else putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }
    }

    private fun writeScreenshot(file: File) {
        val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 56f }
            canvas.drawText("12:00", 60f, 70f, ink)
            canvas.drawText("小林", 220f, 225f, ink)
            canvas.drawText("12:00", 440f, 410f, ink)
            canvas.drawText("周五在A302讨论项目", 110f, 610f, ink)
            canvas.drawText("记得带电脑", 110f, 800f, ink)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun enterOfflineFromLogin() {
        runBlocking { app.repository.logout() }
        waitFor("先离线使用")
        click("先离线使用")
        waitFor("你的工作台")
    }

    private fun waitFor(text: String) {
        try {
            compose.waitUntil(20000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Expected '$text'. Current screen: " + compose.onRoot().printToString(maxDepth = 8), timeout)
        }
    }

    private fun click(text: String) {
        compose.onNodeWithText(text).performScrollTo().performClick()
    }

    private fun takePicture(name: String) {
        compose.onNodeWithText(banner).assertIsDisplayed()
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(300)
        val image = instrumentation.uiAutomation.takeScreenshot()
        val directory = File(app.getExternalFilesDir(null), "shared-image-screens").apply { mkdirs() }
        try {
            File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { image.recycle() }
    }
}
