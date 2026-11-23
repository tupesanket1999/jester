package io.github.tupesanket1999.jester.logic

import java.net.URI
import java.net.URLDecoder

/**
 * `jester://` links (QR pairing, Samsung Modes & Routines, Tasker, shortcuts). Everything here is untrusted input:
 * strict validation, length caps, https only. add / ask / focus still show a one-tap confirm in the app.
 *  - jester://pair?hub=<https url>&topic=<ntfy topic>&token=<device token>[&ntfy=<https ntfy server>]
 *  - jester://add?text=<text>     jester://ask?text=<text>     jester://focus?min=<1..240>
 */
sealed class Link {
    data class Pair(val hub: String, val topic: String, val token: String, val ntfy: String) : Link()
    data class Add(val text: String) : Link()
    data class Ask(val text: String) : Link()
    data class Focus(val minutes: Int) : Link()
}

object DeepLink {
    const val SCHEME = "jester"
    const val DEFAULT_NTFY = "https://ntfy.sh"
    private val TOPIC = Regex("""^[A-Za-z0-9_-]{6,64}$""")
    private val TOKEN = Regex("""^[\x21-\x7E]{16,256}$""")

    fun parse(raw: String?): Link? {
        if (raw == null || raw.length > 2048) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (!SCHEME.equals(uri.scheme, ignoreCase = true)) return null
        val q = query(uri.rawQuery ?: "") ?: return null
        return when (uri.host?.lowercase()) {
            "pair" -> {
                val hub = https(q["hub"]) ?: return null
                val ntfy = if (q["ntfy"].isNullOrEmpty()) DEFAULT_NTFY else https(q["ntfy"]) ?: return null
                val topic = q["topic"]?.takeIf { TOPIC.matches(it) } ?: return null
                val token = q["token"]?.takeIf { TOKEN.matches(it) } ?: return null
                Link.Pair(hub, topic, token, ntfy)
            }
            "add" -> text(q["text"])?.let { Link.Add(it) }
            "ask" -> text(q["text"])?.let { Link.Ask(it) }
            "focus" -> q["min"]?.toIntOrNull()?.takeIf { it in 1..240 }?.let { Link.Focus(it) }
            else -> null
        }
    }

    private fun text(s: String?): String? = s?.replace(Regex("""[\u0000-\u001F\u007F]"""), " ")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 1000 }

    /** https only, a real host, no user-info, no query or fragment, ≤ 200 chars. Returned without a trailing slash. */
    fun https(s: String?): String? {
        if (s == null || s.length > 200) return null
        val u = runCatching { URI(s) }.getOrNull() ?: return null
        if (!"https".equals(u.scheme, ignoreCase = true)) return null
        val host = u.host ?: return null
        if (host.isEmpty() || !host.contains('.') || u.rawUserInfo != null || u.rawQuery != null || u.rawFragment != null) return null
        return s.trimEnd('/')
    }

    private fun query(raw: String): Map<String, String>? {
        val out = HashMap<String, String>()
        if (raw.isEmpty()) return out
        for (part in raw.split('&')) {
            if (part.isEmpty()) continue
            val i = part.indexOf('=')
            val k = if (i < 0) part else part.substring(0, i)
            val v = if (i < 0) "" else part.substring(i + 1)
            val dk = runCatching { URLDecoder.decode(k, "UTF-8") }.getOrNull() ?: return null
            val dv = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrNull() ?: return null
            if (out.containsKey(dk)) return null // duplicate keys are ambiguous: reject
            out[dk] = dv
        }
        return out
    }

    /** What the Mac's QR helper should encode (for tests and docs). */
    fun pairUrl(hub: String, topic: String, token: String, ntfy: String? = null): String {
        fun e(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        return "jester://pair?hub=${e(hub)}&topic=${e(topic)}&token=${e(token)}" + (ntfy?.let { "&ntfy=${e(it)}" } ?: "")
    }
}
