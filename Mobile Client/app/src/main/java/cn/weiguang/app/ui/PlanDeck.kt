package cn.weiguang.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private fun ColorScheme.planTone(tone: String) = when (tone) {
    "positive" -> primary
    "attention" -> tertiary
    "muted" -> onSurfaceVariant
    else -> onSurface
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else List(length()) { getJSONObject(it) }

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun PlanDeck(
    plan: JSONObject,
    busy: Boolean = false,
    onOpinion: ((JSONObject) -> Unit)? = null,
    openTask: (JSONObject) -> Unit,
) {
    val workId = plan.getString("work_id")
    val version = plan.getInt("version")
    val confirmed = remember(plan) { plan.optJSONArray("confirmed").objects() }
    val draft = plan.optJSONObject("candidate")
    val candidate = remember(plan) { draft?.optJSONArray("cards").objects() }
    val draftId = draft?.optString("task_id")
    val draftRevision = draft?.optInt("revision")
    var preview by remember(workId, draftId, draftRevision) { mutableStateOf(candidate.isNotEmpty()) }
    val showingDraft = preview && candidate.isNotEmpty()
    val cards = if (showingDraft) candidate else confirmed
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var selectedCard by remember(workId) { mutableStateOf<Pair<JSONObject, JSONObject>?>(null) }

    selectedCard?.let { (card, selection) ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var opinion by remember(card.getString("id")) { mutableStateOf("") }
        ModalBottomSheet(
            onDismissRequest = { selectedCard = null },
            sheetState = sheet,
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = colors.surface,
        ) {
            WorkspaceSheetBars()
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    ReadableText.text(card.getString("title"), "方案详情"),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    ReadableText.text(card.getString("value")),
                    color = colors.planTone(card.optString("tone")),
                    style = MaterialTheme.typography.titleLarge,
                )
                card.optJSONArray("rows").objects().forEach { row ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = colors.surfaceContainerLow,
                        border = BorderStroke(0.5.dp, colors.outlineVariant.copy(alpha = 0.6f)),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                ReadableText.text(row.getString("label"), "详情") + " · " + ReadableText.text(row.getString("value")),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                if (onOpinion != null) {
                    OutlinedTextField(
                        value = opinion,
                        onValueChange = { if (it.length <= 1000) opinion = it },
                        label = { Text("你希望怎么调整？") },
                        modifier = Modifier.fillMaxWidth().testTag("plan-opinion-text"),
                        enabled = !busy,
                        shape = MaterialTheme.shapes.small,
                    )
                    WorkspaceButton(
                        onClick = {
                            onOpinion(
                                JSONObject(selection.toString())
                                    .put("event_id", UUID.randomUUID().toString())
                                    .put("text", opinion.trim())
                            )
                            scope.launch { sheet.hide(); selectedCard = null }
                        },
                        enabled = opinion.isNotBlank() && !busy,
                        modifier = Modifier.fillMaxWidth().testTag("plan-send-opinion"),
                    ) {
                        Text("提交修改意见")
                    }
                }
                if (selection.has("task_id")) {
                    TextButton(
                        onClick = {
                            scope.launch { sheet.hide(); selectedCard = null }
                            openTask(JSONObject().put("id", selection.getString("task_id")).put("work_id", workId))
                        },
                    ) { Text("查看依据与确认") }
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerLow,
        contentColor = colors.onSurface,
        shadowElevation = 1.dp,
        border = BorderStroke(0.75.dp, colors.outlineVariant.copy(alpha = 0.75f)),
        modifier = Modifier.fillMaxWidth().testTag("plan-deck"),
    ) {
        Column(Modifier.padding(vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("方案看板", color = colors.primary, style = MaterialTheme.typography.labelLarge)
                }
                Text(
                    ReadableText.text(plan.getString("title"), "未命名事项"),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !showingDraft,
                        onClick = { preview = false },
                        shape = CircleShape,
                        label = { Text(ReadableText.confirmedVersion(version)) },
                        colors = FilterChipDefaults.filterChipColors(
                            labelColor = colors.onSurface,
                            selectedLabelColor = colors.onPrimary,
                            selectedContainerColor = colors.primary,
                        ),
                        modifier = Modifier.testTag("plan-confirmed"),
                    )
                    if (candidate.isNotEmpty()) {
                        FilterChip(
                            selected = showingDraft,
                            onClick = { preview = true },
                            shape = CircleShape,
                            label = { Text("待确认更新") },
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = colors.tertiary,
                                selectedLabelColor = colors.onTertiaryContainer,
                                selectedContainerColor = colors.tertiaryContainer,
                            ),
                            modifier = Modifier.testTag("plan-candidate"),
                        )
                    }
                }
                AnimatedVisibility(
                    visible = showingDraft,
                    enter = fadeIn(tween(220)),
                    exit = fadeOut(tween(160)),
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = colors.tertiaryContainer.copy(alpha = 0.45f),
                        contentColor = colors.onTertiaryContainer,
                    ) {
                        Text(
                            ReadableText.text(draft?.optString("summary").orEmpty(), "有一份更新等待你确认。"),
                            color = colors.tertiary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            if (cards.isEmpty()) {
                Text(
                    "确认第一份方案后会显示在这里。",
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 18.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                key(workId) {
                    val pager = rememberPagerState { cards.size }
                    HorizontalPager(
                        state = pager,
                        contentPadding = PaddingValues(start = 18.dp, end = 34.dp),
                        pageSpacing = 12.dp,
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.testTag("plan-pager"),
                    ) { index ->
                        val movingEdge by remember(pager, index) {
                            derivedStateOf {
                                pager.currentPage == index && !pager.isScrollInProgress
                            }
                        }
                        PlanPage(cards[index], movingEdge, onOpinion != null) {
                            val selection = JSONObject()
                                .put("card_id", cards[index].getString("id"))
                                .put("version", version)
                                .put("mode", if (showingDraft) "candidate" else "confirmed")
                            if (showingDraft) {
                                selection
                                    .put("task_id", draftId)
                                    .put("revision", draftRevision)
                                    .put("card_version", draft!!.optInt("card_version"))
                            }
                            selectedCard = cards[index] to selection
                        }
                    }
                    val currentPage = pager.currentPage.coerceAtMost(cards.lastIndex)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "${currentPage + 1} / ${cards.size}",
                                color = colors.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.testTag("plan-page-count"),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                repeat(cards.size) { idx ->
                                    val dotColor by animateColorAsState(
                                        targetValue = if (idx == currentPage) colors.primary else colors.outlineVariant,
                                        animationSpec = tween(220),
                                        label = "page dot",
                                    )
                                    Box(
                                        Modifier
                                            .height(6.dp)
                                            .width(if (idx == currentPage) 16.dp else 6.dp)
                                            .clip(CircleShape)
                                            .background(dotColor)
                                    )
                                }
                            }
                        }
                        Row {
                            TextButton(
                                onClick = { scope.launch { pager.animateScrollToPage(currentPage - 1) } },
                                enabled = currentPage > 0,
                            ) { Text("上一张") }
                            TextButton(
                                onClick = { scope.launch { pager.animateScrollToPage(currentPage + 1) } },
                                enabled = currentPage < cards.lastIndex,
                            ) { Text("下一张") }
                        }
                    }
                }
            }
            if (showingDraft) {
                TextButton(
                    onClick = { openTask(JSONObject().put("id", draftId).put("work_id", workId)) },
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.tertiary),
                    modifier = Modifier.padding(start = 10.dp),
                ) { Text("查看依据与确认") }
            }
        }
    }
}

