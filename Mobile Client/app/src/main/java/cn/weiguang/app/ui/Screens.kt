package cn.weiguang.app.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import cn.weiguang.app.BuildConfig
import cn.weiguang.app.AppViewModel
import cn.weiguang.app.EditorDraft
import cn.weiguang.app.data.*
import coil.compose.AsyncImage
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val SourceLabels = mapOf("manual" to "手动记录", "screenshot_ocr" to "截图识别", "calendar" to "系统日历", "location" to "位置采样")
private val SyncLabels = mapOf("local" to "仅本机", "pending" to "待同步", "synced" to "已同步", "failed" to "等待重试", "conflict" to "版本冲突")

@Composable
private fun Panel(accent: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = if (accent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun Note(text: String) { Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable
private fun Title(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.headlineLarge)
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Mark(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Note(label)
    }
}

@Composable
fun WeiguangRoot(model: AppViewModel, sharedImage: Uri?, clearSharedImage: () -> Unit) {
    val session by model.session.collectAsStateWithLifecycle()
    val endpoint by model.endpoint.collectAsStateWithLifecycle()
    val update by model.updater.state.collectAsStateWithLifecycle()
    LaunchedEffect(endpoint) { model.updater.check(endpoint, manual = false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.updater.check(endpoint, manual = false) }
    val observedEvents by model.events.collectAsStateWithLifecycle()
    val observedDecisions by model.decisions.collectAsStateWithLifecycle()
    val events = observedEvents.filter { it.accountKey == session?.accountKey }
    val decisions = observedDecisions.filter { it.accountKey == session?.accountKey }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()
    var showEndpoint by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(model::recognize) }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) model.readCalendar() else model.message = "日历授权已取消，可以继续手动添加" }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) model.location() else model.message = "定位授权已取消，可以手动记录地点" }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) model.notifyTest() else model.message = "通知授权已取消" }
    val requestCalendar = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) model.readCalendar()
        else calendarPermission.launch(Manifest.permission.READ_CALENDAR)
    }
    val requestLocation = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) model.location()
        else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    val requestNotification = {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else model.notifyTest()
    }

    LaunchedEffect(session?.accountKey) {
        model.resetForAccount()
        tab = 0; page = ""
        if (session != null && session?.endpoint != "offline") model.sync()
    }
    LaunchedEffect(model.message) {
        model.message?.let { value -> model.message = null; snackbar.showSnackbar(value, duration = SnackbarDuration.Long) }
    }
    LaunchedEffect(tab, page, session?.accountKey) { scrollState.scrollTo(0) }
    BackHandler(page.isNotEmpty() && !model.busy) { page = "" }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (page.isNotEmpty()) IconButton(onClick = { page = "" }, enabled = !model.busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                    else Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 10.dp))
                    Text("微光", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (BuildConfig.ENABLE_SELF_UPDATE) TextButton(onClick = { page = "updates" }) {
                        Text(if (update.release != null) "发现更新" else "更新")
                    }
                    SuggestionChip(onClick = { }, label = { Text(if (session?.endpoint == "offline") "离线体验" else "开发内测") })
                    if (session != null && session?.endpoint != "offline") IconButton(onClick = model::sync, enabled = !model.busy) { Icon(Icons.Outlined.Sync, "同步") }
                }
                if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("operation-progress"))
            }
        },
        bottomBar = {
            if (session != null && page.isEmpty()) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf("首页" to Icons.Outlined.Home, "线索" to Icons.Outlined.Layers, "设备" to Icons.Outlined.Watch, "我的" to Icons.Outlined.PersonOutline).forEachIndexed { index, item ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, enabled = !model.busy, icon = { Icon(item.second, item.first) }, label = { Text(item.first) })
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(scrollState).padding(horizontal = 22.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (page == "updates" && BuildConfig.ENABLE_SELF_UPDATE) {
                UpdateScreen(model, endpoint, onEndpoint = { showEndpoint = true })
            } else if (session == null) {
                LoginScreen(model, onEndpoint = { showEndpoint = true })
            } else {
                if (sharedImage != null) Panel(accent = true) {
                    Text("有一张分享过来的截图")
                    Button(onClick = { model.recognize(sharedImage); clearSharedImage() }, enabled = !model.busy) { Text("识别并核对") }
                }
                when {
                    page == "agent" -> AgentScreen(model, events)
                    page == "groups" -> GroupScreen(model)
                    page == "watch" -> WatchScreen()
                    tab == 0 -> HomeScreen(model, events, decisions, { imagePicker.launch("image/*") }, { tab = 1 }, { page = "agent" })
                    tab == 1 -> ClueScreen(model, events, { imagePicker.launch("image/*") }, requestCalendar, requestLocation)
                    tab == 2 -> DevicesScreen(model, requestCalendar, requestLocation, requestNotification, { page = "watch" })
                    else -> ProfileScreen(model) { page = "groups"; model.loadGroups() }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showEndpoint) EndpointDialog(model) { showEndpoint = false }
    model.editor?.let { draft -> EditorSheet(model, draft) }
    model.calendarEntries?.let { entries ->
        AlertDialog(onDismissRequest = { model.calendarEntries = null }, title = { Text("选择一条日历记录") }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (entries.isEmpty()) Text("未来七天没有可读取的系统日程。第三方课表可能没有写入系统日历。")
                entries.forEach { entry -> TextButton(onClick = {
                    model.editor = EditorDraft(entry.text, "calendar", "schedule", occurredAt = entry.startsAt)
                    model.calendarEntries = null
                }) { Text(entry.text, modifier = Modifier.fillMaxWidth()) } }
            }
        }, confirmButton = { TextButton(onClick = { model.calendarEntries = null }) { Text("关闭") } })
    }
}

