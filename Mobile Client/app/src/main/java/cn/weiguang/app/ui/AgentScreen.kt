package cn.weiguang.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.weiguang.app.AppViewModel
import cn.weiguang.app.data.LocalEvent
import cn.weiguang.app.data.itemObjects
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

private val taskLabels = mapOf(
    "queued" to "排队中",
    "running" to "正在处理",
    "waiting_input" to "等待你处理",
    "completed" to "已完成",
    "cancelled" to "已取消",
    "failed" to "未完成",
)
private val actionLabels = mapOf(
    "proposed" to "等待授权",
    "authorized" to "已授权，等待执行",
    "executing" to "执行中",
    "succeeded" to "执行成功",
    "unknown" to "结果未知，请先核对",
    "failed" to "执行失败",
    "stale" to "计划已变化，请重新预览",
    "rejected" to "已拒绝",
    "cancelled" to "已取消",
    "reconciling" to "正在核对",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColumnScope.AgentScreen(model: AppViewModel, events: List<LocalEvent>) {
    val draft = model.workDraft
    val session by model.session.collectAsStateWithLifecycle()
    val captureGrant by model.captureGrant.collectAsStateWithLifecycle()
    val captureStatus by cn.weiguang.app.capture.CaptureRuntime.status.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val account = session?.accountKey
    val workId = model.selectedWorkId
    var instruction by key(account, workId) { rememberSaveable { mutableStateOf("请整理这些线索，需要补充时问我。") } }
    var selected by key(account, workId) { rememberSaveable { mutableStateOf(emptyMap<String, Int>()) } }
    var selectionChanged by key(account, workId) { rememberSaveable { mutableStateOf(false) } }
    val synced = remember(events) { events.filter { it.syncState == "synced" && !it.deleted } }
    val chosen = remember(synced, selected) { synced.filter { selected[it.eventId] == it.revision } }
    LaunchedEffect(chosen, selected) {
        if (chosen.size < selected.size) {
            selected = chosen.associate { it.eventId to it.revision }
            selectionChanged = true
        }
    }
    LaunchedEffect(account) { while (true) { model.pollAgent(); delay(2000) } }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        WorkspaceHeader("云端事项", modifier = Modifier.weight(1f))
        WorkspaceBadge(if (model.agentError == null) model.agentMode else "暂时无法连接",
            Modifier.testTag("agent-connection"), accent = model.agentError == null)
        IconButton(onClick = model::refreshAgent, enabled = !model.busy) {
            Icon(Icons.Outlined.Refresh, "刷新事项与任务", Modifier.size(21.dp))
        }
    }
    val running = model.agentTasks.count { it.optString("status") in setOf("queued", "running") }
    val waiting = model.agentTasks.count { it.optString("status") == "waiting_input" }
    val completed = model.agentTasks.count { it.optString("status") == "completed" }
    if (model.agentError == null) {
        SakuraStatusCard(
            title = when {
                waiting > 0 -> "有事项等你确认"
                running > 0 -> "正在整理线索"
                model.agentTasks.isEmpty() -> "暂无云端任务"
                else -> "云端事项已同步"
            },
            detail = when {
                waiting > 0 -> "$waiting 项任务等待你处理"
                running > 0 -> "$running 项任务正在处理"
                completed > 0 -> "$completed 项任务已完成"
                else -> "创建事项后，可以选择要使用的线索"
            },
            active = running > 0,
        )
    }
    model.agentError?.let {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) {
            Text(
                ReadableText.error(it, "暂时无法读取事项，请刷新后重试。"),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }

    val createWork: @Composable () -> Unit = {
        WorkspaceCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WorkspaceGlyph(Icons.Outlined.AddTask, accent = true)
                Text("创建事项", style = MaterialTheme.typography.titleMedium)
            }
            OutlinedTextField(
                value = draft.title,
                onValueChange = { model.changeWorkDraft(draft.copy(title = it)) },
                label = { Text("事项名称") },
                modifier = Modifier.fillMaxWidth().testTag("agent-work-title"),
                enabled = !model.busy,
                singleLine = true,
                shape = MaterialTheme.shapes.small,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                model.agentCapabilities.forEach { capability ->
                    val id = capability.getString("id")
                    FilterChip(
                        selected = draft.type == id,
                        onClick = { model.changeWorkDraft(draft.copy(type = id)) },
                        shape = CircleShape,
                        label = { Text(capability.getString("name")) },
                        enabled = !model.busy,
                        modifier = Modifier.testTag("agent-plugin-$id"),
                    )
                }
            }
            WorkspaceButton(
                onClick = { focus.clearFocus(); model.createAgentWork() },
                enabled = !model.busy && draft.title.isNotBlank() && model.agentCapabilities.any { it.optString("id") == draft.type },
                modifier = Modifier.fillMaxWidth().testTag("agent-create-work"),
            ) { Text("创建并选择") }
        }
    }

    if (model.agentWork == null) createWork()

    if (model.agentWorks.isNotEmpty()) {
        WorkspaceCard {
            Text("我的事项", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                model.agentWorks.forEach { work ->
                    val id = work.getString("id")
                    FilterChip(
                        selected = model.selectedWorkId == id,
                        onClick = { model.selectAgentWork(id) },
                        shape = CircleShape,
                        label = { Text(ReadableText.text(work.getString("title"), "未命名事项")) },
                        enabled = !model.busy,
                    )
                }
            }
        }
    }

    model.agentWork?.let { work ->
        val planView = model.agentPlan?.takeIf { it.optString("work_id") == work.optString("id") }
        if (planView != null) {
            val submitOpinion = { body: JSONObject -> model.submitPlanOpinion(work.getString("id"), body) }
            PlanDeck(
                plan = planView,
                busy = model.busy,
                onOpinion = submitOpinion.takeIf { planView.optBoolean("supports_opinions") },
                openTask = model::selectAgentTask,
            )
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WorkspaceCard(accent = captureGrant?.workId == work.getString("id")) {
                val context = LocalContext.current
                val enabled = captureGrant?.workId == work.getString("id")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WorkspaceGlyph(Icons.Outlined.ChatBubbleOutline, accent = enabled)
                    Text("聊天自动记录", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "本机自动识别前台微信或 QQ 内容并保存为线索，悬浮窗可随时暂停或结束。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                WorkspaceButton(
                    onClick = { model.setAutoCapture(!enabled) },
                    enabled = !model.busy,
                    secondary = true,
                    modifier = Modifier.fillMaxWidth().testTag("capture-toggle"),
                ) {
                    Text(if (enabled) "停止本机记录" else "开启本机记录")
                }
                if (enabled) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Text(
                            text = if (captureGrant?.paused == true) "已暂停采集，已保存材料仍可同步"
                            else ReadableText.text(captureStatus.detail, "请检查本机记录状态。"),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WorkspaceButton(
                            onClick = model::pauseAutoCapture,
                            enabled = !model.busy,
                            secondary = true,
                            modifier = Modifier.weight(1f).testTag("capture-pause"),
                        ) {
                            Text(if (captureGrant?.paused == true) "继续采集" else "暂停采集")
                        }
                        WorkspaceButton(
                            onClick = {
                                context.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                )
                            },
                            secondary = true,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (captureStatus.connected) "查看系统聊天采集权限" else "开启系统聊天采集权限")
                        }
                    }
                }
            }
        }
        val state = work.optJSONObject("state") ?: JSONObject()
        val items = state.optJSONObject("items")
        if (planView == null) WorkspaceCard {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    ReadableText.text(work.getString("title"), "未命名事项"),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                WorkspaceBadge(
                    ReadableText.confirmedVersion(work.getInt("memory_version")),
                    Modifier.testTag("agent-state-version"),
                    accent = work.getInt("memory_version") > 0,
                )
            }
            if (items != null) {
                items.keys().forEach { key ->
                    val item = items.getJSONObject(key)
                    val itemTitle = ReadableText.text(item.optString("title"), "未命名日程")
                    val at = ReadableText.scheduleTime(item.optString("at"))
                    val status = if (item.optString("status") == "completed") "已完成" else "待完成"
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    ) {
                        Text(
                            "$itemTitle\n$at · $status",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                        )
                    }
                }
            }
            state.optJSONObject("plan")?.let { plan ->
                val confirmedTime = ReadableText.dateTime(state.optString("scheduled_at"))
                Text(
                    if (confirmedTime == "时间待确认") "日期待确认" else confirmedTime,
                    style = MaterialTheme.typography.bodyMedium,
                )
                val labels = mapOf("attending" to "参加", "declined" to "退出", "tentative" to "待确认")
                Text(
                    plan.itemObjects("participants").joinToString("、") {
                        val name = ReadableText.text(it.optString("name"), "未命名成员")
                        "$name（${labels[it.optString("status")] ?: "状态待确认"}）"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (work.getInt("memory_version") > 0 && items != null && items.length() > 0) WorkspaceCard {
            key(work.getString("id")) { CalendarPreview(model, items) }
        }
        WorkspaceCard {
            Text("选择线索", style = MaterialTheme.typography.titleMedium)
            Text(
                when (chosen.size) {
                    0 -> "只会使用你勾选的线索，最多 10 条。"
                    10 -> "已选满 10 条。取消一条后可以换选。"
                    else -> "已选 ${chosen.size} 条，最多 10 条。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (selectionChanged) {
                Text("有已选线索更新或移除，请重新核对。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (synced.isEmpty()) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Text(
                        "请先到「线索」保存、允许上传并完成同步。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                    )
                }
            }
            synced.forEach { event ->
                val checked = selected[event.eventId] == event.revision
                val enabled = !model.busy && (checked || chosen.size < 10)
                val colors = MaterialTheme.colorScheme
                val bgColor by animateColorAsState(
                    targetValue = if (checked) colors.primaryContainer.copy(alpha = 0.55f) else colors.surfaceContainerLow,
                    animationSpec = tween(180),
                    label = "source item bg",
                )
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = bgColor,
                    contentColor = if (checked) colors.onPrimaryContainer else colors.onSurface,
                    border = BorderStroke(
                        width = if (checked) 1.dp else 0.5.dp,
                        color = if (checked) colors.primary.copy(alpha = 0.4f) else colors.outlineVariant.copy(alpha = 0.6f),
                    ),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .testTag("agent-source-${event.eventId}")
                            .toggleable(checked, enabled = enabled, role = Role.Checkbox) { yes ->
                                selected = if (yes) selected + (event.eventId to event.revision) else selected - event.eventId
                                selectionChanged = false
                            }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                        Text(
                            ReadableText.clue(event.text).take(180),
                            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            OutlinedTextField(
                value = instruction,
                onValueChange = { instruction = it },
                label = { Text("这次需要做什么") },
                modifier = Modifier.fillMaxWidth().testTag("agent-instruction"),
                enabled = !model.busy,
                shape = MaterialTheme.shapes.small,
            )
            WorkspaceButton(
                onClick = {
                    focus.clearFocus()
                    model.startAgentTask(instruction, chosen)
                },
                enabled = !model.busy && chosen.size in 1..10 && instruction.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag("agent-start"),
            ) { Text("开始处理") }
        }
    }

    if (model.agentTasks.isNotEmpty()) {
        WorkspaceCard {
            Text("任务记录", style = MaterialTheme.typography.titleMedium)
            model.agentTasks
                .filter { model.selectedWorkId == null || it.optString("work_id") == model.selectedWorkId }
                .forEachIndexed { idx, task ->
                    if (idx > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            thickness = 0.5.dp,
                        )
                    }
                    TextButton(
                        onClick = { model.selectAgentTask(task) },
                        enabled = !model.busy,
                        modifier = Modifier.fillMaxWidth().testTag("agent-task-${task.getString("id")}"),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        val status = taskLabels[task.optString("status")] ?: "状态待确认"
                        Text(
                            "$status · ${ReadableText.text(task.optString("instruction"), "处理所选线索")}",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
        }
    }

    model.agentTask?.let { task -> AgentTaskCard(model, task, events) }
    if (model.agentWork != null) createWork()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarPreview(model: AppViewModel, items: JSONObject) {
    val choices = items.keys().asSequence().toList()
    if (choices.isEmpty()) return
    var selectedId by rememberSaveable { mutableStateOf(choices.first()) }
    val itemId = selectedId.takeIf { it in choices } ?: choices.first()
    val startText = items.optJSONObject(itemId)?.optString("at").orEmpty()
    val start = remember(startText) { runCatching { Instant.parse(startText) }.getOrNull() }
    val zone = ZoneId.systemDefault()
    var endText by rememberSaveable(itemId, startText) { mutableStateOf<String?>(null) }
    val end = endText?.let(LocalDateTime::parse)
    var pickingEnd by rememberSaveable(itemId, startText) { mutableStateOf(false) }
    val duration = if (start != null && end != null) Duration.between(start, end.atZone(zone).toInstant()) else null
    val validEnd = duration != null && duration > Duration.ZERO && duration <= Duration.ofDays(7)
    Text("导出日历文件", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { key ->
            FilterChip(
                selected = itemId == key,
                onClick = { selectedId = key },
                shape = CircleShape,
                label = { Text(ReadableText.text(items.getJSONObject(key).optString("title"), "未命名日程")) },
                enabled = !model.busy,
            )
        }
    }
    if (start == null) {
        Note("确定这项日程的开始时间后，就能导出日历。")
        return
    }
    Note("开始于 ${ReadableText.dateTime(startText, zone)}")
    WorkspaceButton(
        onClick = { pickingEnd = true },
        enabled = !model.busy,
        secondary = true,
    ) {
        Text(end?.let { "结束于 ${ReadableText.dateTime(it.toString())}" } ?: "选择结束日期和时间")
    }
    Text(
        if (end != null && !validEnd) "结束时间需要晚于开始时间，且相隔不超过 7 天。" else "时间按手机所在时区显示。",
        style = MaterialTheme.typography.bodySmall,
        color = if (end != null && !validEnd) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    WorkspaceButton(
        onClick = { end?.let { model.previewCalendar(itemId, it.toString()) } },
        enabled = !model.busy && validEnd,
        secondary = true,
    ) { Text("预览日历") }
    if (pickingEnd) {
        WorkspaceDateTimeDialog(
            initial = end ?: start.plusSeconds(1800).atZone(zone).toLocalDateTime(),
            onDismiss = { pickingEnd = false },
            onSelect = { endText = it.toString(); pickingEnd = false },
        )
    }
}

@Composable
private fun AgentTaskCard(model: AppViewModel, task: JSONObject, events: List<LocalEvent>) {
    val output = task.optJSONObject("interaction") ?: JSONObject()
    val kind = output.optString("kind")
    val status = task.optString("status")
    var answer by remember(task.optString("id"), task.optInt("revision")) { mutableStateOf("") }
    var showSources by remember(task.optString("id")) { mutableStateOf(false) }
    var saveId by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        val aid = saveId
        if (uri != null && aid != null) model.saveAgentCalendar(uri, aid)
    }
    WorkspaceCard(accent = status == "waiting_input") {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            WorkspaceBadge(
                taskLabels[status] ?: "状态待确认",
                Modifier.testTag("agent-task-status"),
                accent = status == "waiting_input" || status == "running",
            )
        }
        Text(
            ReadableText.text(task.optString("instruction"), "处理所选线索"),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (output.length() > 0 && (output.optString("contract_version") != "cloud-v1" || kind !in listOf("information", "clarification", "state_proposal", "action_proposal"))) {
            Text("当前应用暂不支持此交互版本，请升级应用后再操作。")
            return@WorkspaceCard
        }
        if (!task.isNull("error")) {
            Text(
                ReadableText.error(task.optString("error"), "本次任务未完成，请重试。"),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (status in listOf("queued", "running")) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primaryContainer,
            )
            Text(
                "可以离开页面，稍后从任务记录继续。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val card = task.optJSONObject("card")
        card?.let {
            Text(ReadableText.text(it.optString("title"), "任务结果"), style = MaterialTheme.typography.titleMedium)
            Text(ReadableText.text(it.optString("summary")), style = MaterialTheme.typography.bodyMedium)
            it.itemObjects("fields").forEach { field ->
                val refs = field.optJSONArray("source_refs")
                val labels = (0 until (refs?.length() ?: 0)).map { i ->
                    val ref = refs!!.getString(i)
                    when {
                        ref.startsWith("memory:") -> "已确认事项状态"
                        ref.startsWith("input:") -> "本任务用户补充"
                        else -> task.itemObjects("mobile_evidence").find { e -> e.optString("screen_id") == ref.substringBefore(':') }
                            ?.let { e -> "所选线索的第 ${e.optInt("revision")} 版" } ?: "授权历史材料"
                    }
                }.distinct()
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            ReadableText.text(field.optString("label"), "补充信息"),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(ReadableText.text(field.optString("value")), style = MaterialTheme.typography.bodyMedium)
                        if (labels.isNotEmpty()) {
                            Text(
                                "依据：${labels.joinToString("、")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        if (kind == "clarification") {
            val question = output.optString("question")
            Text(
                ReadableText.text(question, "请补充日程名称、日期或时间等信息后再试。"),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (kind == "state_proposal" && status == "waiting_input") {
            Text(
                "确认后才会更新正式方案。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (kind == "action_proposal") {
            val p = output.getJSONObject("parameters")
            Text(
                "动作预览：${if (output.optString("tool") == "calendar.export") "生成日历文件" else "写入已配置日历"}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(ReadableText.text(p.optString("title"), "未命名日程"), style = MaterialTheme.typography.titleMedium)
            val zone = ZoneId.systemDefault()
            val start = ReadableText.dateTime(p.optString("start"), zone)
            val end = ReadableText.dateTime(p.optString("end"), zone)
            Text("按设备时间，从 $start 到 $end。", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (p.optString("calendar_target") == "download") "生成供你保存和导入的日历文件。" else "写入已配置的日历。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val receipt = model.agentReceipt
            val actionState = receipt?.optString("status")
            Text(actionLabels[actionState] ?: "正在读取执行状态", style = MaterialTheme.typography.labelLarge)
            if (actionState == "proposed") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkspaceButton(onClick = { model.respondAgent("authorize_action") }, enabled = !model.busy) {
                        Text("授权执行这个动作")
                    }
                    WorkspaceButton(onClick = { model.respondAgent("reject_action") }, enabled = !model.busy, secondary = true) {
                        Text("拒绝执行")
                    }
                }
            }
            if (actionState == "unknown") {
                WorkspaceButton(onClick = model::reconcileAgent, enabled = !model.busy, secondary = true) {
                    Text("核对结果，不重复执行")
                }
            }
            if (actionState == "succeeded" && output.optString("tool") == "calendar.export") {
                Text("日历文件已生成，尚未导入系统日历。", style = MaterialTheme.typography.bodySmall)
                WorkspaceButton(
                    onClick = { saveId = output.getString("action_id"); save.launch("ScreenMind-日程.ics") },
                    enabled = !model.busy,
                ) { Text("保存日历文件") }
            }
        } else {
            if (status == "waiting_input") {
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    label = { Text(if (kind == "clarification") "回答问题" else "补充或修正要求") },
                    modifier = Modifier.fillMaxWidth().testTag("agent-answer"),
                    enabled = !model.busy,
                    shape = MaterialTheme.shapes.small,
                )
                WorkspaceButton(
                    onClick = {
                        if (kind == "clarification") model.respondAgent("answer", answer)
                        else model.controlAgent("revise", answer)
                    },
                    enabled = !model.busy && answer.isNotBlank(),
                    secondary = kind == "state_proposal",
                ) { Text("提交并继续") }
                if (kind == "state_proposal") {
                    WorkspaceButton(
                        onClick = { model.respondAgent("accept_state") },
                        enabled = !model.busy && answer.isBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("agent-accept-state"),
                    ) { Text("确认更新事项") }
                    WorkspaceButton(
                        onClick = { model.respondAgent("reject_state") },
                        enabled = !model.busy,
                        secondary = true,
                    ) { Text("不采用这个方案") }
                }
            }
            if (status in listOf("queued", "running", "waiting_input")) {
                TextButton(onClick = { model.controlAgent("cancel") }, enabled = !model.busy) { Text("取消任务") }
            }
            if (status == "failed") {
                WorkspaceButton(onClick = { model.controlAgent("retry") }, enabled = !model.busy, secondary = true) {
                    Text("重试任务")
                }
            }
        }
        TextButton(onClick = { showSources = !showSources }) {
            Text(if (showSources) "收起依据" else "查看来源依据")
        }
        if (showSources) {
            val refs = task.itemObjects("mobile_evidence")
            if (refs.isEmpty()) Text("该结果来自已确认事项或用户补充。", style = MaterialTheme.typography.bodySmall)
            refs.forEach { ref ->
                val source = events.find { it.eventId == ref.optString("event_id") }
                if (source != null && source.revision == ref.optInt("revision")) {
                    Text(ReadableText.clue(source.text), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("来源线索已更新、移除或尚未同步，请刷新核对。", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                "事项已确认状态与本任务补充也会作为上下文；历史材料不自动成为新的指令。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
