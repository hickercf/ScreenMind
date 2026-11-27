package cn.weiguang.app.ui

import android.graphics.drawable.ColorDrawable
import android.view.Window
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import cn.weiguang.app.R

internal val SakuraLight = lightColorScheme(
    primary = Color(0xFFA9365C), onPrimary = Color.White,
    primaryContainer = Color(0xFFFADDE6), onPrimaryContainer = Color(0xFF66243E),
    secondary = Color(0xFF815568), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF8E5EC), onSecondaryContainer = Color(0xFF5A3347),
    tertiary = Color(0xFF845634), tertiaryContainer = Color(0xFFFFE9D4),
    outline = Color(0xFF9C7282), background = Color(0xFFFFF5F8),
    surface = Color(0xFFFFFCFD), onSurface = Color(0xFF382332),
    onBackground = Color(0xFF382332), onSurfaceVariant = Color(0xFF745563),
    surfaceVariant = Color(0xFFF8E8EE), outlineVariant = Color(0xFFECD4DE),
    surfaceContainerLow = Color(0xFFFFF4F8), surfaceContainer = Color(0xFFF9E9EF),
    surfaceContainerHigh = Color(0xFFF7E1EB), surfaceContainerHighest = Color(0xFFF4D8E4),
    inverseSurface = Color(0xFF503542), inverseOnSurface = Color(0xFFFFEBF3), inversePrimary = Color(0xFFFFB2CB),
    surfaceTint = Color.Transparent
)
private val WorkspaceDark = darkColorScheme(
    primary = Color(0xFFFFB2CB), onPrimary = Color(0xFF582238),
    primaryContainer = Color(0xFF582A40), onPrimaryContainer = Color(0xFFFFDCE8),
    secondary = Color(0xFFE0B9C8), onSecondary = Color(0xFF4C2638),
    secondaryContainer = Color(0xFF483040), onSecondaryContainer = Color(0xFFF8DCE7),
    outline = Color(0xFFA98595), background = Color(0xFF251820),
    surface = Color(0xFF32212C), onSurface = Color(0xFFF7E8EF),
    onBackground = Color(0xFFF7E8EF), onSurfaceVariant = Color(0xFFDBC0CD),
    surfaceVariant = Color(0xFF47303E), outlineVariant = Color(0xFF614454),
    surfaceContainerLow = Color(0xFF2C1B26), surfaceContainer = Color(0xFF392530),
    surfaceContainerHigh = Color(0xFF472D3B), surfaceContainerHighest = Color(0xFF543949),
    inverseSurface = Color(0xFFF0DBE4), inverseOnSurface = Color(0xFF382332), inversePrimary = Color(0xFFA9365C),
    surfaceTint = Color.Transparent
)

private val RoundedFont = FontFamily(
    Font(R.font.screenmind_rounded_regular, FontWeight.Normal),
    Font(R.font.screenmind_rounded_bold, FontWeight.Bold),
)

private val CleanSansFont = FontFamily.SansSerif

private fun TextStyle.displayRounded(weight: FontWeight = FontWeight.Bold) =
    copy(fontFamily = RoundedFont, fontWeight = weight)

private fun TextStyle.cleanBody(weight: FontWeight = FontWeight.Normal) =
    copy(fontFamily = CleanSansFont, fontWeight = weight)

