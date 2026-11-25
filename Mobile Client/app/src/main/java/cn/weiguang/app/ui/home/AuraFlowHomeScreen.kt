package cn.weiguang.app.ui.home

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.weiguang.app.EditorDraft
import cn.weiguang.app.AppViewModel
import cn.weiguang.app.capture.CaptureRuntime
import cn.weiguang.app.data.LocalDecision
import cn.weiguang.app.data.LocalEvent
import cn.weiguang.app.data.itemObjects
import cn.weiguang.app.home.*
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val HomeSourceLabels = mapOf(
    "manual" to "手动记录",
    "screenshot_ocr" to "截图识别",
    "calendar" to "系统日历",
    "location" to "位置采样"
)
private val HomeSyncLabels = mapOf(
    "local" to "仅本机",
    "pending" to "待同步",
    "synced" to "已同步",
    "failed" to "等待重试",
    "conflict" to "版本冲突"
)

@Composable
fun AuraFlowHomeScreen(
    model: AppViewModel,
    events: List<LocalEvent>,
    decisions: List<LocalDecision>,
    onImage: () -> Unit,
    onClues: () -> Unit,
    onCalendar: () -> Unit,
    onAgent: () -> Unit
) {
    val session by model.session.collectAsStateWithLifecycle()
    val captureStatus by CaptureRuntime.status.collectAsStateWithLifecycle()
    val captureGrant by model.captureGrant.collectAsStateWithLifecycle()

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, session?.accountKey) {
        val current = session ?: return@LaunchedEffect
        if (current.endpoint == "offline") return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                model.refreshHomeSdui()
                try {
                    model.repository.homeUpdates(current.accountKey).collect { model.refreshHomeSdui() }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { /* Reconnect after refreshing the session and snapshot. */ }
                val localGateway = current.endpoint in setOf("http://127.0.0.1:8765", "http://10.0.2.2:8765")
                delay(if (cn.weiguang.app.BuildConfig.DEBUG && localGateway) 2_000L else 60_000L)
            }
        }
    }
    val sdui by remember(model, events, decisions) { derivedStateOf { model.effectiveHomeSdui(events, decisions) } }
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    val agenda = remember(sdui, now) { sdui.agenda(now) }
    val localContext = sdui.localContext?.takeUnless { sdui.showcaseMode }
    val homeCity = localContext?.city
    val weatherStatus = localContext?.weather?.status
    var showCityDialog by remember(session?.accountKey) { mutableStateOf(false) }
    var placeEventId by remember(session?.accountKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(session?.accountKey, homeCity?.code, weatherStatus) {
        if (homeCity != null && weatherStatus == "not_loaded") model.homeRefreshWeather()
    }
    var activeDetailId by remember(session?.accountKey, sdui.showcaseMode) { mutableStateOf<String?>(null) }
    var gapSheetCardId by remember { mutableStateOf<String?>(null) }
    var alertSheetCardId by remember { mutableStateOf<String?>(null) }
    var showDirector by remember { mutableStateOf(false) }
    var archivedExpanded by remember { mutableStateOf(false) }

    // Particle FX trigger state
    var fxKey by remember { mutableIntStateOf(0) }
    var fxColor by remember { mutableStateOf(AuraColors.Travel) }
    var fxVapor by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            delay((60 - now.second) * 1000L)
            now = ZonedDateTime.now()
        }
    }

    val activeDetailCard = sdui.findCard(activeDetailId)
    var detailWire by remember(session?.accountKey, activeDetailId) { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(session?.accountKey, activeDetailId, activeDetailCard, sdui.timelineIds != null) {
        val account = session?.accountKey ?: return@LaunchedEffect
        val id = activeDetailId ?: return@LaunchedEffect
        if (sdui.timelineIds == null) return@LaunchedEffect
        try { detailWire = model.repository.homeDetail(account, id) }
        catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { detailWire = null }
    }
    val effectiveThemeKey = activeDetailCard?.themeKey ?: model.homePillTheme ?: if (sdui.showcaseMode) sdui.activeTheme else agenda.today.firstOrNull()?.card?.themeKey ?: "explore"
    val palette = auraPalette(effectiveThemeKey)

    val pillSensing = model.homePillActive || model.busy
    val pillSensingText = when {
        model.homePillActive -> model.homePillText
        model.busy -> "灵枢同步中 · 正在更新流光视图..."
        else -> ""
    }
    val pillIdleText = when {
        captureGrant != null && captureGrant?.paused == false && captureStatus.connected ->
            "聊天自动感知已开启 · ${captureGrant?.title.orEmpty()}"
        sdui.showcaseMode -> "演示模式 · 查看场景控制台"
        sdui.fromServer -> "线索与云端事项"
        else -> "本机线索"
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Obsidian Kinetic Aurora Background (scoped strictly to Home tab)
        KineticAuroraBackground(themeKey = effectiveThemeKey, modifier = Modifier.matchParentSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // L2 · Dynamic Perception Pill
            L2PerceptionPill(
                themeKey = effectiveThemeKey,
                sensing = pillSensing,
                sensingText = pillSensingText,
                idleText = pillIdleText,
                onClick = { if (sdui.showcaseMode) showDirector = true else onClues() }
            )

            // Editorial Header + Quick Action Strip
            val dateTitle = DateTimeFormatter.ofPattern("M月d日").format(now)
            val proposalCount = sdui.events.count { it.lifecycleStage == LifecycleStage.PROPOSAL }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(dateTitle, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                        Text("· 今日", color = AuraColors.TextSecondary, fontSize = 18.sp, fontWeight = FontWeight.Light)
                    }
                    Text(
                        text = "把线索，变成下一步。",
                        color = AuraColors.TextSecondary,
                        fontSize = 11.5.sp
                    )
                    localContext?.let {
                        HomeWeatherButton(it, enabled = !model.busy) { showCityDialog = true }
                    }
                    if (sdui.showcaseMode && sdui.dayMode == DayMode.ACTIVE_TIMELINE) {
                        Text(
                            text = "今日 ${sdui.events.size} 项安排 · ${if (proposalCount > 0) "$proposalCount 项待拍板" else "全部已确认"}",
                            color = palette.accent.copy(alpha = 0.9f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                if (!sdui.showcaseMode) {
                    TextButton(onClick = { model.editor = EditorDraft(type = "schedule") }) { Text("＋ 添加日程", color = palette.accent) }
                } else Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (sdui.dayMode == DayMode.EMPTY_EXPLORE) AuraColors.Explore.copy(alpha = 0.22f) else Color(0x0FFFFFFF))
                            .border(1.dp, if (sdui.dayMode == DayMode.EMPTY_EXPLORE) AuraColors.Explore.copy(alpha = 0.6f) else AuraColors.Hairline, CircleShape)
                            .clickable {
                                val nextMode = if (sdui.dayMode == DayMode.EMPTY_EXPLORE) "ACTIVE_TIMELINE" else "EMPTY_EXPLORE"
                                model.homeSetDayMode(nextMode)
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            if (sdui.dayMode == DayMode.EMPTY_EXPLORE) "日程轴" else "出去逛逛 ✦",
                            color = if (sdui.dayMode == DayMode.EMPTY_EXPLORE) Color(0xFFE9D5FF) else Color(0xFFE2E8F0),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0x0FFFFFFF))
                            .border(1.dp, AuraColors.Hairline, CircleShape)
                            .clickable { showDirector = true }
                            .padding(horizontal = 9.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Outlined.Tune, "控制台", tint = palette.accent, modifier = Modifier.size(13.dp))
                            Text("控制台", color = Color(0xFFE2E8F0), fontSize = 10.5.sp)
                        }
                    }
                }
            }

            // Real Feature Action Pills (Cloud Agent + OCR Screenshot + Showcase Quick Toggle)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ActionGlassChip(
                    label = "进入云端事项",
                    icon = Icons.Outlined.AutoAwesome,
                    accent = AuraColors.Travel,
                    enabled = !model.busy && session?.endpoint != "offline",
                    modifier = Modifier.testTag("open-agent"),
                    onClick = onAgent
                )
                ActionGlassChip(
                    label = "导入截图识别",
                    icon = Icons.Outlined.ImageSearch,
                    accent = AuraColors.Shopping,
                    enabled = !model.busy,
                    onClick = onImage
                )
                ActionGlassChip("导入日历", Icons.Outlined.DateRange, AuraColors.Office, !model.busy, onClick = onCalendar)
                ActionGlassChip(
                    label = if (sdui.showcaseMode) "切回真实线索" else "流光全场景示例",
                    icon = Icons.Outlined.Bolt,
                    accent = AuraColors.Explore,
                    enabled = !model.busy,
                    onClick = { model.homeSimulate(if (sdui.showcaseMode) "exit_showcase" else "load_showcase") }
                )
            }

            // =================================================================
            // MACRO DAY MODE DISPATCHER (L0)
            // =================================================================
            if (!sdui.showcaseMode && sdui.dayMode == DayMode.REST_MODE) {
                RestModeView(dateTitle, { model.homeSetDayMode("EMPTY_EXPLORE") }, { model.homeSetDayMode("ACTIVE_TIMELINE") })
            } else if (!sdui.showcaseMode && sdui.dayModeOverride == "EMPTY_EXPLORE") {
                Text("当前没有可用的漫游路线。", color = AuraColors.TextSecondary)
                TextButton(onClick = { model.homeSetDayMode("ACTIVE_TIMELINE") }) { Text("返回日程") }
            } else if (!sdui.showcaseMode) {
                HomeAgendaView(agenda, now, { activeDetailId = it.id },
                    onDraft = { title, at -> model.editor = EditorDraft(title, type = "schedule", occurredAt = at?.toInstant()?.toString()) },
                    onDismiss = { model.homeDismissMisjudgment(it) },
                    onReminder = model::homeDismissReminder,
                    homeContext = localContext,
                    onPlace = { placeEventId = it.id },
                    onEstimate = { from, to -> model.homeEstimateWalk(from, to) },
                    estimating = model.busy,
                    onShift = { item ->
                        val source = events.find { it.eventId == item.card.sourceEventId }
                        if (source != null && item.card.sourceWorkId == null) {
                            model.edit(source)
                            model.editor?.let { model.editor = it.copy(occurredAt = item.start(now.zone)?.plusMinutes(30)?.toInstant()?.toString()) }
                        } else activeDetailId = item.card.id
                    })
            } else when (sdui.dayMode) {
                DayMode.EMPTY_EXPLORE -> EmptyExploreView(
                    state = sdui,
                    onToggleWeather = {
                        model.homeSetWeather(if (sdui.weatherContext.mode == "sunny") "rainy" else "sunny")
                    },
                    onAdopt = {
                        fxColor = AuraColors.Explore
                        fxVapor = false
                        fxKey += 1
                        model.homeAdoptExplore()
                    },
                    onCycle = { model.homeCycleExplore() },
                    onRest = { model.homeSetDayMode("REST_MODE") },
                    onLoadShowcase = { model.homeSimulate("load_showcase") }
                )

                DayMode.REST_MODE -> RestModeView(
                    dateTitle = dateTitle,
                    onWakeExplore = { model.homeSetDayMode("EMPTY_EXPLORE") },
                    onRestoreTimeline = {
                        if (sdui.events.isEmpty()) model.homeSimulate("load_showcase")
                        else model.homeSetDayMode("ACTIVE_TIMELINE")
                    }
                )

                DayMode.ACTIVE_TIMELINE -> ActiveTimelineView(
                    state = sdui,
                    countdownSec = sdui.focusAt?.let { java.time.Duration.between(sdui.simulatedAt?.let(Instant::parse) ?: now.toInstant(), Instant.parse(it)).seconds.coerceAtLeast(0).toInt() }
                        ?: if (sdui.timelineIds == null) 2 * 3600 + 14 * 60 else 0,
                    archivedExpanded = archivedExpanded,
                    onToggleArchived = { archivedExpanded = !archivedExpanded },
                    onOpenDetail = { activeDetailId = it },
                    onDismissReminder = { model.homeDismissReminder(it) },
                    onResolveConflict = { model.homeResolveConflict(it) },
                    onOpenGapSheet = { gapSheetCardId = it },
                    onOpenAlertSheet = { alertSheetCardId = it },
                    onVaporizeCard = { cardId ->
                        fxColor = AuraColors.Explore
                        fxVapor = true
                        fxKey += 1
                        model.homeDismissMisjudgment(cardId)
                    }
                )
            }

            // =================================================================
            // REAL LEGACY DECISIONS & RECENT CLUES (OBSIDIAN GLASS INTEGRATION)
            // Keeps full compatibility with real sync, OCR, and DeviceFlowTest
            // =================================================================
            if (decisions.isNotEmpty() || events.isNotEmpty() || session?.endpoint == "offline") {
                HorizontalDivider(color = AuraColors.Hairline, modifier = Modifier.padding(top = 4.dp))
            }

            decisions.take(10).forEach { item ->
                AuraDecisionCard(model = model, item = item, events = events)
            }

            if (events.isNotEmpty()) {
                OutlinedButton(
                    onClick = model::generate,
                    enabled = !model.busy && session?.endpoint != "offline",
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AuraColors.Travel)
                ) {
                    Text("用已同步线索生成联调建议")
                }
            }

            if (session?.endpoint == "offline") {
                Text(
                    "当前为离线体验，线索保存在本机。",
                    color = AuraColors.TextSecondary,
                    fontSize = 11.5.sp
                )
            }

            // Recent Clues Footer Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("最近线索", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onClues) { Text("查看全部", color = palette.accent, fontSize = 12.sp) }
            }
            if (events.isEmpty()) {
                Text("暂无本机线索。可点击上方「导入截图识别」或到「线索」手动记录。", color = AuraColors.TextSecondary, fontSize = 12.sp)
            } else {
                events.take(3).forEach { event ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xB8111624))
                            .border(1.dp, AuraColors.Hairline, RoundedCornerShape(18.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "${HomeSourceLabels[event.sourceKind] ?: event.sourceKind} · ${HomeSyncLabels[event.syncState] ?: event.syncState}",
                            color = palette.accent,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(event.text, color = AuraColors.TextPrimary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "查看与纠错",
                            color = AuraColors.TextSecondary,
                            fontSize = 11.5.sp,
                            modifier = Modifier.clickable(enabled = !model.busy) { model.edit(event) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }

        // Particle Burst / Vaporization Overlay
        ParticleBurstOverlay(burstKey = fxKey, color = fxColor, vaporMode = fxVapor)
    }

    // L1 · Fluid Detail Sheet
    if (activeDetailCard != null && !sdui.showcaseMode) {
        val entry = (agenda.today + agenda.later + agenda.unscheduled).find { it.card.id == activeDetailCard.id }
        val card = entry?.card ?: activeDetailCard
        val source = events.find { it.eventId == card.sourceEventId }
        val endLabel = entry?.takeIf { it.clock != null }?.let { item ->
            item.end?.let { end ->
                val format = if (end.toLocalDate() == item.date) "HH:mm" else "M月d日 HH:mm"
                end.format(DateTimeFormatter.ofPattern(format))
            }
        }
        val timeLabel = endLabel?.let { "${card.time}–$it" } ?: card.time
        val dateLabel = listOfNotNull(entry?.date?.format(DateTimeFormatter.ofPattern("M月d日")), timeLabel).joinToString(" ")
        SourceDetailSheet(card, dateLabel, { activeDetailId = null },
            onEdit = source?.let { { activeDetailId = null; model.edit(it) } },
            onAgent = card.sourceWorkId?.takeIf { session?.endpoint != "offline" }?.let { workId -> {
                activeDetailId = null
                model.selectAgentWork(workId, card.sourceTaskId)
                onAgent()
            } },
            onConfirm = { model.homeConfirmCard(card.id) },
            onComplete = { model.homeCompleteCard(card.id); activeDetailId = null },
            onDismiss = { model.homeDismissMisjudgment(card.id); activeDetailId = null },
            place = localContext?.places?.get(card.id),
            onPlace = localContext?.takeIf { card.lifecycleStage != LifecycleStage.ARCHIVED }?.let {
                { activeDetailId = null; placeEventId = card.id }
            })
    } else if (activeDetailCard != null) {
        val header = detailWire?.optJSONObject("header")
        val noPlans = detailWire?.let { it.optString("lifecycleStage") == "PROPOSAL" && it.isNull("stickyDock") } == true
        if (noPlans) {
            AlertDialog(onDismissRequest = { activeDetailId = null }, title = { Text(activeDetailCard.title) },
                text = { Text("当前条件下暂无可确认方案。") },
                confirmButton = { TextButton(onClick = { activeDetailId = null }) { Text("返回") } })
        } else
        AuraDetailModalSheet(
            card = if (header != null) activeDetailCard.copy(title = header.optString("title", activeDetailCard.title),
                subtitle = header.optString("subtitle", activeDetailCard.subtitle)) else activeDetailCard,
            friends = sdui.diningFriends,
            onDismiss = { activeDetailId = null },
            onSelectPlan = { idx -> model.homeSelectPlan(activeDetailCard.id, idx) },
            onConfirmCard = {
                fxColor = auraPalette(activeDetailCard.themeKey).accent
                fxVapor = false
                fxKey += 1
                model.homeConfirmCard(activeDetailCard.id)
            },
            onToggleChecklist = { idx -> model.homeToggleChecklist(activeDetailCard.id, idx) },
            onToggleNote = { idx -> model.homeToggleNote(activeDetailCard.id, idx) },
            onVaporizeMisjudgment = {
                fxColor = AuraColors.Explore
                fxVapor = true
                fxKey += 1
                model.homeDismissMisjudgment(activeDetailCard.id)
            },
            onOpenSourceClue = { evId ->
                events.find { it.eventId == evId }?.let { model.edit(it) }
            },
            onOpenAgent = onAgent,
            onToast = { model.message = it }
        )
    } else if (activeDetailId != null) {
        val archived = sdui.archivedEvents.find { it.id == activeDetailId }
        if (archived != null) AlertDialog(onDismissRequest = { activeDetailId = null },
            title = { Text(archived.title) }, text = { Text(detailWire?.optJSONObject("header")?.optString("subtitle") ?: archived.summary) },
            confirmButton = { TextButton(onClick = { activeDetailId = null }) { Text("关闭") } })
    }

    if (localContext != null && showCityDialog) {
        HomeCityDialog(localContext, onSelect = { city ->
            showCityDialog = false
            model.homeSelectCity(city.code)
        }, onDismiss = { showCityDialog = false })
    }
    val placeCard = sdui.findCard(placeEventId)
    val account = session?.accountKey
    if (localContext != null && placeCard != null && account != null) {
        HomePlaceDialog(localContext.cities, homeCity, localContext.places[placeCard.id],
            suggested = placeCard.sourceFacts().firstOrNull { it.first == "地点" }?.second.orEmpty(),
            search = { city, query ->
                HomePlaceResults.fromJson(model.repository.searchHomePlaces(account, city.code, query))
            },
            onSelect = { choice ->
                placeEventId = null
                model.homeSetPlace(placeCard.id, choice?.id)
            },
            onDismiss = { placeEventId = null })
    }

    // Mini Bottom Sheet: Gap Suggestion
    sdui.findCard(gapSheetCardId)?.let { card ->
        GapSuggestionSheet(
            afterCard = card,
            onDismiss = { gapSheetCardId = null },
            onInsertSpot = { spot -> model.homeInsertGapSpot(card.id, spot) }
        )
    }

    // Mini Bottom Sheet: Alert Plan B
    sdui.findCard(alertSheetCardId)?.let { card ->
        AlertPlanBSheet(
            card = card,
            onDismiss = { alertSheetCardId = null },
            onApplyChoice = { choice -> model.homeApplyPlanB(card.id, choice) }
        )
    }

    // AuraFlow Director & SDUI JSON Protocol Sheet
    if (showDirector && sdui.showcaseMode) {
        AuraDirectorSheet(
            state = sdui,
            activeDetailId = activeDetailId,
            onDismiss = { showDirector = false },
            onSetDayMode = { model.homeSetDayMode(it) },
            onSetWeather = { model.homeSetWeather(it) },
            onSimulate = { scenario ->
                if (scenario == "sms_ticket") {
                    fxColor = AuraColors.Travel
                    fxVapor = false
                    fxKey += 1
                }
                model.homeSimulate(scenario)
            },
            onToast = { model.message = it }
        )
    }
}

@Composable
private fun ActionGlassChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(if (enabled) accent.copy(alpha = 0.13f) else Color(0x0AFFFFFF))
            .border(1.dp, if (enabled) accent.copy(alpha = 0.42f) else AuraColors.Hairline, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(icon, null, tint = if (enabled) accent else AuraColors.TextMuted, modifier = Modifier.size(14.dp))
        Text(label, color = if (enabled) Color.White else AuraColors.TextMuted, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ActiveTimelineView(
    state: AuraFlowState,
    countdownSec: Int,
    archivedExpanded: Boolean,
    onToggleArchived: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onDismissReminder: (String) -> Unit,
    onResolveConflict: (String) -> Unit,
    onOpenGapSheet: (String) -> Unit,
    onOpenAlertSheet: (String) -> Unit,
    onVaporizeCard: (String) -> Unit
) {
    // 1. Auto-Collapsed Archived Pill (Stage D)
    if (state.archivedEvents.isNotEmpty()) {
        val firstArch = state.archivedEvents.first()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x0BFFFFFF))
                    .border(1.dp, AuraColors.Hairline, RoundedCornerShape(16.dp))
                    .clickable(onClick = onToggleArchived)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(AuraColors.TextMuted))
                    Text(
                        "已完成 ${state.archivedEvents.size} 项日程 · ${firstArch.time} ${firstArch.title}",
                        color = AuraColors.TextSecondary,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(if (archivedExpanded) "▴" else "▾", color = AuraColors.TextSecondary, fontSize = 12.sp)
            }
            if (archivedExpanded) {
                state.archivedEvents.forEach { arch ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0x08FFFFFF))
                            .border(1.dp, AuraColors.Hairline, RoundedCornerShape(16.dp))
                            .clickable { onOpenDetail(arch.id) }
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${arch.time} - ${arch.endTime}", color = AuraColors.TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Text("ARCHIVED", color = AuraColors.TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                        Text(arch.summary, color = Color(0xCCCBD5E1), fontSize = 11.5.sp, lineHeight = 17.sp)
                    }
                }
                state.dailyFootprint?.let { fp ->
                    Text("✦ $fp", color = AuraColors.TextMuted, fontSize = 10.5.sp, modifier = Modifier.padding(horizontal = 6.dp))
                }
            }
        }
    }

    // 2. NOW · Focus Card (Top Center)
    val focus = if (state.timelineIds != null) state.findCard(state.focusId)
        else state.events.find { it.id == "evt_travel_outbound" } ?: state.events.firstOrNull()
    if (focus != null) {
        val focusPalette = auraPalette(focus.themeKey)
        val focusConfirmed = focus.lifecycleStage == LifecycleStage.CONFIRMED
        val infinite = rememberInfiniteTransition(label = "nowBreathe")
        val glowAlpha by infinite.animateFloat(
            initialValue = 0.28f,
            targetValue = 0.62f,
            animationSpec = infiniteRepeatable(tween(2100), RepeatMode.Reverse),
            label = "nowGlow"
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("此刻焦点", color = AuraColors.TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text(
                    if (focusConfirmed) "查看详情" else "查看方案",
                    color = focusPalette.accent,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Brush.linearGradient(listOf(AuraColors.CardNowTop, AuraColors.CardNowBottom)))
                    .border(1.dp, focusPalette.accent.copy(alpha = glowAlpha), RoundedCornerShape(24.dp))
                    .clickable { onOpenDetail(focus.id) }
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(focus.time, color = focusPalette.accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                            Text("·", color = AuraColors.TextMuted)
                            Text(
                                when {
                                    state.focusInProgress -> "进行中"
                                    focusConfirmed -> "已确认"
                                    else -> "待确认"
                                },
                                color = AuraColors.TextSecondary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(focus.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(focus.subtitle, color = AuraColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            formatCountdownHms(countdownSec),
                            color = focusPalette.accent,
                            fontSize = 13.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Text(if (state.focusInProgress) "后结束" else "后开始", color = AuraColors.TextSecondary, fontSize = 10.sp)
                    }
                }
                if (state.focusProgress > 0f) {
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color(0x16FFFFFF))) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(state.focusProgress.coerceIn(0f, 1f))
                                .clip(CircleShape)
                                .background(Brush.horizontalGradient(listOf(focusPalette.accent, focusPalette.alt)))
                        )
                    }
                }
            }
        }
    }

    // 3. Luminous Vertical Timeline + 4 Dynamic Connector States
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("今日日程", color = AuraColors.TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text("点击卡片展开 · 虚框左滑雾化", color = AuraColors.TextMuted, fontSize = 10.sp)
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val timeline = state.timelineIds?.mapNotNull(state::findCard) ?: state.events
            timeline.forEachIndexed { index, ev ->
                TimelineCardNode(
                    card = ev,
                    onClick = { onOpenDetail(ev.id) },
                    onDismissReminder = { onDismissReminder(ev.id) },
                    onVaporize = { onVaporizeCard(ev.id) }
                )

                // Dynamic Connector Line to Next Node
                ev.connectorToNext?.takeIf { index < timeline.lastIndex && state.events.indexOf(ev) + 1 == state.events.indexOf(timeline[index+1]) }?.let { conn ->
                    TimelineConnectorRow(
                        connector = conn,
                        onResolveConflict = { onResolveConflict(ev.id) },
                        onOpenGap = { onOpenGapSheet(ev.id) },
                        onOpenAlert = { onOpenAlertSheet(ev.id) }
                    )
                }
            }
        }
    }

    // 4. Cross-Day Relay Anchor (Bottom of Timeline)
    state.crossDayRelay?.let { relay ->
        val relayConfirmed = relay.lifecycleStage == LifecycleStage.CONFIRMED
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(if (relayConfirmed) AuraColors.Travel.copy(alpha = 0.10f) else Color(0x0BFFFFFF))
                .then(
                    if (relayConfirmed) Modifier.border(1.dp, AuraColors.Travel.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                    else Modifier.dashedBorder(1.dp, Color(0x38FFFFFF), 18.dp)
                )
                .clickable { onOpenDetail(relay.id) }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (relayConfirmed) AuraColors.Travel else AuraColors.TextSecondary))
                Text(
                    "${relay.time.substringBefore(' ')}返程 · ${relay.title.substringBefore('·').trim()} · ${if (relayConfirmed) relay.subtitle else "未订票（提前48h唤醒）"}",
                    color = Color(0xFFE2E8F0),
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(if (relayConfirmed) "查看 ↗" else "选票 →", color = AuraColors.Travel, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
internal fun TimelineCardNode(
    card: AuraCard,
    onClick: () -> Unit,
    onDismissReminder: () -> Unit,
    onVaporize: () -> Unit
) {
    val palette = auraPalette(card.themeKey)
    val isConfirmed = card.lifecycleStage == LifecycleStage.CONFIRMED
    val swipeable = card.isMisjudgment || !isConfirmed
    var swipeOffsetX by remember(card.id) { mutableFloatStateOf(0f) }
    val infinite = rememberInfiniteTransition(label = "propPulse")
    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.42f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "dashedAlpha"
    )

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        // Left Luminous Timeline Rail + Dot
        Column(
            modifier = Modifier.width(20.dp).padding(top = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(palette.accent)
                    .border(2.dp, AuraColors.ScreenBg, CircleShape)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            val dragFrac = (-swipeOffsetX / 240f).coerceIn(0f, 1f)
            val cardModifier = Modifier
                .testTag("home-card-${card.id}")
                .fillMaxWidth()
                .offset { IntOffset(swipeOffsetX.roundToInt(), 0) }
                .alpha(1f - dragFrac * 0.7f)
                .clip(RoundedCornerShape(20.dp))
                .then(
                    if (isConfirmed) {
                        Modifier
                            .background(Brush.linearGradient(listOf(AuraColors.CardSolidTop, AuraColors.CardSolidBottom)))
                            .border(1.dp, AuraColors.Hairline, RoundedCornerShape(20.dp))
                    } else {
                        Modifier
                            .background(Brush.linearGradient(listOf(palette.accent.copy(alpha = 0.09f), Color(0xAD0B0E18))))
                            .dashedBorder(1.2.dp, palette.accent.copy(alpha = pulseAlpha), 20.dp)
                    }
                )
                .then(
                    if (swipeable) {
                        Modifier.pointerInput(card.id) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (swipeOffsetX < -150f) {
                                        swipeOffsetX = 0f
                                        onVaporize()
                                    } else {
                                        swipeOffsetX = 0f
                                    }
                                },
                                onDragCancel = { swipeOffsetX = 0f },
                                onHorizontalDrag = { change, amount ->
                                    if (amount < 0f || swipeOffsetX < 0f) {
                                        change.consume()
                                        swipeOffsetX = (swipeOffsetX + amount).coerceIn(-260f, 0f)
                                    }
                                }
                            )
                        }
                    } else Modifier
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp)

            Row(
                modifier = cardModifier,
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(card.time, color = palette.accent, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        Text(card.title, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(card.subtitle, color = AuraColors.TextSecondary, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                Spacer(Modifier.width(8.dp))
                when {
                    card.category == AuraCategory.SHOPPING && card.checklist.isNotEmpty() -> {
                        val done = card.checklist.count { it.done }
                        ProgressRingBadge(done.toFloat() / card.checklist.size, "$done/${card.checklist.size}", palette.accent, 30.dp)
                    }
                    card.isMisjudgment -> {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(AuraColors.Explore.copy(alpha = 0.22f))
                                .border(1.dp, AuraColors.Explore.copy(alpha = 0.5f), CircleShape)
                                .clickable(onClick = onVaporize)
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                        ) {
                            Text("← 雾化 ×", color = Color(0xFFE9D5FF), fontSize = 10.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    else -> {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(palette.accent.copy(alpha = if (isConfirmed) 0.14f else 0.18f))
                                .border(1.dp, palette.accent.copy(alpha = if (isConfirmed) 0.32f else 0.48f), CircleShape)
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                        ) {
                            Text(card.badge, color = palette.accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Stage B · Parasitic Pre-Trip Reminder Strip (only on confirmed cards, dismissible)
            if (isConfirmed && card.reminderStrip != null && !card.reminderStrip.dismissed) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                        .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                        .background(Color(0x0DFFFFFF))
                        .border(1.dp, AuraColors.Hairline, RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "✦ ${card.reminderStrip.text}",
                        color = Color(0xFFCBD5E1),
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "×",
                        color = AuraColors.TextSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.clickable(onClick = onDismissReminder).padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineConnectorRow(
    connector: ConnectorInfo,
    onResolveConflict: () -> Unit,
    onOpenGap: () -> Unit,
    onOpenAlert: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Vertical glowing rail segment
        val railColor = when (connector.connectorState) {
            ConnectorState.ALERT_PULSE -> AuraColors.Alert
            ConnectorState.CONFLICT_DASHED -> AuraColors.Dining
            ConnectorState.GAP_SUGGESTION -> AuraColors.Explore
            ConnectorState.NORMAL_SOLID -> AuraColors.Shopping.copy(alpha = 0.55f)
        }
        Box(
            modifier = Modifier.width(20.dp).height(30.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.width(4.dp).fillMaxHeight()) {
                val effect = if (connector.connectorState in listOf(ConnectorState.CONFLICT_DASHED, ConnectorState.ALERT_PULSE)) {
                    PathEffect.dashPathEffect(floatArrayOf(7f, 5f))
                } else null
                drawLine(
                    color = railColor,
                    start = Offset(size.width / 2f, 0f),
                    end = Offset(size.width / 2f, size.height),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = effect
                )
            }
        }

        when (connector.connectorState) {
            ConnectorState.GAP_SUGGESTION -> {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(AuraColors.Explore.copy(alpha = 0.14f))
                        .border(1.dp, AuraColors.Explore.copy(alpha = 0.45f), CircleShape)
                        .clickable(onClick = onOpenGap)
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(connector.label, color = Color(0xFFE9D5FF), fontSize = 10.5.sp)
                }
            }

            ConnectorState.CONFLICT_DASHED -> {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(AuraColors.Dining.copy(alpha = 0.11f))
                        .border(1.dp, AuraColors.Dining.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 11.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⚡ ${connector.label}", color = Color(0xFFFED7AA), fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(AuraColors.Dining)
                            .clickable(onClick = onResolveConflict)
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Text(connector.actionLabel ?: "一键顺延 30m", color = Color(0xFF020617), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            ConnectorState.ALERT_PULSE -> {
                val infinite = rememberInfiniteTransition(label = "alertPulse")
                val borderAlpha by infinite.animateFloat(0.45f, 0.98f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "aAlpha")
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(AuraColors.Alert.copy(alpha = 0.15f))
                        .border(1.2.dp, AuraColors.Alert.copy(alpha = borderAlpha), RoundedCornerShape(14.dp))
                        .clickable(onClick = onOpenAlert)
                        .padding(horizontal = 11.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🚨 ${connector.label}", color = Color(0xFFFFE4E6), fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(AuraColors.Alert)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text("B 计划", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            ConnectorState.NORMAL_SOLID -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 4.dp)) {
                    Box(Modifier.size(4.dp).clip(CircleShape).background(AuraColors.Shopping.copy(alpha = 0.7f)))
                    Text(connector.label.ifBlank { "衔接充裕" }, color = AuraColors.TextMuted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun EmptyExploreView(
    state: AuraFlowState,
    onToggleWeather: () -> Unit,
    onAdopt: () -> Unit,
    onCycle: () -> Unit,
    onRest: () -> Unit,
    onLoadShowcase: () -> Unit
) {
    val w = state.weatherContext
    val preset = state.activeExplorePreset

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("今日无日程 · 已为你生成附近漫游灵感", color = AuraColors.Explore, fontSize = 12.sp)
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0x12FFFFFF))
                    .border(1.dp, AuraColors.HairlineStrong, CircleShape)
                    .clickable(onClick = onToggleWeather)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(if (w.mode == "sunny") "☀ 21°C" else "🌧 15°C", color = Color.White, fontSize = 11.sp)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.linearGradient(
                        listOf(AuraColors.Explore.copy(alpha = 0.18f), Color(0xEB100E20), AuraColors.ExploreGold.copy(alpha = 0.14f))
                    )
                )
                .border(1.dp, AuraColors.Explore.copy(alpha = 0.45f), RoundedCornerShape(28.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(AuraColors.Explore.copy(alpha = 0.22f))
                        .border(1.dp, AuraColors.Explore.copy(alpha = 0.45f), CircleShape)
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text("EMPTY_EXPLORE · ${preset.tag}", color = Color(0xFFE9D5FF), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                Text(preset.duration, color = AuraColors.ExploreGold, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
            }

            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(preset.title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text("结合当前「${w.label}」与机主静谧偏好生成", color = Color(0xCCCBD5E1), fontSize = 12.sp)
            }

            HorizontalDivider(color = AuraColors.Hairline)

            // 3-stop route preview
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                preset.stops.forEachIndexed { idx, s ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier
                                .padding(top = 5.dp)
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(if (idx == preset.stops.lastIndex) AuraColors.ExploreGold else AuraColors.Explore)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(s.name, color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                            Text(s.note, color = AuraColors.TextSecondary, fontSize = 11.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(s.time, color = Color(0xFFE9D5FF), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text(s.dist, color = AuraColors.TextMuted, fontSize = 10.sp)
                        }
                    }
                }
            }

            // 3 Interactive Action Buttons
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFFE9D5FF), AuraColors.Explore, AuraColors.ExploreGold)))
                    .clickable(onClick = onAdopt)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("就去这 · 采纳上轴 ✦", color = Color(0xFF020617), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(13.dp))
                        .background(Color(0x14FFFFFF))
                        .border(1.dp, AuraColors.Hairline, RoundedCornerShape(13.dp))
                        .clickable(onClick = onCycle)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("换一批灵感 ↻", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(13.dp))
                        .background(Color(0x0BFFFFFF))
                        .border(1.dp, AuraColors.Hairline, RoundedCornerShape(13.dp))
                        .clickable(onClick = onRest)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("今日宜放空", color = Color(0xFFCBD5E1), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        Text(
            "点击上方天气胶囊对比晴天户外 / 雨天室内路线，或点此载入全场景示例",
            color = AuraColors.TextMuted,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onLoadShowcase)
        )
    }
}

@Composable
private fun RestModeView(
    dateTitle: String,
    onWakeExplore: () -> Unit,
    onRestoreTimeline: () -> Unit
) {
    val infinite = rememberInfiniteTransition(label = "restHalo")
    val scale by infinite.animateFloat(0.9f, 1.12f, infiniteRepeatable(tween(2800), RepeatMode.Reverse), label = "haloScale")
    val alpha by infinite.animateFloat(0.35f, 0.78f, infiniteRepeatable(tween(2800), RepeatMode.Reverse), label = "haloAlpha")

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(modifier = Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(listOf(AuraColors.Office.copy(alpha = 0.34f * alpha), Color.Transparent)),
                    radius = size.minDimension * 0.5f * scale
                )
            }
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Color(0x0DFFFFFF))
                    .border(1.dp, AuraColors.HairlineStrong, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Color(0xCCE2E8F0), AuraColors.Office.copy(alpha = 0.65f))))
                )
            }
        }
        Text("REST MODE · $dateTitle", color = AuraColors.TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text("今日宜放空", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Medium)
        Text(
            "灵枢已静默守候中，非紧急消息将自动归档，不打扰你的独处时光。",
            color = AuraColors.TextSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0x16FFFFFF))
                    .border(1.dp, AuraColors.HairlineStrong, CircleShape)
                    .clickable(onClick = onWakeExplore)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text("唤醒出去逛逛 ✦", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0x0BFFFFFF))
                    .border(1.dp, AuraColors.Hairline, CircleShape)
                    .clickable(onClick = onRestoreTimeline)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text("返回示例日程", color = AuraColors.TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun AuraDecisionCard(
    model: AppViewModel,
    item: LocalDecision,
    events: List<LocalEvent>
) {
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(AuraColors.Travel.copy(alpha = 0.11f), Color(0xE00E1424))))
            .border(1.dp, AuraColors.Travel.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            if (data.optString("provider") == "rule_demo") "规则演示 · 可替换 AI" else "云端建议",
            color = AuraColors.Travel,
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            if (supported) data.optString("title") else "收到暂不支持的卡片",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            if (supported) data.optString("summary") else "请与云端确认协议版本后重试。",
            color = Color(0xFFCBD5E1),
            fontSize = 13.sp
        )
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起依据" else "查看 ${evidence.size} 条依据", color = AuraColors.Travel)
        }
        if (expanded) {
            evidence.forEach { reference ->
                val event = events.find { it.eventId == reference.optString("event_id") }
                HorizontalDivider(color = AuraColors.Hairline)
                Text(event?.text ?: "此线索已移除或尚未同步到本机", color = Color(0xFFE2E8F0), fontSize = 13.sp)
                event?.let {
                    TextButton(onClick = { model.edit(it) }, enabled = !model.busy) {
                        Text("核对原始记录", color = AuraColors.Travel)
                    }
                }
            }
        }
        when {
            !active -> Text("依据已修改、移除或建议已过期，请重新生成。", color = AuraColors.TextSecondary, fontSize = 12.sp)
            !data.isNull("feedback") && data.optString("feedback").isNotEmpty() -> Text(
                if (data.optString("feedback") == "accepted") "已采纳 · 反馈已记录" else "已忽略 · 反馈已记录",
                color = AuraColors.Shopping,
                fontSize = 12.sp
            )
            supported -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { model.feedback(item.decisionId, "accepted") },
                    enabled = !model.busy,
                    colors = ButtonDefaults.buttonColors(containerColor = AuraColors.Travel, contentColor = Color(0xFF020617))
                ) { Text("采纳") }
                OutlinedButton(
                    onClick = { model.feedback(item.decisionId, "dismissed") },
                    enabled = !model.busy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text("忽略") }
            }
        }
    }
}

private fun Modifier.dashedBorder(strokeWidth: androidx.compose.ui.unit.Dp, color: Color, cornerRadius: androidx.compose.ui.unit.Dp): Modifier =
    drawBehind {
        val stroke = Stroke(
            width = strokeWidth.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
        )
        drawRoundRect(
            color = color,
            style = stroke,
            cornerRadius = CornerRadius(cornerRadius.toPx())
        )
    }
