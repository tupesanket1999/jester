package io.github.tupesanket1999.jester.reminders

import io.github.tupesanket1999.jester.RingItem
import io.github.tupesanket1999.jester.JesterApp
import io.github.tupesanket1999.jester.notify.Notifier
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.flow.update
import java.io.File
import java.time.ZoneId

/**
 * Local exact alarms for the next 24 h of cached reminders (backup for when push is late or the phone is offline).
 * Re-planned whenever tasks refresh, on boot, on timezone change and when the exact-alarm permission changes.
 */
object Alarms {
    private const val FILE = "alarms.txt" // request codes currently scheduled, to cancel stale ones

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun reschedule(ctx: Context, tasks: List<TaskLite>) {
        val app = JesterApp.get(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        val plan = app.withShown { Reminders.plan(tasks, now, ZoneId.systemDefault(), it) }
        val f = File(ctx.filesDir, FILE)
        val old = runCatching { f.readLines().mapNotNull { it.toIntOrNull() } }.getOrDefault(emptyList())
        val codes = plan.map { it.key.hashCode() }
        (old - codes.toSet()).forEach { code -> am.cancel(intent(ctx, code, null)) }
        for (r in plan) {
            val pi = intent(ctx, r.key.hashCode(), r)
            val at = maxOf(r.atMs, now + 1000)
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) // inexact fallback until permission is granted
        }
        f.writeText(codes.joinToString("\n"))
    }

    private fun intent(ctx: Context, code: Int, r: Reminder?): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction("io.github.tupesanket1999.jester.ALARM")
        if (r != null) i.putExtra("id", r.id).putExtra("title", r.title).putExtra("due", r.due)
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

/** An exact alarm fired: show the reminder only if push has not already shown this id+due. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val due = intent.getStringExtra("due") ?: return
        val title = intent.getStringExtra("title") ?: "Reminder"
        val app = JesterApp.get(ctx)
        val post = app.withShown { log ->
            Reminders.shouldPostAlarm(id, due, log, app.loadCachedTasks()).also { if (it) log.mark(Reminders.key(id, due), 1, System.currentTimeMillis()) }
        }
        if (!post) return
        val urgent = app.tasks.value.firstOrNull { it.id == id }?.priority == "urgent"
        Notifier.reminder(ctx, id, title, due, alert = true, urgent = urgent)
        // shown in the bubble until Done/Snooze, or until the hub answers again (it is authoritative)
        app.ringing.update { l -> if (l.any { it.id == id }) l else l + RingItem(id, title, 0, due) }
    }
}
