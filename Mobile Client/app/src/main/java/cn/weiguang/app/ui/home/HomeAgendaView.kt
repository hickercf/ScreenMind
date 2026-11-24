package cn.weiguang.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.weiguang.app.home.*
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val ideas = listOf("去公园散步", "去书店读书", "看一场展览")
private val alertWords = Regex("延误|暴雨|停运|闭店|歇业|台风|预警")
private val dayFormat = DateTimeFormatter.ofPattern("M月d日")

@Composable
internal fun HomeAgendaView(
    agenda: HomeAgenda, now: ZonedDateTime, onDetail: (AuraCard) -> Unit,
    onDraft: (String, ZonedDateTime?) -> Unit, onDismiss: (String) -> Unit,
    onReminder: (String) -> Unit, onShift: (AgendaEntry) -> Unit
) {
    var history by rememberSaveable { mutableStateOf(false) }
    if (agenda.past.isNotEmpty()) {
        TextButton(onClick = { history = !history }) {
            Text("今日回顾 · ${agenda.past.size} 项  ${if (history) "▴" else "▾"}", color = AuraColors.TextSecondary)
        }
        if (history) agenda.past.forEach {
            Text("${it.time}  ${it.title} · ${it.summary}", color = AuraColors.TextSecondary, fontSize = 13.sp)
        }
    }

    val first = agenda.today.firstOrNull()
    val accent = first?.let { auraPalette(it.card.themeKey).accent } ?: AuraColors.Explore
    val shape = RoundedCornerShape(24.dp)
    var idea by rememberSaveable(now.toLocalDate().toString()) { mutableIntStateOf(0) }
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(accent.copy(alpha = .19f), AuraColors.CardNowBottom)))
            .border(1.dp, accent.copy(alpha = .45f), shape)
            .then(if (first != null) Modifier.clickable { onDetail(first.card) } else Modifier)
            .padding(20.dp).testTag("home-focus"), verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        if (first == null) {
            Text("今日暂无待办日程", color = accent, fontSize = 13.sp)
            Text(ideas[idea], color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 24.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onDraft(ideas[idea], now.plusMinutes(30).withSecond(0).withNano(0)) },
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = AuraColors.ScreenBg)) { Text("加入日程") }
                TextButton(onClick = { idea = (idea + 1) % ideas.size }) { Text("换个灵感", color = AuraColors.TextPrimary) }
            }
        } else {
            val seconds = first.start(now.zone)?.let { Duration.between(now, it).seconds }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("此刻焦点", color = accent, fontSize = 13.sp)
                Text(when {
                    seconds == null -> "时间待定"
                    seconds <= 0 -> if (first.end != null) "进行中" else "现在开始"
                    else -> "距开始 ${(seconds + 59) / 60} 分钟"
                }, color = accent, fontSize = 13.sp)
            }
            Text(first.card.title, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
            Text("${first.card.time}  ·  ${first.card.subtitle}  ·  ${first.card.badge}", color = AuraColors.TextSecondary, fontSize = 13.sp)
        }
    }

    @Composable fun node(item: AgendaEntry) {
        val card = item.card
        key(card.id) {
            TimelineCardNode(card, { onDetail(card) }, { onReminder(card.id) }, { onDismiss(card.id) })
            if (alertWords.containsMatchIn(card.originalText)) {
                TextButton(onClick = { onDetail(card) }) { Text("线索提示行程有变动 · 核对详情", color = AuraColors.Alert) }
            }
        }
    }
    if (agenda.today.isNotEmpty()) {
        Text("今日时间轴", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        agenda.today.forEachIndexed { index, item ->
            node(item)
            val next = agenda.today.getOrNull(index + 1)
            val start = item.end ?: item.start(now.zone)
            val nextStart = next?.start(now.zone)
            if (start != null && next != null && nextStart != null) {
                val gap = Duration.between(start, nextStart).toMinutes()
                if (gap > 180) TextButton(onClick = { onDraft("", start.plusMinutes(gap / 2)) }) {
                    Text("空档 ${gap / 60} 小时 ${gap % 60} 分 · 添加安排", color = AuraColors.Explore)
                } else if (gap < 50) TextButton(onClick = { onShift(next) }) {
                    Text(if (gap < 0) "重叠 ${-gap} 分钟 · 调整下一项" else "相隔 $gap 分钟 · 调整下一项", color = AuraColors.ExploreGold)
                }
            }
        }
    }
    agenda.later.groupBy { it.date }.forEach { (date, entries) ->
        Text("${date?.format(dayFormat)} · 后续日程", color = AuraColors.TextSecondary, fontSize = 13.sp)
        entries.forEach { node(it) }
    }
    if (agenda.unscheduled.isNotEmpty()) {
        Text("时间待确认", color = AuraColors.TextSecondary, fontSize = 13.sp)
        agenda.unscheduled.forEach { node(it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceDetailSheet(card: AuraCard, dateLabel: String, onClose: () -> Unit, onEdit: (() -> Unit)?,
                              onAgent: (() -> Unit)?, onConfirm: () -> Unit, onComplete: () -> Unit, onDismiss: () -> Unit) {
    val palette = auraPalette(card.themeKey)
    ModalBottomSheet(onDismissRequest = onClose, containerColor = AuraColors.SheetBg, contentColor = AuraColors.TextPrimary) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${palette.label} · ${card.badge}", color = palette.accent, fontSize = 13.sp)
            Text(card.title, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
            Text(listOf(dateLabel, card.subtitle).filter { it.isNotBlank() }.joinToString(" · "), color = AuraColors.TextSecondary)
            val facts = remember(card.category, card.originalText) { card.sourceFacts() }
            if (facts.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(palette.accent.copy(alpha = .10f)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    facts.forEach { (label, value) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(label, color = palette.accent, fontSize = 13.sp)
                            Text(value, color = AuraColors.TextPrimary, fontSize = 14.sp)
                        }
                    }
                }
            }
            if (card.originalText.isNotBlank()) {
                HorizontalDivider(color = AuraColors.Hairline)
                Text("原始线索", color = palette.accent, fontSize = 12.sp)
                Text(card.originalText)
            }
            card.notes.forEach { Text(it.text, color = AuraColors.TextSecondary) }
            if (onAgent != null) Button(onClick = onAgent) { Text(if (card.sourceTaskId != null) "处理关联任务" else "查看云端事项") }
            else if (card.lifecycleStage == LifecycleStage.PROPOSAL) Button(onClick = onConfirm) { Text("确认这项安排") }
            else if (card.lifecycleStage == LifecycleStage.CONFIRMED) Button(onClick = onComplete) { Text("标记完成") }
            if (onEdit != null) OutlinedButton(onClick = onEdit) { Text("核对或修改线索", color = palette.accent) }
            if (card.lifecycleStage == LifecycleStage.PROPOSAL) TextButton(onClick = onDismiss) { Text("从首页移除", color = AuraColors.TextSecondary) }
        }
    }
}