@Composable
private fun ColumnScope.LoginScreen(model: AppViewModel, onEndpoint: () -> Unit) {
    var register by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    Spacer(Modifier.height(12.dp))
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Icon(Icons.Outlined.AutoAwesome, null, Modifier.padding(20.dp).size(32.dp), tint = MaterialTheme.colorScheme.primary)
    }
    Title(if (register) "创建你的空间。" else "欢迎回到微光。", "连接你的信息，留住你的选择。")
    OutlinedTextField(username, { username = it }, label = { Text("账号") }, supportingText = { Text("3–32 位字母、数字或下划线") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !model.busy, shape = RoundedCornerShape(16.dp))
    OutlinedTextField(password, { password = it }, label = { Text("密码") }, supportingText = { Text("至少 8 位") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), enabled = !model.busy, shape = RoundedCornerShape(16.dp))
    if (register) OutlinedTextField(confirmation, { confirmation = it }, label = { Text("确认密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), enabled = !model.busy, shape = RoundedCornerShape(16.dp))
    Button(onClick = {
        if (register && password != confirmation) model.message = "两次输入的密码不一致"
        else model.login(username, password, register)
    }, enabled = !model.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(16.dp)) { Text(if (register) "注册并登录" else "登录") }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { register = !register }, enabled = !model.busy) { Text(if (register) "已有账号，去登录" else "创建测试账号") }
        TextButton(onClick = onEndpoint, enabled = !model.busy) { Text("服务连接") }
    }
    OutlinedButton(onClick = model::offline, enabled = !model.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("先离线体验 OCR 与页面") }
    Note("测试账号由联调服务器验证。离线体验的数据只保存在这台手机中。")
}

@Composable
private fun EndpointDialog(model: AppViewModel, onDismiss: () -> Unit) {
    val endpoint by model.endpoint.collectAsStateWithLifecycle()
    var address by remember(endpoint) { mutableStateOf(endpoint) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("连接联调服务") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(address, { address = it }, label = { Text("服务地址") }, modifier = Modifier.fillMaxWidth())
            Text("USB 调试并设置端口转发时使用 127.0.0.1:8765；安卓模拟器使用 10.0.2.2:8765。发布服务使用 HTTPS。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { model.saveEndpoint(address); onDismiss() }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun ColumnScope.HomeScreen(model: AppViewModel, events: List<LocalEvent>, decisions: List<LocalDecision>, onImage: () -> Unit, onClues: () -> Unit, onAgent: () -> Unit) {
    val session by model.session.collectAsStateWithLifecycle()
    Note(DateTimeFormatter.ofPattern("M 月 d 日").format(ZonedDateTime.now()))
    Title("把线索，变成下一步。", "你决定哪些信息值得连接。")
    Button(onClick = onAgent, enabled = !model.busy && session?.endpoint != "offline", modifier = Modifier.fillMaxWidth().testTag("open-agent")) { Text("进入云端事项") }
    if (decisions.isEmpty()) Panel(accent = true) {
        Mark(Icons.Outlined.AutoAwesome, "从第一条线索开始")
        Text("一张截图，也可以成为\n清晰的下一步。", style = MaterialTheme.typography.titleLarge)
        Text("导入文字，核对来源，再选择是否交给云端整理。")
        Button(onClick = onImage, enabled = !model.busy) { Text("导入截图") }
    }
    decisions.take(10).forEach { item -> DecisionCard(model, item, events) }
    if (events.isNotEmpty()) OutlinedButton(onClick = model::generate, enabled = !model.busy && session?.endpoint != "offline", modifier = Modifier.fillMaxWidth()) { Text("用已同步线索生成联调建议") }
    if (session?.endpoint == "offline") Note("当前为离线体验，登录测试账号后可以上传和生成建议。")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("最近线索", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = onClues) { Text("查看全部") }
    }
    if (events.isEmpty()) Note("暂无记录。你也可以到「线索」手动记录或导入日程。")
    events.take(3).forEach { event -> Panel {
        Mark(Icons.Outlined.Description, "${SourceLabels[event.sourceKind]} · ${SyncLabels[event.syncState]}")
        Text(event.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = { model.edit(event) }, enabled = !model.busy) { Text("查看与纠错") }
    } }
}

@Composable
private fun DecisionCard(model: AppViewModel, item: LocalDecision, events: List<LocalEvent>) {
    val data = remember(item.body) { JSONObject(item.body) }
    var expanded by remember(item.decisionId) { mutableStateOf(false) }
    val evidence = data.itemObjects("evidence")
    val locallyChanged = evidence.any { reference ->
        val event = events.find { it.eventId == reference.optString("event_id") }
        event == null || event.revision != reference.optInt("revision")
    }
    val expired = data.optLong("expires_at", 0) <= Instant.now().epochSecond
    val active = data.optString("status") == "active" && !locallyChanged && !expired
    val supported = data.optInt("schema_version") == 1 && data.optString("card_type") in listOf("summary", "question")
    Panel(accent = true) {
        Mark(Icons.Outlined.AutoAwesome, if (data.optString("provider") == "rule_demo") "规则演示 · 可替换 AI" else "云端建议")
        Text(if (supported) data.optString("title") else "收到暂不支持的卡片", style = MaterialTheme.typography.titleLarge)
        Text(if (supported) data.optString("summary") else "请与云端确认协议版本后重试。")
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起依据" else "查看 ${evidence.size} 条依据") }
        if (expanded) evidence.forEach { reference ->
            val event = events.find { it.eventId == reference.optString("event_id") }
            HorizontalDivider()
            Text(event?.text ?: "此线索已移除或尚未同步到本机", style = MaterialTheme.typography.bodyMedium)
            event?.let { TextButton(onClick = { model.edit(it) }, enabled = !model.busy) { Text("核对原始记录") } }
        }
        when {
            !active -> Note("依据已修改、移除或建议已过期，请重新生成。")
            !data.isNull("feedback") && data.optString("feedback").isNotEmpty() -> Note(if (data.optString("feedback") == "accepted") "已采纳 · 反馈已记录" else "已忽略 · 反馈已记录")
            supported -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.feedback(item.decisionId, "accepted") }, enabled = !model.busy) { Text("采纳") }
                OutlinedButton(onClick = { model.feedback(item.decisionId, "dismissed") }, enabled = !model.busy) { Text("忽略") }
            }
        }
    }
}

