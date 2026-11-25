package cn.weiguang.app.home

import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

data class AgendaEntry(val card: AuraCard, val date: LocalDate?, val clock: LocalTime?, val end: ZonedDateTime? = null) {
    fun start(zone: ZoneId): ZonedDateTime? = if (date != null && clock != null) {
        instantAt(card.scheduledAt, zone) ?: date.atTime(clock).atZone(zone)
    } else null
}

data class HomeAgenda(
    val today: List<AgendaEntry>,
    val later: List<AgendaEntry>,
    val unscheduled: List<AgendaEntry>,
    val past: List<ArchivedCard>
)

private val weekday = Regex("(下周|本周|这周|周|星期)([一二三四五六日天])")
private val isoDate = Regex("""\d{4}-\d{2}-\d{2}""")
private val monthDay = Regex("""(?:(\d{4})年)?(\d{1,2})月(\d{1,2})[日号]""")
private val relativeDay = Regex("今天|今日|今晚|明天|明日|明早|后天|昨天|" + weekday.pattern)
private val clockText = Regex("""(?:[01]?\d|2[0-3]):[0-5]\d""")
private val cnClock = Regex("""(?:上午|下午|晚上|明早|今晚)?\d{1,2}点""")
private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")

private fun instantAt(value: String?, zone: ZoneId): ZonedDateTime? {
    if (value.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(value).atZoneSameInstant(zone) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(value).atZone(zone) }.getOrNull()
}

private fun dateIn(text: String, base: LocalDate): LocalDate? {
    isoDate.find(text)?.let { return runCatching { LocalDate.parse(it.value) }.getOrNull() }
    monthDay.find(text)?.destructured?.let { (year, month, day) ->
        return runCatching { LocalDate.of(year.toIntOrNull() ?: base.year, month.toInt(), day.toInt()) }.getOrNull()
    }
    val day = relativeDay.find(text) ?: return null
    return when (day.value) {
        "今天", "今日", "今晚" -> base
        "明天", "明日", "明早" -> base.plusDays(1)
        "后天" -> base.plusDays(2)
        "昨天" -> base.minusDays(1)
        else -> {
            val target = DayOfWeek.of(("一二三四五六日".indexOf(day.value.last()) + 1).let { if (it == 0) 7 else it })
            val monday = base.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            when {
                day.value.startsWith("下周") -> monday.plusWeeks(1).with(TemporalAdjusters.nextOrSame(target))
                day.value.startsWith("本周") || day.value.startsWith("这周") -> monday.with(TemporalAdjusters.nextOrSame(target))
                else -> base.with(TemporalAdjusters.nextOrSame(target))
            }
        }
    }
}

private val timeRange = Regex("""(\d{1,2}:\d{2})\s*(?:—|–|-|至|到)\s*(?:(\d{2}-\d{2})\s+)?(\d{1,2}:\d{2})""")
private val rangeClock = DateTimeFormatter.ofPattern("H:mm")

private fun rangeEnd(card: AuraCard, start: ZonedDateTime): ZonedDateTime? {
    val match = timeRange.find(card.originalText) ?: return null
    val (from, endDay, to) = match.destructured
    return runCatching {
        val fromClock = LocalTime.parse(from, rangeClock)
        val toClock = LocalTime.parse(to, rangeClock)
        val originalDate = Regex("""(\d{2}-\d{2})\s+$from""").find(card.originalText)?.groupValues?.get(1)
        val first = originalDate?.let { MonthDay.parse("--$it").atYear(start.year) } ?: start.toLocalDate()
        val last = endDay.takeIf { it.isNotEmpty() }?.let {
            MonthDay.parse("--$it").atYear(first.year).let { d -> if (d < first) d.plusYears(1) else d }
        } ?: first
        val length = Duration.between(first.atTime(fromClock), last.atTime(toClock))
        start.plus(length).takeIf { it > start }
    }.getOrNull()
}

private fun entry(card: AuraCard, now: ZonedDateTime): AgendaEntry {
    if (card.allDay) {
        fun sourceDate(value: String?): LocalDate? = value?.let {
            runCatching { OffsetDateTime.parse(it).toLocalDate() }.getOrNull()
        }
        return AgendaEntry(card.copy(time = "全天"), sourceDate(card.scheduledAt), null,
            sourceDate(card.endsAt)?.atStartOfDay(now.zone))
    }
    val exact = instantAt(card.scheduledAt, now.zone)
    val captured = instantAt(card.capturedAt, now.zone)?.toLocalDate()
    val base = captured ?: now.toLocalDate()
    // A return weekday follows the outbound date, including trips that start next week.
    val timing = if (card.direction == "return") card.time else card.scheduledAt ?: card.originalText
    val reference = if (card.direction == "return") dateIn(card.originalText, base) ?: base else base
    val explicitClock = clockText.containsMatchIn(timing) || cnClock.containsMatchIn(timing)
    val clock = exact?.toLocalTime()?.withSecond(0)?.withNano(0) ?: if (explicitClock) {
        val (label, _) = AuraFlowEngine.extractTimeMinutes(timing)
        runCatching { LocalTime.parse(label) }.getOrNull()
    } else null
    val date = exact?.toLocalDate() ?: dateIn(timing, reference) ?: captured?.takeIf { clock != null }
    val start = exact ?: if (clock != null && date != null) date.atTime(clock).atZone(now.zone) else null
    val end = instantAt(card.endsAt, now.zone) ?: start?.let { rangeEnd(card, it) }
    return AgendaEntry(card.copy(time = clock?.format(clockFormat) ?: "时间待定"), date, clock, end)
}

