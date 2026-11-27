package cn.weiguang.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val clueDate = DateTimeFormatter.ofPattern("uuuu年M月d日 HH:mm", Locale.CHINA)

internal fun clueDisplayText(raw: String): String {
    val text = raw.trim()
    if (!text.startsWith('{') && !text.startsWith('[')) return raw
    val structured = runCatching {
        val reader = JSONTokener(text)
        reader.nextValue().takeIf { reader.nextClean() == '\u0000' }
    }.getOrNull()
    if (structured is JSONArray && !text.drop(1).trimStart().startsWith('{')) return raw
    if (structured !is JSONObject && structured !is JSONArray) return raw
    val changes = (structured as? JSONObject)?.optJSONArray("schedule_changes")
    val descriptions = (0 until (changes?.length() ?: 0)).mapNotNull { index ->
        val change = changes?.optJSONObject(index) ?: return@mapNotNull null
        val title = (change.opt("title") as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@mapNotNull null
        val at = runCatching { OffsetDateTime.parse(change.optString("at")).format(clueDate) }.getOrNull()
        if (at == null) title else "$title 安排在 $at"
    }
    return descriptions.joinToString("\n").ifEmpty { "这条线索暂无可显示的摘要。" }
}
