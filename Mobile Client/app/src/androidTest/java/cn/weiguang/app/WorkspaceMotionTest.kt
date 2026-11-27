package cn.weiguang.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceMotionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun animatedTabsLeaveOnlyTheSettledPageAndCanReturn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("dedicatedMotionTest") == "true")
        val app = compose.activity.application as WeiguangApp
        check(app.repository.session.value == null)
        compose.onNodeWithText("先离线使用").performScrollTo().performClick()
        val home = compose.onNodeWithText("你的工作台")
        val clues = compose.onNodeWithText("你的线索")
        compose.waitUntil(5000) { compose.onAllNodesWithText("你的工作台").fetchSemanticsNodes().isNotEmpty() }
        home.assertIsDisplayed()
        compose.onNodeWithText("线索", substring = false).performClick()
        clues.assertIsDisplayed()
        home.assertDoesNotExist()
        compose.onNodeWithText("首页", substring = false).performClick()
        home.assertIsDisplayed()
        clues.assertDoesNotExist()
    }
}
