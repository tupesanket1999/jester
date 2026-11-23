package io.github.tupesanket1999.jester.push

import io.github.tupesanket1999.jester.logic.PushTag
import org.json.JSONObject
import java.net.URLDecoder

/** One line of ntfy's JSON stream (https://docs.ntfy.sh/subscribe/api/#json-message-format). */
data class NtfyMessage(
    val id: String,
    val time: Long,
    val event: String,
    val title: String,
    val message: String,
    val priority: Int,
    val tags: List<String>,
    val actionUrls: List<String>,
) {
    /**
     * The hub's reminder pushes carry signed action URLs `/api/act/done:<id>.<exp>.<sig>` (see docs/HUB_API.md).
     * The payload before the last two dots is readable, so we can recover the task id and call the API ourselves.
     */
    val taskId: String? get() = tag?.id ?: actionUrls.firstNotNullOfOrNull { taskIdFromActUrl(it) }

    /** The hub's machine tag `tt:<id>@<due>#<level>` (preferred over parsing the action URL). */
    val tag: PushTag? get() = PushTag.parse(tags)

    /** Escalation level from the hub's title prefix: "⏰ " = 1, "❗ " = 2, "‼️ " = 3. */
    val level: Int get() = tag?.level ?: when {
        title.startsWith("‼") -> 3
        title.startsWith("❗") -> 2
        else -> 1
    }

    /** Title without the hub's emoji prefix. */
    val cleanTitle: String get() = title.removePrefix("⏰ ").removePrefix("❗ ").removePrefix("‼️ ").removePrefix("‼ ")

    val isReminder: Boolean get() = taskId != null || tags.contains("alarm_clock")

    companion object {
        fun parse(line: String): NtfyMessage? = try {
            val o = JSONObject(line)
            val actions = o.optJSONArray("actions")
            val urls = if (actions == null) emptyList() else (0 until actions.length()).mapNotNull { actions.optJSONObject(it)?.optString("url")?.takeIf { u -> u.isNotEmpty() } }
            val tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            NtfyMessage(
                id = o.optString("id"), time = o.optLong("time"), event = o.optString("event"),
                title = o.optString("title"), message = o.optString("message"), priority = o.optInt("priority", 3),
                tags = tags, actionUrls = urls,
            )
        } catch (e: Exception) { null }

        fun taskIdFromActUrl(url: String): String? {
            val i0 = url.indexOf("/api/act/")
            if (i0 < 0) return null
            val token = URLDecoder.decode(url.substring(i0 + "/api/act/".length), "UTF-8")
            val i = token.lastIndexOf('.')
            if (i <= 0) return null
            val j = token.lastIndexOf('.', i - 1)
            if (j <= 0) return null
            val payload = token.substring(0, j)
            val colon = payload.indexOf(':')
            if (colon <= 0) return null
            return payload.substring(colon + 1).takeIf { it.isNotEmpty() }
        }
    }
}
