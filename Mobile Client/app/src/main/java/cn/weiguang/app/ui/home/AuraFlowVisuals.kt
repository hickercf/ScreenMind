package cn.weiguang.app.ui.home

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.weiguang.app.home.*
import kotlin.math.*
import kotlin.random.Random

@Composable
fun KineticAuroraBackground(
    themeKey: String,
    modifier: Modifier = Modifier
) {
    val palette = auraPalette(themeKey)
    val accent by animateColorAsState(palette.accent, animationSpec = tween(750), label = "auroraAccent")
    val alt by animateColorAsState(palette.alt, animationSpec = tween(750), label = "auroraAlt")
    val infinite = rememberInfiniteTransition(label = "auroraMotion")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(14000, easing = LinearEasing)),
        label = "auroraPhase"
    )

    Canvas(modifier = modifier.background(AuraColors.ScreenBg)) {
        val w = size.width
        val h = size.height
        val c1 = Offset(w * (0.48f + 0.14f * sin(phase)), h * (0.04f + 0.04f * cos(phase * 0.8f)))
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.05f), Color.Transparent),
                center = c1,
                radius = max(w, h) * 0.62f
            )
        )
        val c2 = Offset(w * (0.86f + 0.08f * cos(phase * 0.9f)), h * (0.86f + 0.06f * sin(phase * 0.7f)))
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(alt.copy(alpha = 0.14f), Color.Transparent),
                center = c2,
                radius = max(w, h) * 0.50f
            )
        )
    }
}

