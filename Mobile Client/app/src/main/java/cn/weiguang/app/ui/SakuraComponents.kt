package cn.weiguang.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.weiguang.app.R

@Composable
internal fun SakuraStatusCard(title: String, detail: String, modifier: Modifier = Modifier, active: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(26.dp)
    Box(modifier.fillMaxWidth().clip(shape)
        .background(Brush.linearGradient(listOf(colors.primaryContainer, colors.surface)))
        .border(1.dp, colors.outlineVariant, shape)) {
        Canvas(Modifier.matchParentSize()) {
            val petals = listOf(0.12f to 0.25f, 0.31f to 0.15f, 0.94f to 0.13f, 0.90f to 0.77f, 0.42f to 0.88f)
            petals.forEachIndexed { index, (x, y) ->
                val center = Offset(size.width * x, size.height * y)
                rotate(if (index % 2 == 0) -32f else 38f, center) {
                    drawOval(colors.primary.copy(alpha = .14f), center - Offset(4.dp.toPx(), 2.dp.toPx()), Size(8.dp.toPx(), 4.dp.toPx()))
                }
            }
        }
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val motion = if (active) {
                rememberInfiniteTransition(label = "mascotMotion").animateFloat(
                    0f, -3f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "mascotLift"
                )
            } else null
            Image(painterResource(R.drawable.sakura_companion), null,
                Modifier.width(118.dp).height(86.dp).graphicsLayer {
                    scaleX = 1.14f
                    scaleY = 1.14f
                    translationY = (motion?.value ?: 0f).dp.toPx()
                })
            Column(Modifier.weight(1f).padding(start = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}
