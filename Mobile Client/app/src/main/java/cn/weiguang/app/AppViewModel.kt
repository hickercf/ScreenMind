package cn.weiguang.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.weiguang.app.capture.CalendarEntry
import cn.weiguang.app.capture.CaptureService
import cn.weiguang.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Locale
import java.util.UUID

data class EditorDraft(val text: String = "", val source: String = "manual", val type: String = "note", val id: String? = null, val upload: Boolean = false, val occurredAt: String? = null, val uploaded: Boolean = false, val imageUri: Uri? = null)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val updater = AppUpdater(application, viewModelScope)
    val repository = (application as WeiguangApp).repository
    private val capture = CaptureService(application)
    val session = repository.session
    val events = repository.events.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val decisions = repository.decisions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val endpoint = repository.endpoint.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "http://127.0.0.1:8765")
    val theme = repository.theme.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "system")
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var editor by mutableStateOf<EditorDraft?>(null)
    var calendarEntries by mutableStateOf<List<CalendarEntry>?>(null)
    var groups by mutableStateOf<List<Group>>(emptyList())
        private set
    var groupDetail by mutableStateOf<JSONObject?>(null)
        private set
    var invite by mutableStateOf<JSONObject?>(null)
        private set
    private var generationRequest: Pair<List<String>, String>? = null
    var agentWorks by mutableStateOf<List<JSONObject>>(emptyList())
        private set
    var agentCapabilities by mutableStateOf<List<JSONObject>>(emptyList())
        private set
    var agentTasks by mutableStateOf<List<JSONObject>>(emptyList())
        private set
    var agentWork by mutableStateOf<JSONObject?>(null)
        private set
    var agentTask by mutableStateOf<JSONObject?>(null)
        private set
    var agentReceipt by mutableStateOf<JSONObject?>(null)
        private set
    var agentMode by mutableStateOf("正在连接")
        private set
    var agentError by mutableStateOf<String?>(null)
        private set
    var selectedWorkId by mutableStateOf<String?>(null)
        private set
    private var selectedTaskId: String? = null
    private var pollingAgent = false

    fun action(block: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            try { block() }
            catch (_: TimeoutCancellationException) { message = "操作超时，请检查网络或定位设置后重试" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: java.io.IOException) { message = "连接失败，已保留本地数据。请检查服务地址和网络后重试" }
            catch (error: Exception) { message = error.message ?: "操作未完成，请重试" }
            finally { busy = false }
        }
    }
    fun resetForAccount() { groups = emptyList(); groupDetail = null; invite = null; editor = null; calendarEntries = null; generationRequest = null
        agentWorks = emptyList(); agentCapabilities = emptyList(); agentTasks = emptyList(); agentWork = null; agentTask = null; agentReceipt = null
        selectedWorkId = null; selectedTaskId = null; agentError = null; agentMode = "正在连接"
    }

    private suspend fun loadAgent() {
        val account = session.value?.accountKey ?: return
        if (session.value?.endpoint == "offline") return
        val taskId = selectedTaskId; val workId = selectedWorkId
        val snapshot = repository.agentSnapshot(taskId, workId)
        if (account != session.value?.accountKey || selectedTaskId != taskId || selectedWorkId != workId) return
        agentWorks = snapshot.itemObjects("works"); agentCapabilities = snapshot.itemObjects("subagents"); agentTasks = snapshot.itemObjects("tasks")
        agentWork = snapshot.optJSONObject("work"); agentTask = snapshot.optJSONObject("task"); agentReceipt = snapshot.optJSONObject("action")
        val health = snapshot.getJSONObject("health")
        agentMode = if (health.optString("provider") == "mock") "模拟模型 · 验证流程" else "真实模型 · ${health.optString("model")}"
        agentError = null
    }
    suspend fun pollAgent() {
        if (busy || pollingAgent) return
        pollingAgent = true
        try { loadAgent() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: java.io.IOException) { agentError = "暂时无法连接。已有任务仍保存在服务端，恢复网络后可继续。" }
        catch (error: Exception) { agentError = error.message ?: "事项状态读取失败" }
        finally { pollingAgent = false }
    }
    fun refreshAgent() = action { loadAgent() }
    fun selectAgentWork(id: String) = action { selectedWorkId = id; selectedTaskId = null; agentWork = null; agentTask = null; agentReceipt = null; loadAgent() }
    fun selectAgentTask(task: JSONObject) = action {
        selectedTaskId = task.getString("id"); selectedWorkId = task.getString("work_id")
        agentWork = null; agentTask = null; agentReceipt = null; loadAgent()
    }
    fun createAgentWork(title: String, plugin: String) = action {
        check(title.isNotBlank()) { "请填写事项名称" }
        val result = repository.agentMutation("works", JSONObject().put("event_id", UUID.randomUUID().toString()).put("title", title.trim()).put("subagent_id", plugin))
        selectedWorkId = result.getJSONObject("work").getString("id"); selectedTaskId = null
        agentWork = null; agentTask = null; agentReceipt = null; loadAgent()
    }
    fun startAgentTask(instruction: String, selected: List<LocalEvent>) = action {
        val wid = requireNotNull(selectedWorkId) { "请先创建或选择事项" }
        check(instruction.isNotBlank() && selected.isNotEmpty()) { "请填写要求，并选择允许使用的已同步线索" }
        val account = session.value?.accountKey
        check(selected.all { it.accountKey == account && it.syncState == "synced" }) { "线索尚未同步或账号已变化" }
        check(selected.size <= 10) { "一次最多选择10条线索" }
        val refs = JSONArray(selected.map { JSONObject().put("event_id", it.eventId).put("revision", it.revision) })
        val result = repository.agentMutation("tasks", JSONObject().put("request_id", UUID.randomUUID().toString())
            .put("work_id", wid).put("instruction", instruction).put("events", refs))
        selectedTaskId = result.getJSONObject("task").getString("id"); agentTask = null; agentReceipt = null; loadAgent()
    }
    fun respondAgent(kind: String, answer: String = "") = action {
        val task = requireNotNull(agentTask); val output = task.getJSONObject("interaction")
        val body = JSONObject().put("event_id", UUID.randomUUID().toString()).put("expected_revision", task.getInt("revision"))
            .put("output_version", output.getInt("output_version")).put("kind", kind)
        when (kind) {
            "answer" -> { check(answer.isNotBlank()) { "请填写回答" }; body.put("question_id", output.getString("question_id")).put("answer", answer) }
            "accept_state", "reject_state" -> body.put("proposal_id", output.getString("proposal_id"))
            "authorize_action", "reject_action" -> body.put("action_id", output.getString("action_id"))
        }
        repository.agentMutation("tasks/${task.getString("id")}/responses", body); loadAgent()
    }
    fun controlAgent(kind: String, text: String = "") = action {
        val task = requireNotNull(agentTask)
        val body = JSONObject().put("event_id", UUID.randomUUID().toString()).put("expected_revision", task.getInt("revision")).put("kind", kind)
        if (kind == "revise") { check(text.isNotBlank()) { "请填写补充要求" }; body.put("message", text) }
        repository.agentMutation("tasks/${task.getString("id")}/actions", body); loadAgent()
    }
    fun previewCalendar(itemId: String, end: String) = action {
        val work = requireNotNull(agentWork)
        val time = java.time.LocalDateTime.parse(end.trim()).atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString()
        val result = repository.agentMutation("works/${work.getString("id")}/action-proposals", JSONObject()
            .put("event_id", UUID.randomUUID().toString()).put("expected_state_version", work.getInt("memory_version"))
            .put("item_id", itemId).put("end", time).put("tool", "calendar.export"))
        selectedTaskId = result.getJSONObject("task").getString("id"); agentTask = null; agentReceipt = null; loadAgent()
    }
    fun reconcileAgent() = action {
        val id = requireNotNull(agentReceipt).getString("id")
        repository.agentMutation("actions/$id/reconcile", JSONObject()); loadAgent()
    }
    fun saveAgentCalendar(uri: Uri, actionId: String) = action {
        val data = repository.agentCalendar(actionId)
        withContext(Dispatchers.IO) {
            requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri)).use { it.write(data.getString("content").toByteArray(Charsets.UTF_8)) }
        }
        message = "日历文件已保存，请在日历应用中导入；保存文件不代表导入完成。"
    }
    fun login(username: String, password: String, register: Boolean) = action {
        repository.authenticate(username.trim(), password, register)
        message = if (register) "账号已创建" else "登录成功"
    }
    fun offline() = action { repository.enterOffline() }
    fun logout() = action {
        val revoked = repository.logout()
        resetForAccount()
        message = if (revoked) "已退出登录" else "已在本机退出，服务端会话因网络问题未能撤销，将按有效期失效"
    }
    fun saveEndpoint(value: String) = action { repository.setEndpoint(value); message = "服务地址已保存" }
    fun setTheme(value: String) = action { repository.setTheme(value) }
    fun recognize(uri: Uri) = action { editor = EditorDraft(text = capture.recognize(uri), source = "screenshot_ocr", type = "message", imageUri = uri) }
    fun readCalendar() = action { calendarEntries = capture.calendar() }
    fun location() = action {
        val point = capture.location()
        editor = EditorDraft(
            text = String.format(Locale.US, "位置 %.4f, %.4f\n估计精度 %.0f 米\n定位采样 %s", point.latitude, point.longitude, point.accuracy, Instant.ofEpochMilli(point.time)),
            source = "location", type = "location", occurredAt = Instant.ofEpochMilli(point.time).toString()
        )
    }
    fun notifyTest() = action { capture.notifyTest(); message = "手机通知已发出，请人工检查穿戴设备是否收到" }
    fun edit(event: LocalEvent) {
        if (event.syncState in listOf("pending", "failed")) { message = "请先同步这条线索，再修改内容"; return }
        editor = EditorDraft(event.text, event.sourceKind, event.eventType, event.eventId, event.consentToUpload, event.occurredAt, event.serverRevision > 0)
    }
    fun saveEditor(value: EditorDraft) = action {
        repository.saveEvent(value.text, value.source, value.type, value.upload, value.id, value.occurredAt)
        editor = null
        message = if (value.upload && session.value?.endpoint != "offline") "已保存，等待网络上传" else "已保存在本机"
    }
    fun upload(id: String) = action { repository.uploadEvent(id); message = "已加入上传队列" }
    fun delete(id: String) = action { repository.removeEvent(id); message = "已移除线索，已上传内容将同步删除" }
    fun sync() = action { repository.sync(); repository.refreshRemote(); message = "同步完成" }
    fun generate() = action {
        repository.sync()
        val ids = events.value.filter { it.syncState == "synced" }.take(10).map { it.eventId }.sorted()
        check(ids.isNotEmpty()) { "请先确认上传至少一条线索，待状态显示已同步后生成建议" }
        val request = generationRequest?.takeIf { it.first == ids } ?: (ids to UUID.randomUUID().toString()).also { generationRequest = it }
        repository.generate(ids, request.second)
        generationRequest = null
        message = "规则演示建议已生成，队友可替换为 AI 服务"
    }
    fun feedback(id: String, value: String) = action { repository.feedback(id, value); repository.refreshRemote(); message = "反馈已保存" }
    fun loadGroups() = action { groups = repository.groups() }
    fun createGroup(name: String) = action { repository.createGroup(name); groups = repository.groups(); message = "测试组已创建" }
    fun joinGroup(code: String) = action { repository.joinGroup(code); groups = repository.groups(); message = "已加入测试组" }
    fun openGroup(id: String) = action { groupDetail = repository.groupDetail(id); invite = null }
    fun createInvite(id: String) = action { invite = repository.invite(id) }
    fun removeMember(groupId: String, memberId: String) = action { repository.removeMember(groupId, memberId); groupDetail = repository.groupDetail(groupId) }
    fun share(groupId: String, eventId: String) = action { repository.share(groupId, eventId); message = "所选文字已共享到测试组" }
    fun unshare(groupId: String, eventId: String) = action { repository.unshare(groupId, eventId); message = "该测试组中的共享已撤回" }
}

