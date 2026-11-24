package cn.weiguang.app.ui.home

import androidx.compose.ui.graphics.Color
import java.util.Locale

/**
 * Scoped AuraFlow Obsidian Kinetic Aurora tokens.
 * Used ONLY inside the Home module (tab == 0) so other tabs keep WeiguangTheme intact.
 */
object AuraColors {
    val ObsidianBg = Color(0xFF05070B)
    val ScreenBg = Color(0xFF06080F)
    val CardSolidTop = Color(0xF0151B2A)
    val CardSolidBottom = Color(0xF70C101A)
    val CardNowTop = Color(0xE6131A2C)
    val CardNowBottom = Color(0xF20A0E18)
    val GlassPanel = Color(0xE0101522)
    val SheetBg = Color(0xF5060911)
    val MiniSheetTop = Color(0xFA131826)
    val MiniSheetBottom = Color(0xFC090C16)

    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)
    val Hairline = Color(0x1CFFFFFF)
    val HairlineStrong = Color(0x2BFFFFFF)

    val Travel = Color(0xFF00E5FF)
    val TravelAlt = Color(0xFF3B82F6)
    val Dining = Color(0xFFFF914D)
    val DiningAlt = Color(0xFFF43F5E)
    val Shopping = Color(0xFF00E676)
    val ShoppingAlt = Color(0xFF14B8A6)
    val Explore = Color(0xFFC084FC)
    val ExploreGold = Color(0xFFFBBF24)
    val Office = Color(0xFF818CF8)
    val OfficeAlt = Color(0xFF6366F1)
    val Rest = Color(0xFF94A3B8)
    val Alert = Color(0xFFFF4D6D)
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