@Composable
fun L2PerceptionPill(
    themeKey: String,
    sensing: Boolean,
    sensingText: String,
    idleText: String = "灵枢感知 · 静默守候",
    onClick: () -> Unit = {}
) {
    val palette = auraPalette(themeKey)
    val accent by animateColorAsState(palette.accent, tween(500), label = "pillAccent")
    val infinite = rememberInfiniteTransition(label = "pillWave")
    val bars = List(5) { idx ->
        infinite.animateFloat(
            initialValue = 0.25f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(520, delayMillis = idx * 85, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar$idx"
        )
    }
    val pulse by infinite.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "dotPulse"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color(0xF00B0F19))
                .border(
                    width = 1.dp,
                    color = if (sensing) accent.copy(alpha = 0.72f) else AuraColors.HairlineStrong,
                    shape = CircleShape
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (sensing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.5.dp),
                    modifier = Modifier.height(14.dp)
                ) {
                    bars.forEach { frac ->
                        Box(
                            modifier = Modifier
                                .width(2.4.dp)
                                .height((14f * frac.value).dp)
                                .clip(CircleShape)
                                .background(accent)
                        )
                    }
                }
                Text(
                    text = sensingText,
                    color = AuraColors.TextPrimary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = pulse))
                )
                Text(
                    text = idleText,
                    color = Color(0xE6CBD5E1),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun ProgressRingBadge(
    progress: Float,
    label: String,
    accent: Color,
    sizeDp: Dp = 32.dp
) {
    Box(modifier = Modifier.size(sizeDp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
            drawArc(
                color = Color(0x22FFFFFF),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = stroke
            )
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                style = stroke
            )
        }
        Text(
            text = label,
            color = AuraColors.TextPrimary,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun TravelCorridorCanvas(
    plan: TravelPlan,
    isReturn: Boolean,
    intermodalMode: Boolean,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "travelAnim")
    val t by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3400, easing = LinearEasing)),
        label = "travelProgress"
    )
    val t2 by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "carProgress"
    )

    Canvas(modifier = modifier.fillMaxWidth().height(186.dp).background(Color(0xFF070B14))) {
        val w = size.width
        val h = size.height
        val sx = w / 336f
        val sy = h / 186f

        // Subtle coordinate grid
        for (y in listOf(34f, 68f, 102f, 136f, 170f)) {
            drawLine(Color(0x1494A3B8), Offset(0f, y * sy), Offset(w, y * sy), strokeWidth = 1f)
        }
        for (x in listOf(48f, 96f, 144f, 192f, 240f, 288f)) {
            drawLine(Color(0x1494A3B8), Offset(x * sx, 0f), Offset(x * sx, h), strokeWidth = 1f)
        }

        val labelPaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textSize = 10.sp.toPx()
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val subPaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#94A3B8")
            textSize = 8.5.sp.toPx()
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }

        if (intermodalMode) {
            val p0 = Offset(28f * sx, 134f * sy)
            val p1 = Offset(88f * sx, 92f * sy)
            val p2 = Offset(232f * sx, 92f * sy)
            val p3 = Offset(306f * sx, 82f * sy)

            val leg1 = Path().apply { moveTo(p0.x, p0.y); quadraticTo(56f * sx, 122f * sy, p1.x, p1.y) }
            val leg2 = Path().apply { moveTo(p1.x, p1.y); quadraticTo(160f * sx, 22f * sy, p2.x, p2.y) }
            val leg3 = Path().apply { moveTo(p2.x, p2.y); quadraticTo(268f * sx, 118f * sy, p3.x, p3.y) }

            drawPath(leg1, AuraColors.Office, style = Stroke(2.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
            drawPath(leg2, Brush.horizontalGradient(listOf(AuraColors.Travel, AuraColors.TravelAlt)), style = Stroke(2.8.dp.toPx(), cap = StrokeCap.Round))
            drawPath(leg3, AuraColors.Dining, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round))

            // Animated plane on leg2
            val planePos = quadBezier(p1, Offset(160f * sx, 22f * sy), p2, t)
            drawCircle(AuraColors.Travel.copy(alpha = 0.25f), 10.dp.toPx(), planePos)
            drawCircle(Color.White, 4.dp.toPx(), planePos)

            // Animated taxi dot on leg3
            val carPos = quadBezier(p2, Offset(268f * sx, 118f * sy), p3, t2)
            drawCircle(AuraColors.Dining, 4.dp.toPx(), carPos)

            // Nodes
            drawCircle(AuraColors.Office, 4.dp.toPx(), p0)
            drawCircle(AuraColors.Travel.copy(alpha = 0.22f), 10.dp.toPx(), p1)
            drawCircle(AuraColors.Travel, 5.dp.toPx(), p1)
            drawCircle(AuraColors.Travel.copy(alpha = 0.22f), 10.dp.toPx(), p2)
            drawCircle(AuraColors.Travel, 5.dp.toPx(), p2)
            drawCircle(AuraColors.Dining, 5.dp.toPx(), p3)

            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText("机场线 24m", p0.x + 14f, p0.y + 18.dp.toPx(), subPaint)
                canvas.nativeCanvas.drawText(plan.depHub, p1.x, p1.y + 20.dp.toPx(), labelPaint)
                canvas.nativeCanvas.drawText(plan.arrHub, p2.x, p2.y + 20.dp.toPx(), labelPaint)
                canvas.nativeCanvas.drawText("专车 55m", p3.x - 10f, p3.y + 20.dp.toPx(), subPaint)
                subPaint.textAlign = Paint.Align.LEFT
                canvas.nativeCanvas.drawText("INTERMODAL · 空铁/专车无缝联运", 12.dp.toPx(), 18.dp.toPx(), subPaint)
            }
        } else {
            // Beijing -> Tianjin -> Jinan -> Nanjing -> Shanghai Mercator-normalized points
            val cities = listOf(
                Triple("北京", "PEK", Offset(78f * sx, 38f * sy)),
                Triple("天津", "TSN", Offset(112f * sx, 56f * sy)),
                Triple("济南", "TNA", Offset(124f * sx, 92f * sy)),
                Triple("南京", "NKG", Offset(188f * sx, 134f * sy)),
                Triple("上海", "SHA", Offset(262f * sx, 146f * sy))
            )

            // Subtle East China coastline silhouette
            val coast = Path().apply {
                moveTo(45f * sx, 16f * sy)
                lineTo(155f * sx, 34f * sy)
                lineTo(188f * sx, 54f * sy)
                lineTo(160f * sx, 68f * sy)
                lineTo(232f * sx, 80f * sy)
                lineTo(205f * sx, 108f * sy)
                lineTo(254f * sx, 128f * sy)
                lineTo(286f * sx, 152f * sy)
                lineTo(266f * sx, 178f * sy)
                lineTo(40f * sx, 178f * sy)
                close()
            }
            drawPath(coast, Color(0x1000E5FF))
            drawPath(coast, Color(0x2838BDF8), style = Stroke(1.dp.toPx()))

            val pek = cities.first().third
            val sha = cities.last().third
            val start = if (isReturn) sha else pek
            val end = if (isReturn) pek else sha
            val ctrl = Offset((start.x + end.x) * 0.5f + 26f * sx, (start.y + end.y) * 0.5f - 46f * sy)

            val isRail = plan.mode == "rail"
            // HSR polyline
            val railPath = Path().apply {
                val ordered = if (isReturn) cities.reversed() else cities
                moveTo(ordered.first().third.x, ordered.first().third.y)
                ordered.drop(1).forEach { lineTo(it.third.x, it.third.y) }
            }
            drawPath(
                railPath,
                color = if (isRail) AuraColors.Explore else AuraColors.Explore.copy(alpha = 0.32f),
                style = Stroke(width = if (isRail) 2.8.dp.toPx() else 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)))
            )

            // Flight arc
            val flightPath = Path().apply {
                moveTo(start.x, start.y)
                quadraticTo(ctrl.x, ctrl.y, end.x, end.y)
            }
            drawPath(
                flightPath,
                brush = Brush.linearGradient(listOf(AuraColors.Travel, AuraColors.TravelAlt)),
                alpha = if (!isRail) 1f else 0.35f,
                style = Stroke(width = if (!isRail) 2.8.dp.toPx() else 1.5.dp.toPx(), cap = StrokeCap.Round)
            )

            // Moving vehicle
            val vehiclePos = if (isRail) {
                val ordered = if (isReturn) cities.reversed() else cities
                val seg = (t * (ordered.size - 1)).coerceAtMost((ordered.size - 1.001f))
                val i = seg.toInt()
                val frac = seg - i
                Offset(
                    ordered[i].third.x + (ordered[i + 1].third.x - ordered[i].third.x) * frac,
                    ordered[i].third.y + (ordered[i + 1].third.y - ordered[i].third.y) * frac
                )
            } else {
                quadBezier(start, ctrl, end, t)
            }
            val vehicleColor = if (isRail) AuraColors.Explore else AuraColors.Travel
            drawCircle(vehicleColor.copy(alpha = 0.28f), 11.dp.toPx(), vehiclePos)
            drawCircle(Color.White, 4.dp.toPx(), vehiclePos)

            // City pins + labels
            cities.forEachIndexed { idx, (name, code, pt) ->
                val terminal = idx == 0 || idx == cities.lastIndex
                if (terminal) {
                    drawCircle(AuraColors.Travel.copy(alpha = 0.2f), 10.dp.toPx(), pt)
                    drawCircle(AuraColors.Travel, 4.5.dp.toPx(), pt)
                } else {
                    drawCircle(AuraColors.Explore.copy(alpha = 0.8f), 2.8.dp.toPx(), pt)
                }
                drawIntoCanvas { canvas ->
                    if (terminal) {
                        canvas.nativeCanvas.drawText("$name $code", pt.x, pt.y - 10.dp.toPx(), labelPaint)
                    } else {
                        canvas.nativeCanvas.drawText(name, pt.x - 16.dp.toPx(), pt.y + 4.dp.toPx(), subPaint)
                    }
                }
            }
        }
    }
}

