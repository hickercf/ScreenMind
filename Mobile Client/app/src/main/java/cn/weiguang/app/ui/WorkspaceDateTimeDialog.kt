package cn.weiguang.app.ui

import android.content.Context
import android.content.res.Configuration
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val calendarDay = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.SIMPLIFIED_CHINESE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceDateTimeDialog(
    initial: LocalDateTime,
    onDismiss: () -> Unit,
    onSelect: (LocalDateTime) -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val chinese = configuration.locales.get(0).takeIf { it.language == "zh" } ?: Locale.SIMPLIFIED_CHINESE
    val calendarConfig = remember(configuration, chinese) { Configuration(configuration).apply { setLocale(chinese) } }
    val calendarContext = remember(context, calendarConfig) { context.createConfigurationContext(calendarConfig) }
    val is24Hour = DateFormat.is24HourFormat(context)
    ProvidePickerLocale(calendarContext) {
        DateTimeSteps(initial, is24Hour, onDismiss, onSelect)
    }
}

@Composable
private fun ProvidePickerLocale(context: Context, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalConfiguration provides context.resources.configuration, LocalContext provides context, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeSteps(
    initial: LocalDateTime, is24Hour: Boolean, onDismiss: () -> Unit, onSelect: (LocalDateTime) -> Unit,
) {
    val first = initial.takeIf { it.year in 1..9999 } ?: LocalDateTime.now()
    val compactHeight = LocalConfiguration.current.screenHeightDp < 500
    val calendarContext = LocalContext.current
    val date = rememberDatePickerState(
        initialSelectedDateMillis = first.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = minOf(1900, first.year)..maxOf(2100, first.year),
        initialDisplayMode = if (compactHeight) DisplayMode.Input else DisplayMode.Picker,
    )
    val time = rememberTimePickerState(first.hour, first.minute, is24Hour)
    var choosingTime by rememberSaveable { mutableStateOf(false) }
    val day = date.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
    if (!choosingTime || day == null) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = { choosingTime = true }, enabled = day != null) { Text("下一步") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        ) {
            ProvidePickerLocale(calendarContext) {
                DatePicker(
                    state = date,
                    title = null,
                    headline = {
                        Text(day?.format(calendarDay) ?: "选择日期", Modifier.padding(start = 24.dp),
                            style = MaterialTheme.typography.titleLarge)
                    },
                )
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("选择时间") },
            text = {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(day.format(calendarDay), style = MaterialTheme.typography.bodyLarge)
                    ProvidePickerLocale(calendarContext) { TimeInput(state = time) }
                }
            },
            dismissButton = { TextButton(onClick = { choosingTime = false }) { Text("上一步") } },
            confirmButton = {
                TextButton(onClick = { onSelect(day.atTime(time.hour, time.minute)) }) { Text("确定") }
            },
        )
    }
}
