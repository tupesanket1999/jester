package io.github.tupesanket1999.jester

import io.github.tupesanket1999.jester.reminders.TaskLite
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class RingItem(val id: String, val title: String, val level: Int, val due: String?)

/**
 * Optional hub API client (docs/HUB_API.md). Only these endpoints: capture, lists, done/snooze, status, tz, bots, chat.
 * There is deliberately no call that can start work on the Mac.
 * The token goes only into the Authorization header; nothing here logs requests or headers.
 */
class Hub(private val prefs: Prefs) {

    /** Returns (HTTP status, body). Status -1 = not configured or network error. */
    fun call(method: String, path: String, body: String? = null): Pair<Int, String?> {
        if (!prefs.configured) return -1 to null
        val c = try { URL(prefs.hubUrl + path).openConnection() as HttpURLConnection } catch (e: Exception) { return -1 to null }
        return try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 25_000
            c.setRequestProperty("Authorization", "Bearer " + prefs.token)
            c.setRequestProperty("Accept", "application/json")
            if (method == "POST") {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write((body ?: "{}").toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
            code to text
        } catch (e: IOException) {
            -1 to null
        } finally {
            c.disconnect()
        }
    }

    private fun getJson(path: String): JSONObject? {
        val (code, body) = call("GET", path)
        return if (code in 200..299 && body != null) runCatching { JSONObject(body) }.getOrNull() else null
    }

    fun tasks(): List<TaskLite>? = getJson("/api/tasks")?.optJSONArray("tasks")?.let(::parseTasks)

    fun ringing(): List<RingItem>? = getJson("/api/ringing")?.optJSONArray("ringing")?.let { a ->
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            RingItem(o.optString("id"), o.optString("title"), o.optInt("level", 1), o.optString("due").ifEmpty { null })
        }
    }

    fun status(): JSONObject? = getJson("/api/status")

    /** (names, current) */
    fun bots(): Pair<List<String>, String>? = getJson("/api/bots")?.let { o ->
        val a = o.optJSONArray("bots") ?: JSONArray()
        (0 until a.length()).map { a.getString(it) } to o.optString("current")
    }

    /** (bot.json text, parts.px text) */
    fun bot(name: String): Pair<String, String>? = getJson("/api/bots/" + enc(name))?.let { o ->
        val m = o.optJSONObject("manifest") ?: return null
        m.toString() to o.optString("parts")
    }

    companion object {
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        fun donePath(id: String) = "/api/items/${enc(id)}/done"
        fun snoozePath(id: String) = "/api/items/${enc(id)}/snooze"
        fun textBody(text: String) = JSONObject().put("text", text).toString()

        fun parseTasks(a: JSONArray): List<TaskLite> = (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            TaskLite(
                id = o.optString("id"), title = o.optString("title"), done = o.optBoolean("done"),
                due = o.optString("due").ifEmpty { null }, priority = o.optString("priority").ifEmpty { null },
                file = o.optString("file"),
            )
        }
    }
}
