package cn.weiguang.app.ui

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

internal object ReadableText {
    const val UnreadableClue = "这条线索暂时无法读成文字。你可以打开并补充说明。"

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE)
    private val secondFormat = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm:ss", Locale.SIMPLIFIED_CHINESE)
    private val isoDateTime = Regex("""(?<!\d)\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?(?:Z|[+-]\d{2}:\d{2})?(?![\d:T])""")
    private val objectSyntax = Regex("""\{\s*(?:"[^"\r\n]+"\s*:|[\w-]+\s*:|\})""")
    private val arraySyntax = Regex("""^\[\s*(?:[{"\[\d-]|true\b|false\b|null\b|\])""")
    private val internalTokens = Regex("""\b(?:schedule_changes|source_refs|expected_revision|stack_trace)\b|\b(?:task|work|evt|screen|act)_[a-f0-9]{8,}\b|\b[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}\b""", RegexOption.IGNORE_CASE)
    private val han = Regex("""\p{IsHan}""")
    private val technicalError = Regex("""https?://|\b(?:Exception|Traceback|HTTP)\b|/api/|/v1/|请求失败\s*[（(][45]\d{2}""", RegexOption.IGNORE_CASE)
    private val scheduleProgress = mapOf("pending" to "待完成", "completed" to "已完成")
    private val embeddedProgress = Regex("""(?<= · )(pending|completed)(?= · |\s*$)""")
    private val legacyField = Regex("""(\S+) · (pending|completed) · (?:无先后约束|须早于：(.+))""")

    fun clue(value: String): String = text(value, UnreadableClue)

    fun text(value: String, fallback: String = "暂无可显示的内容。"): String =
        editable(value).ifBlank { fallback }

    fun editable(value: String): String {
        val raw = value.trim()
        if (raw.startsWith("```json", ignoreCase = true)) {
            return schedule(raw.substringAfter('\n', "").removeSuffix("```").trim())
        }
        if (raw.startsWith("{") && (raw == "{" || objectSyntax.containsMatchIn(raw) || raw.startsWith("{\""))) {
            return schedule(raw)
        }
        if (objectSyntax.containsMatchIn(raw) || arraySyntax.containsMatchIn(raw) ||
            "```" in raw || internalTokens.containsMatchIn(raw)
        ) return ""
        legacyField.matchEntire(raw)?.let { field ->
            val at = field.groupValues[1]
            if (isoDateTime.matches(at)) {
                val before = field.groups[3]?.value?.let { "在${it}之前" }
                return listOfNotNull(scheduleTime(at), scheduleProgress[field.groupValues[2]], before).joinToString(" · ")
            }
        }
        return scheduleProgress[raw] ?: embeddedProgress.replace(isoDateTime.replace(raw) { scheduleTime(it.value) }) {
            scheduleProgress.getValue(it.value)
        }
    }

    fun dateTime(value: String, zone: ZoneId? = null): String =
        parseDateTime(value.trim(), zone)?.let(::formatClock) ?: "时间待确认"

    private fun formatClock(time: LocalDateTime): String =
        time.format(if (time.second == 0) dateFormat else secondFormat)

    fun scheduleTime(value: String, deviceZone: ZoneId = ZoneId.systemDefault()): String {
        val time = try {
            OffsetDateTime.parse(value.trim())
        } catch (_: DateTimeParseException) {
            return dateTime(value)
        }
        val zone = if (time.offset == ZoneOffset.UTC) "协调世界时" else "UTC${time.offset.id}"
        val original = "${formatClock(time.toLocalDateTime())}（$zone）"
        val local = time.atZoneSameInstant(deviceZone)
        return if (time.offset == local.offset) original else "$original\n本机时间 ${formatClock(local.toLocalDateTime())}"
    }

    fun deviceTime(value: String?): LocalDateTime? =
        value?.let { parseDateTime(it.trim(), ZoneId.systemDefault()) }

    fun confirmedVersion(version: Int): String =
        if (version > 0) "已确认 · 第 $version 版" else "尚未确认方案"

    fun error(value: String?, fallback: String): String {
        val message = value.orEmpty()
        if (!han.containsMatchIn(message) || message.contains('\n') || technicalError.containsMatchIn(message)) return fallback
        return text(message, fallback)
    }

    private fun schedule(raw: String): String {
        val changes = runCatching { JSONObject(raw).optJSONArray("schedule_changes") }.getOrNull() ?: return ""
        return (0 until changes.length()).mapNotNull { changes.optJSONObject(it) }.mapNotNull { item ->
            val title = if (item.has("title")) editableTitle(item.optString("title")) else ""
            val sourceTime = item.optString("at")
            val at = sourceTime.takeIf { parseDateTime(it) != null }?.let { scheduleTime(it) }
            val status = scheduleProgress[item.optString("status")]
            if (title.isBlank() && at == null && status == null) return@mapNotNull null
            listOfNotNull(title.ifBlank { "一项日程" }, at, status).joinToString("，")
        }.joinToString("\n")
    }

    private fun editableTitle(value: String): String = value.trim().takeUnless {
        it.startsWith("{") || it.startsWith("[") || objectSyntax.containsMatchIn(it) ||
            "```" in it || internalTokens.containsMatchIn(it)
    }.orEmpty()

    private fun parseDateTime(value: String, zone: ZoneId? = null): LocalDateTime? = try {
        val time = OffsetDateTime.parse(value)
        if (zone == null) time.toLocalDateTime() else time.atZoneSameInstant(zone).toLocalDateTime()
    } catch (_: DateTimeParseException) {
        try {
            LocalDateTime.parse(value)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