private val WorkspaceTypography = Typography().let {
    it.copy(
        displaySmall = it.displaySmall.displayRounded(FontWeight.Bold).copy(fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
        headlineLarge = it.headlineLarge.displayRounded(FontWeight.Bold).copy(fontSize = 27.sp, lineHeight = 35.sp, letterSpacing = (-0.3).sp),
        headlineMedium = it.headlineMedium.displayRounded(FontWeight.Bold).copy(fontSize = 22.sp, lineHeight = 30.sp),
        headlineSmall = it.headlineSmall.displayRounded(FontWeight.Bold).copy(fontSize = 19.sp, lineHeight = 26.sp),
        titleLarge = it.titleLarge.displayRounded(FontWeight.Bold).copy(fontSize = 18.sp, lineHeight = 25.sp),
        titleMedium = it.titleMedium.cleanBody(FontWeight.SemiBold).copy(fontSize = 16.sp, lineHeight = 23.sp, letterSpacing = 0.1.sp),
        titleSmall = it.titleSmall.cleanBody(FontWeight.SemiBold).copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodyLarge = it.bodyLarge.cleanBody(FontWeight.Normal).copy(fontSize = 15.5.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
        bodyMedium = it.bodyMedium.cleanBody(FontWeight.Normal).copy(fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.15.sp),
        bodySmall = it.bodySmall.cleanBody(FontWeight.Normal).copy(fontSize = 12.5.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp),
        labelLarge = it.labelLarge.cleanBody(FontWeight.SemiBold).copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.3.sp),
        labelMedium = it.labelMedium.cleanBody(FontWeight.Medium).copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
        labelSmall = it.labelSmall.cleanBody(FontWeight.Medium).copy(fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.3.sp),
    )
}

private val WorkspaceShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

@Composable
internal fun WorkspaceTheme(mode: String, window: Window, content: @Composable () -> Unit) {
    val dark = when (mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val colors = if (dark) WorkspaceDark else SakuraLight
    LaunchedEffect(window, dark) {
        window.setBackgroundDrawable(ColorDrawable(colors.background.toArgb()))
        window.updateBarIcons(lightBackground = !dark)
    }
    MaterialTheme(
        colorScheme = colors,
        typography = WorkspaceTypography,
        shapes = WorkspaceShapes,
        content = content,
    )
}

private fun Window.updateBarIcons(lightBackground: Boolean) {
    WindowCompat.getInsetsController(this, decorView).apply {
        isAppearanceLightStatusBars = lightBackground
        isAppearanceLightNavigationBars = lightBackground
    }
}

@Composable
internal fun WorkspaceSheetBars() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    val lightBackground = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    LaunchedEffect(window, lightBackground) { window.updateBarIcons(lightBackground) }
}

@Composable
internal fun WorkspaceCard(
    accent: Boolean = false,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val containerColor by animateColorAsState(
        targetValue = if (accent) colors.surfaceContainerLow else colors.surface,
        animationSpec = tween(220),
        label = "card container",
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = colors.onSurface,
        tonalElevation = if (accent) 1.dp else 0.dp,
        shadowElevation = if (accent) 1.5.dp else 0.5.dp,
        border = BorderStroke(
            width = 0.75.dp,
            color = if (accent) colors.primary.copy(alpha = 0.22f) else colors.outlineVariant.copy(alpha = 0.72f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
internal fun WorkspaceHeader(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun WorkspaceGlyph(
    icon: ImageVector,
    accent: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(13.dp),
        color = if (accent) colors.primaryContainer.copy(alpha = 0.75f) else colors.secondaryContainer.copy(alpha = 0.85f),
        contentColor = if (accent) colors.primary else colors.onSecondaryContainer,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun WorkspaceActionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    description: String? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    titleMaxLines: Int = Int.MAX_VALUE,
    onClickLabel: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.985f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "row press scale",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(MaterialTheme.shapes.medium)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                enabled = enabled,
                role = Role.Button,
                onClickLabel = onClickLabel,
                onClick = onClick,
            )
            .heightIn(min = 56.dp)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        WorkspaceGlyph(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        Surface(
            shape = CircleShape,
            color = colors.surfaceContainer,
            contentColor = colors.onSurfaceVariant,
        ) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowForward,
                    null,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

@Composable
internal fun WorkspaceBadge(
    text: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (accent) colors.primaryContainer else colors.secondaryContainer,
        contentColor = if (accent) colors.onPrimaryContainer else colors.onSecondaryContainer,
        border = BorderStroke(0.5.dp, if (accent) colors.primary.copy(alpha = 0.25f) else colors.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp).then(modifier),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
internal fun WorkspaceButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    secondary: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.975f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "button scale",
    )
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        enabled = enabled,
        interactionSource = interactionSource,
        shape = MaterialTheme.shapes.small,
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            disabledElevation = 0.dp,
        ),
        border = if (secondary) BorderStroke(0.75.dp, colors.outlineVariant.copy(alpha = 0.8f)) else null,
        colors = if (secondary) {
            ButtonDefaults.filledTonalButtonColors(
                containerColor = colors.secondaryContainer.copy(alpha = 0.85f),
                contentColor = colors.onSecondaryContainer,
            )
        } else {
            ButtonDefaults.buttonColors(
                containerColor = colors.primary,
                contentColor = colors.onPrimary,
            )
        },
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 11.dp),
        content = content,
    )
}