@Composable
fun DiningIsochroneCanvas(
    plan: DiningPlan,
    friends: List<DiningFriend>,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "diningIso")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "diningPhase"
    )
    val ringPulse by infinite.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "ringPulse"
    )

    Canvas(modifier = modifier.fillMaxWidth().height(186.dp).background(Color(0xFF0B080A))) {
        val w = size.width
        val h = size.height
        val sx = w / 336f
        val sy = h / 186f
        val target = Offset(plan.targetX * sx, plan.targetY * sy)

        // Subtle warm grid
        for (y in listOf(36f, 72f, 108f, 144f)) {
            drawLine(Color(0x16FDBA74), Offset(0f, y * sy), Offset(w, y * sy), 1f)
        }
        for (x in listOf(56f, 112f, 168f, 224f, 280f)) {
            drawLine(Color(0x16FDBA74), Offset(x * sx, 0f), Offset(x * sx, h), 1f)
        }

        // Isochrone 15 / 30 / 45m rings
        drawCircle(
            brush = Brush.radialGradient(listOf(AuraColors.Dining.copy(alpha = 0.28f), Color.Transparent), target, 76f * sx),
            radius = 76f * sx,
            center = target
        )
        drawCircle(AuraColors.Dining.copy(alpha = 0.22f * ringPulse), 72f * sx, target, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))))
        drawCircle(AuraColors.Dining.copy(alpha = 0.36f * ringPulse), 48f * sx, target, style = Stroke(1.1.dp.toPx()))
        drawCircle(AuraColors.Dining.copy(alpha = 0.56f * ringPulse), 24f * sx, target, style = Stroke(1.2.dp.toPx()))

        val friendPaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textSize = 9.sp.toPx()
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val subPaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#CBD5E1")
            textSize = 8.sp.toPx()
            textAlign = Paint.Align.CENTER
        }
        val badgePaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#FED7AA")
            textSize = 8.sp.toPx()
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }

        friends.forEachIndexed { idx, f ->
            val fp = Offset(f.x * sx, f.y * sy)
            val ctrl = Offset((fp.x + target.x) * 0.5f, (fp.y + target.y) * 0.5f + (if (idx % 2 == 0) -18f else 16f) * sy)
            val path = Path().apply { moveTo(fp.x, fp.y); quadraticTo(ctrl.x, ctrl.y, target.x, target.y) }
            drawPath(path, AuraColors.Dining.copy(alpha = 0.65f), style = Stroke(1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))

            val dotPt = quadBezier(fp, ctrl, target, (phase + idx * 0.22f) % 1f)
            drawCircle(Color(0xFFFFF7ED), 3.dp.toPx(), dotPt)

            // Commute badge
            val mins = plan.commutes.getOrElse(idx) { 25 }
            val badgeCenter = Offset(ctrl.x, ctrl.y + (if (idx % 2 == 0) 4f else -4f) * sy)
            drawRoundRect(
                color = Color(0xEE170E0D),
                topLeft = Offset(badgeCenter.x - 16.dp.toPx(), badgeCenter.y - 7.dp.toPx()),
                size = Size(32.dp.toPx(), 14.dp.toPx()),
                cornerRadius = CornerRadius(7.dp.toPx())
            )
            drawRoundRect(
                color = AuraColors.Dining.copy(alpha = 0.45f),
                topLeft = Offset(badgeCenter.x - 16.dp.toPx(), badgeCenter.y - 7.dp.toPx()),
                size = Size(32.dp.toPx(), 14.dp.toPx()),
                cornerRadius = CornerRadius(7.dp.toPx()),
                style = Stroke(0.9.dp.toPx())
            )
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText("${mins}m", badgeCenter.x, badgeCenter.y + 3.dp.toPx(), badgePaint)
            }

            // Friend pin
            drawCircle(Color(0xFF1B1316), 9.dp.toPx(), fp)
            drawCircle(Color(0x4DFFFFFF), 9.dp.toPx(), fp, style = Stroke(1.1.dp.toPx()))
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(f.short, fp.x, fp.y + 3.dp.toPx(), friendPaint)
                canvas.nativeCanvas.drawText(f.name, fp.x, fp.y + 19.dp.toPx(), subPaint)
            }
        }

        // Target restaurant pin
        drawCircle(AuraColors.Dining, 7.dp.toPx(), target)
        drawCircle(Color.White, 7.dp.toPx(), target, style = Stroke(1.8.dp.toPx()))
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawText(plan.name.substringBefore("·").trim(), target.x, target.y - 13.dp.toPx(), friendPaint)
        }
    }
}

