package cn.weiguang.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClueEditorDraftTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun aManualNoteSurvivesRecreationAndDiscardingChangesKeepsTheSavedText() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("dedicatedDraftTest") == "true")
        val repo = (compose.activity.application as WeiguangApp).repository
        runBlocking { repo.enterOffline() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("记下第一条线索").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("记下第一条线索").assertIsDisplayed().performClick()
        val input = compose.onNodeWithTag("clue-editor-text")
        compose.onNodeWithText("确认并保存").assertIsNotEnabled()
        val original = "周五带上讨论稿"
        input.performTextInput(original)
        compose.activityRule.scenario.recreate()
        input.assertTextContains(original)
        compose.onNodeWithText("确认并保存").performScrollTo().performClick()
        compose.onNodeWithText(original).assertIsDisplayed().performClick()
        input.performTextReplacement("周六带上讨论稿")
        compose.activityRule.scenario.recreate()
        input.assertTextContains("周六带上讨论稿")
        val device = InstrumentationRegistry.getInstrumentation()
        compose.onNodeWithContentDescription("关闭编辑").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onNodeWithText("继续编辑").isDisplayed() }
        compose.onNodeWithText("继续编辑").performClick()
        input.assertTextContains("周六带上讨论稿")
        device.uiAutomation.waitForIdle(400, 5_000)
        if (keyboardOnScreen(device.uiAutomation) == true) {
            device.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitUntil(10_000) { keyboardOnScreen(device.uiAutomation) == false }
        }
        device.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { compose.onNodeWithText("放弃修改").isDisplayed() }
        compose.onNodeWithText("放弃修改").performClick()
        compose.onNodeWithText(original).assertIsDisplayed()
        compose.onAllNodesWithText("周六带上讨论稿").assertCountEquals(0)
    }
}