@Composable
private fun PlanPage(
    card: JSONObject,
    movingEdge: Boolean,
    canSubmitOpinion: Boolean,
    open: () -> Unit,
) {
    val rows = remember(card) { card.optJSONArray("rows").objects() }
    val segments = remember(card) { card.optJSONArray("segments").objects() }
    val colors = MaterialTheme.colorScheme
    val toneColor = colors.planTone(card.optString("tone"))
    Surface(
        onClick = open,
        shape = RoundedCornerShape(22.dp),
        color = colors.surface,
        contentColor = colors.onSurface,
        shadowElevation = if (movingEdge) 2.dp else 0.5.dp,
        border = BorderStroke(
            width = if (movingEdge) 1.dp else 0.75.dp,
            color = if (movingEdge) colors.primary.copy(alpha = 0.28f) else colors.outlineVariant.copy(alpha = 0.7f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 224.dp, max = 320.dp)
            .testTag("plan-card-${card.getString("id")}"),
    ) {
        Box {
            CardTopAccent(movingEdge, colors.primary, Modifier.matchParentSize())
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    ReadableText.text(card.getString("title"), "方案详情"),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    ReadableText.text(card.getString("value")),
                    color = toneColor,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                ReadableText.editable(card.optString("detail")).takeIf { it.isNotBlank() }?.let {
                    Text(it, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                if (segments.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().height(7.dp).clip(CircleShape),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        segments.forEach { segment ->
                            Box(
                                Modifier
                                    .weight(segment.getInt("count").toFloat().coerceAtLeast(0.1f))
                                    .fillMaxHeight()
                                    .clip(CircleShape)
                                    .background(colors.planTone(segment.optString("tone")))
                            )
                        }
                    }
                }
                rows.forEach { row ->
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.55f), thickness = 0.5.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            ReadableText.text(row.getString("label"), "详情"),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                        )
                        Text(
                            ReadableText.text(row.getString("value")),
                            color = colors.planTone(row.optString("tone")),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    if (canSubmitOpinion) "点开查看 · 提出意见" else "点开查看",
                    color = colors.primary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun CardTopAccent(active: Boolean, color: Color, modifier: Modifier) {
    val alpha by animateFloatAsState(
        targetValue = if (active) 0.45f else 0.14f,
        animationSpec = tween(350),
        label = "card top accent",
    )
    Canvas(modifier) {
        val barWidth = (size.width - 44.dp.toPx()).coerceAtLeast(0f)
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(color.copy(alpha = 0f), color.copy(alpha = alpha), color.copy(alpha = 0f)),
                startX = 22.dp.toPx(),
                endX = 22.dp.toPx() + barWidth,
            ),
            topLeft = Offset(22.dp.toPx(), 0f),
            size = Size(barWidth, 3.dp.toPx()),
            cornerRadius = CornerRadius(2.dp.toPx()),
        )
    }
}