@Composable
private fun ColumnScope.ClueScreen(model: AppViewModel, events: List<LocalEvent>, onImage: () -> Unit, onCalendar: () -> Unit, onLocation: () -> Unit) {
    Title("你的线索", "每一条，都能看到来处。")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onImage, enabled = !model.busy, modifier = Modifier.weight(1f)) { Text("导入截图") }
        OutlinedButton(onClick = { model.editor = EditorDraft() }, enabled = !model.busy, modifier = Modifier.weight(1f)) { Text("手动记录") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onCalendar, enabled = !model.busy) { Icon(Icons.Outlined.CalendarMonth, null); Spacer(Modifier.width(6.dp)); Text("日历") }
        TextButton(onClick = onLocation, enabled = !model.busy) { Icon(Icons.Outlined.LocationOn, null); Spacer(Modifier.width(6.dp)); Text("位置") }
    }
    if (events.isEmpty()) Panel {
        Icon(Icons.Outlined.Layers, null, tint = MaterialTheme.colorScheme.primary)
        Text("这里会保存你确认过的信息。")
        Note("截图在手机上识别，上传文字需要你的选择。")
    }
    events.forEach { event -> EventCard(model, event) }
}

@Composable
private fun EventCard(model: AppViewModel, event: LocalEvent) {
    val session by model.session.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmUpload by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    Panel {
        Mark(Icons.Outlined.Description, "${SourceLabels[event.sourceKind]} · ${SyncLabels[event.syncState]}")
        Text(event.text, maxLines = 6, overflow = TextOverflow.Ellipsis)
        Note("记录于 ${formatTime(event.capturedAt)}")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { model.edit(event) }, enabled = !model.busy) { Text("纠错") }
            if (event.syncState == "local") TextButton(onClick = { confirmUpload = true }, enabled = !model.busy && session?.endpoint != "offline") { Text("上传") }
            if (event.syncState == "synced") TextButton(onClick = { showShare = true; model.loadGroups() }, enabled = !model.busy) { Text("共享") }
            TextButton(onClick = { confirmDelete = true }, enabled = !model.busy) { Text("删除") }
        }
        if (event.syncState == "conflict") Note("云端版本已变化。顶部同步会以云端记录恢复此条，请先复制需要保留的文字。")
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除这条线索？") }, text = { Text("已上传的内容及其组内共享将同步删除，相关建议会失效。离线删除将在联网后完成。") }, confirmButton = { TextButton(onClick = { model.delete(event.eventId); confirmDelete = false }) { Text("删除") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
    if (confirmUpload) AlertDialog(onDismissRequest = { confirmUpload = false }, title = { Text("允许云端处理这段文字？") }, text = { Text(event.text.take(500)) }, confirmButton = { TextButton(onClick = { model.upload(event.eventId); confirmUpload = false }) { Text("确认上传") } }, dismissButton = { TextButton(onClick = { confirmUpload = false }) { Text("取消") } })
    if (showShare) AlertDialog(onDismissRequest = { showShare = false }, title = { Text("选择共享到哪个测试组") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("所选组全体成员可见以下文字。\n${event.text.take(300)}")
            if (model.groups.isEmpty()) Text("暂无测试组，请先在「我的」创建或加入。")
            model.groups.forEach { group ->
                Text(group.name, style = MaterialTheme.typography.titleMedium)
                Row {
                    TextButton(onClick = { model.share(group.id, event.eventId); showShare = false }, enabled = !model.busy) { Text("确认共享") }
                    TextButton(onClick = { model.unshare(group.id, event.eventId); showShare = false }, enabled = !model.busy) { Text("撤回共享") }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { showShare = false }) { Text("关闭") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorSheet(model: AppViewModel, initial: EditorDraft) {
    val session by model.session.collectAsStateWithLifecycle()
    var draft by remember(initial) { mutableStateOf(initial) }
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = { if (!model.busy) model.editor = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("核对这条线索", style = MaterialTheme.typography.headlineSmall)
            Note("${SourceLabels[draft.source]} · 文字可修改")
            draft.imageUri?.let { AsyncImage(it, "本次导入的原始截图", Modifier.fillMaxWidth().heightIn(max = 210.dp)) }
            OutlinedTextField(draft.text, { draft = draft.copy(text = it) }, modifier = Modifier.fillMaxWidth(), label = { Text("识别或记录内容") }, minLines = 4, maxLines = 8, enabled = !model.busy, shape = RoundedCornerShape(16.dp))
            OutlinedButton(onClick = {
                val base = ZonedDateTime.now()
                DatePickerDialog(context, { _, year, month, day ->
                    TimePickerDialog(context, { _, hour, minute ->
                        draft = draft.copy(occurredAt = ZonedDateTime.of(year, month + 1, day, hour, minute, 0, 0, ZoneId.systemDefault()).toInstant().toString())
                    }, base.hour, base.minute, true).show()
                }, base.year, base.monthValue - 1, base.dayOfMonth).show()
            }, enabled = !model.busy) { Text(draft.occurredAt?.let { "时间 · ${formatTime(it)}" } ?: "补充确定的日期与时间") }
            if (draft.uploaded) Note("这条线索已上传，纠错会同步更新云端。删除线索可移除云端记录。")
            else if (session?.endpoint != "offline") Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(draft.upload, { draft = draft.copy(upload = it) }, enabled = !model.busy)
                Text("允许上传确认后的文字，用于生成建议", style = MaterialTheme.typography.bodyMedium)
            }
            Note("原始截图仅用于本次核对，不上传、不复制到应用长期存储。")
            Button(onClick = { model.saveEditor(draft) }, enabled = !model.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("确认并保存") }
        }
    }
}

@Composable
private fun ColumnScope.DevicesScreen(model: AppViewModel, onCalendar: () -> Unit, onLocation: () -> Unit, onNotify: () -> Unit, onWatch: () -> Unit) {
    val context = LocalContext.current
    Title("设备与连接", "按实际支持的能力连接。")
    Panel {
        Mark(Icons.Outlined.Smartphone, "当前设备")
        Text("${Build.MANUFACTURER} ${Build.MODEL}", style = MaterialTheme.typography.titleLarge)
        Note("Android ${Build.VERSION.RELEASE} · 本地 OCR 与线索核对")
    }
    Panel {
        Mark(Icons.Outlined.Watch, "穿戴设备")
        Text("先验证腕上提醒", style = MaterialTheme.typography.titleLarge)
        Note("请先在手表或手环配套应用中允许转发微光通知。实际送达需要人工核对。")
        Button(onClick = onNotify, enabled = !model.busy) { Text("发送测试通知") }
        TextButton(onClick = onWatch) { Text("查看腕上交互设计") }
        HorizontalDivider()
        Note("健康数据 · 待选定机型与 SDK\n腕上按钮回传 · 待设备接口联调")
    }
    Panel {
        Text("数据来源", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onCalendar, enabled = !model.busy) { Text("选择系统日历记录") }
        TextButton(onClick = onLocation, enabled = !model.busy) { Text("获取一次大致位置") }
        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("管理系统授权") }
        Note("后台聊天通知采集与连续屏幕采集预留给后续版本，当前没有运行这两项服务。")
    }
}

@Composable
private fun ColumnScope.WatchScreen() {
    Title("腕上交互设计", "此页是展示规格，等待真实设备接入。")
    Surface(modifier = Modifier.align(Alignment.CenterHorizontally).width(230.dp), shape = RoundedCornerShape(48.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Note("示例 · 18:30")
            Text("项目讨论", style = MaterialTheme.typography.titleLarge)
            Text("A302")
            Button(onClick = { }, enabled = false) { Text("知道了 · 设计态") }
        }
    }
    Note("普通通知转发、健康数据读取、腕上交互分别验收。手机 APK 可以独立使用。")
}

@Composable
private fun ColumnScope.ProfileScreen(model: AppViewModel, onGroups: () -> Unit) {
    val session by model.session.collectAsStateWithLifecycle()
    val theme by model.theme.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf(false) }
    Title("我的空间", "${session?.username.orEmpty()} · 个人数据独立保存")
    Panel {
        Mark(Icons.Outlined.Groups, "测试组")
        Text("一起测试，独立使用。", style = MaterialTheme.typography.titleLarge)
        Note("组长管理成员，每个人管理自己的线索。组内可见范围由明确共享决定。")
        Button(onClick = onGroups, enabled = !model.busy && session?.endpoint != "offline") { Text("创建、加入与管理") }
    }
    Panel {
        Text("外观", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (key, label) -> FilterChip(selected = theme == key, onClick = { model.setTheme(key) }, label = { Text(label) }, enabled = !model.busy) }
        }
    }
    Panel {
        Text("数据与连接", style = MaterialTheme.typography.titleLarge)
        Note(if (session?.endpoint == "offline") "离线体验 · 内容仅保存在本机" else "服务地址\n${session?.endpoint}")
        Note("本机按账号与服务器分别存储。切换账号后仅展示对应线索；云端处理和组内共享分别确认。")
    }
    OutlinedButton(onClick = { confirmLogout = true }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.AutoMirrored.Outlined.Logout, null); Spacer(Modifier.width(8.dp)); Text("退出登录") }
    Note("微光 ${BuildConfig.VERSION_NAME} · 开发内测版")
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("退出当前账号？") }, text = { Text("待上传任务将停止，本机记录按账号保留。重新登录后可以同步。") }, confirmButton = { TextButton(onClick = { confirmLogout = false; model.logout() }) { Text("退出") } }, dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } })
}

