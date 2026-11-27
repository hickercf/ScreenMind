package cn.weiguang.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import androidx.work.*
import cn.weiguang.app.WeiguangApp
import cn.weiguang.app.BuildConfig
import cn.weiguang.app.capture.CaptureGrant
import com.chronocapsule.contract.ScreenContract
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit

private val Context.settingsDataStore by preferencesDataStore("settings")
private const val AgentWorksPath = "/v1/agent/works"

private fun sameMoment(first: String?, second: String?): Boolean = first == second ||
    (first != null && second != null && runCatching {
        Instant.parse(first).truncatedTo(ChronoUnit.MICROS) == Instant.parse(second).truncatedTo(ChronoUnit.MICROS)
    }.getOrDefault(false))

private fun LocalEvent.sameContents(other: LocalEvent): Boolean =
    text == other.text && eventType == other.eventType && sourceKind == other.sourceKind &&
        sameMoment(occurredAt, other.occurredAt) && sameMoment(capturedAt, other.capturedAt) &&
        timezone == other.timezone && screenJson == other.screenJson

private fun LocalEvent.asPrivateCopy() = copy(
    eventId = UUID.randomUUID().toString(), revision = 1, serverRevision = 0, syncState = "local_copy",
    consentToUpload = false, deleted = false, agentWorkId = null, agentRequestId = null, agentTaskId = null, agentError = null,
)

