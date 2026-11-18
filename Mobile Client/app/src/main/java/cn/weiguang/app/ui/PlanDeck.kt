package cn.weiguang.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val Backdrop = Color(0xFFE5F1FF)
private val Panel = Color(0xFFF8FBFF)
private val Ink = Color(0xFF173451)
private val Quiet = Color(0xFF526D87)
private val Blue = Color(0xFF205FA3)
private val Amber = Color(0xFF805311)

private fun accent(tone: String) = when (tone) {
    "positive" -> Blue
    "attention" -> Amber
    "muted" -> Quiet
    else -> Ink
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else List(length()) { getJSONObject(it) }

@Composable
internal fun PlanDeck(plan: JSONObject, openTask: (JSONObject) -> Unit) {
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

    Surface(shape = RoundedCornerShape(24.dp), color = Backdrop, contentColor = Ink,
        modifier = Modifier.fillMaxWidth().testTag("plan-deck")) {
        Column(Modifier.padding(vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.padding(horizontal = 18.dp)) {
                Text("方案看板", color = Quiet, style = MaterialTheme.typography.labelLarge)
                Text(plan.getString("title"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !showingDraft, onClick = { preview = false },
                        label = { Text(if (version == 0) "还未确认" else "已确认 · v$version") },
                        colors = FilterChipDefaults.filterChipColors(labelColor = Ink, selectedLabelColor = Color.White,
                            selectedContainerColor = Blue),
                        modifier = Modifier.testTag("plan-confirmed"))
                    if (candidate.isNotEmpty()) FilterChip(selected = showingDraft, onClick = { preview = true },
                        label = { Text("待确认更新") },
                        colors = FilterChipDefaults.filterChipColors(labelColor = Amber, selectedLabelColor = Amber,
                            selectedContainerColor = Color(0xFFFFEAC6)), modifier = Modifier.testTag("plan-candidate"))
                }
                if (showingDraft) Text(draft!!.optString("summary"), color = Amber, style = MaterialTheme.typography.bodySmall)
            }
            if (cards.isEmpty()) {
                Text("确认第一份方案后会显示在这里。", color = Quiet, modifier = Modifier.padding(horizontal = 18.dp))
            } else key(workId) {
                val pager = rememberPagerState { cards.size }
                HorizontalPager(state = pager, contentPadding = PaddingValues(start = 18.dp, end = 36.dp),
                    pageSpacing = 10.dp, verticalAlignment = Alignment.Top, modifier = Modifier.testTag("plan-pager")) { index ->
                    PlanPage(cards[index])
                }
                val currentPage = pager.currentPage.coerceAtMost(cards.lastIndex)
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${currentPage + 1} / ${cards.size}", color = Quiet, modifier = Modifier.testTag("plan-page-count"))
                    Row {
                        TextButton(onClick = { scope.launch { pager.animateScrollToPage(currentPage - 1) } },
                            enabled = currentPage > 0, colors = ButtonDefaults.textButtonColors(contentColor = Blue)) { Text("上一张") }
                        TextButton(onClick = { scope.launch { pager.animateScrollToPage(currentPage + 1) } },
                            enabled = currentPage < cards.lastIndex,
                            colors = ButtonDefaults.textButtonColors(contentColor = Blue)) { Text("下一张") }
                    }
                }
            }
            if (showingDraft) TextButton(
                onClick = { openTask(JSONObject().put("id", draftId).put("work_id", workId)) },
                colors = ButtonDefaults.textButtonColors(contentColor = Amber),
                modifier = Modifier.padding(start = 10.dp)) { Text("查看依据与确认") }
        }
    }
}

@Composable
private fun PlanPage(card: JSONObject) {
    val rows = remember(card) { card.optJSONArray("rows").objects() }
    val segments = remember(card) { card.optJSONArray("segments").objects() }
    Surface(shape = RoundedCornerShape(18.dp), color = Panel, contentColor = Ink,
        modifier = Modifier.fillMaxWidth().heightIn(min = 224.dp, max = 320.dp).testTag("plan-card-${card.getString("id")}")) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(card.getString("title"), color = Quiet, style = MaterialTheme.typography.labelLarge)
            Text(card.getString("value"), color = accent(card.optString("tone")),
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            if (card.optString("detail").isNotBlank()) Text(card.getString("detail"), color = Quiet,
                style = MaterialTheme.typography.bodySmall)
            if (segments.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    segments.forEach { segment ->
                        Box(Modifier.weight(segment.getInt("count").toFloat()).fillMaxHeight().background(accent(segment.optString("tone"))))
                    }
                }
            }
            rows.forEach { row ->
                HorizontalDivider(color = Backdrop)
                Text(row.getString("label"), style = MaterialTheme.typography.labelMedium, color = Quiet)
                Text(row.getString("value"), color = accent(row.optString("tone")), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
