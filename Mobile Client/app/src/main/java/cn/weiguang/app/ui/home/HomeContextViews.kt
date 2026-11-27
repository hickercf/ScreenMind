package cn.weiguang.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.weiguang.app.home.HomeCity
import cn.weiguang.app.home.HomeLocalContext
import cn.weiguang.app.home.HomePlace
import cn.weiguang.app.home.HomePlaceResults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Composable
private fun HomePickerTheme(content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme.copy(
        primary = AuraColors.Rose,
        onPrimary = AuraColors.ScreenBg,
        surface = AuraColors.SheetBg,
        surfaceContainer = AuraColors.SheetBg,
        surfaceContainerHigh = AuraColors.SheetBg,
        onSurface = AuraColors.TextPrimary,
        onSurfaceVariant = AuraColors.TextSecondary,
        outline = AuraColors.HairlineStrong
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
internal fun HomeWeatherButton(context: HomeLocalContext, enabled: Boolean, onClick: () -> Unit) {
    val weather = context.weather.display()
    val city = context.city?.name
    val label = when {
        city == null -> "选择天气城市"
        weather != null -> "$city · $weather"
        context.weather.status == "unavailable" -> "$city · 天气暂不可用"
        else -> "$city · 查看天气"
    }
    AssistChip(onClick = onClick, enabled = enabled, label = { Text(label, color = AuraColors.Rose) })
}

@Composable
internal fun HomeCityDialog(context: HomeLocalContext, onSelect: (HomeCity) -> Unit, onDismiss: () -> Unit) {
    val reportTime = context.weather.reportedAt?.let {
        runCatching { OffsetDateTime.parse(it).format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrNull()
    }
    HomePickerTheme {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("选择城市") },
            text = {
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    if (reportTime != null) Text("天气发布于 $reportTime")
                    context.cities.forEach { city ->
                        TextButton(onClick = { onSelect(city) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (city == context.city) "${city.name} · 刷新天气" else city.name)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
        )
    }
}

@Composable
internal fun HomePlaceDialog(
    cities: List<HomeCity>,
    initialCity: HomeCity?,
    saved: HomePlace?,
    suggested: String,
    search: suspend (HomeCity, String) -> HomePlaceResults,
    onSelect: (HomePlace?) -> Unit,
    onDismiss: () -> Unit
) {
    var city by remember { mutableStateOf(initialCity) }
    var showCities by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(saved?.name ?: suggested) }
    var results by remember { mutableStateOf<HomePlaceResults?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    HomePickerTheme {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("选择日程地点") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        TextButton(onClick = { showCities = true }, enabled = !loading) {
                            Text(city?.let { "搜索城市 · ${it.name}" } ?: "选择搜索城市")
                        }
                        DropdownMenu(expanded = showCities, onDismissRequest = { showCities = false }) {
                            cities.forEach { option ->
                                DropdownMenuItem(text = { Text(option.name) }, onClick = {
                                    city = option
                                    results = null
                                    showCities = false
                                })
                            }
                        }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it; results = null },
                        label = { Text("地点名称") },
                        enabled = !loading,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = {
                        val keyword = query.trim()
                        val location = requireNotNull(city)
                        loading = true
                        scope.launch {
                            try {
                                results = search(location, keyword)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                results = HomePlaceResults("unavailable", emptyList())
                            } finally {
                                loading = false
                            }
                        }
                    }, enabled = !loading && city != null && query.trim().length in 2..80) { Text(if (loading) "搜索中" else "搜索") }
                    when (results?.status) {
                        "empty" -> Text("没有找到地点，试试更完整的名称。")
                        "unavailable" -> Text("地点服务暂不可用，请稍后重试。")
                        "available" -> Column(Modifier.heightIn(max = 210.dp).verticalScroll(rememberScrollState())) {
                            results?.places.orEmpty().forEach { place ->
                                TextButton(onClick = { onSelect(place) }, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(place.name)
                                        if (place.address.isNotBlank()) Text(place.address)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
            dismissButton = if (saved != null) ({ TextButton(onClick = { onSelect(null) }) { Text("清除地点") } }) else null
        )
    }
}
