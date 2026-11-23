package io.github.tupesanket1999.jester

import io.github.tupesanket1999.jester.queue.WriteQueue
import io.github.tupesanket1999.jester.queue.WriteQueue.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteQueueTest {
    private val store = WriteQueue.MemoryStore()
    private val q = WriteQueue(store)
    private val sentPaths = mutableListOf<String>()
    private fun hub(status: Int, body: String? = null): (WriteQueue.Op) -> Pair<Int, String?> = { sentPaths += it.path; status to body }

    @Test fun sendsInOrderAndReportsBodies() {
        q.enqueue("POST", "/api/inbox", """{"text":"buy milk 6pm"}""", 1000)
        q.enqueue("POST", "/api/chat", """{"text":"hi"}""", 1001)
        val replies = mutableListOf<String?>()
        assertEquals(2, q.drain(2000, hub(201, """{"reply":"ok"}""")) { _, b -> replies += b })
        assertEquals(listOf("/api/inbox", "/api/chat"), sentPaths)
        assertEquals(2, replies.size)
        assertEquals(0, q.pending().size)
    }

    @Test fun networkErrorKeepsEverythingAndBacksOff() {
        q.enqueue("POST", "/api/inbox", "{}", 0)
        q.enqueue("POST", "/api/items/a1/done", null, 0)
        assertEquals(0, q.drain(10_000, hub(-1)))
        assertEquals(listOf("/api/inbox"), sentPaths)                   // stopped at the first failure
        val head = q.pending().first()
        assertEquals(2, q.pending().size)
        assertEquals(1, head.attempts); assertEquals(10_000 + 15_000, head.nextAt)
        assertEquals(0, q.drain(20_000, hub(201)))                      // not due yet
        assertEquals(2, q.drain(25_000, hub(201)))
        assertEquals(0, q.pending().size)
    }

    @Test fun throwingSenderIsARetry() {
        q.enqueue("POST", "/api/inbox", "{}", 0)
        assertEquals(0, q.drain(0, { throw java.io.IOException("no route") }))
        assertEquals(1, q.pending().size)
    }

    @Test fun permanentRejectionIsParkedNotLost() {
        q.enqueue("POST", "/api/items/gone/done", null, 0)
        q.enqueue("POST", "/api/inbox", """{"text":"x"}""", 0)
        var n = 0
        assertEquals(1, q.drain(0, { n++; if (n == 1) 404 to null else 201 to null }))
        assertEquals(1, q.failed().size); assertEquals(404, q.failed()[0].lastStatus)
        assertEquals(0, q.pending().size)
        q.retryFailed(5)
        assertEquals(1, q.pending().size); assertEquals(0, q.failed().size)
    }

    @Test fun classifyStatuses() {
        assertEquals(Outcome.SENT, WriteQueue.classify(200)); assertEquals(Outcome.SENT, WriteQueue.classify(201))
        listOf(-1, 401, 403, 408, 429, 500, 503).forEach { assertEquals("$it", Outcome.RETRY, WriteQueue.classify(it)) }
        listOf(400, 404, 409, 422).forEach { assertEquals("$it", Outcome.FAILED, WriteQueue.classify(it)) }
    }

    @Test fun backoffDoublesAndCaps() {
        assertEquals(listOf(15_000L, 30_000L, 60_000L, 120_000L), (1..4).map { WriteQueue.backoffMs(it) })
        assertEquals(WriteQueue.MAX_BACKOFF_MS, WriteQueue.backoffMs(50))
    }

    @Test fun coalesceKeepsOnlyLatestTimezone() {
        q.enqueue("POST", "/api/devices/me", """{"tz":"UTC"}""", 0, coalesceKey = "tz")
        q.enqueue("POST", "/api/inbox", "{}", 1)
        q.enqueue("POST", "/api/devices/me", """{"tz":"Europe/London"}""", 2, coalesceKey = "tz")
        assertEquals(listOf("/api/inbox", "/api/devices/me"), q.pending().map { it.path })
        assertEquals("""{"tz":"Europe/London"}""", q.pending()[1].body)
    }

    @Test fun inboxAndChatCarryOneIdempotencyKeyAcrossRetries() {
        val op = q.enqueue("POST", "/api/inbox", "{\"text\":\"buy milk 6pm\"}", 0)
        val key = org.json.JSONObject(op.body!!).getString("key")
        assertEquals(op.id, key)
        assertEquals("buy milk 6pm", org.json.JSONObject(op.body!!).getString("text"))
        val bodies = mutableListOf<String?>()
        q.drain(0, { bodies += it.body; -1 to null })                       // lost response
        q.drain(WriteQueue.backoffMs(1), { bodies += it.body; 201 to null }) // retry
        assertEquals(2, bodies.size)
        assertEquals(bodies[0], bodies[1])                                   // identical body, same key
        val chat = q.enqueue("POST", "/api/chat", "{\"text\":\"hi\"}", 0)
        assertEquals(chat.id, org.json.JSONObject(chat.body!!).getString("key"))
        val done = q.enqueue("POST", "/api/items/a1/done", "{}", 0)
        assertEquals("{}", done.body)                                        // other endpoints untouched
        assertTrue(chat.id != op.id)
    }

    @Test fun jsonRoundTrip() {
        q.enqueue("POST", "/api/inbox", """{"text":"a \"quoted\" ✓"}""", 7, coalesceKey = "k")
        q.enqueue("POST", "/api/items/x/done", null, 8)
        val back = WriteQueue.fromJson(WriteQueue.toJson(q.pending()))
        assertEquals(q.pending(), back)
        assertNull(back[1].body)
        assertEquals(emptyList<WriteQueue.Op>(), WriteQueue.fromJson(""))
    }
}
