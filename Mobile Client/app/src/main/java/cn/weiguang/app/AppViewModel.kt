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
import cn.weiguang.app.home.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
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

data class EditorDraft(val text: String = "", val source: String = "manual", val type: String = "note", val id: String? = null, val upload: Boolean = false, val occurredAt: String? = null, val uploaded: Boolean = false, val imageUri: Uri? = null,
    val screenJson: String? = null)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val updater = AppUpdater(application, viewModelScope)
    val repository = (application as WeiguangApp).repository
    private val capture = CaptureService(application)
    val session = repository.session
    val captureGrant = repository.captureGrants.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val events = repository.events.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val decisions = repository.decisions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val endpoint = repository.endpoint.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CloudEndpoint.default)
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
    var agentPlan by mutableStateOf<JSONObject?>(null)
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
    private var homeAccount by mutableStateOf<String?>(null)
    private val homePreferences = application.getSharedPreferences("home_card_states", 0)
    private var demoHomeMutation by mutableStateOf(LocalAuraMutationState())
    private var serverHomeSdui by mutableStateOf<AuraFlowState?>(null)
    private var localHomeMutation by mutableStateOf(LocalAuraMutationState())
    var homePillActive by mutableStateOf(false)
        private set
    var homePillText by mutableStateOf("")
        private set
    var homePillTheme by mutableStateOf<String?>(null)
        private set
    private var pillJob: Job? = null

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
        agentWorks = emptyList(); agentCapabilities = emptyList(); agentTasks = emptyList(); agentWork = null; agentPlan = null; agentTask = null; agentReceipt = null
        selectedWorkId = null; selectedTaskId = null; agentError = null; agentMode = "正在连接"
        pillJob?.cancel()
        homeAccount = session.value?.accountKey
        fun saved(field: String) = homePreferences.getStringSet("$homeAccount/$field", emptySet()).orEmpty().toSet()
        demoHomeMutation = LocalAuraMutationState()
        val completed = JSONObject(homePreferences.getString("$homeAccount/completionTimes", "{}") ?: "{}")
        localHomeMutation = LocalAuraMutationState(confirmedIds = saved("confirmed"),
            completedAt = completed.keys().asSequence().associateWith { completed.getString(it) },
            dismissedIds = saved("dismissed"), dismissedReminders = saved("reminders"))
        serverHomeSdui = null; homePillActive = false; homePillText = ""; homePillTheme = null
    }

    private suspend fun loadAgent() {
        val account = session.value?.accountKey ?: return
        if (session.value?.endpoint == "offline") return
        val taskId = selectedTaskId; val workId = selectedWorkId
        val snapshot = repository.agentSnapshot(taskId, workId)
        if (account != session.value?.accountKey || selectedTaskId != taskId || selectedWorkId != workId) return
        agentWorks = snapshot.itemObjects("works"); agentCapabilities = snapshot.itemObjects("subagents"); agentTasks = snapshot.itemObjects("tasks")
        agentWork = snapshot.optJSONObject("work"); agentTask = snapshot.optJSONObject("task"); agentReceipt = snapshot.optJSONObject("action")
        val nextPlan = snapshot.optJSONObject("plan")
        if (agentPlan?.toString() != nextPlan?.toString()) agentPlan = nextPlan
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
    fun setAutoCapture(enabled: Boolean) = action {
        repository.setCaptureTarget(selectedWorkId, enabled)
        message = if (enabled) "已允许将聊天文字整理到当前事项，请在系统设置开启微光聊天记录" else "已关闭事项自动记录"
    }
    fun pauseAutoCapture() = action {
        val target = requireNotNull(captureGrant.value)
        repository.setCapturePaused(!target.paused, target.token)
    }
    fun selectAgentWork(id: String, taskId: String? = null) = action {
        selectedWorkId = id
        selectedTaskId = taskId
        agentWork = null
        agentPlan = null
        agentTask = null
        agentReceipt = null
        loadAgent()
    }
    fun selectAgentTask(task: JSONObject) = selectAgentWork(task.getString("work_id"), task.getString("id"))
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
    fun recognize(uri: Uri) = action {
        val account = requireNotNull(session.value).accountKey
        val payload = capture.structured(uri)
        check(account == session.value?.accountKey) { "账号已变化，请重新导入" }
        editor = EditorDraft(text = payload.blocks.joinToString("\n") { it.text }, source = "screenshot_ocr", type = "message",
            imageUri = uri, screenJson = payload.toJson().toString())
    }
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
        if (value.screenJson != null && value.id == null) {
            val payload = com.chronocapsule.contract.ScreenContract.Payload.fromJson(JSONObject(value.screenJson))
            cn.weiguang.app.capture.ChronoCapture.save(getApplication(), payload, requireNotNull(session.value).accountKey,
                upload = value.upload, occurredAt = value.occurredAt)
        } else repository.saveEvent(value.text, value.source, value.type, value.upload, value.id, value.occurredAt)
        editor = null
        message = if (value.upload && session.value?.endpoint != "offline") "已保存，等待网络上传" else "已保存在本机"
    }
    fun upload(id: String) = action { repository.uploadEvent(id); message = "已加入上传队列" }
    fun delete(id: String) = action { repository.removeEvent(id); message = "已移除线索，已上传内容将同步删除" }
    fun sync() = action {
        repository.sync()
        repository.refreshRemote()
        fetchServerHomeSduiSilent()
        message = "同步完成"
    }
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

    // =========================================================================
    // AURAFLOW HOME SDUI CONTROLLER (SCOPED TO HOME MODULE)
    // =========================================================================

    fun effectiveHomeSdui(localEvents: List<LocalEvent>, localDecisions: List<LocalDecision>): AuraFlowState {
        if (homeAccount != session.value?.accountKey) return AuraFlowEngine.synthesize(localEvents, localDecisions, emptyList(), LocalAuraMutationState())
        if (demoHomeMutation.showcaseMode) return AuraFlowEngine.synthesize(emptyList(), emptyList(), emptyList(), demoHomeMutation)
        serverHomeSdui?.takeIf { it.showcaseMode }?.let { return it }
        val local = AuraFlowEngine.synthesize(localEvents, localDecisions, agentWorks, localHomeMutation)
        val remote = serverHomeSdui?.takeIf { homeAccount == session.value?.accountKey && !it.showcaseMode }
        val sources = localEvents.associateBy { it.eventId }
        val localCards = local.events.associateBy { it.id }
        val cards = remote?.events.orEmpty().associateBy { it.id }.toMutableMap()
        for ((id, card) in localCards) {
            val server = cards[id]
            if (remote != null && server == null && sources[card.sourceEventId]?.syncState == "synced") continue
            if (server == null || sources[card.sourceEventId]?.syncState != "synced") cards[id] = card
            else cards[id] = server.copy(sourceWorkId = server.sourceWorkId ?: card.sourceWorkId,
                sourceTaskId = server.sourceTaskId ?: card.sourceTaskId)
        }
        val hidden = remote?.dismissedIds.orEmpty() + localHomeMutation.dismissedIds
        fun adjusted(card: AuraCard): AuraCard {
            val stage = when (card.id) {
                in localHomeMutation.completedAt -> LifecycleStage.ARCHIVED
                in localHomeMutation.confirmedIds -> if (card.lifecycleStage == LifecycleStage.PROPOSAL) LifecycleStage.CONFIRMED else card.lifecycleStage
                else -> card.lifecycleStage
            }
            return card.copy(lifecycleStage = stage, completedAt = card.completedAt ?: localHomeMutation.completedAt[card.id],
                badge = if (stage != card.lifecycleStage) (if (stage == LifecycleStage.ARCHIVED) "已完成" else "已确认") else card.badge,
                reminderStrip = card.reminderStrip?.takeUnless { card.id in localHomeMutation.dismissedReminders })
        }
        val visible = cards.values.filterNot { it.id in hidden }.map(::adjusted)
        val relay = (remote?.crossDayRelay ?: local.crossDayRelay)?.takeUnless {
            it.id in hidden || "evt_${it.sourceEventId}" in hidden
        }?.let(::adjusted)
        return (remote ?: local).copy(events = visible, crossDayRelay = relay,
            dayMode = if (remote != null && remote.dayModeOverride != "AUTO") remote.dayMode
                else if (visible.isEmpty()) DayMode.EMPTY_EXPLORE else DayMode.ACTIVE_TIMELINE)
    }

    suspend fun refreshHomeSdui() = fetchServerHomeSduiSilent()

    private suspend fun fetchServerHomeSduiSilent() {
        val current = session.value ?: return
        if (current.endpoint == "offline") return
        try {
            var remote = AuraFlowState.fromJson(repository.homeSdui(current.accountKey))
            if (session.value?.accountKey != current.accountKey) return
            val previous = serverHomeSdui
            serverHomeSdui = remote
            if (remote.showcaseMode) return
            if (BuildConfig.DEBUG && current.endpoint in setOf("http://127.0.0.1:8765", "http://10.0.2.2:8765") &&
                (previous?.showcaseMode != false || previous.realEventCount != remote.realEventCount ||
                    previous.events.map { it.sourceEventId } != remote.events.map { it.sourceEventId })) {
                repository.refreshRemote()
                if (session.value?.accountKey != current.accountKey) return
            }
            val key = "${current.accountKey}/pending"
            // Only send a local choice once its source card already exists in this account's cloud timeline.
            for (command in homePreferences.getStringSet(key, emptySet()).orEmpty().toList()) {
                val id = command.substringAfter('/')
                val kind = command.substringBefore('/')
                if (remote.findCard(id) == null && id !in remote.dismissedIds) continue
                if (id !in remote.dismissedIds) {
                    val body = JSONObject().put("action", kind).put("card_id", id)
                    if (kind == "dismiss_misjudgment") body.put("reason", "not_my_schedule")
                    remote = AuraFlowState.fromJson(repository.homeSduiAction(current.accountKey, body))
                    if (session.value?.accountKey != current.accountKey) return
                    serverHomeSdui = remote
                }
                val pending = homePreferences.getStringSet(key, emptySet()).orEmpty() - command
                homePreferences.edit().putStringSet(key, pending).apply()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Local-only records remain available while the server is unreachable.
        }
    }

    private fun pulseHomePill(text: String, themeKey: String, durationMs: Long = 1400L, onComplete: (() -> Unit)? = null) {
        pillJob?.cancel()
        homePillText = text
        homePillTheme = themeKey
        homePillActive = true
        pillJob = viewModelScope.launch {
            delay(durationMs)
            homePillActive = false
            homePillTheme = null
            onComplete?.invoke()
        }
    }

    private suspend fun sendHomeAction(account: String, body: JSONObject, live: Boolean): JSONObject {
        val state = serverHomeSdui
        val card = state?.findCard(body.optString("card_id"))
        if (state?.timelineIds == null || card == null) return repository.homeSduiAction(account, body, live)
        val event = "/api/v1/events/" + Uri.encode(card.id)
        val params = JSONObject()
        var method = "POST"
        var retryKey: String? = null
        val path = when (body.getString("action")) {
            "dismiss_misjudgment" -> {
                params.put("reason", body.optString("reason", "USER_SWIPE_DISMISS"))
                "/api/v1/home/events/" + Uri.encode(card.id) + "/dismiss"
            }
            "dismiss_reminder" -> "/api/v1/home/reminders/rem_" + Uri.encode(card.id) + "/ack"
            "select_plan" -> {
                val id = (card.travelPlans.map { it.id } + card.diningPlans.map { it.id })[body.getInt("plan_idx")]
                params.put("entityKey", "selectedPlanId").put("newValue", id)
                event + "/recalculate"
            }
            "confirm_card" -> {
                val detail = repository.homeDetail(account, card.id)
                val dock = detail.optJSONObject("stickyDock")
                check(dock?.optString("dockType") == "SLIDE_CONFIRM_BAR") { "提案已变化，请刷新" }
                val selected = detail.getJSONObject("stateBindings").opt("selectedPlanId")
                retryKey = account + "/confirm/" + card.id + "/" + selected
                val key = homePreferences.getString(retryKey, null) ?: UUID.randomUUID().toString().also {
                    homePreferences.edit().putString(retryKey, it).apply()
                }
                params.put("actionId", dock!!.getString("actionId")).put("selectedPlanId", selected).put("idempotencyKey", key)
                event + "/confirm"
            }
            "toggle_checklist", "toggle_note" -> {
                val idx = body.getInt("item_idx")
                val item = if (body.getString("action") == "toggle_note") "note-" + idx to card.notes[idx].done
                    else card.checklist[idx].let { it.id to it.done }
                method = "PATCH"
                params.put("checked", !item.second)
                event + "/checklist/" + Uri.encode(item.first)
            }
            else -> return repository.homeSduiAction(account, body, live)
        }
        val result = repository.homeContractAction(account, path, method, params)
        retryKey?.let { homePreferences.edit().remove(it).apply() }
        return result
    }

    private fun dispatchHomeAction(body: JSONObject, localUpdate: (LocalAuraMutationState) -> LocalAuraMutationState, toast: String? = null) {
        val current = session.value ?: return
        val simulate = body.optString("action") == "simulate_scenario"
        if (current.endpoint != "offline" && (serverHomeSdui?.showcaseMode == true || simulate)) {
            action {
                val response = sendHomeAction(current.accountKey, body, live = false)
                if (session.value?.accountKey == current.accountKey) {
                    serverHomeSdui = AuraFlowState.fromJson(response)
                    if (toast != null) message = toast
                }
            }
            return
        }
        if (demoHomeMutation.showcaseMode || simulate) {
            demoHomeMutation = localUpdate(demoHomeMutation)
            if (toast != null) message = toast
            return
        }
        val id = body.optString("card_id")
        fun applyLocal(queue: Boolean = false) {
            localHomeMutation = localUpdate(localHomeMutation)
            homePreferences.edit().apply {
                if (queue) {
                    val key = "${current.accountKey}/pending"
                    val pending = homePreferences.getStringSet(key, emptySet()).orEmpty().toMutableSet()
                    val kind = body.getString("action")
                    if (kind == "complete_card") pending.remove("confirm_card/$id")
                    pending.add("$kind/$id")
                    putStringSet(key, pending)
                }
                putStringSet("${current.accountKey}/confirmed", localHomeMutation.confirmedIds)
                putString("${current.accountKey}/completionTimes", JSONObject(localHomeMutation.completedAt).toString())
                putStringSet("${current.accountKey}/dismissed", localHomeMutation.dismissedIds)
                putStringSet("${current.accountKey}/reminders", localHomeMutation.dismissedReminders)
            }.apply()
            if (toast != null) message = toast
        }
        val card = serverHomeSdui?.findCard(id)
        if (current.endpoint == "offline" || card == null) {
            applyLocal(queue = current.endpoint != "offline")
        } else action {
            val result = sendHomeAction(current.accountKey, body, live = true)
            if (session.value?.accountKey == current.accountKey) {
                serverHomeSdui = AuraFlowState.fromJson(result)
                applyLocal()
            }
        }
    }

    fun homeCompleteCard(id: String) = dispatchHomeAction(
        JSONObject().put("action", "complete_card").put("card_id", id),
        { it.copy(completedAt = it.completedAt + (id to Instant.now().toString())) }, "已标记完成"
    )

    fun homeSetDayMode(mode: String) {
        val current = session.value
        if (current != null && current.endpoint != "offline" && serverHomeSdui?.timelineIds != null &&
            mode in setOf("REST_MODE", "EMPTY_EXPLORE", "ACTIVE_TIMELINE")) {
            action {
                val date = java.time.LocalDate.now(java.time.ZoneOffset.ofHours(8)).toString()
                val doc = repository.homeContractAction(current.accountKey, "/api/v1/home/day-mode", "POST",
                    JSONObject().put("date", date).put("mode", mode))
                if (session.value?.accountKey == current.accountKey) serverHomeSdui = AuraFlowState.fromJson(doc)
            }
            return
        }
        dispatchHomeAction(
            JSONObject().put("action", "set_day_mode").put("day_mode", mode),
            { it.copy(dayModeOverride = mode) }
        )
    }

    fun homeSetWeather(weather: String) {
        dispatchHomeAction(
            JSONObject().put("action", "set_weather").put("weather", weather),
            { it.copy(weatherMode = weather, exploreIndex = 0) }
        )
    }

    fun homeCycleExplore() {
        val listSize = AuraFlowEngine.defaultExplorePresets()[demoHomeMutation.weatherMode]?.size ?: 3
        dispatchHomeAction(
            JSONObject().put("action", "cycle_explore"),
            { it.copy(exploreIndex = (it.exploreIndex + 1) % listSize) }
        )
    }

    fun homeAdoptExplore() {
        val currentState = effectiveHomeSdui(events.value, decisions.value)
        val preset = currentState.activeExplorePreset
        val nowMs = System.currentTimeMillis()
        val adoptedCards = preset.stops.mapIndexed { idx, stop ->
            AuraCard(
                id = "evt_explore_${nowMs}_$idx",
                category = AuraCategory.EXPLORE,
                themeKey = "explore",
                lifecycleStage = LifecycleStage.CONFIRMED,
                time = stop.time,
                title = stop.name,
                subtitle = "${stop.dist} · ${stop.note}",
                badge = "漫游已定",
                connectorToNext = if (idx < preset.stops.lastIndex) ConnectorInfo(ConnectorState.NORMAL_SOLID, "漫步衔接") else null,
                entities = listOf(preset.tag, stop.dist, currentState.weatherContext.label),
                exploreMeta = ExploreMeta(preset.title, preset.duration, stop.note, preset.stops, idx),
                detailBlocks = listOf("EXPLORE_ROUTE_CARD")
            )
        }
        dispatchHomeAction(
            JSONObject().put("action", "adopt_explore"),
            { it.copy(adoptedEvents = adoptedCards, dayModeOverride = "ACTIVE_TIMELINE") },
            toast = "已采纳「${preset.title}」并生成今日流光日程"
        )
    }

    fun homeSelectPlan(cardId: String, planIdx: Int) {
        dispatchHomeAction(
            JSONObject().put("action", "select_plan").put("card_id", cardId).put("plan_idx", planIdx),
            { it.copy(selectedPlanByCard = it.selectedPlanByCard + (cardId to planIdx)) }
        )
    }

    fun homeConfirmCard(cardId: String) {
        dispatchHomeAction(
            JSONObject().put("action", "confirm_card").put("card_id", cardId),
            { it.copy(confirmedIds = it.confirmedIds + cardId) },
            toast = "已确认安排"
        )
    }

    fun homeDismissReminder(cardId: String) {
        dispatchHomeAction(
            JSONObject().put("action", "dismiss_reminder").put("card_id", cardId),
            { it.copy(dismissedReminders = it.dismissedReminders + cardId) },
            toast = "已收起行前提醒微囊"
        )
    }

    fun homeResolveConflict(cardId: String) {
        val currentState = effectiveHomeSdui(events.value, decisions.value)
        val idx = currentState.events.indexOfFirst { it.id == cardId }
        val nextCard = if (idx >= 0 && idx + 1 < currentState.events.size) currentState.events[idx + 1] else null
        dispatchHomeAction(
            JSONObject().put("action", "resolve_conflict").put("card_id", cardId).put("shift_minutes", 30),
            { mut ->
                val updatedTimes = if (nextCard != null) {
                    mut.shiftedTimes + (nextCard.id to AuraFlowEngine.shiftTime(nextCard.time, 30))
                } else mut.shiftedTimes
                mut.copy(resolvedConflicts = mut.resolvedConflicts + cardId, shiftedTimes = updatedTimes)
            },
            toast = "已顺延后续日程 30 分钟，消除通勤冲突"
        )
    }

    fun homeInsertGapSpot(afterCardId: String, spot: GapSpot) {
        val newCard = AuraCard(
            id = "evt_gap_${System.currentTimeMillis()}",
            category = AuraCategory.EXPLORE,
            themeKey = "explore",
            lifecycleStage = LifecycleStage.CONFIRMED,
            time = spot.time,
            title = spot.title,
            subtitle = spot.subtitle,
            badge = "补白已定",
            connectorToNext = ConnectorInfo(ConnectorState.NORMAL_SOLID, "准时衔接下一程"),
            entities = listOf("线间补白", spot.time, "顺路推荐"),
            exploreMeta = ExploreMeta(spot.title, spot.durationMinutes?.let { "${it} 分钟" }.orEmpty(),
                spot.subtitle, listOf(ExploreStop(spot.time, spot.title, "当前附近", spot.subtitle)), 0),
            detailBlocks = listOf("EXPLORE_ROUTE_CARD")
        )
        dispatchHomeAction(
            JSONObject()
                .put("action", "insert_gap_spot")
                .put("card_id", afterCardId)
                .put("time", spot.time)
                .put("title", spot.title)
                .put("subtitle", spot.subtitle),
            { it.copy(resolvedConflicts = it.resolvedConflicts + afterCardId, adoptedEvents = it.adoptedEvents + newCard) },
            toast = "已将「${spot.title}」嵌入今日日程空档"
        )
    }

    fun homeApplyPlanB(cardId: String, choice: String) {
        val toastText = if (choice == "switch_hsr") {
            "已无缝切换至复兴号 G14，延误预警已解除"
        } else {
            "已顺延外滩晚宴至 19:30 并通知留座"
        }
        dispatchHomeAction(
            JSONObject().put("action", "apply_plan_b").put("card_id", cardId).put("choice", choice),
            { it.copy(simulatedAlertCardId = null, appliedPlanB = choice) },
            toast = toastText
        )
    }

    fun homeToggleChecklist(cardId: String, itemIdx: Int) {
        dispatchHomeAction(
            JSONObject().put("action", "toggle_checklist").put("card_id", cardId).put("item_idx", itemIdx),
            { mut ->
                val curMap = mut.checklistDone[cardId].orEmpty()
                val nextVal = !(curMap[itemIdx] ?: false)
                mut.copy(checklistDone = mut.checklistDone + (cardId to (curMap + (itemIdx to nextVal))))
            }
        )
    }

    fun homeToggleNote(cardId: String, noteIdx: Int) {
        dispatchHomeAction(
            JSONObject().put("action", "toggle_note").put("card_id", cardId).put("item_idx", noteIdx),
            { mut ->
                val curMap = mut.notesDone[cardId].orEmpty()
                val defaultVal = (cardId == "evt_morning_prep" && noteIdx == 0)
                val nextVal = !(curMap[noteIdx] ?: defaultVal)
                mut.copy(notesDone = mut.notesDone + (cardId to (curMap + (noteIdx to nextVal))))
            }
        )
    }

    fun homeDismissMisjudgment(cardId: String, reason: String = "not_my_schedule") {
        dispatchHomeAction(
            JSONObject().put("action", "dismiss_misjudgment").put("card_id", cardId).put("reason", reason),
            { mut ->
                mut.copy(
                    dismissedIds = mut.dismissedIds + cardId,
                    simulatedJoke = if (cardId == "evt_joke_paris") false else mut.simulatedJoke,
                    negativeFeedbackCount = mut.negativeFeedbackCount + 1
                )
            },
            toast = "已从首页移除，原始线索已保留"
        )
    }

    fun homeSimulate(scenario: String) {
        when (scenario) {
            "sms_ticket" -> pulseHomePill("捕获航旅纵横出票短信 · 自动核销提案", "travel", 1200L) {
                dispatchHomeAction(
                    JSONObject().put("action", "simulate_scenario").put("scenario", scenario),
                    { it.copy(showcaseMode = true, dayModeOverride = "ACTIVE_TIMELINE", simulatedSmsTicket = true) },
                    toast = "已通过出票短信自动核销，点击卡片查看 3D 登机牌"
                )
            }
            "emergency_alert" -> pulseHomePill("预警：MU5102 预计延误 75m · 触发红线熔断", "dining", 1200L) {
                dispatchHomeAction(
                    JSONObject().put("action", "simulate_scenario").put("scenario", scenario),
                    { it.copy(showcaseMode = true, dayModeOverride = "ACTIVE_TIMELINE", simulatedAlertCardId = "evt_travel_outbound", appliedPlanB = null) },
                    toast = "监测到航班延误，点击红线查看 AI B 计划"
                )
            }
            "joke_misjudgment" -> pulseHomePill("捕获群聊：走，明天飞巴黎喝咖啡！", "explore", 1200L) {
                dispatchHomeAction(
                    JSONObject().put("action", "simulate_scenario").put("scenario", scenario),
                    { it.copy(dayModeOverride = "ACTIVE_TIMELINE", simulatedJoke = true, dismissedIds = it.dismissedIds - "evt_joke_paris") },
                    toast = "已插入疑似玩笑卡片 · 请向左滑动雾化移除"
                )
            }
            "load_showcase" -> dispatchHomeAction(
                JSONObject().put("action", "simulate_scenario").put("scenario", scenario),
                { LocalAuraMutationState(showcaseMode = true, dayModeOverride = "ACTIVE_TIMELINE") },
                toast = "已切换至 AuraFlow 全场景流光演示"
            )
            "exit_showcase" -> dispatchHomeAction(
                JSONObject().put("action", "simulate_scenario").put("scenario", scenario),
                { LocalAuraMutationState(showcaseMode = false, dayModeOverride = "AUTO") },
                toast = "已切换回真实线索联动视图"
            )
            "reset_flight_proposal" -> dispatchHomeAction(
                JSONObject().put("action", "simulate_scenario").put("scenario", scenario),
                { it.copy(simulatedSmsTicket = false, confirmedIds = it.confirmedIds - "evt_travel_outbound", appliedPlanB = null) },
                toast = "航班已重置为未订票提案态"
            )
            "reset_showcase" -> dispatchHomeAction(
                JSONObject().put("action", "simulate_scenario").put("scenario", scenario),
                { LocalAuraMutationState(showcaseMode = it.showcaseMode, dayModeOverride = if (it.showcaseMode) "ACTIVE_TIMELINE" else "AUTO") },
                toast = "已重置演示状态"
            )
        }
    }
}

