package cn.weiguang.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.weiguang.app.data.*
import com.chronocapsule.contract.ScreenContract
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ClueConcurrentEditTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val repo get() = (compose.activity.application as WeiguangApp).repository
    private val events get() = repo.database.events()
    private val peer = ApiClient()
    private val endpoint = "http://127.0.0.1:8765"
    private lateinit var token: String
    private lateinit var account: String

    @Before fun signInTwoClients() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("dedicatedConflictTest") == "true")
        assumeTrue(!BuildConfig.LOCK_CLOUD_ENDPOINT)
        runBlocking {
            repo.logout()
            repo.setEndpoint(endpoint)
            val name = "clues_" + System.nanoTime()
            val password = UUID.randomUUID().toString()
            repo.authenticate(name, password, true)
            account = requireNotNull(repo.session.value).accountKey
            val credentials = JSONObject().put("username", name).put("password", password)
            token = peer.call(endpoint, "/v1/auth/login", "POST", credentials).getString("access_token")
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("你的工作台").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("operation-progress").fetchSemanticsNodes().isEmpty() }
    }

    private suspend fun send(event: LocalEvent) =
        peer.call(endpoint, "/v1/events", "POST", event.toPayload(), token)

    private suspend fun remote() = peer.call(endpoint, "/v1/events", token = token).itemObjects()

    @Test fun bothVersionsSurviveWhenThePhoneReceivesAnUpdateWhileEditing() {
        val first = LocalEvent(accountKey = account, text = "周五讨论项目")
        val other = first.copy(revision = 2, text = "讨论改到图书馆")
        val mine = "我会把笔记带到教室"
        runBlocking { send(first); repo.refreshRemote() }
        compose.onNodeWithText(first.text).performScrollTo().performClick()
        compose.onNodeWithTag("clue-editor-text").performTextReplacement(mine)
        runBlocking {
            send(other)
            repo.refreshRemote()
            assertEquals(other.text, events.get(account, first.eventId)?.text)
        }
        compose.onNodeWithText("确认并保存").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("clue-editor-text").fetchSemanticsNodes().isEmpty() }
        runBlocking {
            repo.sync()
            repo.refreshRemote()
            assertEquals(other.text, events.get(account, first.eventId)?.text)
            val copy = events.all(account).single { it.text == mine }
            assertNotEquals(first.eventId, copy.eventId)
            assertEquals(0, copy.serverRevision)
            assertFalse(copy.consentToUpload)
            assertEquals(listOf(other.text), remote().map { it.getJSONObject("payload").getString("text") })
            repo.refreshRemote()
            assertEquals(2, events.all(account).size)
        }
        compose.onNodeWithText(mine).assertExists()
        compose.onNodeWithText(other.text).assertExists()
    }

    @Test fun aRejectedEditDoesNotHoldUpOtherUploadsAndCanBeUploadedSeparately() = runBlocking {
        val first = LocalEvent(accountKey = account, text = "周五讨论项目")
        send(first)
        val other = first.copy(revision = 2, text = "讨论已改到图书馆")
        send(other)
        val local = first.copy(text = "教室里还有笔记", revision = 2, serverRevision = 1,
            syncState = "pending", consentToUpload = true, agentWorkId = "previous-work", agentRequestId = "previous-request")
        val separate = LocalEvent(accountKey = account, text = "记得带电脑", syncState = "pending", consentToUpload = true)
        events.save(local)
        events.save(separate)
        repo.sync()
        assertEquals("synced", events.get(account, separate.eventId)?.syncState)
        repo.refreshRemote()
        repo.refreshRemote()
        val saved = events.all(account)
        assertEquals(3, saved.size)
        assertEquals(other.text, events.get(account, other.eventId)?.text)
        val copy = saved.single { it.text == local.text }
        assertFalse(copy.consentToUpload)
        assertNull(copy.agentWorkId)
        assertNull(copy.agentRequestId)
        assertNull(events.get(account, other.eventId)?.agentRequestId)
        assertEquals(2, remote().size)
        repo.uploadEvent(copy.eventId)
        repo.sync()
        val online = remote().associateBy { it.getString("event_id") }
        assertEquals(3, online.size)
        assertEquals(1, online.getValue(copy.eventId).getInt("revision"))
        assertEquals(2, online.getValue(first.eventId).getInt("revision"))
    }

    @Test fun oldUnsyncedCorrectionSurvivesWhenAnotherClientRemovedTheOriginal() = runBlocking {
        val captured = "2026-11-20T09:00:00Z"
        val text = "图书馆的读书会改到周日"
        val screen = ScreenContract.Payload(UUID.randomUUID().toString(), "book-club", "example.chat", "测试聊天", "读书会",
            captured, listOf(ScreenContract.Block("line-1", "chat", "我", text, null, listOf(12f, 20f, 720f, 84f))))
        val first = LocalEvent(accountKey = account, text = text, sourceKind = "screenshot_ocr", capturedAt = captured, screenJson = screen.toJson().toString())
        send(first)
        peer.call(endpoint, "/v1/events/" + first.eventId, "DELETE", token = token)
        events.save(first.copy(syncState = "conflict", consentToUpload = true, serverRevision = 1, revision = 2,
            occurredAt = "2026-11-22T08:00:00Z", agentWorkId = "old-work", agentRequestId = "old-request", agentTaskId = "old-task"))
        repo.refreshRemote()
        repo.refreshRemote()
        repo.sync()
        val copy = events.all(account).single()
        assertNotEquals(first.eventId, copy.eventId)
        assertEquals(first.screenJson, copy.screenJson)
        assertEquals("2026-11-22T08:00:00Z", copy.occurredAt)
        assertEquals(0, copy.serverRevision)
        assertFalse(copy.consentToUpload)
        assertNull(copy.agentRequestId)
        assertNull(copy.agentTaskId)
        assertNull(copy.agentWorkId)
        assertTrue(remote().isEmpty())
        repo.logout()
        repo.enterOffline()
        assertTrue(events.all(requireNotNull(repo.session.value).accountKey).isEmpty())
        assertEquals(copy, events.get(account, copy.eventId))
    }

    @Test fun anInvalidLegacyNoteStaysPrivateUntilItsOwnerCorrectsAndUploadsIt() = runBlocking {
        val original = LocalEvent(accountKey = account, text = "字".repeat(10_001), syncState = "pending", consentToUpload = true)
        events.save(original)
        repo.sync()
        assertEquals("rejected", events.get(account, original.eventId)?.syncState)
        repo.refreshRemote()
        val saved = events.all(account).single()
        assertEquals(original.text, saved.text)
        assertFalse(saved.consentToUpload)
        assertTrue(remote().isEmpty())
        val revised = repo.saveEvent("整理好的读书笔记", saved.sourceKind, saved.eventType, false, saved.eventId, expectedAccount = account)
        assertFalse(revised.consentToUpload)
        repo.uploadEvent(revised.eventId)
        repo.sync()
        assertEquals(revised.text, remote().single().getJSONObject("payload").getString("text"))
    }

    @Test fun anAlreadyMatchingCloudEditKeepsOneRecord() = runBlocking {
        val first = LocalEvent(accountKey = account, text = "准备读书会", capturedAt = "2026-11-20T08:45:00.123456789Z")
        send(first)
        val last = first.copy(revision = 2, text = "带上读书笔记", occurredAt = "2026-11-22T16:00:00+08:00")
        send(last)
        events.save(last.copy(revision = 3, serverRevision = 1, syncState = "conflict", consentToUpload = true,
            occurredAt = "2026-11-22T08:00:00Z"))
        repo.refreshRemote()
        val only = events.all(account).single()
        assertEquals(first.eventId, only.eventId)
        assertEquals("synced", only.syncState)
        assertEquals(2, only.serverRevision)
    }
}
