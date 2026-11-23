package io.github.tupesanket1999.jester.notify

import io.github.tupesanket1999.jester.R
import io.github.tupesanket1999.jester.JesterApp
import io.github.tupesanket1999.jester.logic.NotifText
import io.github.tupesanket1999.jester.logic.ReplyAction
import io.github.tupesanket1999.jester.logic.ReplyParser
import io.github.tupesanket1999.jester.service.RobotService
import io.github.tupesanket1999.jester.ui.AlarmActivity
import io.github.tupesanket1999.jester.ui.MainActivity
import io.github.tupesanket1999.jester.ui.SheetActivity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Notification channels: a silent MIN channel for the always-on service, HIGH for reminders (these are what a watch
 * mirroring app forwards to the watch), DEFAULT for info (hub nudges, chat replies).
 * Reminder copy is watch-friendly: plain text, no emoji ("URGENT: x", "now · 1:52 PM · 3rd ping").
 */
object Notifier {
    const val CH_SERVICE = "robot"
    const val CH_REMINDERS = "reminders"
    const val CH_INFO = "info"
    const val SERVICE_ID = 1
    const val KEY_REPLY = "jester_reply"
    val QUICK_REPLIES = arrayOf<CharSequence>("Done", "30 min", "Tonight")

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Jester (always on)", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Keeps Jester and reminder push running. Silent."
            setShowBadge(false); setSound(null, null); enableVibration(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Reminders with Done / Snooze / reply. Allow these in your watch app to buzz the watch."
            enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_INFO, "Messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Hub nudges (counts only) and Jester's chat replies."
        })
    }

    private fun pi(ctx: Context, code: Int, intent: Intent, activity: Boolean = false, mutable: Boolean = false): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable && Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        return if (activity) PendingIntent.getActivity(ctx, code, intent, flags) else PendingIntent.getBroadcast(ctx, code, intent, flags)
    }

    fun serviceNotification(ctx: Context, robotShown: Boolean): Notification {
        val open = pi(ctx, 10, Intent(ctx, MainActivity::class.java), activity = true)
        val toggle = Intent(ctx, RobotService::class.java).setAction(if (robotShown) RobotService.ACTION_HIDE else RobotService.ACTION_SHOW)
        val togglePi = PendingIntent.getService(ctx, 11, toggle, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_robot)
            .setContentTitle("Jester is on")
            .setContentText(if (robotShown) "Watching your reminders" else "Jester hidden; reminders still on")
            .setOngoing(true).setSilent(true).setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, if (robotShown) "Hide Jester" else "Show Jester", togglePi)
            .build()
    }

    private fun reminderNotifId(id: String) = ("rem:$id").hashCode()

    fun canFullScreen(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    /**
     * A reminder with Done / Snooze 15m / Reply (watch quick replies "Done", "30 min", "Tonight", or free text).
     * [level] = escalation level (1 = first ring). Urgent alerts also open the full-screen alarm.
     */
    fun reminder(ctx: Context, id: String, title: String, due: String?, alert: Boolean, urgent: Boolean = false, level: Int = 1) {
        val n = reminderNotifId(id)
        fun action(a: String) = pi(ctx, n * 4 + a.length, Intent(ctx, ActionReceiver::class.java).setAction(a).putExtra("id", id))
        val open = pi(ctx, n, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), activity = true)
        val replyPi = pi(ctx, n * 4 + 3, Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.REPLY).putExtra("id", id), mutable = true)
        val remote = RemoteInput.Builder(KEY_REPLY).setLabel("Done, 30 min, tonight…").setChoices(QUICK_REPLIES).build()
        val replyAction = NotificationCompat.Action.Builder(0, "Reply", replyPi)
            .addRemoteInput(remote).setAllowGeneratedReplies(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).build()
        val body = NotifText.body(due, level, Instant.now(), ZoneId.systemDefault())
        val b = NotificationCompat.Builder(ctx, CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_robot)
            .setContentTitle(NotifText.title(title, urgent))
            .setContentText(body)
            .setCategory(if (urgent) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (urgent) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setContentIntent(open)
            .addAction(0, "Done", action(ActionReceiver.DONE))
            .addAction(0, "Snooze 15m", action(ActionReceiver.SNOOZE))
            .addAction(replyAction)
        if (!alert) b.setSilent(true) else b.setDefaults(NotificationCompat.DEFAULT_ALL)
        if (alert && urgent && canFullScreen(ctx)) {
            val full = Intent(ctx, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra("id", id).putExtra("title", title).putExtra("due", due).putExtra("level", level)
            b.setFullScreenIntent(pi(ctx, n * 4 + 1, full, activity = true), true)
        }
        post(ctx, n, b.build())
        if (alert) JesterApp.get(ctx).voice.let { it.maybeSpeak(it.reminderLine(title, urgent), urgent = urgent) }
    }

    fun cancelReminder(ctx: Context, id: String) = NotificationManagerCompat.from(ctx).cancel(reminderNotifId(id))

    fun info(ctx: Context, title: String, text: String, tag: String = "info") {
        val open = pi(ctx, 20, Intent(ctx, SheetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), activity = true)
        post(ctx, (tag + title).hashCode(), NotificationCompat.Builder(ctx, CH_INFO)
            .setSmallIcon(R.drawable.ic_stat_robot)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true).setContentIntent(open).build())
    }

    private fun post(ctx: Context, id: Int, n: Notification) {
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (e: SecurityException) { /* notification permission not granted yet */ }
    }
}

/** Done / Snooze / Reply on our reminder notifications (phone or watch). Writes go through the offline queue. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val app = JesterApp.get(ctx)
        val pending = goAsync()
        app.scope.launchCatching({ pending.finish() }) {
            when (intent.action) {
                DONE -> app.done(id)
                SNOOZE -> app.snooze(id, 15)
                REPLY -> {
                    val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString() ?: ""
                    when (val a = ReplyParser.parse(text, LocalDateTime.now())) {
                        ReplyAction.Done -> app.done(id)
                        is ReplyAction.Snooze -> app.snooze(id, a.minutes)
                        ReplyAction.Unknown -> {
                            Notifier.cancelReminder(ctx, id)
                            Notifier.info(ctx, "Jester", "Didn't get \"${text.take(40)}\". Reply Done, 30 min or Tonight.", tag = "reply")
                        }
                    }
                }
            }
            app.drainNow()
        }
    }

    companion object {
        const val DONE = "io.github.tupesanket1999.jester.DONE"
        const val SNOOZE = "io.github.tupesanket1999.jester.SNOOZE"
        const val REPLY = "io.github.tupesanket1999.jester.REPLY"
    }
}

/** Run work for a BroadcastReceiver.goAsync(); errors never crash the receiver (queued work is retried later). */
fun CoroutineScope.launchCatching(onEnd: () -> Unit, block: suspend () -> Unit) =
    launch { try { block() } catch (e: Exception) { /* queued work survives; retried later */ } finally { onEnd() } }
