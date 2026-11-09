package cn.weiguang.app.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val Light = lightColorScheme(
    primary = Color(0xFF285A45), onPrimary = Color.White,
    primaryContainer = Color(0xFFE0EDE3), onPrimaryContainer = Color(0xFF20392A),
    secondary = Color(0xFF687D6C), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCEADF), onSecondaryContainer = Color(0xFF20392A),
    outline = Color(0xFF7B8E81), background = Color(0xFFF4F6F3),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1C3029),
    onBackground = Color(0xFF1C3029), onSurfaceVariant = Color(0xFF52675D),
    surfaceVariant = Color(0xFFE7EEE7), outlineVariant = Color(0xFFDCE5DD)
)
private val Dark = darkColorScheme(
    primary = Color(0xFFB6D8BD), onPrimary = Color(0xFF173124),
    primaryContainer = Color(0xFF293E32), onPrimaryContainer = Color(0xFFE1EFE3),
    secondary = Color(0xFFB1C7B6), onSecondary = Color(0xFF173124),
    secondaryContainer = Color(0xFF354D3E), onSecondaryContainer = Color(0xFFE1EFE3),
    outline = Color(0xFF82988A), background = Color(0xFF141C1A),
    surface = Color(0xFF202B27), onSurface = Color(0xFFE4EEE7),
    onBackground = Color(0xFFE4EEE7), onSurfaceVariant = Color(0xFFA6BDB0),
    surfaceVariant = Color(0xFF293C31), outlineVariant = Color(0xFF34483E)
)

@Composable
fun WeiguangTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val typography = Typography().let {
        it.copy(
            headlineLarge = it.headlineLarge.copy(fontSize = 30.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.SansSerif),
            headlineSmall = it.headlineSmall.copy(fontSize = 24.sp, fontWeight = FontWeight.Medium),
            titleLarge = it.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium),
            bodyLarge = it.bodyLarge.copy(fontSize = 16.sp, lineHeight = 25.sp),
            bodyMedium = it.bodyMedium.copy(fontSize = 14.sp, lineHeight = 22.sp)
        )
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = typography, content = content)
}