@Composable
fun ShoppingPriceChartCanvas(
    history: List<PricePoint>,
    selectedIdx: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (history.isEmpty()) return
    val minP = (history.minOf { it.price } - 15).toFloat()
    val maxP = (history.maxOf { it.price } + 15).toFloat()
    val lowest = history.minOf { it.price }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(112.dp)
            .pointerInput(history) {
                detectTapGestures { offset ->
                    val w = size.width.toFloat()
                    val padPx = 24.dp.toPx()
                    val step = (w - padPx * 2f) / (history.size - 1).coerceAtLeast(1)
                    val nearest = ((offset.x - padPx) / step).roundToInt().coerceIn(0, history.lastIndex)
                    onSelect(nearest)
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val padX = 24.dp.toPx()
        val topY = 18.dp.toPx()
        val botY = h - 22.dp.toPx()
        val step = (w - padX * 2) / (history.size - 1).coerceAtLeast(1)

        val pts = history.mapIndexed { i, p ->
            val x = padX + i * step
            val y = botY - ((p.price - minP) / (maxP - minP).coerceAtLeast(1f)) * (botY - topY)
            Offset(x, y)
        }

        // Historical low dashed line
        val lowY = botY - ((lowest - minP) / (maxP - minP).coerceAtLeast(1f)) * (botY - topY)
        drawLine(
            AuraColors.Shopping.copy(alpha = 0.35f),
            Offset(12.dp.toPx(), lowY),
            Offset(w - 12.dp.toPx(), lowY),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
        )

        val linePath = Path().apply {
            moveTo(pts.first().x, pts.first().y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
        }
        val areaPath = Path().apply {
            addPath(linePath)
            lineTo(pts.last().x, botY + 4.dp.toPx())
            lineTo(pts.first().x, botY + 4.dp.toPx())
            close()
        }
        drawPath(areaPath, Brush.verticalGradient(listOf(AuraColors.Shopping.copy(alpha = 0.32f), Color.Transparent)))
        drawPath(linePath, AuraColors.Shopping, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        val datePaint = Paint().apply {
            isAntiAlias = true
            textSize = 8.5.sp.toPx()
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }
        pts.forEachIndexed { idx, pt ->
            val active = idx == selectedIdx
            if (active) drawCircle(AuraColors.Shopping.copy(alpha = 0.25f), 8.dp.toPx(), pt)
            drawCircle(if (active) Color.White else AuraColors.Shopping, if (active) 4.4.dp.toPx() else 3.dp.toPx(), pt)
            drawCircle(AuraColors.Shopping, if (active) 4.4.dp.toPx() else 3.dp.toPx(), pt, style = Stroke(1.5.dp.toPx()))
            datePaint.color = if (active) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#64748B")
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(history[idx].date, pt.x, h - 4.dp.toPx(), datePaint)
            }
        }
    }
}

@Composable
fun ShoppingMallRouteCanvas(modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "mallAnim")
    val t by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing)),
        label = "mallT"
    )
    Canvas(modifier = modifier.fillMaxWidth().height(124.dp).background(Color(0xFF060E0B))) {
        val sx = size.width / 336f
        val sy = size.height / 124f

        fun drawPlane(pts: List<Pair<Float, Float>>, fill: Color, stroke: Color) {
            val p = Path().apply {
                moveTo(pts[0].first * sx, pts[0].second * sy)
                pts.drop(1).forEach { lineTo(it.first * sx, it.second * sy) }
                close()
            }
            drawPath(p, fill)
            drawPath(p, stroke, style = Stroke(1.dp.toPx()))
        }

        drawPlane(listOf(190f to 14f, 316f to 14f, 296f to 44f, 170f to 44f), AuraColors.Shopping.copy(alpha = 0.06f), AuraColors.Shopping.copy(alpha = 0.30f))
        drawPlane(listOf(106f to 48f, 236f to 48f, 216f to 78f, 86f to 78f), AuraColors.ShoppingAlt.copy(alpha = 0.07f), AuraColors.ShoppingAlt.copy(alpha = 0.36f))
        drawPlane(listOf(22f to 82f, 154f to 82f, 134f to 112f, 8f to 112f), AuraColors.Shopping.copy(alpha = 0.05f), AuraColors.Shopping.copy(alpha = 0.30f))

        val b1 = Offset(60f * sx, 97f * sy)
        val l2 = Offset(160f * sx, 63f * sy)
        val l3 = Offset(248f * sx, 29f * sy)
        val route = Path().apply {
            moveTo(b1.x, b1.y)
            quadraticTo(112f * sx, 76f * sy, l2.x, l2.y)
            quadraticTo(206f * sx, 46f * sy, l3.x, l3.y)
        }
        drawPath(route, Brush.horizontalGradient(listOf(AuraColors.Shopping, AuraColors.ShoppingAlt)), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))

        val dotPos = if (t < 0.5f) {
            quadBezier(b1, Offset(112f * sx, 76f * sy), l2, t * 2f)
        } else {
            quadBezier(l2, Offset(206f * sx, 46f * sy), l3, (t - 0.5f) * 2f)
        }
        drawCircle(AuraColors.Shopping.copy(alpha = 0.25f), 8.dp.toPx(), dotPos)
        drawCircle(Color.White, 3.6.dp.toPx(), dotPos)

        drawCircle(AuraColors.Shopping, 4.dp.toPx(), b1)
        drawCircle(AuraColors.ShoppingAlt, 4.dp.toPx(), l2)
        drawCircle(AuraColors.Shopping, 4.dp.toPx(), l3)

        val paint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#ECFDF5")
            textSize = 8.5.sp.toPx()
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawText("B1 礼盒", b1.x, b1.y + 15.dp.toPx(), paint)
            canvas.nativeCanvas.drawText("L2 香氛", l2.x, l2.y - 9.dp.toPx(), paint)
            canvas.nativeCanvas.drawText("L3 纪念杯", l3.x, l3.y - 9.dp.toPx(), paint)
        }
    }
}

