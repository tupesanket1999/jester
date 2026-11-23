package io.github.tupesanket1999.jester.logic

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

data class Parsed(val title: String, val due: String? = null, val recur: String? = null, val priority: String? = null)

/**
 * Faithful Kotlin port of the hub's date parser (parseQuickAdd). Used only for the quick-add preview chips:
 * the hub still parses the text itself on save, so the two must agree (same tests in QuickParseTest).
 */
object QuickParse {
    private val WEEKDAYS = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
    private const val WD = "(sun|mon|tue|tues|wed|thu|thur|thurs|fri|sat)(?:day|nesday|rsday|urday|sday)?"
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val I = RegexOption.IGNORE_CASE

    private fun wdIndex(word: String) = WEEKDAYS.indexOfFirst { it.startsWith(word.lowercase().take(3)) }

    fun format(d: LocalDate, h: Int, mi: Int): String =
        String.format(Locale.ROOT, "%04d-%02d-%02d %02d:%02d", d.year, d.monthValue, d.dayOfMonth, h, mi)

    fun parse(input: String, now: Instant, zone: ZoneId): Parsed {
        var s = " " + input.trim().replace(Regex("\\s+"), " ") + " "
        fun take(re: Regex): MatchResult? {
            val m = re.find(s) ?: return null
            s = s.substring(0, m.range.first) + " " + s.substring(m.range.last + 1)
            return m
        }
        val today = LocalDateTime.ofInstant(now, zone)
        val todayDate = today.toLocalDate()
        var date: LocalDate? = null
        var time: Pair<Int, Int>? = null
        var recur: String? = null
        var priority: String? = null
        var relative: Instant? = null

        if (take(Regex("""\s(?:!!|urgent|asap)(?=\s)""", I)) != null) priority = "urgent"
        else if (take(Regex("""\s(?:!|important)(?=\s)""", I)) != null) priority = "important"

        val every = take(Regex("""\severy\s+$WD(?=\s)""", I))
        if (every != null) recur = "every " + WEEKDAYS[wdIndex(every.groupValues[1])]
        else if (take(Regex("""\s(?:every\s+day|daily)(?=\s)""", I)) != null) recur = "every day"
        else if (take(Regex("""\s(?:every\s+weekday|weekdays)(?=\s)""", I)) != null) recur = "every weekday"
        else if (take(Regex("""\s(?:every\s+week|weekly)(?=\s)""", I)) != null) recur = "every week"
        else if (take(Regex("""\s(?:every\s+month|monthly)(?=\s)""", I)) != null) recur = "every month"

        take(Regex("""\sin\s+(\d+)\s*(m|min|mins|minute|minutes|h|hr|hrs|hour|hours|d|day|days)(?=\s)""", I))?.let { m ->
            val n = m.groupValues[1].toLong()
            val u = m.groupValues[2].lowercase()
            val ms = if (u.startsWith("d")) n * 86_400_000 else if (u.startsWith("h")) n * 3_600_000 else n * 60_000
            relative = now.plusMillis(ms)
        }

        var m: MatchResult?
        m = take(Regex("""\s(\d{4})-(\d{2})-(\d{2})(?=\s)"""))
        if (m != null) {
            date = runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
        } else {
            m = take(Regex("""\s(?:on\s+)?(\d{1,2})\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*(?=\s)""", I))
                ?: take(Regex("""\s(?:on\s+)?(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\s+(\d{1,2})(?=\s)""", I))
            if (m != null) {
                val first = m.groupValues[1]
                val (dd, mon) = if (first[0].isDigit()) first.toInt() to m.groupValues[2] else m.groupValues[2].toInt() to first
                val mo = MONTHS.indexOf(mon.lowercase().take(3)) + 1
                var y = today.year
                if (mo < today.monthValue || (mo == today.monthValue && dd < today.dayOfMonth)) y++
                date = runCatching { LocalDate.of(y, mo, dd) }.getOrNull()
            } else if (take(Regex("""\stoday(?=\s)""", I)) != null) {
                date = todayDate
            } else if (take(Regex("""\stonight(?=\s)""", I)) != null) {
                date = todayDate; time = 20 to 0
            } else if (take(Regex("""\stomorrow(?=\s)""", I)) != null) {
                date = todayDate.plusDays(1)
            } else {
                m = take(Regex("""\s(?:on\s+)?(next\s+)?$WD(?=\s)""", I))
                if (m != null) {
                    val target = wdIndex(m.groupValues[2])
                    val todayWd = today.dayOfWeek.value % 7 // Sunday = 0, as in JS
                    var delta = (target - todayWd + 7) % 7
                    if (delta == 0 || m.groupValues[1].isNotEmpty()) delta = if (delta == 0) 7 else delta
                    date = todayDate.plusDays(delta.toLong())
                }
            }
        }

        if (time == null) {
            m = take(Regex("""\s(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)(?=\s)""", I))
            if (m != null) {
                var h = m.groupValues[1].toInt() % 12
                if (m.groupValues[3].lowercase() == "pm") h += 12
                time = h to (m.groupValues[2].ifEmpty { "0" }.toInt())
            } else {
                m = take(Regex("""\s(?:at\s+)?([01]?\d|2[0-3]):([0-5]\d)(?=\s)"""))
                if (m != null) time = m.groupValues[1].toInt() to m.groupValues[2].toInt()
                else {
                    m = take(Regex("""\sat\s+([01]?\d|2[0-3])(?=\s)""", I))
                    if (m != null) time = m.groupValues[1].toInt() to 0
                    else if (take(Regex("""\s(?:at\s+)?noon(?=\s)""", I)) != null) time = 12 to 0
                    else if (take(Regex("""\s(?:at\s+)?midnight(?=\s)""", I)) != null) time = 0 to 0
                    else if (take(Regex("""\s(?:in\s+the\s+)?morning(?=\s)""", I)) != null) time = 9 to 0
                    else if (take(Regex("""\s(?:in\s+the\s+)?evening(?=\s)""", I)) != null) time = 18 to 0
                }
            }
        }

        var due: String? = null
        val rel = relative
        if (rel != null) {
            val l = LocalDateTime.ofInstant(rel, zone)
            due = format(l.toLocalDate(), l.hour, l.minute)
        } else if (date != null || time != null) {
            var d = date ?: todayDate
            val t = time ?: (9 to 0)
            if (date == null && (t.first < today.hour || (t.first == today.hour && t.second <= today.minute))) d = d.plusDays(1)
            due = format(d, t.first, t.second)
        } else if (recur != null) {
            due = format(todayDate.plusDays(1), 9, 0)
        }

        val title = s
            .replaceFirst(Regex("""^\s*(?:hey\s+bot,?\s+)?(?:please\s+)?(?:remind\s+me\s+(?:to\s+)?|reminder:?\s+|todo:?\s+|add\s+)""", I), " ")
            .replaceFirst(Regex("""\s+(?:at|on|by)\s*$""", I), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return Parsed(title.ifEmpty { input.trim() }, due, recur, priority)
    }

    data class Chip(val label: String, val hot: Boolean = false)

    /** What the preview shows under the quick-add field: when, time, repeat, priority. Empty when nothing was understood. */
    fun chips(p: Parsed, now: Instant, zone: ZoneId): List<Chip> {
        val out = ArrayList<Chip>()
        val today = LocalDateTime.ofInstant(now, zone).toLocalDate()
        p.recur?.let { out += Chip("Repeats $it") }
        p.due?.let { due ->
            val d = runCatching { LocalDate.parse(due.substring(0, 10)) }.getOrNull()
            if (d != null && p.recur == null) {
                val days = ChronoUnit.DAYS.between(today, d)
                out += Chip(when {
                    days == 0L -> "Today"
                    days == 1L -> "Tomorrow"
                    days in 2..6 -> d.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
                    else -> "${d.dayOfMonth} ${MONTHS[d.monthValue - 1].replaceFirstChar { it.uppercase() }}" + if (d.year != today.year) " ${d.year}" else ""
                })
            }
            out += Chip(due.substring(11))
        }
        when (p.priority) {
            "urgent" -> out += Chip("Urgent: nags until done", hot = true)
            "important" -> out += Chip("Important", hot = true)
        }
        return out
    }

}
