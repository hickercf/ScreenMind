package cn.weiguang.app

import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.weiguang.app.data.AgentCommand
import cn.weiguang.app.data.AppDatabase
import cn.weiguang.app.data.MIGRATION_1_2
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Needs the authenticated gateway on 127.0.0.1:8765 in mock/basic mode. */
@RunWith(AndroidJUnit4::class)
class AgentFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun selectedEvidenceRequiresExplicitStateConfirmation() {
        val repository = (compose.activity.application as WeiguangApp).repository
        val event = runBlocking {
            repository.logout()
            repository.setEndpoint("http://127.0.0.1:8765")
            repository.authenticate("agent_" + System.nanoTime(), "Agent-test-123", true)
            assertEquals("Run the device suite against SCREENMIND_PROVIDER=mock", "mock",
                repository.agentSnapshot(null, null).getJSONObject("health").getString("provider"))
            val item = repository.saveEvent(
                """{"schedule_changes":[{"id":"meeting","title":"组会","at":"2026-12-10T18:00:00+08:00"}]}""",
                "manual", "schedule", true)
            repository.sync()
            item
        }
        compose.waitUntil(30000) { compose.onAllNodesWithTag("open-agent").fetchSemanticsNodes().isNotEmpty() }
        ready()
        compose.onNodeWithTag("open-agent").performScrollTo().performClick()
        compose.onNodeWithTag("agent-work-title").performTextInput("设备端日程测试")
        compose.waitUntil(30000) { compose.onAllNodesWithTag("agent-plugin-schedule").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("agent-plugin-schedule").performScrollTo().performClick()
        compose.onNodeWithTag("agent-create-work").performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithTag("agent-source-${event.eventId}").fetchSemanticsNodes().isNotEmpty() }
        ready()
        compose.onNodeWithTag("agent-source-${event.eventId}").performScrollTo().performClick()
        compose.onNodeWithTag("agent-start").performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithTag("agent-accept-state").fetchSemanticsNodes().isNotEmpty() }
        ready()
        compose.onNodeWithText("已确认状态 v0", substring = true).assertExists()
        compose.onNodeWithTag("agent-accept-state").performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("已确认状态 v1", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("agent-accept-state").assertCountEquals(0)
    }

    @Test fun roomUpgradePreservesEvidenceAndPendingCommandsSurviveReopen() = runBlocking {
        val context = compose.activity.applicationContext
        val name = "migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE events (accountKey TEXT NOT NULL,eventId TEXT NOT NULL,revision INTEGER NOT NULL,eventType TEXT NOT NULL,sourceKind TEXT NOT NULL,text TEXT NOT NULL,capturedAt TEXT NOT NULL,occurredAt TEXT,timezone TEXT NOT NULL,syncState TEXT NOT NULL,consentToUpload INTEGER NOT NULL,deleted INTEGER NOT NULL,serverRevision INTEGER NOT NULL,PRIMARY KEY(accountKey,eventId))")
            db.execSQL("CREATE TABLE decisions (accountKey TEXT NOT NULL,decisionId TEXT NOT NULL,body TEXT NOT NULL,PRIMARY KEY(accountKey,decisionId))")
            db.execSQL("INSERT INTO events VALUES ('a','e',1,'note','manual','保留原文','2026-11-09T12:00:00Z',NULL,'Asia/Shanghai','local',0,0,0)")
            db.version = 1
        }
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(MIGRATION_1_2).build()
        val command = AgentCommand("a", "/v1/agent/tasks", """{"request_id":"same-id"}""")
        try {
            val first = open()
            try {
                val db = first
                assertEquals("保留原文", db.events().get("a", "e")?.text)
                db.agentCommands().save(command)
            } finally { first.close() }
            val second = open()
            try {
                val db = second
                assertEquals(command, db.agentCommands().get("a", command.path))
                assertNull(db.agentCommands().get("b", command.path))
                assertEquals("保留原文", db.events().get("a", "e")?.text)
            } finally { second.close() }
        } finally { context.deleteDatabase(name) }
    }

    private fun ready() {
        compose.waitUntil(30000) { compose.onAllNodesWithTag("operation-progress").fetchSemanticsNodes().isEmpty() }
    }
}
