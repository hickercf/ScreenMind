package cn.weiguang.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Entity(tableName = "events", primaryKeys = ["accountKey", "eventId"])
data class LocalEvent(
    val accountKey: String,
    val eventId: String = UUID.randomUUID().toString(),
    val revision: Int = 1,
    val eventType: String = "note",
    val sourceKind: String = "manual",
    val text: String,
    val capturedAt: String = Instant.now().toString(),
    val occurredAt: String? = null,
    val timezone: String = ZoneId.systemDefault().id,
    val syncState: String = "local",
    val consentToUpload: Boolean = false,
    val deleted: Boolean = false,
    val serverRevision: Int = 0
) {
    fun toPayload(): JSONObject = JSONObject()
        .put("schema_version", 1).put("event_id", eventId).put("revision", revision)
        .put("event_type", eventType).put("source_kind", sourceKind)
        .put("captured_at", capturedAt).put("occurred_at", occurredAt ?: JSONObject.NULL)
        .put("timezone", timezone).put("review_status", "confirmed")
        .put("payload", JSONObject().put("text", text))
}

@Entity(tableName = "decisions", primaryKeys = ["accountKey", "decisionId"])
data class LocalDecision(val accountKey: String, val decisionId: String, val body: String)

@Dao
interface EventDao {
    @Query("SELECT * FROM events WHERE accountKey=:account AND deleted=0 ORDER BY capturedAt DESC")
    fun observe(account: String): Flow<List<LocalEvent>>
    @Query("SELECT * FROM events WHERE accountKey=:account AND eventId=:id")
    suspend fun get(account: String, id: String): LocalEvent?
    @Query("SELECT * FROM events WHERE accountKey=:account AND consentToUpload=1 AND syncState IN ('pending','failed')")
    suspend fun pending(account: String): List<LocalEvent>
    @Query("SELECT * FROM events WHERE accountKey=:account")
    suspend fun all(account: String): List<LocalEvent>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(event: LocalEvent)
    @Query("UPDATE events SET syncState=:status,serverRevision=:serverRevision WHERE accountKey=:account AND eventId=:id AND revision=:revision")
    suspend fun mark(account: String, id: String, revision: Int, status: String, serverRevision: Int)
    @Query("DELETE FROM events WHERE accountKey=:account AND eventId=:id")
    suspend fun remove(account: String, id: String)
    @Query("DELETE FROM events WHERE accountKey=:account")
    suspend fun clear(account: String)
}

@Dao
interface DecisionDao {
    @Query("SELECT * FROM decisions WHERE accountKey=:account ORDER BY rowid DESC")
    fun observe(account: String): Flow<List<LocalDecision>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(decision: LocalDecision)
    @Query("DELETE FROM decisions WHERE accountKey=:account")
    suspend fun clear(account: String)
}

@Database(entities = [LocalEvent::class, LocalDecision::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun events(): EventDao
    abstract fun decisions(): DecisionDao
}

data class UserSession(
    val userId: String,
    val username: String,
    val accessToken: String,
    val refreshToken: String,
    val endpoint: String
) {
    val accountKey: String get() = "$endpoint|$userId"
}

data class Group(val id: String, val name: String, val role: String)

fun JSONObject.itemObjects(key: String = "items"): List<JSONObject> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).map { array.getJSONObject(it) }
}

object Validation {
    fun credentials(username: String, password: String): String? {
        if (!Regex("^[A-Za-z0-9_]{3,32}$").matches(username)) return "账号须为 3–32 位字母、数字或下划线"
        if (password.length !in 8..128) return "密码长度须为 8–128 位"
        return null
    }
    fun eventText(text: String): String? = when {
        text.isBlank() -> "请填写线索内容"
        text.length > 10000 -> "单条线索最多 10000 字符，请分段导入"
        else -> null
    }
}
