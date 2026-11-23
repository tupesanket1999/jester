package io.github.tupesanket1999.jester.reminders

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The fields of a hub Task that reminders need. */
data class TaskLite(val id: String, val title: String, val done: Boolean, val due: String?, val priority: String? = null, val file: String = "")

data class Reminder(val id: String, val title: String, val due: String, val atMs: Long) {
    val key get() = Reminders.key(id, due)
}

/**
 * Offline reminder backup. Pure logic:
 *  - [plan]: which cached tasks get a local exact alarm (next 24 h, not done).
 *  - [ShownLog]: what was already shown (push or alarm), keyed by id+due, so the backup alarm never duplicates a push,
 *    while a push escalation (higher level) still buzzes.
 * Due times are floating local "YYYY-MM-DD HH:mm", interpreted in the device's current timezone.
 */
object Reminders {
    const val WINDOW_MS = 24 * 3600_000L
    /** A reminder that came due while the phone was off/offline still fires if it is at most this late. */
    const val GRACE_MS = 15 * 60_000L
    private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun key(id: String, due: String) = "$id@$due"

    /** Floating local time -> epoch ms in [zone]. DST gaps move forward (java.time rule); null if unparseable. */
    fun dueToEpochMs(due: String, zone: ZoneId): Long? = try {
        LocalDateTime.parse(due.trim(), FMT).atZone(zone).toInstant().toEpochMilli()
    } catch (e: Exception) { null }

    fun plan(tasks: List<TaskLite>, nowMs: Long, zone: ZoneId, shown: ShownLog? = null): List<Reminder> =
        tasks.asSequence()
            .filter { !it.done && !it.due.isNullOrBlank() }
            .mapNotNull { t -> dueToEpochMs(t.due!!, zone)?.let { Reminder(t.id, t.title, t.due, it) } }
            .filter { it.atMs in (nowMs - GRACE_MS)..(nowMs + WINDOW_MS) }
            .filter { shown == null || !shown.wasShown(it.key) }
            .sortedBy { it.atMs }
            .toList()

    /** Alarm fired: post a local notification only if neither push nor an earlier alarm showed this id+due. */
    fun shouldPostAlarm(id: String, due: String, shown: ShownLog, cached: List<TaskLite>?): Boolean {
        if (shown.wasShown(key(id, due))) return false
        val t = cached?.firstOrNull { it.id == id } ?: return true // unknown to the cache: be safe, remind
        return !t.done && t.due == due // done or moved since the alarm was set: skip
    }

    /**
     * Push arrived for id+due at escalation [level] (1 = first ring). Returns true if the phone should
     * buzz (new, or a higher level than what was shown), false for a silent update.
     */
    fun shouldAlertPush(id: String, due: String?, level: Int, shown: ShownLog): Boolean {
        if (due == null) return true
        return level > shown.level(key(id, due))
    }
}

/** key -> (highest level shown, when). Serialised as lines "key\tlevel\tms". */
class ShownLog(private val entries: MutableMap<String, Pair<Int, Long>> = LinkedHashMap()) {
    fun wasShown(key: String) = entries.containsKey(key)
    fun level(key: String) = entries[key]?.first ?: 0

    fun mark(key: String, level: Int, nowMs: Long) {
        val prev = entries[key]?.first ?: 0
        entries[key] = maxOf(prev, level) to nowMs
    }

    /** Forget entries older than [keepMs] (default 3 days) so the file stays small. */
    fun prune(nowMs: Long, keepMs: Long = 3 * 24 * 3600_000L) {
        entries.entries.removeIf { nowMs - it.value.second > keepMs }
    }

    fun serialize(): String = entries.entries.joinToString("\n") { "${it.key}\t${it.value.first}\t${it.value.second}" }

    companion object {
        fun parse(s: String?): ShownLog {
            val m = LinkedHashMap<String, Pair<Int, Long>>()
            s?.lineSequence()?.forEach { line ->
                val p = line.split("\t")
                if (p.size == 3) { val l = p[1].toIntOrNull(); val t = p[2].toLongOrNull(); if (l != null && t != null) m[p[0]] = l to t }
            }
            return ShownLog(m)
        }
    }
}
