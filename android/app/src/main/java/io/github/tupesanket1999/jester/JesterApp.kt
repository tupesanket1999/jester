package io.github.tupesanket1999.jester

import io.github.tupesanket1999.jester.notify.Notifier
import io.github.tupesanket1999.jester.queue.WriteQueue
import io.github.tupesanket1999.jester.reminders.Alarms
import io.github.tupesanket1999.jester.reminders.ShownLog
import io.github.tupesanket1999.jester.reminders.TaskLite
import io.github.tupesanket1999.jester.update.Updater
import io.github.tupesanket1999.jester.voice.Voice
import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.TimeZone

/** What the hub's AI did for a chat message (shown as chips with Undo where possible). */
data class ChatAct(val kind: String, val id: String?, val title: String?)

data class ChatLine(
    val fromMe: Boolean, val text: String, val pending: Boolean = false, val opId: String? = null,
    val acts: List<ChatAct> = emptyList(), val at: Long = System.currentTimeMillis(),
)

/** App-wide state and the few operations every screen, receiver and the service share. */
class JesterApp : Application() {
    lateinit var prefs: Prefs
    lateinit var hub: Hub
    lateinit var queue: WriteQueue
    lateinit var voice: Voice
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Reminders that are ringing now (hub /api/ringing + local alarms), shown in the robot's bubble. */
    val ringing = MutableStateFlow<List<RingItem>>(emptyList())
    val chat = MutableStateFlow<List<ChatLine>>(emptyList())
    val tasks = MutableStateFlow<List<TaskLite>>(emptyList())
    /** Bumped when the bot pack changes so the overlay reloads it. */
    val botVersion = MutableStateFlow(0)
    @Volatile var sheetVisible = false
    /** Set when the last chat message was spoken (mic): Jester reads the reply aloud. */
    @Volatile var speakNextReply = false
    /** A one-off line for the floating robot's bubble (battery humour etc.), cleared by the overlay. */
    val robotQuip = MutableStateFlow<String?>(null)
    /** Bumped when look prefs change so every screen recomposes with the new outfit. */
    val lookVersion = MutableStateFlow(0)

