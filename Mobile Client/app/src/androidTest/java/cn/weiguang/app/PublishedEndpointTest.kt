package cn.weiguang.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.weiguang.app.data.CloudEndpoint
import cn.weiguang.app.data.Repository
import cn.weiguang.app.data.SessionStore
import cn.weiguang.app.data.UserSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PublishedEndpointTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun publishedAppHasNoServerPickerAndKeepsOnlyMatchingSessions() {
        assumeTrue(BuildConfig.LOCK_CLOUD_ENDPOINT)
        assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("dedicatedEndpointTest") == "true")
        val app = compose.activity.application as WeiguangApp
        check(app.repository.session.value == null) { "This test requires a clean emulator installation" }
        compose.onNodeWithText("服务连接").assertDoesNotExist()
        compose.onNodeWithText("登录", substring = false).assertIsDisplayed()

        val persisted = SessionStore(app)
        val expected = "https://agent.qwertyuiopasdf.asia"
        val synthetic = UserSession("fixture", "fixture", "test-access", "test-refresh", expected)
        try {
            persisted.save(synthetic)
            val known = Repository(app, app.database)
            assertEquals(synthetic, known.session.value)
            assertEquals(expected, runBlocking { known.endpoint.first() })
            check(runCatching { runBlocking { known.setEndpoint("https://example.com") } }.isFailure)

            persisted.save(synthetic.copy(endpoint = "https://example.com"))
            assertNull(Repository(app, app.database).session.value)
            assertNull(persisted.load())
            assertEquals(expected, CloudEndpoint.selected("http://127.0.0.1:8765"))
            assertTrue(CloudEndpoint.acceptsDownload("$expected/v1/app/releases/test.apk"))
            assertFalse(CloudEndpoint.acceptsDownload("https://example.com/update.apk"))
        } finally {
            persisted.clear()
        }
    }
}
