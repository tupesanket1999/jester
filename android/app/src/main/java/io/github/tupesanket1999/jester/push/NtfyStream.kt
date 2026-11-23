package io.github.tupesanket1999.jester.push

import io.github.tupesanket1999.jester.Hub
import io.github.tupesanket1999.jester.RingItem
import io.github.tupesanket1999.jester.JesterApp
import io.github.tupesanket1999.jester.notify.Notifier
import io.github.tupesanket1999.jester.reminders.Reminders
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Subscribes to the hub's ntfy topic over ntfy's HTTP JSON stream (`<server>/<topic>/json`), reconnecting with
 * backoff, resuming with `since=<last id>` so nothing published while offline is lost.
 * Shows our own notifications (Done / Snooze through the hub API) instead of the hub's signed links.
 */
class NtfyStream(private val app: JesterApp) {
    @Volatile var connected = false
        private set
    @Volatile private var conn: HttpURLConnection? = null

    /** Unblocks a pending read (blocking IO ignores coroutine cancellation). */
    fun stop() { conn?.disconnect() }

    suspend fun run() {
        var backoff = 2_000L
        while (coroutineContext.isActive) {
            val topic = app.prefs.ntfyTopic
            if (topic.isEmpty()) { connected = false; delay(30_000); continue }
            val opened = try { streamOnce(topic) } catch (e: Exception) { false }
            connected = false
            backoff = if (opened) 2_000L else minOf(backoff * 2, 5 * 60_000L)
            delay(backoff)
        }
    }

    /** Returns true if the connection opened (so the next retry starts with a short backoff). */
    private suspend fun streamOnce(topic: String): Boolean {
        val since = app.prefs.ntfyLastId.let { if (it.isEmpty()) "" else "?since=" + Hub.enc(it) }
        val c = URL("${app.prefs.ntfyServer}/${Hub.enc(topic)}/json$since").openConnection() as HttpURLConnection
        conn = c
        c.connectTimeout = 15_000
        c.readTimeout = 90_000 // ntfy sends a keepalive every ~45 s
        try {
            if (c.responseCode != 200) return false
            connected = true
            c.inputStream.bufferedReader().use { r ->
                while (coroutineContext.isActive) {
                    val line = r.readLine() ?: break
                    val m = NtfyMessage.parse(line) ?: continue
                    if (m.event != "message") continue
                    handle(m)
                    if (m.id.isNotEmpty()) app.prefs.ntfyLastId = m.id
                }
            }
            return true
        } finally {
            c.disconnect()
        }
    }

    private fun handle(m: NtfyMessage) {
        val id = m.taskId
        if (id == null) {
            // work nudges (counts only) and other hub messages
            Notifier.info(app, m.title.ifEmpty { "Jester" }, m.message.ifEmpty { m.title }, tag = m.id)
            return
        }
        // Find the due time (dedupe key) from the hub's ringing list, else the cached tasks.
        app.refreshRinging()
        val ring = app.ringing.value.firstOrNull { it.id == id }
        val due = m.tag?.due ?: ring?.due ?: app.tasks.value.firstOrNull { it.id == id }?.due
        val level = maxOf(m.level, ring?.level ?: 1)
        val alert = app.withShown { log ->
            Reminders.shouldAlertPush(id, due, level, log).also { if (due != null) log.mark(Reminders.key(id, due), level, System.currentTimeMillis()) }
        }
        val urgent = level >= 3 || m.priority >= 5 || app.tasks.value.firstOrNull { it.id == id }?.priority == "urgent"
        Notifier.reminder(app, id, m.cleanTitle, due, alert = alert, urgent = urgent, level = level)
        if (ring == null) app.ringing.update { l -> if (l.any { it.id == id }) l else l + RingItem(id, m.cleanTitle, level, due) }
    }
}