@Composable
private fun ColumnScope.GroupScreen(model: AppViewModel) {
    val focusManager = LocalFocusManager.current
    var name by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    var pendingMemberRemoval by remember { mutableStateOf<Pair<String, String>?>(null) }
    Title("测试组", "共享需要明确选择，个人线索默认独立。")
    Panel {
        OutlinedTextField(name, { name = it }, label = { Text("新建组名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { focusManager.clearFocus(); model.createGroup(name) }, enabled = !model.busy) { Text("创建组") }
        HorizontalDivider()
        OutlinedTextField(code, { code = it }, label = { Text("邀请码") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { focusManager.clearFocus(); model.joinGroup(code) }, enabled = !model.busy) { Text("加入组") }
    }
    if (model.groups.isEmpty()) Note("尚未加入测试组。")
    model.groups.forEach { group -> Panel {
        Text(group.name, style = MaterialTheme.typography.titleLarge)
        Note(if (group.role == "owner") "组长" else "成员")
        TextButton(onClick = { model.openGroup(group.id) }, enabled = !model.busy) { Text("查看成员与共享") }
    } }
    model.groupDetail?.let { detail ->
        val id = detail.getString("id")
        val isOwner = model.groups.find { it.id == id }?.role == "owner"
        Panel(accent = true) {
            Text(detail.getString("name"), style = MaterialTheme.typography.titleLarge)
            detail.itemObjects("members").forEach { member ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(member.getString("username")); Note(if (member.getString("role") == "owner") "组长" else "成员") }
                    if (isOwner && member.getString("role") != "owner") TextButton(onClick = { pendingMemberRemoval = id to member.getString("id") }, enabled = !model.busy) { Text("移除") }
                }
            }
            if (isOwner) Button(onClick = { model.createInvite(id) }, enabled = !model.busy) { Text("生成限时邀请码") }
            model.invite?.let { invitation ->
                Text(invitation.getString("code"), style = MaterialTheme.typography.headlineSmall)
                Note("24 小时有效 · 最多使用 5 次")
                TextButton(onClick = { clipboard.setText(AnnotatedString(invitation.getString("code"))); model.message = "邀请码已复制" }) { Text("复制邀请码") }
            }
            HorizontalDivider()
            Text("成员明确共享的内容", style = MaterialTheme.typography.titleMedium)
            if (detail.itemObjects("shared").isEmpty()) Note("暂无共享记录。")
            detail.itemObjects("shared").forEach { shared -> Text(shared.getString("text")); Note("由 ${shared.getString("username")} 共享") }
        }
    }
    pendingMemberRemoval?.let { (groupId, userId) -> AlertDialog(onDismissRequest = { pendingMemberRemoval = null }, title = { Text("移除这位成员？") }, text = { Text("该成员将失去测试组访问权限，其在组内共享的内容也会移除。") }, confirmButton = { TextButton(onClick = { model.removeMember(groupId, userId); pendingMemberRemoval = null }) { Text("确认移除") } }, dismissButton = { TextButton(onClick = { pendingMemberRemoval = null }) { Text("取消") } }) }
}

private fun formatTime(value: String): String = try {
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
} catch (_: Exception) { value }