    private val shownLock = Any()

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        hub = Hub(prefs)
        queue = WriteQueue(FileQueueStore(File(filesDir, "queue.json")))
        voice = Voice(this, prefs)
        tasks.value = loadCachedTasks()
        Notifier.createChannels(this)
        scope.launch { runCatching { Updater.cleanup(this@JesterApp) } } // old downloaded APKs
    }

    // ---- writes: always through the queue, so nothing typed is lost ----

    fun enqueue(method: String, path: String, body: String?, coalesceKey: String? = null): WriteQueue.Op {
        val op = queue.enqueue(method, path, body, System.currentTimeMillis(), coalesceKey)
        kick()
        return op
    }

    /**
     * Text from the chat sheet. Always /api/chat: the hub answers with AI when it has it and falls back to plain
     * inbox capture by itself when it does not (sending to /api/inbox here turned "how are you" into todos).
     */
    fun sendText(text: String) {
        val op = queue.enqueue("POST", "/api/chat", Hub.textBody(text), System.currentTimeMillis())
        chat.update { (it + ChatLine(true, text, pending = true, opId = op.id)).takeLast(40) }
        kick()
    }

    fun capture(text: String) = enqueue("POST", "/api/inbox", Hub.textBody(text))

    fun done(id: String) {
        enqueue("POST", Hub.donePath(id), "{}")
        ringing.update { l -> l.filter { it.id != id } }
        tasks.update { l -> l.map { if (it.id == id) it.copy(done = true) else it } }
        Notifier.cancelReminder(this, id)
    }

    fun snooze(id: String, minutes: Int = 15) {
        enqueue("POST", Hub.snoozePath(id), JSONObject().put("minutes", minutes).toString())
        ringing.update { l -> l.filter { it.id != id } }
        Notifier.cancelReminder(this, id)
        // the local backup alarm for this id+due must not fire at the old time
        tasks.value.firstOrNull { it.id == id }?.due?.let { due ->
            withShown { it.mark(io.github.tupesanket1999.jester.reminders.Reminders.key(id, due), 1, System.currentTimeMillis()) }
        }
    }

    /** Focus timer: the hub adds a "focus done" reminder at now + minutes. */
    fun focus(minutes: Int, label: String? = null) =
        enqueue("POST", "/api/focus", JSONObject().put("minutes", minutes).apply { label?.let { put("label", it) } }.toString())

    fun reportTimezone() =
        enqueue("POST", "/api/devices/me", JSONObject().put("tz", TimeZone.getDefault().id).toString(), coalesceKey = "tz")

    fun kick() { scope.launch { drainNow() } }

    fun drainNow(): Int = queue.drain(System.currentTimeMillis(), { hub.call(it.method, it.path, it.body) }) { op, body -> onSent(op, body) }

    private fun onSent(op: WriteQueue.Op, body: String?) {
        val json = runCatching { JSONObject(body ?: "{}") }.getOrNull() ?: JSONObject()
        val reply: String? = when (op.path) {
            "/api/chat" -> json.optString("reply").ifEmpty { null }
            "/api/inbox" -> json.optJSONObject("task")?.let { t ->
                "Saved: " + t.optString("title") + (t.optString("due").takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
            }
            else -> null
        }
        val acts = json.optJSONArray("actions")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }.map { o ->
                ChatAct(o.optString("kind"), o.optString("id").ifEmpty { null }, o.optString("title").ifEmpty { null })
            }
        } ?: emptyList()
        val inChat = chat.value.any { it.opId == op.id } // false if the process restarted while it was queued
        chat.update { lines -> lines.map { if (it.opId == op.id) it.copy(pending = false) else it } }
        if (reply != null && inChat) chat.update { (it + ChatLine(false, reply, acts = acts)).takeLast(40) }
        if (reply != null && op.path == "/api/chat") {
            if (speakNextReply) { speakNextReply = false; voice.maybeSpeak(reply, explicit = true) }
            if (!sheetVisible || !inChat) Notifier.info(this, "Jester", reply)
        }
        if (acts.isNotEmpty()) scope.launch { refreshTasks() }
    }

    // ---- reads: refreshed by the service; cached for offline ----

    fun refreshTasks(): Boolean {
        val t = hub.tasks() ?: return false
        tasks.value = t
        File(filesDir, "tasks.json").writeText(tasksToJson(t))
        Alarms.reschedule(this, t)
        return true
    }

    fun refreshRinging(): Boolean {
        // When the hub answers it is authoritative; locally fired alarms only stay in the bubble while it is unreachable.
        ringing.value = hub.ringing() ?: return false
        return true
    }

    fun refreshStatus(): Boolean {
        val s = hub.status() ?: return false
        val ai = s.optJSONObject("ai")
        prefs.aiConfigured = ai?.optBoolean("configured") == true
        prefs.aiUsed = ai?.optInt("usedToday", -1) ?: -1
        prefs.aiCap = ai?.optInt("capToday", -1) ?: -1
        prefs.aiProvider = ai?.optString("provider") ?: ""
        prefs.macState = s.optJSONObject("mac")?.optString("state") ?: ""
        lookVersion.update { it + 1 }
        return true
    }

    fun loadCachedTasks(): List<TaskLite> = runCatching {
        Hub.parseTasks(JSONArray(File(filesDir, "tasks.json").readText()))
    }.getOrDefault(emptyList())

    /** Read-modify-write of the shown log (push + alarm dedupe), serialised. */
    fun <T> withShown(block: (ShownLog) -> T): T = synchronized(shownLock) {
        val f = File(filesDir, "shown.txt")
        val log = ShownLog.parse(runCatching { f.readText() }.getOrNull())
        val out = block(log)
        log.prune(System.currentTimeMillis())
        f.writeText(log.serialize())
        out
    }

    companion object {
        fun get(ctx: Context) = ctx.applicationContext as JesterApp

        fun tasksToJson(t: List<TaskLite>): String = JSONArray().apply {
            t.forEach { x ->
                put(JSONObject().put("id", x.id).put("title", x.title).put("done", x.done).put("file", x.file)
                    .apply { x.due?.let { put("due", it) }; x.priority?.let { put("priority", it) } })
            }
        }.toString()
    }
}

/** Queue persisted as one small JSON file, written atomically (temp file + rename). */
class FileQueueStore(private val file: File) : WriteQueue.Store {
    override fun load(): Pair<List<WriteQueue.Op>, List<WriteQueue.Op>> = runCatching {
        val o = JSONObject(file.readText())
        WriteQueue.fromJson(o.optString("pending")) to WriteQueue.fromJson(o.optString("failed"))
    }.getOrDefault(emptyList<WriteQueue.Op>() to emptyList())

    override fun save(pending: List<WriteQueue.Op>, failed: List<WriteQueue.Op>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONObject().put("pending", WriteQueue.toJson(pending)).put("failed", WriteQueue.toJson(failed)).toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
