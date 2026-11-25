package cn.weiguang.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.weiguang.app.home.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuraDetailModalSheet(
    card: AuraCard,
    friends: List<DiningFriend>,
    onDismiss: () -> Unit,
    onSelectPlan: (Int) -> Unit,
    onConfirmCard: () -> Unit,
    onToggleChecklist: (Int) -> Unit,
    onToggleNote: (Int) -> Unit,
    onVaporizeMisjudgment: () -> Unit,
    onOpenSourceClue: ((String) -> Unit)? = null,
    onOpenAgent: (() -> Unit)? = null,
    onToast: (String) -> Unit
) {
    val palette = auraPalette(card.themeKey)
    var forceSandbox by remember(card.id, card.lifecycleStage) { mutableStateOf(false) }
    var intermodalMode by remember(card.id) { mutableStateOf(false) }
    var selectedPriceIdx by remember(card.id) { mutableIntStateOf((card.priceHistory.lastIndex).coerceAtLeast(0)) }

    val hasHoloPass = card.category in listOf(AuraCategory.TRAVEL, AuraCategory.DINING, AuraCategory.SHOPPING) && !card.isMisjudgment
    val showHolo = hasHoloPass && card.lifecycleStage == LifecycleStage.CONFIRMED && !forceSandbox

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AuraColors.SheetBg,
        contentColor = AuraColors.TextPrimary,
        dragHandle = null
    ) {
        Box(modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp)) {
            KineticAuroraBackground(themeKey = card.themeKey, modifier = Modifier.matchParentSize())

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Sheet header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0x14FFFFFF))
                            .border(1.dp, AuraColors.Hairline, CircleShape)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = AuraColors.TextPrimary, modifier = Modifier.size(18.dp))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.width(38.dp).height(4.dp).clip(CircleShape).background(Color(0x33FFFFFF)))
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = palette.label,
                            color = palette.accent,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (hasHoloPass && card.lifecycleStage == LifecycleStage.CONFIRMED) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color(0x18FFFFFF))
                                .border(1.dp, AuraColors.HairlineStrong, CircleShape)
                                .clickable { forceSandbox = !forceSandbox }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(if (showHolo) "查看沙盘" else "3D 凭证", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    } else {
                        Spacer(Modifier.size(36.dp))
                    }
                }

                // Title + Entities
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(card.title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                            Text("${card.time} · ${card.subtitle}", color = AuraColors.TextSecondary, fontSize = 12.sp)
                        }
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(palette.accent.copy(alpha = 0.15f))
                                .border(1.dp, palette.accent.copy(alpha = 0.4f), CircleShape)
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                        ) {
                            Text(card.badge.ifBlank {
                                when (card.lifecycleStage) {
                                    LifecycleStage.PROPOSAL -> "待确认"
                                    LifecycleStage.CONFIRMED -> "已确认"
                                    LifecycleStage.ARCHIVED -> "已完成"
                                }
                            }, color = palette.accent, fontSize = 11.sp)
                        }
                    }
                    if (card.entities.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            card.entities.forEach { tag ->
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(Color(0x10FFFFFF))
                                        .border(1.dp, AuraColors.Hairline, CircleShape)
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(tag, color = Color(0xFFE2E8F0), fontSize = 10.5.sp)
                                }
                            }
                        }
                    }
                }

                // Main Body
                if (showHolo) {
                    Text(
                        text = "✦ 3D 全息凭证 · 手指拖动倾斜折射光效",
                        color = palette.accent,
                        fontSize = 11.sp
                    )
                    HoloArtifactCard(
                        card = card,
                        friends = friends,
                        onToggleChecklist = onToggleChecklist,
                        onQuickAction = onToast
                    )
                } else if (card.isMisjudgment) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(AuraColors.Explore.copy(alpha = 0.10f))
                            .border(1.dp, AuraColors.Explore.copy(alpha = 0.4f), RoundedCornerShape(22.dp))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("AI 语境置信度偏低（${(card.confidence * 100).toInt()}%）", color = Color(0xFFE9D5FF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "原文：「${card.originalText.ifBlank { "走，明天飞巴黎喝咖啡！" }}」。若属于朋友间调侃，可直接雾化移除，云端将同步学习该群的闲聊语气。",
                            color = Color(0xFFCBD5E1),
                            fontSize = 12.sp,
                            lineHeight = 19.sp
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(AuraColors.Explore.copy(alpha = 0.28f))
                                .border(1.dp, AuraColors.Explore.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                                .clickable { onVaporizeMisjudgment(); onDismiss() }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("立即雾化移除并纠偏 ✦", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    when (card.category) {
                        AuraCategory.TRAVEL -> {
                            val plans = card.travelPlans.ifEmpty { AuraFlowEngine.defaultTravelPlans(card.direction) }
                            val activePlan = plans.getOrElse(card.selectedPlanIdx) { plans.first() }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(Color(0xFF070B14))
                                    .border(1.dp, AuraColors.Hairline, RoundedCornerShape(22.dp))
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Box(Modifier.size(7.dp).clip(CircleShape).background(AuraColors.Travel))
                                        Text("京沪立体轨迹沙盘", color = Color(0xFFE2E8F0), fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                                    }
                                    Row(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(Color(0x10FFFFFF))
                                            .border(1.dp, AuraColors.Hairline, CircleShape)
                                            .padding(2.dp)
                                    ) {
                                        listOf(false to "城际走廊", true to "联运接驳").forEach { (isInter, label) ->
                                            Box(
                                                modifier = Modifier
                                                    .clip(CircleShape)
                                                    .background(if (intermodalMode == isInter) AuraColors.Travel else Color.Transparent)
                                                    .clickable { intermodalMode = isInter }
                                                    .padding(horizontal = 10.dp, vertical = 3.dp)
                                            ) {
                                                Text(
                                                    label,
                                                    color = if (intermodalMode == isInter) Color(0xFF020617) else AuraColors.TextSecondary,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }
                                    }
                                }
                                TravelCorridorCanvas(plan = activePlan, isReturn = card.direction == "return", intermodalMode = intermodalMode)
                                Text(
                                    text = "✦ ${activePlan.note}",
                                    color = AuraColors.TextSecondary,
                                    fontSize = 10.5.sp,
                                    modifier = Modifier.fillMaxWidth().background(Color(0x66000000)).padding(horizontal = 14.dp, vertical = 8.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Text("AI 精选 ${plans.size} 套方案（点击切换轨迹）", color = Color(0xFFCBD5E1), fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                plans.forEachIndexed { idx, plan ->
                                    val selected = idx == card.selectedPlanIdx
                                    Column(
                                        modifier = Modifier
                                            .width(204.dp)
                                            .clip(RoundedCornerShape(18.dp))
                                            .background(if (selected) AuraColors.Travel.copy(alpha = 0.16f) else Color(0xCC101522))
                                            .border(1.dp, if (selected) AuraColors.Travel.copy(alpha = 0.78f) else AuraColors.Hairline, RoundedCornerShape(18.dp))
                                            .clickable { onSelectPlan(idx) }
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                                            Column(Modifier.weight(1f)) {
                                                Text(plan.carrier, color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                                Text(plan.modeText, color = AuraColors.TextSecondary, fontSize = 10.5.sp)
                                            }
                                            ProgressRingBadge(plan.punctuality / 100f, "${plan.punctuality}%", AuraColors.Travel)
                                        }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                                            Text("¥${plan.price}", color = Color.White, fontSize = 15.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                            Text("${plan.dep}→${plan.arr}", color = AuraColors.Travel, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                }
                            }

                            if (card.lifecycleStage == LifecycleStage.PROPOSAL) {
                                SlideToConfirmBar(
                                    label = "滑动一键锁座出票 · ¥${activePlan.price}",
                                    themeKey = "travel",
                                    onConfirmed = onConfirmCard
                                )
                            }
                        }

                        AuraCategory.DINING -> {
                            val plans = card.diningPlans.ifEmpty { AuraFlowEngine.defaultDiningPlans() }
                            val activeRest = plans.getOrElse(card.selectedPlanIdx) { plans.first() }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(Color(0xFF0B080A))
                                    .border(1.dp, AuraColors.Dining.copy(alpha = 0.3f), RoundedCornerShape(22.dp))
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Box(Modifier.size(7.dp).clip(CircleShape).background(AuraColors.Dining))
                                        Text("多人地理重心与 15/30/45m 等时圈", color = Color(0xFFFFEDD5), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                    }
                                    Text("平均 ${activeRest.avgCommute}m", color = AuraColors.Dining, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                                }
                                DiningIsochroneCanvas(plan = activeRest, friends = friends)
                                Text(
                                    text = "✦ ${activeRest.note}",
                                    color = Color(0xCCFFEDD5),
                                    fontSize = 10.5.sp,
                                    modifier = Modifier.fillMaxWidth().background(Color(0x66000000)).padding(horizontal = 14.dp, vertical = 8.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Text("切换餐厅重算 4 人通勤路径", color = Color(0xFFCBD5E1), fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                plans.forEachIndexed { idx, rest ->
                                    val selected = idx == card.selectedPlanIdx
                                    Column(
                                        modifier = Modifier
                                            .width(204.dp)
                                            .clip(RoundedCornerShape(18.dp))
                                            .background(if (selected) AuraColors.Dining.copy(alpha = 0.16f) else Color(0xCC101522))
                                            .border(1.dp, if (selected) AuraColors.Dining.copy(alpha = 0.78f) else AuraColors.Hairline, RoundedCornerShape(18.dp))
                                            .clickable { onSelectPlan(idx) }
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                                            Column(Modifier.weight(1f)) {
                                                Text(rest.name, color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                                Text(rest.queue, color = AuraColors.Dining, fontSize = 10.5.sp)
                                            }
                                            ProgressRingBadge(rest.match / 100f, "${rest.match}%", AuraColors.Dining)
                                        }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                                            Text("人均 ¥${rest.price}", color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                            Text("平均 ${rest.avgCommute}m", color = AuraColors.TextSecondary, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                }
                            }

                            if (card.lifecycleStage == LifecycleStage.PROPOSAL) {
                                SlideToConfirmBar(
                                    label = "滑动一键订座并同步群聊",
                                    themeKey = "dining",
                                    onConfirmed = onConfirmCard
                                )
                            }
                        }

                        AuraCategory.SHOPPING -> {
                            val history = card.priceHistory.ifEmpty { AuraFlowEngine.defaultPriceHistory() }
                            val safeIdx = selectedPriceIdx.coerceIn(0, history.lastIndex)
                            val selPt = history[safeIdx]
                            val checklist = card.checklist.ifEmpty { AuraFlowEngine.defaultChecklist() }
                            val doneCount = checklist.count { it.done }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(Color(0xFF060E0B))
                                    .border(1.dp, AuraColors.Shopping.copy(alpha = 0.3f), RoundedCornerShape(22.dp))
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("全网历史价格走势（点击节点检视）", color = Color(0xFFD1FAE5), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                    Text("${selPt.date} · ¥${selPt.price}", color = AuraColors.Shopping, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                }
                                ShoppingPriceChartCanvas(history = history, selectedIdx = safeIdx, onSelect = { selectedPriceIdx = it })
                                Row(
                                    modifier = Modifier.fillMaxWidth().background(Color(0x66000000)).padding(horizontal = 14.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("✦ ${selPt.tag}", color = Color(0xCCA7F3D0), fontSize = 10.5.sp)
                                    Text("当前 ¥328 vs 原价 ¥380", color = AuraColors.TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(Color(0xFF060E0B))
                                    .border(1.dp, AuraColors.Hairline, RoundedCornerShape(22.dp))
                            ) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("恒隆广场 · B1 → L2 → L3 最优采购动线", color = Color(0xFFE2E8F0), fontSize = 11.sp)
                                    Text("步行 6m", color = AuraColors.Shopping, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                                ShoppingMallRouteCanvas()
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color(0x0DFFFFFF))
                                    .border(1.dp, AuraColors.Hairline, RoundedCornerShape(20.dp))
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("AI 购物清单（勾选同步首屏进度环）", color = Color(0xFFE2E8F0), fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                                    Text("$doneCount/${checklist.size}", color = AuraColors.Shopping, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                                checklist.forEachIndexed { idx, item ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (item.done) AuraColors.Shopping.copy(alpha = 0.15f) else Color(0x4D000000))
                                            .border(1.dp, if (item.done) AuraColors.Shopping.copy(alpha = 0.4f) else AuraColors.Hairline, RoundedCornerShape(12.dp))
                                            .clickable { onToggleChecklist(idx) }
                                            .padding(horizontal = 11.dp, vertical = 9.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Box(
                                                Modifier
                                                    .size(18.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(if (item.done) AuraColors.Shopping else Color.Transparent)
                                                    .border(1.dp, if (item.done) AuraColors.Shopping else Color(0x55FFFFFF), RoundedCornerShape(4.dp)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (item.done) Text("✓", color = Color(0xFF020617), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                            Text(
                                                item.name,
                                                color = if (item.done) Color(0xAAA7F3D0) else Color.White,
                                                fontSize = 12.sp,
                                                textDecoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(item.price, color = Color(0xFFCBD5E1), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                            Text(item.save, color = AuraColors.Shopping, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                }
                            }

                            if (card.lifecycleStage == LifecycleStage.PROPOSAL) {
                                SlideToConfirmBar(
                                    label = "滑动一键领券并生成 3D 核销卡",
                                    themeKey = "shopping",
                                    onConfirmed = onConfirmCard
                                )
                            }
                        }

                        AuraCategory.EXPLORE -> {
                            val meta = card.exploreMeta
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(AuraColors.Explore.copy(alpha = 0.10f))
                                    .border(1.dp, AuraColors.Explore.copy(alpha = 0.4f), RoundedCornerShape(22.dp))
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("✦ 漫游导航详情", color = Color(0xFFE9D5FF), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                    Text(meta?.duration ?: "2.0 小时", color = AuraColors.ExploreGold, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                                Text(meta?.note ?: card.subtitle, color = Color(0xFFCBD5E1), fontSize = 12.sp)
                                meta?.stops?.forEachIndexed { idx, s ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (idx == meta.currentStopIdx) AuraColors.Explore.copy(alpha = 0.22f) else Color(0x22000000))
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("${s.time} · ${s.name}", color = Color.White, fontSize = 12.sp)
                                        Text(s.dist, color = AuraColors.TextSecondary, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Brush.horizontalGradient(listOf(AuraColors.Explore, AuraColors.ExploreGold)))
                                        .clickable { onToast("已开启步行实景导航前往 ${card.title}") }
                                        .padding(vertical = 11.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("开启步行导航 ↗", color = Color(0xFF020617), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        AuraCategory.OFFICE -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(Color(0x0DFFFFFF))
                                    .border(1.dp, AuraColors.Hairline, RoundedCornerShape(22.dp))
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("AI 议程提炼与行动点", color = Color(0xFFC7D2FE), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                card.notes.forEachIndexed { idx, note ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (note.done) AuraColors.Office.copy(alpha = 0.16f) else Color(0x4D000000))
                                            .border(1.dp, if (note.done) AuraColors.Office.copy(alpha = 0.4f) else AuraColors.Hairline, RoundedCornerShape(12.dp))
                                            .clickable { onToggleNote(idx) }
                                            .padding(11.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            Modifier
                                                .size(18.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(if (note.done) AuraColors.Office else Color.Transparent)
                                                .border(1.dp, if (note.done) AuraColors.Office else Color(0x55FFFFFF), RoundedCornerShape(4.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (note.done) Text("✓", color = Color(0xFF020617), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Text(
                                            note.text,
                                            color = if (note.done) Color(0xAAC7D2FE) else Color.White,
                                            fontSize = 12.sp,
                                            textDecoration = if (note.done) TextDecoration.LineThrough else TextDecoration.None
                                        )
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (card.lifecycleStage == LifecycleStage.PROPOSAL) {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(AuraColors.Office.copy(alpha = 0.3f))
                                                .border(1.dp, AuraColors.Office.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                                .clickable(onClick = onConfirmCard)
                                                .padding(vertical = 10.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("确认排入日程", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0x14FFFFFF))
                                            .border(1.dp, AuraColors.Hairline, RoundedCornerShape(12.dp))
                                            .clickable {
                                                if (card.sourceWorkId != null && onOpenAgent != null) {
                                                    onDismiss(); onOpenAgent()
                                                } else if (card.sourceEventId != null && onOpenSourceClue != null) {
                                                    onDismiss(); onOpenSourceClue(card.sourceEventId)
                                                } else {
                                                    onToast("已准备会议室与 AI 纪要模板")
                                                }
                                            }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            when {
                                                card.sourceWorkId != null -> "进入云端事项"
                                                card.sourceEventId != null -> "核对原始线索"
                                                else -> "一键入会 · AI 纪要"
                                            },
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Direct link back to real source clue or Cloud Agent if attached
                if (card.sourceEventId != null && onOpenSourceClue != null && card.category != AuraCategory.OFFICE) {
                    TextButton(onClick = { onDismiss(); onOpenSourceClue(card.sourceEventId) }) {
                        Text("查看并纠错原始 OCR/线索记录", color = palette.accent, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GapSuggestionSheet(
    afterCard: AuraCard,
    onDismiss: () -> Unit,
    onInsertSpot: (GapSpot) -> Unit
) {
    val spots = afterCard.connectorToNext?.gapSpots?.ifEmpty { AuraFlowEngine.defaultGapSpots() } ?: AuraFlowEngine.defaultGapSpots()
    val gapHours = afterCard.connectorToNext?.gapHours ?: "3.5h"
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = AuraColors.MiniSheetTop,
        contentColor = AuraColors.TextPrimary
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("GAP_SUGGESTION · 线间补白", color = AuraColors.Explore, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text("空档 $gapHours · 顺路轻去处推荐", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("基于当前位置挑选 ${spots.size} 个顺路轻去处，点击直接嵌入时间轴：", color = AuraColors.TextSecondary, fontSize = 12.sp)
            spots.forEach { spot ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0x0DFFFFFF))
                        .border(1.dp, AuraColors.Hairline, RoundedCornerShape(18.dp))
                        .clickable { onInsertSpot(spot); onDismiss() }
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(spot.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(spot.detail.ifBlank { spot.subtitle }, color = AuraColors.TextSecondary, fontSize = 11.5.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(AuraColors.Explore.copy(alpha = 0.22f))
                            .padding(horizontal = 11.dp, vertical = 5.dp)
                    ) {
                        Text("+ 插入", color = Color(0xFFE9D5FF), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertPlanBSheet(
    card: AuraCard,
    onDismiss: () -> Unit,
    onApplyChoice: (String) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = AuraColors.MiniSheetTop,
        contentColor = AuraColors.TextPrimary
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("ALERT_PULSE · AI B 计划", color = AuraColors.Alert, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text("${card.title} 监测到延误/突发异常", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("原定后续安排将受影响，AI 已备好两套无痛化解方案：", color = AuraColors.TextSecondary, fontSize = 12.sp)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(AuraColors.Alert.copy(alpha = 0.12f))
                    .border(1.dp, AuraColors.Alert.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                    .clickable { onApplyChoice("shift_dinner"); onDismiss() }
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("方案 A：自动顺延晚宴至 19:30 并通知留座", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("推荐", color = AuraColors.Alert, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
                }
                Text("同步告知同行好友并联系餐厅保留包厢", color = Color(0xFFCBD5E1), fontSize = 11.5.sp)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0x0DFFFFFF))
                    .border(1.dp, AuraColors.Travel.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                    .clickable { onApplyChoice("switch_hsr"); onDismiss() }
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("方案 B：保留原计划 · 改乘高铁 G14", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("准点 99%", color = AuraColors.Travel, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
                }
                Text("12:00 北京南发车，16:18 准点抵虹桥，按原时间赴宴", color = Color(0xFFCBD5E1), fontSize = 11.5.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuraDirectorSheet(
    state: AuraFlowState,
    activeDetailId: String?,
    onDismiss: () -> Unit,
    onSetDayMode: (String) -> Unit,
    onSetWeather: (String) -> Unit,
    onSimulate: (String) -> Unit,
    onToast: (String) -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    val clipboard = LocalClipboardManager.current
    val jsonText = remember(state, activeDetailId) { state.wireJson ?: state.toProtocolJson(activeDetailId) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AuraColors.MiniSheetTop,
        contentColor = AuraColors.TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("灵枢 · AURAFLOW 控制台", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("SDUI ${state.protocolVersion} · ${if (state.fromServer) "云端实时驱动" else "本机规则引擎"}", color = AuraColors.TextSecondary, fontSize = 11.sp)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭", tint = AuraColors.TextSecondary) }
            }

            // Route badges
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x55000000))
                    .border(1.dp, AuraColors.Hairline, RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ROUTE:", color = AuraColors.TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text(state.dayMode.name, color = AuraColors.Travel, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text("→", color = AuraColors.TextMuted, fontSize = 10.sp)
                Text(state.topConnectorState(), color = AuraColors.Dining, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }

            // Switch Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x66000000))
                    .border(1.dp, AuraColors.Hairline, RoundedCornerShape(14.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(0 to "体验控制台", 1 to "SDUI 实时协议").forEach { (idx, label) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (tab == idx) Color(0x22FFFFFF) else Color.Transparent)
                            .clickable { tab = idx }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = if (tab == idx) Color.White else AuraColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (tab == 0) {
                // Data Source Switch
                Text("0. 首页数据源模式", color = AuraColors.TextSecondary, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DirectorChip(
                        title = "真实线索联动",
                        subtitle = "${state.realEventCount} 条数据",
                        selected = !state.showcaseMode,
                        accent = AuraColors.Shopping,
                        modifier = Modifier.weight(1f)
                    ) { onSimulate("exit_showcase"); onDismiss() }
                    DirectorChip(
                        title = "全场景流光示例",
                        subtitle = "京沪全生命周期",
                        selected = state.showcaseMode,
                        accent = AuraColors.Travel,
                        modifier = Modifier.weight(1f)
                    ) { onSimulate("load_showcase"); onDismiss() }
                }

                // Macro Day Mode
                Text("1. 宏观日程模式调度 (dayMode)", color = AuraColors.TextSecondary, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DirectorChip("今日日程", "TIMELINE", state.dayMode == DayMode.ACTIVE_TIMELINE, AuraColors.Travel, Modifier.weight(1f)) {
                        onSetDayMode("ACTIVE_TIMELINE"); onDismiss()
                    }
                    DirectorChip("出去逛逛", "EXPLORE", state.dayMode == DayMode.EMPTY_EXPLORE, AuraColors.Explore, Modifier.weight(1f)) {
                        onSetDayMode("EMPTY_EXPLORE"); onDismiss()
                    }
                    DirectorChip("放空态", "REST", state.dayMode == DayMode.REST_MODE, AuraColors.Rest, Modifier.weight(1f)) {
                        onSetDayMode("REST_MODE"); onDismiss()
                    }
                }

                // Weather Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x0DFFFFFF))
                        .border(1.dp, AuraColors.Hairline, RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("环境天气（驱动逛逛路线）", color = AuraColors.TextSecondary, fontSize = 11.5.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("sunny" to "☀ 晴朗", "rainy" to "🌧 细雨").forEach { (mode, label) ->
                            val active = state.weatherContext.mode == mode
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (active) AuraColors.ExploreGold.copy(alpha = 0.22f) else Color(0x33000000))
                                    .clickable { onSetWeather(mode) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(label, color = if (active) AuraColors.ExploreGold else AuraColors.TextSecondary, fontSize = 11.sp)
                            }
                        }
                    }
                }

                // Lifecycle Simulations
                Text("2. 生命周期变形 & 后台静默感知", color = AuraColors.TextSecondary, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
                DirectorActionRow("模拟收到出票短信（零手动核销）", "捕获航旅纵横短信，静默变形为 MU5102 3D登机牌", AuraColors.Travel) {
                    onSimulate("sms_ticket"); onDismiss()
                }
                DirectorActionRow("模拟突发延误 / 暴雨预警", "时间轴切换红色脉冲熔断线，提供 AI B 计划", AuraColors.Alert) {
                    onSimulate("emergency_alert"); onDismiss()
                }
                DirectorActionRow("模拟群聊玩笑误判（左滑雾化）", "捕获「明天飞巴黎喝咖啡」→ 左滑雾化并记录负反馈", AuraColors.Explore) {
                    onSimulate("joke_misjudgment"); onDismiss()
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSimulate("reset_flight_proposal"); onDismiss() }, modifier = Modifier.weight(1f)) {
                        Text("重置航班提案态", fontSize = 11.5.sp, color = AuraColors.TextPrimary)
                    }
                    OutlinedButton(onClick = { onSimulate("reset_showcase"); onDismiss() }, modifier = Modifier.weight(1f)) {
                        Text("重置演示状态 ↻", fontSize = 11.5.sp, color = AuraColors.TextPrimary)
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("实时同步 SDUI_STATE 协议", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x16FFFFFF))
                            .clickable {
                                clipboard.setText(AnnotatedString(jsonText))
                                onToast("已复制当前 SDUI 协议 JSON")
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Outlined.ContentCopy, null, tint = AuraColors.Travel, modifier = Modifier.size(14.dp))
                        Text("复制 JSON", color = Color.White, fontSize = 11.sp)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF04060B))
                        .border(1.dp, AuraColors.Hairline, RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = jsonText,
                        color = Color(0xFF86EFAC),
                        fontSize = 10.5.sp,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectorChip(
    title: String,
    subtitle: String,
    selected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) accent.copy(alpha = 0.16f) else Color(0x0BFFFFFF))
            .border(1.dp, if (selected) accent.copy(alpha = 0.65f) else AuraColors.Hairline, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Text(title, color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = if (selected) accent else AuraColors.TextSecondary, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

@Composable
private fun DirectorActionRow(
    title: String,
    desc: String,
    accent: Color,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x0BFFFFFF))
            .border(1.dp, AuraColors.Hairline, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
            Text(title, color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(desc, color = AuraColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(start = 16.dp))
    }
}