@Composable
fun SlideToConfirmBar(
    label: String,
    themeKey: String,
    onConfirmed: () -> Unit
) {
    val palette = auraPalette(themeKey)
    val density = LocalDensity.current
    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    val handleSizeDp = 44.dp
    val handleSizePx = with(density) { handleSizeDp.toPx() }
    val padPx = with(density) { 6.dp.toPx() }
    var dragX by remember { mutableFloatStateOf(0f) }
    var triggered by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(CircleShape)
                .background(Color(0xF20F172A))
                .border(1.dp, palette.accent.copy(alpha = 0.5f), CircleShape)
                .onSizeChanged { trackWidthPx = it.width.toFloat() }
                .padding(6.dp)
        ) {
            val maxDrag = (trackWidthPx - handleSizePx - padPx * 2f).coerceAtLeast(1f)
            val fillFrac = ((dragX + handleSizePx) / (trackWidthPx - padPx * 2f).coerceAtLeast(1f)).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fillFrac)
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(palette.accent.copy(alpha = 0.35f), palette.accent.copy(alpha = 0.68f))))
            )
            Box(modifier = Modifier.fillMaxSize().padding(start = 32.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = label,
                    color = AuraColors.TextPrimary.copy(alpha = (1f - dragX / (maxDrag * 0.8f)).coerceIn(0.15f, 1f)),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .offset { IntOffset(dragX.roundToInt(), 0) }
                    .size(handleSizeDp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color.White, palette.accent)))
                    .pointerInput(maxDrag) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (!triggered) {
                                    if (dragX >= maxDrag * 0.82f) {
                                        triggered = true
                                        onConfirmed()
                                    } else {
                                        dragX = 0f
                                    }
                                }
                            },
                            onDragCancel = { if (!triggered) dragX = 0f },
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                if (!triggered) {
                                    dragX = (dragX + amount).coerceIn(0f, maxDrag)
                                    if (dragX >= maxDrag * 0.88f) {
                                        triggered = true
                                        onConfirmed()
                                    }
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "滑动确认", tint = Color(0xFF020617), modifier = Modifier.size(20.dp))
            }
        }
        Text(
            text = "或点击此处快速完成滑动确认",
            color = AuraColors.TextSecondary,
            fontSize = 11.sp,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable {
                if (!triggered) {
                    triggered = true
                    onConfirmed()
                }
            }
        )
    }
}

