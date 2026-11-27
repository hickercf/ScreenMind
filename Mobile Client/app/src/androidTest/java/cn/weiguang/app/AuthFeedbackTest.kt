package cn.weiguang.app

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Dedicated emulator only: map device 8765 to an isolated local gateway before running. */
@RunWith(AndroidJUnit4::class)
class AuthFeedbackTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun loginErrorsRemainVisibleAndCorrectedRegistrationOpensHome() {
        prepareLocalLogin()
        val username = "auth_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val password = "Auth-test-" + UUID.randomUUID().toString().take(8)
        compose.onNodeWithText("账号", substring = false).performTextInput(username)
        compose.onNodeWithText("密码", substring = false).performTextInput("123")
        compose.onNodeWithText("登录", substring = false).performScrollTo().performClick()
        assertPersistentLoginError("密码长度须为 8–128 位")

        compose.onNodeWithText("创建测试账号", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("密码", substring = false).performScrollTo().performTextReplacement(password)
        compose.onNodeWithText("确认密码", substring = false).performScrollTo().performTextInput(password + "x")
        compose.onNodeWithText("注册并登录", substring = false).performScrollTo().performClick()
        assertPersistentLoginError("两次输入的密码不一致")

        // The random username has not been registered. This must be a real local HTTP401,
        // rather than a missing UI callback, a validation error, or a simulated transport.
        compose.onNodeWithText("已有账号，去登录", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("登录", substring = false).performScrollTo().performClick()
        assertPersistentLoginError("账号或密码错误")

        compose.onNodeWithText("创建测试账号", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("确认密码", substring = false).performScrollTo().performTextReplacement(password)
        compose.onNodeWithText("注册并登录", substring = false).performScrollTo().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("你的工作台", substring = false).fetchSemanticsNodes().isNotEmpty()
        }
        waitReady()
        compose.onNodeWithTag("open-agent").assertExists()
        val repo = (compose.activity.application as WeiguangApp).repository
        assertEquals(username, repo.session.value?.username)
        assertEquals(LOCAL_ENDPOINT, repo.session.value?.endpoint)
    }

    @Test fun ordinarySnackbarSurvivesRecomposition() {
        prepareLocalLogin()
        val model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val notice = "普通提示稳定显示测试"
        compose.runOnIdle { model.message = notice }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(notice, substring = false).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        SystemClock.sleep(650)
        compose.waitForIdle()
        compose.onNodeWithText(notice, substring = false).assertIsDisplayed()
    }

    private fun prepareLocalLogin() {
        assumeTrue("Use -e managedAuthTest true on the dedicated local emulator",
            InstrumentationRegistry.getArguments().getString("managedAuthTest") == "true")
        val repo = (compose.activity.application as WeiguangApp).repository
        runBlocking {
            check(repo.endpoint.first() == LOCAL_ENDPOINT) { "A dedicated local gateway is required" }
            check(repo.session.value?.endpoint in listOf(null, "offline", LOCAL_ENDPOINT)) {
                "Do not run auth diagnostics with a public-service account"
            }
            repo.logout()
            repo.setEndpoint(LOCAL_ENDPOINT)
            repo.setTheme("light")
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("登录 ScreenMind", substring = false).fetchSemanticsNodes().isNotEmpty()
        }
        waitReady()
    }

    private fun assertPersistentLoginError(expected: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("login-error").fetchSemanticsNodes().isNotEmpty() }
        waitReady()
        compose.onNodeWithTag("login-error").performScrollTo().assertTextContains(expected, substring = false)
        SystemClock.sleep(650)
        compose.waitForIdle()
        compose.onNodeWithTag("login-error").assertIsDisplayed().assertTextContains(expected, substring = false)
    }

    private fun waitReady() {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("operation-progress").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
    }

    private companion object { const val LOCAL_ENDPOINT = "http://127.0.0.1:8765" }
}
