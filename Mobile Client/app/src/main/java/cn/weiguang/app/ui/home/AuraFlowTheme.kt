package cn.weiguang.app.ui.home

import androidx.compose.ui.graphics.Color
import cn.weiguang.app.ui.SakuraLight
import java.util.Locale

/** The light sakura palette for the agenda and its detail sheets. */
object AuraColors {
    val ScreenBg = SakuraLight.background
    val CardSolidTop = SakuraLight.surface
    val CardSolidBottom = Color(0xFFFFF8FA)
    val CardNowTop = Color(0xFFFFE9EF)
    val CardNowBottom = Color(0xFFF8D1E0)
    val GlassPanel = SakuraLight.surface
    val SheetBg = SakuraLight.background
    val MiniSheetTop = SakuraLight.surface
    val MiniSheetBottom = SakuraLight.primaryContainer

    val TextPrimary = SakuraLight.onSurface
    val TextSecondary = SakuraLight.onSurfaceVariant
    val TextMuted = Color(0xFF805E6D)
    val Hairline = SakuraLight.outlineVariant
    val HairlineStrong = Color(0xFFE0BCCB)
    val Rose = SakuraLight.primary
    val Tint = SakuraLight.surfaceVariant

    val Travel = Color(0xFF4D6283)
    val TravelAlt = Color(0xFF758DB2)
    val Dining = Color(0xFFA3425A)
    val DiningAlt = Color(0xFFC87B88)
    val Shopping = Color(0xFF476D5C)
    val ShoppingAlt = Color(0xFF73A58C)
    val Explore = Color(0xFF9C456E)
    val ExploreGold = Color(0xFF89501F)
    val Office = Color(0xFF705485)
    val OfficeAlt = Color(0xFF9F85B5)
    val Rest = Color(0xFF775F72)
    val Alert = Color(0xFFA23742)
}

data class AuraPalette(
    val accent: Color,
    val alt: Color,
    val label: String
)

fun auraPalette(themeKey: String): AuraPalette = when (themeKey) {
    "travel" -> AuraPalette(AuraColors.Travel, AuraColors.TravelAlt, "出行订票")
    "dining" -> AuraPalette(AuraColors.Dining, AuraColors.DiningAlt, "聚餐社交")
    "shopping" -> AuraPalette(AuraColors.Shopping, AuraColors.ShoppingAlt, "线下购物")
    "explore" -> AuraPalette(AuraColors.Explore, AuraColors.ExploreGold, "城市漫游")
    "office" -> AuraPalette(AuraColors.Office, AuraColors.OfficeAlt, "事务日程")
    "alert" -> AuraPalette(AuraColors.Alert, AuraColors.Dining, "异常预警")
    else -> AuraPalette(AuraColors.Rest, AuraColors.Office, "静默放空")
}

fun formatCountdownHms(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val h = safe / 3600
    val m = (safe % 3600) / 60
    val s = safe % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}
