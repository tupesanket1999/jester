package io.github.tupesanket1999.jester.queue

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Offline write queue: every write (inbox, done, snooze, chat, tz) is stored first and sent later.
 * Nothing typed is ever dropped: permanent rejections move to [failed], which the app shows and can retry.
 * Pure logic; persistence goes through [Store] so the JVM tests can run it in memory.
 */
class WriteQueue(private val store: Store) {

    data class Op(
        val id: String,
        val method: String,
        val path: String,
        val body: String?,
        val createdAt: Long,
        val attempts: Int = 0,
        val nextAt: Long = 0,
        /** Ops with the same key replace each other while pending (e.g. "tz": only the latest timezone matters). */
        val coalesceKey: String? = null,
        val lastStatus: Int = 0,
    )

    interface Store {
        fun load(): Pair<List<Op>, List<Op>>
        fun save(pending: List<Op>, failed: List<Op>)
    }

    /** What happened to one op. */
    enum class Outcome { SENT, RETRY, FAILED }

    private val lock = Any()
    private val drainLock = Any() // one drainer at a time, so an op is never sent twice concurrently

    fun pending(): List<Op> = synchronized(lock) { store.load().first }
    fun failed(): List<Op> = synchronized(lock) { store.load().second }

    fun enqueue(method: String, path: String, body: String?, now: Long, coalesceKey: String? = null): Op = synchronized(lock) {
        val (p, f) = store.load()
        val id = UUID.randomUUID().toString()
        val op = Op(id, method, path, withIdempotencyKey(path, body, id), now, coalesceKey = coalesceKey)
        val kept = if (coalesceKey == null) p else p.filter { it.coalesceKey != coalesceKey }
        store.save(kept + op, f)
        op
    }

    /** Move every failed op back to pending (user pressed "retry"). */
    fun retryFailed(now: Long) = synchronized(lock) {
        val (p, f) = store.load()
        store.save(p + f.map { it.copy(attempts = 0, nextAt = now) }, emptyList())
    }

    /**
     * Send due ops in order. [send] returns the HTTP status (or -1 for a network error) and the response body.
     * Stops at the first retryable failure so order is kept and a down hub is not hammered.
     * [onSent] gets each successful op with its response body (chat replies).
     */
    fun drain(now: Long, send: (Op) -> Pair<Int, String?>, onSent: (Op, String?) -> Unit = { _, _ -> }): Int =
        synchronized(drainLock) { drainLocked(now, send, onSent) }

    private fun drainLocked(now: Long, send: (Op) -> Pair<Int, String?>, onSent: (Op, String?) -> Unit): Int {
        var sent = 0
        while (true) {
            val op = synchronized(lock) { store.load().first.firstOrNull() } ?: return sent
            if (op.nextAt > now) return sent
            val (status, body) = try { send(op) } catch (e: Exception) { -1 to null }
            val outcome = classify(status)
            synchronized(lock) {
                val (p, f) = store.load()
                val rest = p.filter { it.id != op.id }
                when (outcome) {
                    Outcome.SENT -> store.save(rest, f)
                    Outcome.FAILED -> store.save(rest, f + op.copy(lastStatus = status, attempts = op.attempts + 1))
                    Outcome.RETRY -> {
                        val n = op.attempts + 1
                        // Keep it at the head (order matters), with a later nextAt.
                        val updated = op.copy(attempts = n, nextAt = now + backoffMs(n), lastStatus = status)
                        store.save(p.map { if (it.id == op.id) updated else it }, f)
                    }
                }
            }
            when (outcome) {
                Outcome.SENT -> { sent++; onSent(op, body) }
                Outcome.FAILED -> {}
                Outcome.RETRY -> return sent
            }
        }
    }

    /** Earliest time something is due again, or null when empty. */
    fun nextDueAt(): Long? = pending().firstOrNull()?.nextAt

    companion object {
        const val BASE_BACKOFF_MS = 15_000L
        const val MAX_BACKOFF_MS = 30 * 60_000L

        /** Hub endpoints that accept an idempotency `key` (repeat with the same key within 7 days = no duplicate). */
        val IDEMPOTENT_PATHS = setOf("/api/inbox", "/api/chat")

        /**
         * Stamp `key` into inbox/chat bodies once, at enqueue time. The key is stored in the queued body, so every
         * retry after a lost response sends the same key and the hub never creates a duplicate item.
         */
        fun withIdempotencyKey(path: String, body: String?, key: String): String? {
            if (path !in IDEMPOTENT_PATHS) return body
            val o = runCatching { JSONObject(body ?: "{}") }.getOrNull() ?: return body
            if (!o.has("key")) o.put("key", key.take(80))
            return o.toString()
        }

        /** 15 s, 30 s, 1 min, 2 min ... capped at 30 min. */
        fun backoffMs(attempts: Int): Long {
            val shift = (attempts - 1).coerceIn(0, 20)
            return minOf(BASE_BACKOFF_MS shl shift, MAX_BACKOFF_MS)
        }

        /**
         * 2xx = sent. Network error, 5xx, 401 (token being fixed), 403, 408, 429 = retry later.
         * Other 4xx (400/404/409/422) = the hub will never accept it: park in failed (kept, never deleted).
         */
        fun classify(status: Int): Outcome = when {
            status in 200..299 -> Outcome.SENT
            status < 0 || status >= 500 || status in setOf(401, 403, 408, 429) -> Outcome.RETRY
            else -> Outcome.FAILED
        }

        fun toJson(ops: List<Op>): String = JSONArray().apply {
            ops.forEach { o ->
                put(JSONObject().apply {
                    put("id", o.id); put("method", o.method); put("path", o.path)
                    if (o.body != null) put("body", o.body)
                    put("createdAt", o.createdAt); put("attempts", o.attempts); put("nextAt", o.nextAt)
                    if (o.coalesceKey != null) put("coalesceKey", o.coalesceKey)
                    put("lastStatus", o.lastStatus)
                })
            }
        }.toString()

        fun fromJson(s: String?): List<Op> {
            if (s.isNullOrBlank()) return emptyList()
            val a = JSONArray(s)
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Op(
                    id = o.getString("id"), method = o.getString("method"), path = o.getString("path"),
                    body = if (o.has("body")) o.getString("body") else null,
                    createdAt = o.optLong("createdAt"), attempts = o.optInt("attempts"), nextAt = o.optLong("nextAt"),
                    coalesceKey = if (o.has("coalesceKey")) o.getString("coalesceKey") else null,
                    lastStatus = o.optInt("lastStatus"),
                )
            }
        }
    }

    /** In-memory store (tests). */
    class MemoryStore : Store {
        var pending: List<Op> = emptyList()
        var failed: List<Op> = emptyList()
        override fun load() = pending to failed
        override fun save(pending: List<Op>, failed: List<Op>) { this.pending = pending; this.failed = failed }
    }
}