internal val LocalEvent.needsReconciliation: Boolean get() = syncState == "conflict" || syncState == "rejected"

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Repository(private val context: Context, val database: AppDatabase) {
    private val sessions = SessionStore(context)
    private val api = ApiClient()

    fun homeUpdates(account: String) = api.homeUpdates(requireNotNull(session.value).also {
        check(it.accountKey == account) { "账号已变化" }
    })
    private val mutex = Mutex()
    private val sessionState = MutableStateFlow(sessions.load()?.let {
        if (CloudEndpoint.acceptsSession(it.endpoint)) it else {
            sessions.clear()
            null
        }
    })
    val session: StateFlow<UserSession?> = sessionState.asStateFlow()
    private val settings = context.settingsDataStore
    val endpoint = settings.data.map { CloudEndpoint.selected(it[stringPreferencesKey("endpoint")]) }
    val theme = settings.data.map { it[stringPreferencesKey("theme")] ?: "system" }
    val events = session.flatMapLatest { current -> current?.let { database.events().observe(it.accountKey) } ?: flowOf(emptyList()) }
    val decisions = session.flatMapLatest { current -> current?.let { database.decisions().observe(it.accountKey) } ?: flowOf(emptyList()) }
    val captureGrants: Flow<CaptureGrant?> = combine(session, settings.data) { current, prefs ->
        current?.takeUnless { it.endpoint == "offline" }?.let { captureGrant(it.accountKey, prefs) }
    }.distinctUntilChanged()

    suspend fun setEndpoint(value: String) {
        check(!BuildConfig.LOCK_CLOUD_ENDPOINT) { "此版本已连接 ScreenMind 云端" }
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
            settings.edit { clearCapture(it, current.accountKey) }
            sessions.clear()
            sessionState.value = null
        }
        revoked
    }

    suspend fun saveEvent(
        text: String, source: String, type: String, upload: Boolean,
        existingId: String? = null, occurredAt: String? = null, clearOccurredAt: Boolean = false,
        expectedAccount: String? = null, openedEvent: LocalEvent? = null,
    ): LocalEvent = mutex.withLock {
        val current = requireNotNull(session.value) { "请先登录或进入离线体验" }
        check(expectedAccount == null || expectedAccount == current.accountKey) { "账号已变化，请重新打开这条线索。" }
        check(openedEvent == null || (openedEvent.accountKey == current.accountKey && openedEvent.eventId == existingId)) {
            "账号或线索已变化，请重新打开。"
        }
        val latest = existingId?.let { database.events().get(current.accountKey, it) }?.takeUnless { it.deleted }
        val changedWhileEditing = openedEvent != null &&
            (latest == null || latest.needsReconciliation || !openedEvent.sameContents(latest))
        val old = if (changedWhileEditing) openedEvent else latest
        check(existingId == null || old != null) { "这条线索已移除，请返回线索列表。" }
        check(changedWhileEditing || (old?.syncState !in listOf("pending", "failed") && old?.needsReconciliation != true)) {
            "请先完成这条线索的同步，再修改内容"
        }
        val shouldUpload = (upload || (old?.serverRevision ?: 0) > 0) && current.endpoint != "offline"
        val keepScreen = old?.screenJson != null && text == old.text
        if (!keepScreen) Validation.eventText(text)?.let { throw IllegalArgumentException(it) }
        val body = if (keepScreen) text else text.trim()
        val base = old ?: LocalEvent(accountKey = current.accountKey, text = body, sourceKind = source, eventType = type)
        val sameText = if (base.screenJson != null) keepScreen else body == base.text.trim()
        val event = base.copy(
            revision = base.serverRevision + 1,
            text = body, occurredAt = if (clearOccurredAt) null else occurredAt ?: base.occurredAt,
            syncState = when {
                shouldUpload -> "pending"
                base.syncState == "local_copy" -> "local_copy"
                else -> "local"
            }, consentToUpload = shouldUpload,
            screenJson = base.screenJson.takeIf { sameText },
            agentWorkId = base.agentWorkId.takeIf { sameText }, agentRequestId = base.agentRequestId.takeIf { sameText },
            agentTaskId = base.agentTaskId.takeIf { sameText }, agentError = base.agentError.takeIf { sameText },
        )
        if (changedWhileEditing) {
            if (latest?.syncState == "synced" && latest.sameContents(event)) return@withLock latest
            val copy = event.asPrivateCopy()
            database.events().save(copy)
            return@withLock copy
        }
        database.events().save(event)
        if (shouldUpload) scheduleSync(current.accountKey)
        event
    }

    /** Durable evidence + stable task request are saved together before any network call. */
    suspend fun saveCapture(payload: ScreenContract.Payload, expectedAccount: String, workId: String? = null,
                            upload: Boolean = false, requireTarget: Boolean = false, occurredAt: String? = null,
                            expectedCaptureToken: String? = null): LocalEvent = mutex.withLock {
        val current = requireNotNull(session.value) { "请先登录" }
        check(current.accountKey == expectedAccount) { "账号已变化，请重新采集" }
        if (requireTarget) check(captureTarget(expectedAccount) == workId) { "自动记录授权已关闭或事项已变化" }
        if (expectedCaptureToken != null) check(captureGrant(expectedAccount)?.token == expectedCaptureToken) { "采集已暂停或授权已变化" }
        check(payload.validate().isEmpty() && payload.blocks.isNotEmpty()) { "识别结果不符合输入要求" }
        val send = upload && !requireTarget && current.endpoint != "offline"
        val destination = workId.takeUnless { requireTarget }
        val event = LocalEvent(accountKey = current.accountKey, sourceKind = "screenshot_ocr", eventType = "message",
            text = payload.blocks.joinToString("\n") { it.text }, capturedAt = payload.capturedAt, occurredAt = occurredAt,
            screenJson = payload.toJson().toString(), consentToUpload = send, syncState = if (send) "pending" else "local",
            agentWorkId = destination, agentRequestId = if (destination != null) UUID.randomUUID().toString() else null)
        database.events().save(event)
        if (send) scheduleSync(current.accountKey)
        event
    }

    suspend fun setCaptureTarget(workId: String?, enabled: Boolean, expectedToken: String? = null) = mutex.withLock {
        val current = requireNotNull(session.value)
        check(current.endpoint != "offline") { "请先登录云端账号" }
        if (expectedToken != null) check(captureGrant(current.accountKey)?.token == expectedToken) { "采集授权已变化" }
        var title = ""
        if (enabled) {
            check(workId != null) { "请先选择事项" }
            title = authenticated("/v1/agent/works/$workId").getJSONObject("work").getString("title")
        }
        settings.edit {
            clearCapture(it, current.accountKey)
            if (enabled && workId != null) {
                it[stringPreferencesKey("capture:${current.accountKey}")] = workId
                it[stringPreferencesKey("captureTitle:${current.accountKey}")] = title
                it[stringPreferencesKey("captureToken:${current.accountKey}")] = UUID.randomUUID().toString()
            }
        }
    }

    suspend fun setCapturePaused(paused: Boolean, expectedToken: String) = mutex.withLock {
        val current = requireNotNull(session.value)
        val grant = requireNotNull(captureGrant(current.accountKey)) { "请先开启事项自动记录" }
        check(grant.token == expectedToken) { "事项已变化，请重试" }
        settings.edit {
            it[booleanPreferencesKey("capturePaused:${current.accountKey}")] = paused
            it[stringPreferencesKey("captureToken:${current.accountKey}")] = UUID.randomUUID().toString()
        }
    }

    private fun clearCapture(prefs: androidx.datastore.preferences.core.MutablePreferences, account: String) {
        for (prefix in listOf("capture", "captureTitle", "captureToken")) prefs.remove(stringPreferencesKey("$prefix:$account"))
        prefs.remove(booleanPreferencesKey("capturePaused:$account"))
    }

    private fun captureGrant(account: String, prefs: Preferences): CaptureGrant? {
        val work = prefs[stringPreferencesKey("capture:$account")] ?: return null
        return CaptureGrant(account, work, prefs[stringPreferencesKey("captureTitle:$account")] ?: "当前事项",
            prefs[stringPreferencesKey("captureToken:$account")] ?: "legacy:$work",
            prefs[booleanPreferencesKey("capturePaused:$account")] ?: false)
    }

    suspend fun captureGrant(account: String): CaptureGrant? {
        if (session.value?.accountKey != account || session.value?.endpoint == "offline") return null
        return captureGrant(account, settings.data.first())
    }

    suspend fun captureTarget(account: String): String? {
        return captureGrant(account)?.takeUnless { it.paused }?.workId
    }

    fun scheduleSync(accountKey: String) {
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf("account" to accountKey))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("sync:$accountKey", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }

    suspend fun sync(expectedAccount: String? = null): Boolean = mutex.withLock {
        val current = session.value ?: return@withLock false
        if (current.endpoint == "offline" || (expectedAccount != null && expectedAccount != current.accountKey)) return@withLock false
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
                val status = when ((error as? ApiFailure)?.status) {
                    409 -> "conflict"
                    422 -> "rejected"
                    else -> "failed"
                }
                database.events().mark(current.accountKey, event.eventId, event.revision, status, event.serverRevision)
                if (status == "failed") throw error
            }
        }
        // Every accepted capture has its own request; never drop a screen while another task runs.
        val saved = database.events().all(current.accountKey)
        for (event in saved.filter {
            !it.deleted && it.syncState == "synced" && it.agentWorkId != null && it.agentTaskId == null && it.agentError == null
        }) {
            val body = JSONObject().put("request_id", requireNotNull(event.agentRequestId))
                .put("work_id", event.agentWorkId).put("instruction", "请根据新增聊天材料整理或更新当前事项，缺少信息先澄清，只生成候选等待用户确认。")
                .put("events", JSONArray().put(JSONObject().put("event_id", event.eventId).put("revision", event.revision)))
            try {
                val result = authenticated("/v1/agent/tasks", "POST", body)
                database.events().save(event.copy(agentTaskId = result.getJSONObject("task").getString("id")))
            } catch (error: ApiFailure) {
                if (error.status in listOf(400, 403, 404, 409, 422)) {
                    database.events().save(event.copy(agentError = error.message))
                } else throw error // transient errors keep the same durable request for WorkManager retry
            }
        }
        saved.any { it.needsReconciliation }
    }

    suspend fun uploadEvent(id: String) = mutex.withLock {
        val current = requireNotNull(session.value)
        check(current.endpoint != "offline") { "请登录测试账号后上传" }
        val event = requireNotNull(database.events().get(current.accountKey, id))
        check(!event.deleted && !event.needsReconciliation) { "请先同步，再打开这条线索。" }
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

    suspend fun refreshRemote(expectedAccount: String? = null): Int = mutex.withLock {
        val current = session.value ?: return@withLock 0
        val account = current.accountKey
        if (current.endpoint == "offline" || (expectedAccount != null && expectedAccount != account)) return@withLock 0
        val items = authenticated("/v1/events").getJSONArray("items")
        val incoming = mutableMapOf<String, LocalEvent>()
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            val id = item.getString("event_id")
            val revision = item.getInt("revision")
            val payload = item.getJSONObject("payload")
            incoming[id] = LocalEvent(accountKey = account, eventId = id, revision = revision, serverRevision = revision,
                eventType = item.getString("event_type"), sourceKind = item.getString("source_kind"), text = payload.getString("text"),
                capturedAt = item.getString("captured_at"), occurredAt = if (item.isNull("occurred_at")) null else item.getString("occurred_at"),
                timezone = item.getString("timezone"), syncState = "synced", consentToUpload = true,
                screenJson = payload.optJSONObject("screen")?.toString())
        }
        val decisions = authenticated("/v1/decisions").getJSONArray("items")
        database.withTransaction {
            val events = database.events()
            var copies = 0
            for (local in events.all(account)) {
                val remote = incoming.remove(local.eventId)
                if (local.syncState in listOf("pending", "failed")) continue
                if (local.deleted) {
                    if (remote == null) events.remove(account, local.eventId)
                    continue
                }
                val protectLocal = local.needsReconciliation || (remote != null && !local.consentToUpload)
                if (protectLocal && (remote == null || !local.sameContents(remote))) {
                    events.save(local.asPrivateCopy())
                    copies++
                }
                if (remote == null) {
                    if (local.syncState == "synced" || local.needsReconciliation) events.remove(account, local.eventId)
                    continue
                }
                val updated = if (local.syncState == "synced" && local.serverRevision == remote.revision) {
                    remote.copy(agentWorkId = local.agentWorkId, agentRequestId = local.agentRequestId,
                        agentTaskId = local.agentTaskId, agentError = local.agentError)
                } else remote
                if (updated != local) events.save(updated)
            }
            for (event in incoming.values) events.save(event)
            database.decisions().clear(account)
            for (index in 0 until decisions.length()) {
                val item = decisions.getJSONObject(index)
                database.decisions().save(LocalDecision(account, item.getString("decision_id"), item.toString()))
            }
            copies
        }
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
    private suspend fun sendAgentCommand(command: AgentCommand, keepReceipt: Boolean = false): JSONObject {
        check(session.value?.accountKey == command.accountKey) { "账号已变化，请重新打开事项" }
        return try {
            val result = authenticated(command.path, "POST", JSONObject(command.body))
            if (!keepReceipt) database.agentCommands().remove(command.accountKey, command.path)
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
        if (prior != null && fullPath == AgentWorksPath) {
            val original = JSONObject(prior.body)
            if (original.getString("title") == body.getString("title") &&
                original.getString("subagent_id") == body.getString("subagent_id")
            ) return@withLock sendAgentCommand(prior)
        }
        check(prior == null) { "上一次操作还在确认中，请稍后刷新。" }
        val command = AgentCommand(account, fullPath, body.toString())
        database.agentCommands().save(command)
        sendAgentCommand(command)
    }

    suspend fun agentSnapshot(taskId: String?, workId: String?): JSONObject = mutex.withLock {
        val account = requireNotNull(session.value).accountKey
        var recoveredWork: JSONObject? = null
        for (pending in database.agentCommands().all(account)) {
            val creatingWork = pending.path == AgentWorksPath
            val receipt = sendAgentCommand(pending, keepReceipt = creatingWork)
            if (creatingWork) recoveredWork = JSONObject()
                .put("work", receipt.getJSONObject("work"))
                .put("event_id", JSONObject(pending.body).getString("event_id"))
        }
        val result = JSONObject().put("account", account)
            .put("health", authenticated("/v1/agent/health"))
            .put("subagents", authenticated("/v1/agent/subagents").getJSONArray("subagents"))
            .put("works", authenticated("/v1/agent/works").getJSONArray("works"))
            .put("tasks", authenticated("/v1/agent/tasks").getJSONArray("tasks"))
        if (recoveredWork != null) result.put("recovered_work", recoveredWork)
        if (workId != null) {
            result.put("work", authenticated("/v1/agent/works/$workId").getJSONObject("work"))
            try {
                result.put("plan", authenticated("/v1/agent/works/$workId/plan"))
            } catch (error: ApiFailure) {
                if (error.status != 404) throw error
            }
        }
        if (taskId != null) {
            val task = authenticated("/v1/agent/tasks/$taskId").getJSONObject("task")
            result.put("task", task)
            val output = task.optJSONObject("interaction")
            if (output?.optString("kind") == "action_proposal") result.put("action", authenticated("/v1/agent/actions/${output.getString("action_id")}").getJSONObject("action"))
        }
        result
    }

    suspend fun acknowledgeRecoveredWork(account: String, eventId: String) = mutex.withLock {
        if (session.value?.accountKey != account) return@withLock
        val commands = database.agentCommands()
        val saved = commands.get(account, AgentWorksPath) ?: return@withLock
        if (JSONObject(saved.body).getString("event_id") == eventId) commands.remove(account, AgentWorksPath)
    }

    suspend fun agentCalendar(actionId: String): JSONObject = mutex.withLock {
        authenticated("/v1/agent/actions/$actionId/calendar")
    }

    private suspend fun readHome(): JSONObject = try {
        authenticated("/api/v1/home/timeline?clientCapabilities=ANDROID_COMPAT")
    } catch (error: ApiFailure) {
        if (error.status != 404) throw error
        authenticated("/v1/home/sdui?live=true&follow=true")
    }

    suspend fun homeSdui(account: String): JSONObject = mutex.withLock {
        check(session.value?.accountKey == account) { "账号已变化" }
        readHome()
    }

    suspend fun homeDetail(account: String, id: String): JSONObject = mutex.withLock {
        check(session.value?.accountKey == account) { "账号已变化" }
        authenticated("/api/v1/events/" + android.net.Uri.encode(id) + "/detail")
    }

    suspend fun searchHomePlaces(account: String, city: String, query: String): JSONObject = mutex.withLock {
        check(session.value?.accountKey == account) { "账号已变化" }
        val encoded = android.net.Uri.encode(query)
        val code = android.net.Uri.encode(city)
        authenticated("/api/v1/places/search?cityCode=$code&query=$encoded")
    }

    suspend fun homeContractAction(account: String, path: String, method: String, body: JSONObject): JSONObject = mutex.withLock {
        check(session.value?.accountKey == account) { "账号已变化" }
        authenticated(path, method, body)
        readHome()
    }

    suspend fun homeSduiAction(account: String, body: JSONObject, live: Boolean = true): JSONObject = mutex.withLock {
        check(session.value?.accountKey == account) { "账号已变化" }
        authenticated("/v1/home/sdui/actions?live=$live", "POST", body)
        readHome()
    }
}

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val expectedAccount = inputData.getString("account") ?: return Result.failure()
        val repository = (applicationContext as WeiguangApp).repository
        return try {
            if (repository.sync(expectedAccount)) repository.refreshRemote(expectedAccount)
            Result.success()
        }
        catch (error: ApiFailure) { if (error.status in listOf(400, 401, 403, 409, 422)) Result.failure() else Result.retry() }
        catch (_: java.io.IOException) { Result.retry() }
    }
}