@Composable
fun HoloArtifactCard(
    card: AuraCard,
    friends: List<DiningFriend>,
    onToggleChecklist: (Int) -> Unit,
    onQuickAction: (String) -> Unit
) {
    val palette = auraPalette(card.themeKey)
    var tiltX by remember { mutableFloatStateOf(0f) }
    var tiltY by remember { mutableFloatStateOf(0f) }
    var glareX by remember { mutableFloatStateOf(0.5f) }
    var glareY by remember { mutableFloatStateOf(0.5f) }

    val animTiltX by animateFloatAsState(tiltX, spring(stiffness = Spring.StiffnessMediumLow), label = "tiltX")
    val animTiltY by animateFloatAsState(tiltY, spring(stiffness = Spring.StiffnessMediumLow), label = "tiltY")

    val bgBrush = when (card.category) {
        AuraCategory.TRAVEL -> Brush.linearGradient(listOf(Color(0xFF0D192B), Color(0xFF101533), Color(0xFF081526)))
        AuraCategory.DINING -> Brush.linearGradient(listOf(Color(0xFF241311), Color(0xFF1B1018), Color(0xFF190D0B)))
        else -> Brush.linearGradient(listOf(Color(0xFF092018), Color(0xFF0C1A22), Color(0xFF071912)))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                rotationX = animTiltX
                rotationY = animTiltY
                cameraDistance = 14f * density
            }
            .clip(RoundedCornerShape(26.dp))
            .background(bgBrush)
            .border(1.dp, palette.accent.copy(alpha = 0.52f), RoundedCornerShape(26.dp))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { tiltX = 0f; tiltY = 0f; glareX = 0.5f; glareY = 0.5f },
                    onDragCancel = { tiltX = 0f; tiltY = 0f; glareX = 0.5f; glareY = 0.5f },
                    onDrag = { change, _ ->
                        change.consume()
                        val nx = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        val ny = (change.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                        glareX = nx
                        glareY = ny
                        tiltX = (0.5f - ny) * 14f
                        tiltY = (nx - 0.5f) * 16f
                    }
                )
            }
    ) {
        // Dynamic holographic specular foil overlay
        Canvas(modifier = Modifier.matchParentSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.24f), palette.accent.copy(alpha = 0.16f), AuraColors.Explore.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(size.width * glareX, size.height * glareY),
                    radius = size.maxDimension * 0.72f
                )
            )
            // Ticket side punch cutouts for Travel pass
            if (card.category == AuraCategory.TRAVEL) {
                drawCircle(AuraColors.SheetBg, radius = 11.dp.toPx(), center = Offset(0f, size.height * 0.56f))
                drawCircle(AuraColors.SheetBg, radius = 11.dp.toPx(), center = Offset(size.width, size.height * 0.56f))
            }
        }

        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (card.category) {
                AuraCategory.TRAVEL -> {
                    val p = card.travelPlans.getOrNull(card.selectedPlanIdx) ?: AuraFlowEngine.defaultTravelPlans(card.direction).first()
                    val isRet = card.direction == "return"
                    val depCode = if (isRet) "SHA" else "PEK"
                    val arrCode = if (isRet) "PEK" else "SHA"
                    val isRail = p.mode == "rail"

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(p.carrier, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("${p.modeText} · ${if (isRail) "刷证直进" else "电子登机牌"}", color = Color(0xCC99F6E4), fontSize = 11.sp)
                        }
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .background(AuraColors.Travel.copy(alpha = 0.15f))
                                .border(1.dp, AuraColors.Travel.copy(alpha = 0.4f), CircleShape)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text("CONFIRMED", color = AuraColors.Travel, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(depCode, color = Color.White, fontSize = 28.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Text(p.depHub, color = Color(0xFFCBD5E1), fontSize = 11.sp)
                            Text(p.dep, color = AuraColors.Travel, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Box(Modifier.fillMaxWidth().height(1.5.dp).background(Brush.horizontalGradient(listOf(AuraColors.Travel, AuraColors.TravelAlt))))
                            Spacer(Modifier.height(4.dp))
                            Text(if (isRail) "高铁直达" else "直飞航线", color = AuraColors.TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(arrCode, color = Color.White, fontSize = 28.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Text(p.arrHub, color = Color(0xFFCBD5E1), fontSize = 11.sp)
                            Text(p.arr, color = Color(0xFF7DD3FC), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    }

                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x28FFFFFF)))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("SEAT", color = AuraColors.TextSecondary, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)
                            Text(p.seat, color = AuraColors.Travel, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (isRail) "CHECK-IN" else "GATE", color = AuraColors.TextSecondary, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)
                            Text(p.gate, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("STATUS", color = AuraColors.TextSecondary, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)
                            Text("准点计划中", color = Color(0xFF6EE7B7), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    // Stylized barcode strip
                    Row(Modifier.fillMaxWidth().height(26.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        for (i in 0 until 32) {
                            Box(
                                Modifier
                                    .width(if (i % 3 == 0) 3.dp else 1.5.dp)
                                    .fillMaxHeight()
                                    .background(if (i % 7 == 0) AuraColors.Travel else Color(0xCCE2E8F0))
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("ETKT 781-AURAFLOW-5102", color = AuraColors.TextSecondary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        Text("AURAFLOW VERIFIED", color = AuraColors.TextSecondary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0x14FFFFFF))
                                .border(1.dp, AuraColors.Hairline, RoundedCornerShape(14.dp))
                                .clickable { onQuickAction("已打开选座图 · 当前锁定 ${p.seat}") }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("值机座位", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(AuraColors.Travel.copy(alpha = 0.16f))
                                .border(1.dp, AuraColors.Travel.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                                .clickable { onQuickAction("已预约 ${p.arrHub} 接驳专车") }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("接驳叫车 ↗", color = AuraColors.Travel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                AuraCategory.DINING -> {
                    val r = card.diningPlans.getOrNull(card.selectedPlanIdx) ?: AuraFlowEngine.defaultDiningPlans().first()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Column {
                            Box(
                                Modifier
                                    .clip(CircleShape)
                                    .background(AuraColors.Dining.copy(alpha = 0.2f))
                                    .border(1.dp, AuraColors.Dining.copy(alpha = 0.45f), CircleShape)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("RESERVED · 免排队", color = AuraColors.Dining, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(r.room, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("${r.name} · ${card.time}", color = Color(0xCCFFEDD5), fontSize = 12.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("平均通勤", color = AuraColors.TextSecondary, fontSize = 10.sp)
                            Text("${r.avgCommute} min", color = AuraColors.Dining, fontSize = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x28FFFFFF)))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        friends.forEach { f ->
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(AuraColors.Dining, AuraColors.DiningAlt)))
                                    .border(1.5.dp, Color(0xFF1B1018), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(f.short, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text("已同步群日程", color = AuraColors.TextSecondary, fontSize = 11.sp)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(r.address, color = Color(0xFFCBD5E1), fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(AuraColors.Dining)
                                .clickable { onQuickAction("已唤起导航前往 ${r.name}") }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Text("一键导航", color = Color(0xFF020617), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                else -> {
                    val done = card.checklist.count { it.done }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Box(
                                Modifier
                                    .clip(CircleShape)
                                    .background(AuraColors.Shopping.copy(alpha = 0.2f))
                                    .border(1.dp, AuraColors.Shopping.copy(alpha = 0.45f), CircleShape)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("2 VOUCHERS ACTIVE", color = AuraColors.Shopping, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("恒隆广场 · 现货核销卡", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text("叠券立省 ¥340 · $done/${card.checklist.size} 完成", color = Color(0xCCA7F3D0), fontSize = 11.5.sp)
                        }
                        // Stylized QR block
                        Canvas(Modifier.size(54.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).padding(5.dp)) {
                            val cell = size.width / 7f
                            val pattern = listOf(
                                0 to 0, 1 to 0, 2 to 0, 0 to 1, 2 to 1, 0 to 2, 1 to 2, 2 to 2,
                                4 to 0, 5 to 0, 6 to 0, 4 to 1, 6 to 1, 4 to 2, 5 to 2, 6 to 2,
                                0 to 4, 1 to 4, 2 to 4, 0 to 5, 2 to 5, 0 to 6, 1 to 6, 2 to 6,
                                3 to 3, 4 to 4, 5 to 5, 6 to 4, 4 to 6, 6 to 6, 3 to 1, 1 to 3
                            )
                            pattern.forEach { (cx, cy) ->
                                drawRect(Color(0xFF0F172A), Offset(cx * cell, cy * cell), Size(cell * 0.92f, cell * 0.92f))
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x28FFFFFF)))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        card.checklist.forEachIndexed { idx, item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (item.done) AuraColors.Shopping.copy(alpha = 0.16f) else Color(0x55000000))
                                    .border(1.dp, if (item.done) AuraColors.Shopping.copy(alpha = 0.45f) else AuraColors.Hairline, RoundedCornerShape(12.dp))
                                    .clickable { onToggleChecklist(idx) }
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(
                                        Modifier
                                            .size(18.dp)
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(if (item.done) AuraColors.Shopping else Color.Transparent)
                                            .border(1.dp, if (item.done) AuraColors.Shopping else Color(0x55FFFFFF), RoundedCornerShape(5.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (item.done) Text("✓", color = Color(0xFF020617), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Text(
                                        text = item.name,
                                        color = if (item.done) Color(0xAAA7F3D0) else Color.White,
                                        fontSize = 12.sp,
                                        textDecoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None
                                    )
                                }
                                Text(item.price, color = if (item.done) AuraColors.Shopping else Color(0xFFCBD5E1), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class FxParticle(val angle: Float, val speed: Float, val radius: Float, val xSpread: Float, val ySpread: Float, val white: Boolean)

@Composable
fun ParticleBurstOverlay(
    burstKey: Int,
    color: Color,
    vaporMode: Boolean,
    modifier: Modifier = Modifier
) {
    if (burstKey == 0) return
    val progress = remember(burstKey) { Animatable(0f) }
    val particles = remember(burstKey) {
        val rng = Random(burstKey)
        List(44) {
            FxParticle(
                angle = rng.nextFloat() * (2f * PI.toFloat()),
                speed = 70f + rng.nextFloat() * 190f,
                radius = 2f + rng.nextFloat() * 3.5f,
                xSpread = (rng.nextFloat() - 0.5f) * 220f,
                ySpread = (rng.nextFloat() - 0.5f) * 48f,
                white = rng.nextFloat() > 0.65f
            )
        }
    }
    LaunchedEffect(burstKey) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(680, easing = FastOutSlowInEasing))
    }
    val p = progress.value
    if (p >= 1f) return
    Canvas(modifier = modifier.fillMaxSize()) {
        val cx = size.width * 0.5f
        val cy = size.height * (if (vaporMode) 0.42f else 0.55f)
        val alpha = (1f - p).coerceIn(0f, 1f)
        particles.forEach { pt ->
            val x = if (vaporMode) cx + pt.xSpread - pt.speed * p * 1.15f else cx + cos(pt.angle) * pt.speed * p
            val y = if (vaporMode) cy + pt.ySpread + sin(pt.angle) * 32f * p else cy + sin(pt.angle) * pt.speed * p + 65f * p * p
            drawCircle(
                color = (if (pt.white) Color.White else color).copy(alpha = alpha),
                radius = pt.radius.dp.toPx() * (1f - p * 0.45f),
                center = Offset(x, y)
            )
        }
    }
}

private fun quadBezier(p0: Offset, p1: Offset, p2: Offset, t: Float): Offset {
    val u = 1f - t
    return Offset(
        u * u * p0.x + 2f * u * t * p1.x + t * t * p2.x,
        u * u * p0.y + 2f * u * t * p1.y + t * t * p2.y
    )
}