fun AuraFlowState.agenda(now: ZonedDateTime): HomeAgenda {
    val day = now.toLocalDate()
    val minute = now.withSecond(0).withNano(0)
    val dayStart = day.atStartOfDay(now.zone)
    val today = mutableListOf<AgendaEntry>()
    val later = mutableListOf<AgendaEntry>()
    val unscheduled = mutableListOf<AgendaEntry>()
    val past = archivedEvents.filter {
        val date = instantAt(it.scheduledAt, now.zone)?.toLocalDate()
        date == null || date == day
    }.toMutableList()
    val entries = (events + listOfNotNull(crossDayRelay)).distinctBy { it.id }.map { entry(it, now) }
        .sortedWith(compareBy<AgendaEntry> { it.date ?: LocalDate.MAX }.thenBy { it.clock ?: LocalTime.MAX }.thenBy { it.card.id })
    for (item in entries) {
        val (rawCard, date, _) = item
        val remind = item.start(now.zone)?.let { !it.isBefore(minute) && !it.isAfter(now.plusHours(12)) } == true
        val card = if (remind) rawCard else rawCard.copy(reminderStrip = null)
        val displayed = item.copy(card = card)
        val start = item.start(now.zone)
        val complete = card.lifecycleStage == LifecycleStage.ARCHIVED
        val completionDay = instantAt(card.completedAt, now.zone)?.toLocalDate()
        when {
            !complete && date != null && date < day && (item.end == null || !item.end.isAfter(dayStart)) -> continue
            complete || (item.end?.let { !it.isAfter(now) } ?: (start != null && date == day && start < minute)) -> {
                if ((completionDay ?: date ?: day) == day || item.end?.toLocalDate() == day) past += ArchivedCard(card.id, card.time, "", card.title,
                    if (complete) "已完成" else if (item.end != null) "已过结束时间" else "已过开始时间", card.sourceWorkId)
            }
            date == null -> unscheduled += displayed
            date > day -> later += displayed
            else -> today += displayed
        }
    }
    return HomeAgenda(today, later, unscheduled, past.sortedBy { it.time })
}

// Only surface labelled values or identifiers actually present in the source.
private val factPatterns = mapOf(
    AuraCategory.TRAVEL to listOf(
        "班次" to Regex("""(?i)(?<![A-Z0-9])((?:[A-Z]{2}|[GDC])\d{2,5})(?![A-Z0-9])"""),
        "登机口" to Regex("""(?i)(?:登机口|GATE)\s*[：:]?\s*([A-Z]?\d{1,3}[A-Z]?)"""),
        "座位" to Regex("""(?i)(?:座位|座号|SEAT)\s*[：:]?\s*((?:\d+车\s*)?\d{1,3}[A-F])""")
    ),
    AuraCategory.DINING to listOf(
        "人数" to Regex("""(\d{1,3}\s*[人位])"""),
        "地点" to Regex("""(?:餐厅|地点|地址)[：:]\s*([^，。；;\n]{1,60})"""),
        "人均" to Regex("""人均\s*[：:]?\s*([¥￥]?\d+(?:\.\d+)?\s*元?)""")
    ),
    AuraCategory.SHOPPING to listOf(
        "购物清单" to Regex("""(?:购物清单|采购清单|要买)[：:]\s*([^\n。；;]{1,120})"""),
        "预算" to Regex("""预算\s*[：:]?\s*([¥￥]?\d+(?:\.\d+)?\s*元?)""")
    ),
    AuraCategory.OFFICE to listOf("地点" to Regex("""(?:地点|会议室)[：:]\s*([^，。；;\n]{1,60})""")),
    AuraCategory.EXPLORE to listOf("目的地" to Regex("""(?:目的地|集合点|地点)[：:]\s*([^，。；;\n]{1,60})"""))
)

fun AuraCard.sourceFacts(): List<Pair<String, String>> = factPatterns[category].orEmpty().mapNotNull { (label, pattern) ->
    pattern.find(originalText)?.groupValues?.get(1)?.trim()?.let { label to it }
}
