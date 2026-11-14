package cn.weiguang.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.weiguang.app.AppViewModel
import cn.weiguang.app.data.LocalEvent
import cn.weiguang.app.data.itemObjects
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.ZoneId

private val taskLabels = mapOf("queued" to "排队中", "running" to "正在处理", "waiting_input" to "等待你处理", "completed" to "已完成", "cancelled" to "已取消", "failed" to "未完成")
private val actionLabels = mapOf("proposed" to "等待授权", "authorized" to "已授权，等待执行", "executing" to "执行中", "succeeded" to "执行成功", "unknown" to "结果未知，请先核对", "failed" to "执行失败", "stale" to "计划已变化，请重新预览", "rejected" to "已拒绝", "cancelled" to "已取消", "reconciling" to "正在核对")

@Composable
private fun AgentPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun ColumnScope.AgentScreen(model: AppViewModel, events: List<LocalEvent>) {
    var title by remember { mutableStateOf("") }
    var plugin by remember { mutableStateOf("information") }
    var instruction by remember(model.selectedWorkId) { mutableStateOf("请整理所选线索；缺少必要信息时向我提问，方案确认后再生效。") }
    var selected by remember(model.selectedWorkId, events.map { it.eventId to it.revision }) { mutableStateOf(setOf<String>()) }
    val session by model.session.collectAsStateWithLifecycle()
    val account = session?.accountKey
    LaunchedEffect(account) { while (true) { model.pollAgent(); delay(2000) } }
    Text("云端事项", style = MaterialTheme.typography.headlineLarge)
    Text("围绕一件事持续整理、澄清和确认。")
    Text(model.agentMode, style = MaterialTheme.typography.labelLarge)
    model.agentError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    OutlinedButton(onClick = model::refreshAgent, enabled = !model.busy) { Text("刷新事项与任务") }

    AgentPanel {
        Text("创建事项", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(title, { title = it }, label = { Text("事项名称") }, modifier = Modifier.fillMaxWidth().testTag("agent-work-title"), enabled = !model.busy)
        Column {
            model.agentCapabilities.forEach { capability ->
                val id = capability.getString("id")
                FilterChip(selected = plugin == id, onClick = { plugin = id }, label = { Text(capability.getString("name")) }, enabled = !model.busy, modifier = Modifier.testTag("agent-plugin-$id"))
            }
        }
        Button(onClick = { model.createAgentWork(title, plugin) }, enabled = !model.busy && title.isNotBlank() && model.agentCapabilities.any { it.optString("id") == plugin }, modifier = Modifier.testTag("agent-create-work")) { Text("创建并选择") }
        model.agentWorks.forEach { work ->
            FilterChip(selected = model.selectedWorkId == work.getString("id"), onClick = { model.selectAgentWork(work.getString("id")) },
                label = { Text(work.getString("title")) }, enabled = !model.busy)
        }
    }
    model.agentWork?.let { work ->
        if (android.os.Build.VERSION.SDK_INT >= 30) AgentPanel {
            val context = androidx.compose.ui.platform.LocalContext.current
            val enabled = model.captureWorkId == work.getString("id")
            Text("聊天自动记录", style = MaterialTheme.typography.titleMedium)
            Text("开启后，切换微信或QQ聊天页时在本机识别文字，同步并整理到此事项；原始图片不上传。", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { model.setAutoCapture(!enabled) }, enabled = !model.busy, modifier = Modifier.testTag("capture-toggle")) {
                Text(if (enabled) "关闭此事项自动记录" else "允许自动记录到此事项")
            }
            if (enabled) OutlinedButton(onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                Text("开启系统聊天采集权限")
            }
        }
        AgentPanel {
            Text(work.getString("title"), style = MaterialTheme.typography.titleLarge)
            Text("已确认状态 v${work.getInt("memory_version")} · 新方案确认前保持此版本", modifier = Modifier.testTag("agent-state-version"))
            val state = work.optJSONObject("state") ?: JSONObject()
            val items = state.optJSONObject("items")
            if (items != null) items.keys().forEach { key ->
                val item = items.getJSONObject(key)
                Text("${item.optString("title")}\n${item.optString("at")} · ${if (item.optString("status") == "completed") "已完成" else "待完成"}")
            }
            state.optJSONObject("plan")?.let { plan ->
                Text(plan.itemObjects("participants").joinToString("、") { "${it.optString("name")}（${mapOf("attending" to "参加", "declined" to "退出", "tentative" to "待确认")[it.optString("status")] ?: it.optString("status")}）" })
            }
            if (work.getInt("memory_version") > 0 && items != null) CalendarPreview(model, items)
        }
        AgentPanel {
            Text("选择本次允许使用的线索", style = MaterialTheme.typography.titleMedium)
            Text("只读取你选中的已同步线索，以及本事项已有的授权历史。一次最多10条。", style = MaterialTheme.typography.bodySmall)
            val synced = events.filter { it.syncState == "synced" }
            if (synced.isEmpty()) Text("请先到「线索」保存、允许上传并完成同步。")
            synced.forEach { event ->
                Row {
                    Checkbox(checked = event.eventId in selected, onCheckedChange = { yes -> selected = if (yes) selected + event.eventId else selected - event.eventId }, enabled = !model.busy, modifier = Modifier.testTag("agent-source-${event.eventId}"))
                    Text(event.text.take(180), modifier = Modifier.weight(1f).padding(top = 12.dp))
                }
            }
            OutlinedTextField(instruction, { instruction = it }, label = { Text("这次需要做什么") }, modifier = Modifier.fillMaxWidth().testTag("agent-instruction"), enabled = !model.busy)
            Button(onClick = { model.startAgentTask(instruction, synced.filter { it.eventId in selected }) },
                enabled = !model.busy && selected.size in 1..10 && instruction.isNotBlank(), modifier = Modifier.testTag("agent-start")) { Text("交给 Agent 处理") }
        }
    }
    if (model.agentTasks.isNotEmpty()) AgentPanel {
        Text("任务记录", style = MaterialTheme.typography.titleMedium)
        model.agentTasks.filter { model.selectedWorkId == null || it.optString("work_id") == model.selectedWorkId }.forEach { task ->
            TextButton(onClick = { model.selectAgentTask(task) }, enabled = !model.busy, modifier = Modifier.testTag("agent-task-${task.getString("id")}")) {
                Text("${taskLabels[task.optString("status")] ?: task.optString("status")} · ${task.optString("instruction")}")
            }
        }
    }
    model.agentTask?.let { task -> AgentTaskCard(model, task, events) }
}

@Composable
private fun CalendarPreview(model: AppViewModel, items: JSONObject) {
    var itemId by remember(items.toString()) { mutableStateOf(items.keys().asSequence().firstOrNull().orEmpty()) }
    var end by remember { mutableStateOf("") }
    Text("导出日历文件", style = MaterialTheme.typography.titleMedium)
    items.keys().forEach { key -> FilterChip(selected = itemId == key, onClick = { itemId = key }, label = { Text(items.getJSONObject(key).optString("title")) }, enabled = !model.busy) }
    OutlinedTextField(end, { end = it }, label = { Text("结束时间，如 2026-12-10T18:30") }, supportingText = { Text("按本机时区 ${ZoneId.systemDefault()} 解释。下一步会展示完整预览。") }, modifier = Modifier.fillMaxWidth(), enabled = !model.busy)
    OutlinedButton(onClick = { model.previewCalendar(itemId, end) }, enabled = !model.busy && itemId.isNotBlank() && end.isNotBlank()) { Text("预览，不立即执行") }
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
    AgentPanel {
        Text(taskLabels[status] ?: status, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("agent-task-status"))
        Text(task.optString("instruction"))
        if (output.length() > 0 && (output.optString("contract_version") != "cloud-v1" || kind !in listOf("information", "clarification", "state_proposal", "action_proposal"))) {
            Text("当前应用暂不支持此交互版本，请升级应用后再操作。")
            return@AgentPanel
        }
        if (!task.isNull("error")) Text(task.optString("error"), color = MaterialTheme.colorScheme.error)
        if (status in listOf("queued", "running")) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("可以离开页面，稍后从任务记录继续。") }
        val card = task.optJSONObject("card")
        card?.let {
            Text(it.optString("title"), style = MaterialTheme.typography.titleMedium)
            Text(it.optString("summary"))
            it.itemObjects("fields").forEach { field ->
                Text("${field.optString("label")}\n${field.optString("value")}")
                val refs = field.optJSONArray("source_refs")
                val labels = (0 until (refs?.length() ?: 0)).map { i ->
                    val ref = refs!!.getString(i)
                    when {
                        ref.startsWith("memory:") -> "已确认事项状态"
                        ref.startsWith("input:") -> "本任务用户补充"
                        else -> task.itemObjects("mobile_evidence").find { e -> e.optString("screen_id") == ref.substringBefore(':') }
                            ?.let { e -> "所选线索 v${e.optInt("revision")}" } ?: "授权历史材料"
                    }
                }.distinct()
                Text("依据：${labels.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (kind == "clarification") Text(output.optString("question"), style = MaterialTheme.typography.titleMedium)
        if (kind == "state_proposal") Text("候选方案基于已确认状态 v${output.optInt("base_state_version")}，点击确认后才更新。")
        if (kind == "action_proposal") {
            val p = output.getJSONObject("parameters")
            Text("动作预览：${if (output.optString("tool") == "calendar.export") "生成日历文件" else "写入已配置日历"}")
            Text("${p.optString("title")}\n开始：${p.optString("start")}\n结束：${p.optString("end")}")
            Text("目标：${if (p.optString("calendar_target") == "download") "供你保存和导入的文件" else p.optString("calendar_target")}")
            val receipt = model.agentReceipt
            val actionState = receipt?.optString("status")
            Text(actionLabels[actionState] ?: "正在读取执行状态")
            if (actionState == "proposed") {
                Button(onClick = { model.respondAgent("authorize_action") }, enabled = !model.busy) { Text("授权执行这个动作") }
                OutlinedButton(onClick = { model.respondAgent("reject_action") }, enabled = !model.busy) { Text("拒绝执行") }
            }
            if (actionState == "unknown") OutlinedButton(onClick = model::reconcileAgent, enabled = !model.busy) { Text("核对结果，不重复执行") }
            if (actionState == "succeeded" && output.optString("tool") == "calendar.export") {
                Text("日历文件已生成，尚未导入系统日历。")
                Button(onClick = { saveId = output.getString("action_id"); save.launch("ScreenMind-${saveId}.ics") }, enabled = !model.busy) { Text("保存日历文件") }
            }
        } else {
            if (status == "waiting_input") {
                OutlinedTextField(answer, { answer = it }, label = { Text(if (kind == "clarification") "回答问题" else "补充或修正要求") }, modifier = Modifier.fillMaxWidth().testTag("agent-answer"), enabled = !model.busy)
                Button(onClick = { if (kind == "clarification") model.respondAgent("answer", answer) else model.controlAgent("revise", answer) }, enabled = !model.busy && answer.isNotBlank()) { Text("提交并继续") }
                if (kind == "state_proposal") {
                    Button(onClick = { model.respondAgent("accept_state") }, enabled = !model.busy && answer.isBlank(), modifier = Modifier.testTag("agent-accept-state")) { Text("确认更新事项") }
                    OutlinedButton(onClick = { model.respondAgent("reject_state") }, enabled = !model.busy) { Text("不采用这个方案") }
                }
            }
            if (status in listOf("queued", "running", "waiting_input")) TextButton(onClick = { model.controlAgent("cancel") }, enabled = !model.busy) { Text("取消任务") }
            if (status == "failed") OutlinedButton(onClick = { model.controlAgent("retry") }, enabled = !model.busy) { Text("重试任务") }
        }
        TextButton(onClick = { showSources = !showSources }) { Text(if (showSources) "收起依据" else "查看来源依据") }
        if (showSources) {
            val refs = task.itemObjects("mobile_evidence")
            if (refs.isEmpty()) Text("该结果来自已确认事项或用户补充。")
            refs.forEach { ref ->
                val source = events.find { it.eventId == ref.optString("event_id") }
                if (source != null && source.revision == ref.optInt("revision")) Text("线索 v${source.revision}：${source.text}")
                else Text("来源线索已更新、移除或尚未同步，请刷新核对。")
            }
            Text("事项已确认状态与本任务补充也会作为上下文；历史材料不自动成为新的指令。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
