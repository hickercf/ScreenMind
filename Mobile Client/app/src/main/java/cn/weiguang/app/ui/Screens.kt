package cn.weiguang.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.weiguang.app.AppViewModel
import cn.weiguang.app.ui.home.AuraFlowHomeScreen
import cn.weiguang.app.BuildConfig
import cn.weiguang.app.ClueEditor
import cn.weiguang.app.EditorDraft
import cn.weiguang.app.R
import cn.weiguang.app.SharedScreenshot
import cn.weiguang.app.data.*
import coil.compose.AsyncImage
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SourceLabels = mapOf("manual" to "手动记录", "screenshot_ocr" to "截图识别", "calendar" to "系统日历", "location" to "位置采样")
private val SyncLabels = mapOf(
    "local" to "仅本机", "local_copy" to "本机副本", "pending" to "待同步", "synced" to "已同步",
    "failed" to "等待重试", "conflict" to "另一处已修改", "rejected" to "需要核对",
)

@Composable
internal fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Mark(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun WeiguangRoot(model: AppViewModel) {
    val session = model.session.collectAsStateWithLifecycle().value
    val sharedImage = model.sharedScreenshot?.takeIf { it.accountKey == session?.accountKey }
    val endpoint by model.endpoint.collectAsStateWithLifecycle()
    val update by model.updater.state.collectAsStateWithLifecycle()
    LaunchedEffect(endpoint) { model.updater.check(endpoint, manual = false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.updater.check(endpoint, manual = false) }
    val observedEvents by model.events.collectAsStateWithLifecycle()
    val observedDecisions by model.decisions.collectAsStateWithLifecycle()
    val accountKey = session?.accountKey
    val events = remember(observedEvents, accountKey) { observedEvents.filter { it.accountKey == accountKey } }
    val decisions = remember(observedDecisions, accountKey) { observedDecisions.filter { it.accountKey == accountKey } }
    var tab by rememberSaveable(session?.accountKey) { mutableIntStateOf(0) }
    var page by rememberSaveable(session?.accountKey) { mutableStateOf("") }
    var editingClueQuery by remember(accountKey) { mutableStateOf(false) }
    var openedShare by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(sharedImage?.deliveryId) {
        if (sharedImage != null && sharedImage.deliveryId != openedShare) {
            openedShare = sharedImage.deliveryId
            tab = 0
            page = ""
        }
    }
    val snackbar = remember { SnackbarHostState() }
    var showEndpoint by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var choosingImageFor by rememberSaveable { mutableStateOf<String?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val account = choosingImageFor
        choosingImageFor = null
        if (uri != null && account != null) model.recognize(uri, account)
    }
    val chooseImage: () -> Unit = {
        val account = session?.accountKey
        if (account != null) {
            choosingImageFor = account
            imagePicker.launch("image/*")
        }
    }
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

    LaunchedEffect(session?.accountKey) { model.useAccount(session?.accountKey) }
    LaunchedEffect(model.message) {
        model.message?.let { value ->
            snackbar.showSnackbar(ReadableText.error(value, "操作未完成，请稍后重试。"), duration = SnackbarDuration.Long)
            if (model.message == value) model.message = null
        }
    }
    BackHandler(page.isNotEmpty() && !model.busy) { page = if (page == "watch") "devices" else "" }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (page.isNotEmpty()) {
                        Surface(
                            onClick = { page = if (page == "watch") "devices" else "" },
                            enabled = !model.busy,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                            modifier = Modifier.padding(end = 10.dp).size(38.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", Modifier.size(19.dp))
                            }
                        }
                    } else {
                        Image(
                            painterResource(R.drawable.screenmind_launcher),
                            null,
                            Modifier.padding(end = 10.dp).size(30.dp).clip(RoundedCornerShape(9.dp)),
                        )
                    }
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (BuildConfig.ENABLE_SELF_UPDATE && page != "updates") {
                        TextButton(
                            onClick = { page = "updates" },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(if (update.release != null) "发现更新" else "更新")
                        }
                    }
                    if (session?.endpoint == "offline") WorkspaceBadge("离线体验")
                    if (session != null && session?.endpoint != "offline") {
                        IconButton(onClick = { model.sync() }, enabled = !model.busy) {
                            Icon(Icons.Outlined.Sync, "同步", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                AnimatedVisibility(
                    visible = model.busy,
                    enter = fadeIn(tween(150)) + expandVertically(),
                    exit = fadeOut(tween(150)) + shrinkVertically(),
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.5.dp).testTag("operation-progress"),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primaryContainer,
                    )
                }
                SnackbarHost(snackbar, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { data ->
                    Snackbar(
                        snackbarData = data,
                        shape = MaterialTheme.shapes.medium,
                        containerColor = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                }
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = session != null && page.isEmpty() && !(tab == 1 && editingClueQuery),
                enter = fadeIn(tween(200)) + slideInVertically(tween(220)) { it / 3 },
                exit = fadeOut(tween(150)) + slideOutVertically(tween(160)) { it / 3 },
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 4.dp,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f), thickness = 0.5.dp)
                        NavigationBar(
                            modifier = Modifier.widthIn(max = 620.dp),
                            tonalElevation = 0.dp,
                            containerColor = MaterialTheme.colorScheme.surface,
                        ) {
                            val navItems = listOf(
                                0 to Triple("首页", Icons.Outlined.Home, Icons.Filled.Home),
                                2 to Triple("Cloud", Icons.Outlined.CloudQueue, Icons.Outlined.CloudQueue),
                                1 to Triple("线索", Icons.Outlined.Layers, Icons.Filled.Layers),
                                3 to Triple("我的", Icons.Outlined.PersonOutline, Icons.Filled.Person),
                            )
                            navItems.forEach { (index, icons) ->
                                val (label, outlinedIcon, filledIcon) = icons
                                val selected = tab == index
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { tab = index },
                                    enabled = !model.busy,
                                    icon = { Icon(if (selected) filledIcon else outlinedIcon, null) },
                                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        val route = when {
            page == "updates" && BuildConfig.ENABLE_SELF_UPDATE -> "updates"
            session == null -> "login"
            page.isNotEmpty() -> page
            else -> "tab-$tab"
        }
        key(session?.accountKey) {
            var clueQuery by rememberSaveable { mutableStateOf("") }
            val clueListState = rememberLazyListState()
            AnimatedContent(
                targetState = route,
                label = "workspace navigation",
                modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
                transitionSpec = {
                    val backwards = targetState.startsWith("tab-") &&
                        (!initialState.startsWith("tab-") || targetState < initialState)
                    val direction = if (backwards) -1 else 1
                    val contentEnter = fadeIn(tween(210, delayMillis = 30)) +
                        slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { (it / 20) * direction }
                    val contentExit = fadeOut(tween(150)) +
                        slideOutHorizontally(tween(190, easing = FastOutSlowInEasing)) { (-it / 28) * direction }
                    (contentEnter togetherWith contentExit).using(null)
                },
            ) { destination ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    if (destination == "tab-0" && session != null) {
                        MaterialTheme(colorScheme = SakuraLight) {
                            AuraFlowHomeScreen(model, events, decisions, chooseImage, { tab = 1 }, requestCalendar, { tab = 2 },
                                sharedClue = { sharedImage?.let { SharedScreenshotCard(model, it, signedIn = true) } },
                                decisionCards = { decisions.take(10).forEach { DecisionCard(model, it, events) } })
                        }
                        return@Box
                    }
                    if (destination == "tab-1" && session != null) {
                        ClueLibrary(events, clueQuery, { clueQuery = it }, clueListState, editingClueQuery, { editingClueQuery = it },
                            captureActions = { ClueCaptureActions(model, chooseImage, requestCalendar, requestLocation) },
                            eventCard = { event -> EventCard(model, event, session.endpoint != "offline") })
                        return@Box
                    }
                    val scroll = rememberScrollState()
                    LaunchedEffect(sharedImage?.deliveryId) {
                        if (sharedImage != null && (destination == "tab-0" || destination == "login")) scroll.scrollTo(0)
                    }
                    Column(
                        Modifier
                            .widthIn(max = 620.dp)
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (destination == "updates") {
                            UpdateScreen(model, endpoint, onEndpoint = { showEndpoint = true })
                        } else if (destination == "login") {
                            if (sharedImage != null && session == null) SharedScreenshotCard(model, sharedImage, signedIn = false)
                            LoginScreen(model, onEndpoint = { showEndpoint = true })
                        } else if (session != null) {
                            when {
                                destination == "tab-2" -> AgentScreen(model, events)
                                destination == "groups" -> GroupScreen(model)
                                destination == "watch" -> WatchScreen()
                                destination == "devices" -> DevicesScreen(model, requestCalendar, requestLocation, requestNotification, { page = "watch" })
                                else -> ProfileScreen(model, onGroups = { page = "groups"; model.loadGroups() }, onDevices = { page = "devices" })
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                }
            }
        }
    }

    if (showEndpoint && !BuildConfig.LOCK_CLOUD_ENDPOINT) EndpointDialog(model) { showEndpoint = false }
    model.editor?.takeIf { it.accountKey == session?.accountKey }?.let { EditorSheet(model, it) }
    model.calendarEntries?.let { entries ->
        AlertDialog(
            onDismissRequest = { model.calendarEntries = null },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text("选择一条日历记录") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (entries.isEmpty()) Text("未来七天没有可读取的系统日程。第三方课表可能没有写入系统日历。")
                    entries.forEach { entry ->
                        TextButton(
                            onClick = {
                                model.openEditor(EditorDraft(entry.text, "calendar", "schedule", occurredAt = entry.startsAt))
                                model.calendarEntries = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(ReadableText.clue(entry.text), modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { model.calendarEntries = null }) { Text("关闭") } },
        )
    }
}

@Composable
private fun SharedScreenshotCard(model: AppViewModel, image: SharedScreenshot, signedIn: Boolean) {
    WorkspaceCard(accent = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WorkspaceGlyph(Icons.Outlined.Image, accent = true)
            Column(Modifier.weight(1f)) {
                Text("收到一张截图", style = MaterialTheme.typography.titleMedium)
                Note(image.problem ?: "先在手机上识别文字，核对后再保存。")
            }
            IconButton(onClick = { model.dismissSharedScreenshot(image) }) {
                Icon(Icons.Outlined.Close, contentDescription = "忽略这张截图")
            }
        }
        WorkspaceButton(onClick = { model.recognize(image) }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) {
            Text(when {
                model.recognizingShare === image -> "正在识别…"
                image.problem != null -> "重新识别"
                !signedIn -> "在本机识别"
                else -> "识别并核对"
            })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoginScreen(model: AppViewModel, onEndpoint: () -> Unit) {
    var register by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun clearError() {
        formError = null
        model.clearLoginError()
    }
    fun submit() {
        if (model.busy) return
        focus.clearFocus()
        keyboard?.hide()
        clearError()
        if (register && password != confirmation) formError = "两次输入的密码不一致"
        else model.login(username, password, register)
    }

    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            shadowElevation = 2.dp,
            border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        ) {
            Image(painterResource(R.drawable.screenmind_launcher), null, Modifier.size(56.dp))
        }
        WorkspaceHeader(
            title = if (register) "注册 ScreenMind" else stringResource(R.string.welcome_back),
            modifier = Modifier.weight(1f),
        )
    }

    WorkspaceCard(modifier = Modifier.animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))) {
        val shape = MaterialTheme.shapes.small
        OutlinedTextField(
            value = username,
            onValueChange = { username = it; clearError() },
            label = { Text("账号") },
            supportingText = if (register) { { Text("3–32 位字母、数字或下划线") } } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !model.busy,
            shape = shape,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; clearError() },
            label = { Text("密码") },
            supportingText = if (register) { { Text("至少 8 位") } } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (register) ImeAction.Next else ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            enabled = !model.busy,
            shape = shape,
        )
        AnimatedVisibility(visible = register) {
            OutlinedTextField(
                value = confirmation,
                onValueChange = { confirmation = it; clearError() },
                label = { Text("确认密码") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                enabled = !model.busy,
                shape = shape,
            )
        }
        (formError ?: model.loginError)?.let { error ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
            ) {
                Text(
                    ReadableText.error(error, "登录未完成，请检查账号和密码后重试。"),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).testTag("login-error"),
                )
            }
        }
        WorkspaceButton(
            onClick = { submit() },
            enabled = !model.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (model.busy) { if (register) "正在注册…" else "正在登录…" } else { if (register) "注册并登录" else "登录" })
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { register = !register; confirmation = ""; clearError() }, enabled = !model.busy) {
                Text(if (register) "已有账号，去登录" else "创建测试账号")
            }
            TextButton(onClick = model::offline, enabled = !model.busy) {
                Text("先离线使用")
            }
        }
    }
    if (!BuildConfig.LOCK_CLOUD_ENDPOINT) {
        TextButton(onClick = onEndpoint, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) {
            Text("服务连接", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EndpointDialog(model: AppViewModel, onDismiss: () -> Unit) {
    val endpoint by model.endpoint.collectAsStateWithLifecycle()
    var address by remember(endpoint) { mutableStateOf(endpoint) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text("连接联调服务") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("服务地址") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                )
                Text(
                    "USB 调试并设置端口转发时使用 127.0.0.1:8765；安卓模拟器使用 10.0.2.2:8765。发布服务使用 HTTPS。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { model.saveEndpoint(address); onDismiss() }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
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

    WorkspaceCard(accent = true, modifier = Modifier.animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))) {
        Mark(Icons.Outlined.Lightbulb, if (data.optString("provider") == "rule_demo") "日程建议" else "云端建议")
        Text(
            if (supported) ReadableText.text(data.optString("title"), "云端建议") else "收到暂不支持的卡片",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            if (supported) ReadableText.text(data.optString("summary")) else "请与云端确认协议版本后重试。",
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(
            onClick = { expanded = !expanded },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
        ) { Text(if (expanded) "收起依据" else "查看 ${evidence.size} 条依据") }
        if (expanded) {
            evidence.forEach { reference ->
                val event = events.find { it.eventId == reference.optString("event_id") }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f), thickness = 0.5.dp)
                Text(
                    event?.let { ReadableText.clue(it.text) } ?: "此线索已移除或尚未同步到本机",
                    style = MaterialTheme.typography.bodyMedium,
                )
                event?.let {
                    TextButton(
                        onClick = { model.edit(it) },
                        enabled = !model.busy,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
                    ) { Text("核对原始记录") }
                }
            }
        }
        when {
            !active -> Note("依据已修改、移除或建议已过期，请重新生成。")
            !data.isNull("feedback") && data.optString("feedback").isNotEmpty() ->
                Note(if (data.optString("feedback") == "accepted") "已采纳" else "已忽略")
            supported -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WorkspaceButton(onClick = { model.feedback(item.decisionId, "accepted") }, enabled = !model.busy) { Text("采纳") }
                WorkspaceButton(onClick = { model.feedback(item.decisionId, "dismissed") }, enabled = !model.busy, secondary = true) { Text("忽略") }
            }
        }
    }
}

@Composable
private fun ColumnScope.ClueCaptureActions(
    model: AppViewModel,
    onImage: () -> Unit,
    onCalendar: () -> Unit,
    onLocation: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WorkspaceButton(onClick = { model.openEditor() }, enabled = !model.busy, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.EditNote, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("手动记录")
        }
        WorkspaceButton(onClick = onImage, enabled = !model.busy, secondary = true, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.Image, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("导入截图")
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(
            onClick = onCalendar,
            enabled = !model.busy,
            shape = CircleShape,
            modifier = Modifier.heightIn(min = 42.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text("从日历添加", style = MaterialTheme.typography.labelLarge)
        }
        FilledTonalButton(
            onClick = onLocation,
            enabled = !model.busy,
            shape = CircleShape,
            modifier = Modifier.heightIn(min = 42.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Outlined.LocationOn, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text("记录位置", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun EventCard(model: AppViewModel, event: LocalEvent, canUpload: Boolean) {
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmUpload by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WorkspaceGlyph(
                icon = sourceIcon(event.sourceKind),
                accent = event.syncState == "synced",
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    ReadableText.clue(event.text),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
                Note("${eventLabel(event)} · ${formatTime(event.capturedAt)}")
                event.occurredAt?.let { Note("发生于 ${ReadableText.dateTime(it, ZoneId.systemDefault())}") }
            }
        }
        if (event.agentWorkId != null) {
            Note(
                when {
                    event.agentError != null -> "自动整理未完成。可在云端事项中重新选择线索处理。"
                    event.agentTaskId != null -> "可在云端事项查看结果。"
                    else -> "同步后会添加到对应事项。"
                }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(onClick = { model.edit(event) }, enabled = !model.busy) { Text("纠错") }
            if (event.syncState == "local" || event.syncState == "local_copy") {
                TextButton(onClick = { confirmUpload = true }, enabled = !model.busy && canUpload) {
                    Text("上传")
                }
            }
            if (event.syncState == "synced") {
                TextButton(onClick = { showShare = true; model.loadGroups() }, enabled = !model.busy) {
                    Text("共享")
                }
            }
            TextButton(
                onClick = { confirmDelete = true },
                enabled = !model.busy,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            ) { Text("删除") }
        }
        if (event.needsReconciliation) Note("点顶部的同步，手机会保留这次修改。")
        if (event.syncState == "local_copy") Note("这份修改只在这台手机上。需要时可以单独上传。")
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text("删除这条线索？") },
            text = {
                Text(if (event.serverRevision == 0 && !event.consentToUpload) "这条记录只保存在本机，删除后无法恢复。"
                else "已上传的内容及其组内共享将同步删除，相关建议会失效。离线删除将在联网后完成。")
            },
            confirmButton = { TextButton(onClick = { model.delete(event.eventId); confirmDelete = false }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
    if (confirmUpload) {
        AlertDialog(
            onDismissRequest = { confirmUpload = false },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text("允许云端处理这段文字？") },
            text = { Text(ReadableText.clue(event.text).take(500)) },
            confirmButton = { TextButton(onClick = { model.upload(event.eventId); confirmUpload = false }) { Text("确认上传") } },
            dismissButton = { TextButton(onClick = { confirmUpload = false }) { Text("取消") } },
        )
    }
    if (showShare) {
        AlertDialog(
            onDismissRequest = { showShare = false },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text("选择共享到哪个测试组") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("所选组全体成员可见以下内容。\n${ReadableText.clue(event.text).take(300)}")
                    if (model.groups.isEmpty()) Text("暂无测试组，请先在「我的」创建或加入。")
                    model.groups.forEach { group ->
                        Text(ReadableText.text(group.name, "未命名测试组"), style = MaterialTheme.typography.titleMedium)
                        Row {
                            TextButton(onClick = { model.share(group.id, event.eventId); showShare = false }, enabled = !model.busy) { Text("确认共享") }
                            TextButton(onClick = { model.unshare(group.id, event.eventId); showShare = false }, enabled = !model.busy) { Text("撤回共享") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showShare = false }) { Text("关闭") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorSheet(model: AppViewModel, editor: ClueEditor) {
    val session by model.session.collectAsStateWithLifecycle()
    val initial = editor.original
    val draft = editor.draft
    val initialText = remember(initial.text) { ReadableText.editable(initial.text) }
    val inputText = if (draft.text == initial.text) initialText else draft.text
    val textError = if (draft.text.isNotBlank() && draft.screenJson == null) Validation.eventText(draft.text) else null
    val newNote = initial.id == null && initial.source == "manual"
    val unchanged = initial.id != null && draft == initial
    var confirmDiscard by remember(editor) { mutableStateOf(false) }
    var pickingTime by rememberSaveable(initial.id) { mutableStateOf(false) }
    val closeEditor: () -> Unit = {
        if (!model.busy) {
            if (editor.draft != initial) confirmDiscard = true else model.dismissEditor()
        }
    }
    ModalBottomSheet(
        onDismissRequest = closeEditor,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { target ->
            if (target == SheetValue.Hidden && !model.busy && editor.draft != initial) confirmDiscard = true
            target != SheetValue.Hidden || (!model.busy && editor.draft == initial)
        }),
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        WorkspaceSheetBars()
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (newNote) "记一条线索" else "核对这条线索", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = closeEditor, enabled = !model.busy) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭编辑")
                }
            }
            if (!newNote) {
                Note(SourceLabels[draft.source] ?: "线索记录")
            }
            draft.imageUri?.let {
                AsyncImage(
                    model = it,
                    contentDescription = "本次导入的原始截图",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 210.dp)
                        .clip(MaterialTheme.shapes.medium),
                )
            }
            if (initial.text.isNotBlank() && initialText.isBlank()) {
                Note(ReadableText.UnreadableClue)
            }
            OutlinedTextField(
                value = inputText,
                onValueChange = {
                    editor.draft = draft.copy(
                        text = if (it == initialText) initial.text else it,
                        screenJson = initial.screenJson.takeIf { _ -> it == initialText },
                    )
                },
                modifier = Modifier.fillMaxWidth().testTag("clue-editor-text"),
                label = { Text(if (newNote) "想记下什么？" else "线索内容") },
                isError = textError != null,
                supportingText = textError?.let { error -> { Text(error) } },
                minLines = 4,
                maxLines = 8,
                enabled = !model.busy,
                shape = MaterialTheme.shapes.small,
            )
            if (draft.screenJson != null) {
                Note("已保留发言人、消息时间与文字位置。修改文字后将作为普通线索保存。")
            }
            WorkspaceButton(
                onClick = { pickingTime = true },
                enabled = !model.busy,
                secondary = true,
            ) { Text(draft.occurredAt?.let { "发生于 ${ReadableText.dateTime(it, ZoneId.systemDefault())}" } ?: "补充确定的日期与时间") }
            if (draft.occurredAt != null) {
                TextButton(onClick = { editor.draft = editor.draft.copy(occurredAt = null) }, enabled = !model.busy) { Text("移除时间") }
            }
            if (draft.uploaded) {
                Note("这条线索已上传，纠错会同步更新云端。删除线索可移除云端记录。")
            } else if (session?.endpoint != "offline") {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(draft.upload, enabled = !model.busy, role = Role.Checkbox) { editor.draft = draft.copy(upload = it) }
                            .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Checkbox(draft.upload, onCheckedChange = null, enabled = !model.busy)
                        Text("允许上传确认后的文字，用于生成建议", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (initial.imageUri != null) {
                Note("确认后保存识别出的内容，原图留在手机上。")
            }
            WorkspaceButton(
                onClick = { model.saveEditor(editor) },
                enabled = !model.busy && (unchanged || (textError == null && (draft.text.isNotBlank() || draft.screenJson != null))),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (unchanged) "完成" else "确认并保存") }
        }
    }
    if (pickingTime) {
        WorkspaceDateTimeDialog(
            initial = ReadableText.deviceTime(draft.occurredAt) ?: LocalDateTime.now(),
            onDismiss = { pickingTime = false },
            onSelect = { chosen ->
                val originalMinute = ReadableText.deviceTime(draft.occurredAt)?.withSecond(0)?.withNano(0)
                if (chosen != originalMinute) {
                    editor.draft = editor.draft.copy(occurredAt = chosen.atZone(ZoneId.systemDefault()).toInstant().toString())
                }
                pickingTime = false
            },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃这次修改？") },
            text = { Text("这条线索还有未保存的内容。") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } },
            dismissButton = { TextButton(onClick = model::dismissEditor) { Text("放弃修改") } },
        )
    }
}

@Composable
private fun ColumnScope.DevicesScreen(
    model: AppViewModel,
    onCalendar: () -> Unit,
    onLocation: () -> Unit,
    onNotify: () -> Unit,
    onWatch: () -> Unit,
) {
    val context = LocalContext.current
    WorkspaceHeader("设备与连接")
    WorkspaceCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            WorkspaceGlyph(Icons.Outlined.Smartphone, accent = true)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("当前设备", style = MaterialTheme.typography.titleMedium)
                val deviceName = if (Build.MODEL.startsWith("sdk_")) "Android 模拟器" else Build.MODEL
                Note("$deviceName · Android ${Build.VERSION.RELEASE}")
            }
        }
    }
    WorkspaceCard {
        Text("手表提醒", style = MaterialTheme.typography.titleLarge)
        Text(
            "允许手表转发 ScreenMind 通知后，发一条提醒试试。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WorkspaceButton(onClick = onNotify, enabled = !model.busy) { Text("发送测试通知") }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f), thickness = 0.5.dp)
        WorkspaceActionRow(Icons.Outlined.Watch, "查看手表提醒", onWatch)
    }
    WorkspaceCard {
        Text("数据来源", style = MaterialTheme.typography.titleLarge)
        WorkspaceActionRow(Icons.Outlined.CalendarMonth, "选择系统日历记录", onCalendar, enabled = !model.busy)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f), thickness = 0.5.dp)
        WorkspaceActionRow(Icons.Outlined.LocationOn, "获取一次大致位置", onLocation, enabled = !model.busy)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f), thickness = 0.5.dp)
        WorkspaceActionRow(Icons.Outlined.Settings, "管理系统授权", {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        })
    }
}

@Composable
private fun ColumnScope.WatchScreen() {
    WorkspaceHeader("手表提醒预览")
    Surface(
        modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 224.dp).fillMaxWidth(),
        shape = RoundedCornerShape(48.dp),
        color = Color(0xFF101E34),
        contentColor = Color.White,
        shadowElevation = 6.dp,
        border = BorderStroke(2.dp, Color(0xFF304969)),
    ) {
        Column(
            Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.CalendarMonth, null, tint = Color(0xFFA9C6FF))
            Text("18:30", style = MaterialTheme.typography.displaySmall)
            Text("项目讨论", style = MaterialTheme.typography.titleLarge)
            Text("A302", color = Color(0xFFD4E5FF))
        }
    }
    Note("这是一条示例提醒。")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.ProfileScreen(model: AppViewModel, onGroups: () -> Unit, onDevices: () -> Unit) {
    val session by model.session.collectAsStateWithLifecycle()
    val theme by model.theme.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf(false) }
    WorkspaceHeader("账户与设置")
    WorkspaceCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            WorkspaceGlyph(Icons.Outlined.PersonOutline, accent = true)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (session?.endpoint == "offline") "离线体验" else session?.username.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                )
                Note(if (session?.endpoint == "offline") "线索仅保存在这台手机" else "已连接 ScreenMind 云端")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f), thickness = 0.5.dp)
        WorkspaceActionRow(
            icon = Icons.Outlined.Watch,
            title = "设备与授权",
            onClick = onDevices,
            enabled = !model.busy,
        )
        WorkspaceActionRow(
            icon = Icons.Outlined.Groups,
            title = "测试组",
            onClick = onGroups,
            enabled = !model.busy && session?.endpoint != "offline",
        )
    }
    WorkspaceCard {
        Text("外观", style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (key, label) ->
                FilterChip(
                    selected = theme == key,
                    onClick = { model.setTheme(key) },
                    shape = CircleShape,
                    label = { Text(label) },
                    enabled = !model.busy,
                )
            }
        }
    }
    WorkspaceButton(
        onClick = { confirmLogout = true },
        enabled = !model.busy,
        secondary = true,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.AutoMirrored.Outlined.Logout, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("退出登录")
    }
    Note("${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME}")
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text("退出当前账号？") },
            text = { Text("待上传任务将停止，本机记录按账号保留。重新登录后可以同步。") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; model.logout() }) { Text("退出") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ColumnScope.GroupScreen(model: AppViewModel) {
    val focusManager = LocalFocusManager.current
    var name by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    var pendingMemberRemoval by remember { mutableStateOf<Pair<String, String>?>(null) }
    WorkspaceHeader("测试组", "组员只能看到你主动共享到组里的线索。")
    WorkspaceCard {
        Text("创建新组", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("新建组名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )
        WorkspaceButton(
            onClick = { focusManager.clearFocus(); model.createGroup(name) },
            enabled = !model.busy,
        ) { Text("创建组") }
    }
    WorkspaceCard {
        Text("使用邀请码", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("邀请码") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )
        WorkspaceButton(
            onClick = { focusManager.clearFocus(); model.joinGroup(code) },
            enabled = !model.busy,
            secondary = true,
        ) { Text("加入组") }
    }
    if (model.groups.isEmpty()) Note("尚未加入测试组。")
    model.groups.forEach { group ->
        WorkspaceCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(ReadableText.text(group.name, "未命名测试组"), style = MaterialTheme.typography.titleLarge)
                    Note(if (group.role == "owner") "组长" else "成员")
                }
                TextButton(onClick = { model.openGroup(group.id) }, enabled = !model.busy) { Text("查看成员与共享") }
            }
        }
    }
    model.groupDetail?.let { detail ->
        val id = detail.getString("id")
        val isOwner = model.groups.find { it.id == id }?.role == "owner"
        WorkspaceCard(accent = true) {
            Text(ReadableText.text(detail.getString("name"), "未命名测试组"), style = MaterialTheme.typography.titleLarge)
            detail.itemObjects("members").forEach { member ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(member.getString("username"), style = MaterialTheme.typography.bodyLarge)
                        Note(if (member.getString("role") == "owner") "组长" else "成员")
                    }
                    if (isOwner && member.getString("role") != "owner") {
                        TextButton(
                            onClick = { pendingMemberRemoval = id to member.getString("id") },
                            enabled = !model.busy,
                        ) { Text("移除") }
                    }
                }
            }
            if (isOwner) WorkspaceButton(onClick = { model.createInvite(id) }, enabled = !model.busy) { Text("生成限时邀请码") }
            model.invite?.let { invitation ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(invitation.getString("code"), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                        Note("24 小时有效 · 最多使用 5 次")
                        TextButton(
                            onClick = { clipboard.setText(AnnotatedString(invitation.getString("code"))); model.message = "邀请码已复制" },
                            contentPadding = PaddingValues(horizontal = 0.dp),
                        ) { Text("复制邀请码") }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f), thickness = 0.5.dp)
            Text("组内共享", style = MaterialTheme.typography.titleMedium)
            if (detail.itemObjects("shared").isEmpty()) Note("暂无共享记录。")
            detail.itemObjects("shared").forEach { shared ->
                Text(ReadableText.clue(shared.getString("text")), style = MaterialTheme.typography.bodyMedium)
                Note("由 ${shared.getString("username")} 共享")
            }
        }
    }
    pendingMemberRemoval?.let { (groupId, userId) ->
        AlertDialog(
            onDismissRequest = { pendingMemberRemoval = null },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text("移除这位成员？") },
            text = { Text("该成员将失去测试组访问权限，其在组内共享的内容也会移除。") },
            confirmButton = { TextButton(onClick = { model.removeMember(groupId, userId); pendingMemberRemoval = null }) { Text("确认移除") } },
            dismissButton = { TextButton(onClick = { pendingMemberRemoval = null }) { Text("取消") } },
        )
    }
}

private fun sourceIcon(kind: String): ImageVector = when (kind) {
    "screenshot_ocr" -> Icons.Outlined.Image
    "calendar" -> Icons.Outlined.CalendarMonth
    "location" -> Icons.Outlined.LocationOn
    else -> Icons.Outlined.Description
}

internal fun eventLabel(event: LocalEvent): String =
    "${SourceLabels[event.sourceKind] ?: "线索记录"} · ${SyncLabels[event.syncState] ?: "同步状态待确认"}"

private val clueTimeFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm")

internal fun formatTime(value: String): String = try {
    clueTimeFormat.withZone(ZoneId.systemDefault()).format(Instant.parse(value))
} catch (_: java.time.format.DateTimeParseException) { "时间待确认" }
