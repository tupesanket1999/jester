package io.github.tupesanket1999.jester.logic

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The hub's machine tag on reminder pushes: `tt:<id>@<YYYY-MM-DDTHH:mm>#<level>` (see docs/HUB_API.md). */
data class PushTag(val id: String, val due: String, val level: Int) {
    companion object {
        private val RE = Regex("""^tt:([A-Za-z0-9_.:-]{1,64})@(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})#(\d{1,2})$""")
        fun parse(tags: List<String>): PushTag? = tags.firstNotNullOfOrNull { t ->
            RE.matchEntire(t.trim())?.let { m -> PushTag(m.groupValues[1], m.groupValues[2] + " " + m.groupValues[3], m.groupValues[4].toInt()) }
        }
    }
}

object TextClean {
    /** Drop emoji / pictographs / variation selectors (watch fonts draw them as boxes; TTS reads their names). */
    fun noEmoji(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val t = Character.getType(cp)
            val drop = cp >= 0x1F000 || cp in 0x2190..0x2BFF || cp == 0x203C || cp == 0x2049 || cp in 0xFE00..0xFE0F || cp == 0x200D || cp == 0x20E3 ||
                t == Character.OTHER_SYMBOL.toInt() || t == Character.SURROGATE.toInt() || t == Character.PRIVATE_USE.toInt()
            if (!drop) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString().replace(Regex("""\s+"""), " ").trim()
    }
}

/** Notification copy that reads well on a watch: plain text, short, no emoji. */
object NotifText {
    private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val DUE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun title(title: String, urgent: Boolean): String {
        val t = TextClean.noEmoji(title).ifEmpty { "Reminder" }
        val short = if (t.length > 48) t.take(47).trimEnd() + "…" else t
        return (if (urgent) "URGENT: " else "Reminder: ") + short
    }

    fun ordinal(n: Int): String = n.toString() + when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }

    /** "now · 1:52 PM", "in 5 min · 2:00 PM", "20 min late · 3rd ping", "2 h late". */
    fun body(due: String?, level: Int, now: Instant, zone: ZoneId): String {
        val parts = ArrayList<String>()
        val at = due?.let { runCatching { LocalDateTime.parse(it.trim(), DUE).atZone(zone).toInstant() }.getOrNull() }
        if (at != null) {
            val clock = CLOCK.format(at.atZone(zone))
            val mins = Duration.between(at, now).toMinutes()
            parts += when {
                mins < -59 -> "at $clock"
                mins < -1 -> "in ${-mins} min · $clock"
                mins <= 1 -> "now · $clock"
                mins < 60 -> "$mins min late"
                mins < 48 * 60 -> "${mins / 60} h late"
                else -> "${mins / 1440} days late"
            }
        } else parts += "Reminder"
        if (level >= 2) parts += ordinal(level) + " ping"
        return parts.joinToString(" · ")
    }
}

/** A reply typed or picked on the watch / in the notification. */
sealed class ReplyAction {
    object Done : ReplyAction()
    data class Snooze(val minutes: Int) : ReplyAction()
    object Unknown : ReplyAction()
}

object ReplyParser {
    private val DONE = Regex("""^(done|did it|finished|ok|okay|yes|yep|ya|✓|✔|✅|👍)[.! ]*$""", RegexOption.IGNORE_CASE)
    private val HOURS = Regex("""^(\d{1,3})\s*(h|hr|hrs|hour|hours)$""", RegexOption.IGNORE_CASE)
    private val MINS = Regex("""^(?:snooze\s*)?(\d{1,6})\s*(m|min|mins|minute|minutes)?$""", RegexOption.IGNORE_CASE)

    fun parse(text: String, now: LocalDateTime): ReplyAction {
        val t = text.trim().lowercase().removePrefix("snooze ").trim()
        if (t.isEmpty()) return ReplyAction.Unknown
        if (DONE.matches(t)) return ReplyAction.Done
        if (t == "snooze") return ReplyAction.Snooze(15)
        if (t == "tonight") return ReplyAction.Snooze(until(now, 21, 0, sameDayOnly = true))
        if (t == "tomorrow") return ReplyAction.Snooze(minutesBetween(now, now.toLocalDate().plusDays(1).atTime(9, 0)))
        HOURS.matchEntire(t)?.let { return ReplyAction.Snooze(clamp(it.groupValues[1].toInt() * 60)) }
        MINS.matchEntire(t)?.let { return ReplyAction.Snooze(clamp(it.groupValues[1].toInt())) }
        return ReplyAction.Unknown
    }

    /** Minutes until the next [h]:[mi]; "tonight" after 21:00 means one more hour. */
    fun until(now: LocalDateTime, h: Int, mi: Int, sameDayOnly: Boolean = false): Int {
        var target = now.toLocalDate().atTime(h, mi)
        if (!target.isAfter(now)) {
            if (sameDayOnly) return 60
            target = target.plusDays(1)
        }
        return minutesBetween(now, target)
    }

    private fun minutesBetween(a: LocalDateTime, b: LocalDateTime) = clamp(Duration.between(a, b).toMinutes().toInt())
    private fun clamp(m: Int) = m.coerceIn(1, 7 * 1440)
}
