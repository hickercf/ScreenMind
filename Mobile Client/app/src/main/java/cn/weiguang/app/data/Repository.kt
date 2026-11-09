package cn.weiguang.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.*
import cn.weiguang.app.WeiguangApp
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

private val Context.settingsDataStore by preferencesDataStore("settings")

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Repository(private val context: Context, val database: AppDatabase) {
    private val sessions = SessionStore(context)
    private val api = ApiClient()
    private val mutex = Mutex()
    private val sessionState = MutableStateFlow(sessions.load())
    val session: StateFlow<UserSession?> = sessionState.asStateFlow()
    private val settings = context.settingsDataStore
    val endpoint = settings.data.map { it[stringPreferencesKey("endpoint")] ?: "http://127.0.0.1:8765" }
    val theme = settings.data.map { it[stringPreferencesKey("theme")] ?: "system" }
    val events = session.flatMapLatest { current -> current?.let { database.events().observe(it.accountKey) } ?: flowOf(emptyList()) }
    val decisions = session.flatMapLatest { current -> current?.let { database.decisions().observe(it.accountKey) } ?: flowOf(emptyList()) }

    suspend fun setEndpoint(value: String) {
        check(session.value == null) { "请退出当前账号后切换服务" }
        val address = ApiClient.validateEndpoint(value)
        settings.edit { it[stringPreferencesKey("endpoint")] = address }
    }
    suspend fun setTheme(value: String) { settings.edit { it[stringPreferencesKey("theme")] = value } }

    private fun parseSession(data: JSONObject, endpoint: String): UserSession {
        val user = data.getJSONObject("user")
        return UserSession(user.getString("id"), user.getString("username"), data.getString("access_token"), data.getString("refresh_token"), endpoint)
    }
    private fun publishSession(value: UserSession) { sessions.save(value); sessionState.value = value }

    suspend fun authenticate(username: String, password: String, register: Boolean) = mutex.withLock {
        Validation.credentials(username, password)?.let { throw IllegalArgumentException(it) }
        val base = endpoint.first()
        val data = api.call(base, if (register) "/v1/auth/register" else "/v1/auth/login", "POST", JSONObject().put("username", username).put("password", password))
        publishSession(parseSession(data, base))
    }

    suspend fun enterOffline() = mutex.withLock {
        publishSession(UserSession("offline", "离线体验", "", "", "offline"))
    }

    private suspend fun authenticated(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val original = requireNotNull(session.value) { "请先登录" }
        check(original.endpoint != "offline") { "离线体验不连接服务器，请使用测试账号登录" }
        return try { api.call(original.endpoint, path, method, body, original.accessToken) }
        catch (error: ApiFailure) {
            if (error.status != 401) throw error
            val refreshed = try {
                parseSession(api.call(original.endpoint, "/v1/auth/refresh", "POST", JSONObject().put("refresh_token", original.refreshToken)), original.endpoint)
            } catch (refreshError: ApiFailure) {
                if (refreshError.status == 401) {
                    sessions.clear()
                    sessionState.value = null
                    WorkManager.getInstance(context).cancelUniqueWork("sync:${original.accountKey}")
                }
                throw refreshError
            }
            publishSession(refreshed)
            api.call(refreshed.endpoint, path, method, body, refreshed.accessToken)
        }
    }

    suspend fun logout(): Boolean = mutex.withLock {
        val current = session.value ?: return@withLock true
        var revoked = current.endpoint == "offline"
        try {
            if (!revoked) { authenticated("/v1/auth/logout", "POST"); revoked = true }
        } catch (_: java.io.IOException) { /* Local logout still works without connectivity. */ }
        catch (_: ApiFailure) { /* A rejected/expired session is removed locally. */ }
        finally {
            WorkManager.getInstance(context).cancelUniqueWork("sync:${current.accountKey}")
            sessions.clear()
            sessionState.value = null
        }
        revoked
    }

    suspend fun saveEvent(text: String, source: String, type: String, upload: Boolean, existingId: String? = null, occurredAt: String? = null): LocalEvent = mutex.withLock {
        Validation.eventText(text)?.let { throw IllegalArgumentException(it) }
        val current = requireNotNull(session.value) { "请先登录或进入离线体验" }
        val old = existingId?.let { database.events().get(current.accountKey, it) }
        check(old?.syncState !in listOf("pending", "failed")) { "请先完成这条线索的同步，再修改内容" }
        val shouldUpload = (upload || (old?.serverRevision ?: 0) > 0) && current.endpoint != "offline"
        val event = LocalEvent(
            accountKey = current.accountKey, eventId = old?.eventId ?: UUID.randomUUID().toString(),
            revision = if ((old?.serverRevision ?: 0) > 0) old!!.serverRevision + 1 else 1,
            text = text.trim(), sourceKind = old?.sourceKind ?: source, eventType = old?.eventType ?: type,
            capturedAt = old?.capturedAt ?: Instant.now().toString(), occurredAt = occurredAt ?: old?.occurredAt,
            syncState = if (shouldUpload) "pending" else "local", consentToUpload = shouldUpload,
            serverRevision = old?.serverRevision ?: 0
        )
        database.events().save(event)
        if (shouldUpload) scheduleSync(current.accountKey)
        event
    }

    fun scheduleSync(accountKey: String) {
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf("account" to accountKey))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("sync:$accountKey", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }

    suspend fun sync(expectedAccount: String? = null) = mutex.withLock {
        val current = session.value ?: return@withLock
        if (current.endpoint == "offline" || (expectedAccount != null && expectedAccount != current.accountKey)) return@withLock
        for (event in database.events().pending(current.accountKey)) {
            try {
                if (event.deleted) {
                    authenticated("/v1/events/${event.eventId}", "DELETE")
                    database.events().remove(current.accountKey, event.eventId)
                } else {
                    authenticated("/v1/events", "POST", event.toPayload())
                    database.events().mark(current.accountKey, event.eventId, event.revision, "synced", event.revision)
                }
            } catch (error: Exception) {
                val status = if (error is ApiFailure && error.status in listOf(409, 422)) "conflict" else "failed"
                database.events().mark(current.accountKey, event.eventId, event.revision, status, event.serverRevision)
                throw error
            }
        }
    }

    suspend fun uploadEvent(id: String) = mutex.withLock {
        val current = requireNotNull(session.value)
        check(current.endpoint != "offline") { "请登录测试账号后上传" }
        val event = requireNotNull(database.events().get(current.accountKey, id))
        check(event.syncState != "conflict") { "版本冲突，请先从云端恢复这条记录" }
        database.events().save(event.copy(consentToUpload = true, syncState = "pending"))
        scheduleSync(current.accountKey)
    }

    suspend fun removeEvent(id: String) = mutex.withLock {
        val current = requireNotNull(session.value)
        val event = database.events().get(current.accountKey, id) ?: return@withLock
        if (event.serverRevision > 0 || event.consentToUpload) {
            database.events().save(event.copy(deleted = true, syncState = "pending", consentToUpload = true))
            scheduleSync(current.accountKey)
        } else database.events().remove(current.accountKey, id)
        database.decisions().clear(current.accountKey)
    }

    suspend fun refreshRemote() = mutex.withLock {
        val current = session.value ?: return@withLock
        if (current.endpoint == "offline") return@withLock
        val remoteEvents = authenticated("/v1/events").itemObjects()
        val remoteIds = remoteEvents.map { it.getString("event_id") }.toSet()
        for (local in database.events().all(current.accountKey)) {
            if (local.syncState in listOf("synced", "conflict") && local.eventId !in remoteIds) {
                database.events().remove(current.accountKey, local.eventId)
            }
        }
        for (item in remoteEvents) {
            val id = item.getString("event_id")
            val local = database.events().get(current.accountKey, id)
            if (local?.syncState in listOf("pending", "failed") || local?.deleted == true) continue
            database.events().save(LocalEvent(current.accountKey, id, item.getInt("revision"), item.getString("event_type"), item.getString("source_kind"), item.getJSONObject("payload").getString("text"), item.getString("captured_at"), if (item.isNull("occurred_at")) null else item.getString("occurred_at"), item.getString("timezone"), "synced", true, false, item.getInt("revision")))
        }
        val remote = authenticated("/v1/decisions").itemObjects()
        database.decisions().clear(current.accountKey)
        for (item in remote) database.decisions().save(LocalDecision(current.accountKey, item.getString("decision_id"), item.toString()))
    }

    suspend fun generate(ids: List<String>, requestId: String): Unit = mutex.withLock {
        val current = requireNotNull(session.value)
        check(ids.isNotEmpty()) { "请先保存至少一条线索" }
        check(current.endpoint != "offline") { "请登录并上传线索后调用联调建议服务" }
        val result = authenticated("/v1/decisions/generate", "POST", JSONObject().put("request_id", requestId).put("event_ids", JSONArray(ids)))
        database.decisions().save(LocalDecision(current.accountKey, result.getString("decision_id"), result.toString()))
    }

    suspend fun feedback(id: String, action: String) = mutex.withLock {
        authenticated("/v1/decisions/$id/feedback", "PUT", JSONObject().put("action", action))
    }
    suspend fun groups(): List<Group> = mutex.withLock {
        if (session.value?.endpoint == "offline") return@withLock emptyList()
        authenticated("/v1/groups").itemObjects().map { Group(it.getString("id"), it.getString("name"), it.getString("role")) }
    }
    suspend fun createGroup(name: String) = mutex.withLock {
        require(name.trim().length in 1..40) { "组名应为 1–40 字符" }
        authenticated("/v1/groups", "POST", JSONObject().put("name", name.trim()))
    }
    suspend fun joinGroup(code: String) = mutex.withLock { authenticated("/v1/groups/join", "POST", JSONObject().put("code", code.trim())) }
    suspend fun groupDetail(id: String) = mutex.withLock { authenticated("/v1/groups/$id") }
    suspend fun invite(id: String) = mutex.withLock { authenticated("/v1/groups/$id/invites", "POST") }
    suspend fun removeMember(groupId: String, userId: String) = mutex.withLock { authenticated("/v1/groups/$groupId/members/$userId", "DELETE") }
    suspend fun share(groupId: String, eventId: String) = mutex.withLock { authenticated("/v1/groups/$groupId/shares", "POST", JSONObject().put("event_id", eventId)) }
    suspend fun unshare(groupId: String, eventId: String) = mutex.withLock { authenticated("/v1/groups/$groupId/shares/$eventId", "DELETE") }

    // Persist the exact command before sending. An uncertain response is retried with
    // the same id and parameters, including after process death; never invent a new id.
    private suspend fun sendAgentCommand(command: AgentCommand): JSONObject {
        check(session.value?.accountKey == command.accountKey) { "账号已变化，请重新打开事项" }
        return try {
            val result = authenticated(command.path, "POST", JSONObject(command.body))
            database.agentCommands().remove(command.accountKey, command.path)
            result
        } catch (error: ApiFailure) {
            if (error.status in listOf(400,403,404,409,422)) database.agentCommands().remove(command.accountKey, command.path)
            throw error
        }
    }

    suspend fun agentMutation(path: String, body: JSONObject): JSONObject = mutex.withLock {
        val account = requireNotNull(session.value).accountKey
        val fullPath = "/v1/agent/$path"
        val prior = database.agentCommands().get(account, fullPath)
        check(prior == null) { "上次操作结果尚未确定，请刷新以核对原请求" }
        val command = AgentCommand(account, fullPath, body.toString())
        database.agentCommands().save(command)
        sendAgentCommand(command)
    }

    suspend fun agentSnapshot(taskId: String?, workId: String?): JSONObject = mutex.withLock {
        val account = requireNotNull(session.value).accountKey
        for (pending in database.agentCommands().all(account)) sendAgentCommand(pending)
        val result = JSONObject().put("account", account)
            .put("health", authenticated("/v1/agent/health"))
            .put("subagents", authenticated("/v1/agent/subagents").getJSONArray("subagents"))
            .put("works", authenticated("/v1/agent/works").getJSONArray("works"))
            .put("tasks", authenticated("/v1/agent/tasks").getJSONArray("tasks"))
        if (workId != null) result.put("work", authenticated("/v1/agent/works/$workId").getJSONObject("work"))
        if (taskId != null) {
            val task = authenticated("/v1/agent/tasks/$taskId").getJSONObject("task")
            result.put("task", task)
            val output = task.optJSONObject("interaction")
            if (output?.optString("kind") == "action_proposal") result.put("action", authenticated("/v1/agent/actions/${output.getString("action_id")}").getJSONObject("action"))
        }
        result
    }

    suspend fun agentCalendar(actionId: String): JSONObject = mutex.withLock {
        authenticated("/v1/agent/actions/$actionId/calendar")
    }
}

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val expectedAccount = inputData.getString("account") ?: return Result.failure()
        val repository = (applicationContext as WeiguangApp).repository
        return try { repository.sync(expectedAccount); Result.success() }
        catch (error: ApiFailure) { if (error.status in listOf(400, 401, 403, 409, 422)) Result.failure() else Result.retry() }
        catch (_: java.io.IOException) { Result.retry() }
    }
}

