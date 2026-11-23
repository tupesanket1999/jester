package io.github.tupesanket1999.jester.logic

import org.json.JSONArray
import org.json.JSONObject

/**
 * Update rules, kept free of Android types so they are JVM unit-testable (UpdatesTest): which GitHub release is the
 * newest Android one, how versions compare, when to check again and when to bother the user.
 */
object Updates {
    const val INTERVAL_MS = 24 * 3600_000L
    /** After a failed automatic check, try again in an hour instead of waiting a full day. */
    const val RETRY_MS = 3600_000L
    const val NOTES_MAX = 600

    data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
        override fun compareTo(other: Version): Int =
            compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
        override fun toString() = "$major.$minor.$patch"
    }

    /** A downloadable Android release. [sha256] is lowercase hex, null on old releases without a digest. */
    data class Release(
        val tag: String, val version: Version, val apkName: String, val url: String,
        val size: Long, val sha256: String?, val notes: String,
    )

    private val TAG = Regex("""^android-v(\d+)\.(\d+)\.(\d+)$""")
    private val VERSION = Regex("""^(\d+)\.(\d+)\.(\d+)""")
    private val APK = Regex("""^jester-android-.*\.apk$""")
    private val DIGEST = Regex("""^sha256:([0-9a-fA-F]{64})$""")

    private fun version(m: MatchResult?): Version? = m?.let {
        val (a, b, c) = it.destructured
        val ma = a.toIntOrNull(); val mi = b.toIntOrNull(); val pa = c.toIntOrNull()
        if (ma == null || mi == null || pa == null) null else Version(ma, mi, pa)
    }

    /** "0.4.0" (a suffix such as "-debug" is ignored). Null if it does not start with major.minor.patch. */
    fun parseVersion(s: String?): Version? = version(VERSION.find((s ?: "").trim()))

    /** "android-v0.5.1" -> 0.5.1; mac-* and anything else -> null. */
    fun parseTag(tag: String?): Version? = version(TAG.find((tag ?: "").trim()))

    /** "sha256:<64 hex>" -> lowercase hex; anything else (missing, other algorithm, bad length) -> null. */
    fun parseDigest(s: String?): String? = DIGEST.matchEntire((s ?: "").trim())?.groupValues?.get(1)?.lowercase()

    private fun str(o: JSONObject, key: String): String? = if (o.isNull(key)) null else o.optString(key).ifEmpty { null }

    /**
     * The GitHub /releases list -> the highest-version android-vX.Y.Z release that is not a draft (prereleases count)
     * and has a jester-android-*.apk asset with an https download URL. Null if there is none or the JSON is bad.
     */
    fun pick(json: String): Release? {
        val a = runCatching { JSONArray(json) }.getOrNull() ?: return null
        var best: Release? = null
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optBoolean("draft", false)) continue
            val tag = str(o, "tag_name") ?: continue
            val v = parseTag(tag) ?: continue
            if (best != null && v <= best.version) continue
            val assets = o.optJSONArray("assets") ?: continue
            var asset: JSONObject? = null
            for (k in 0 until assets.length()) {
                val x = assets.optJSONObject(k) ?: continue
                val n = str(x, "name") ?: continue
                val u = str(x, "browser_download_url") ?: continue
                if (APK.matches(n) && u.startsWith("https://")) { asset = x; break }
            }
            if (asset == null) continue
            best = Release(
                tag = tag, version = v, apkName = str(asset, "name")!!, url = str(asset, "browser_download_url")!!,
                size = asset.optLong("size", 0L).coerceAtLeast(0L), sha256 = parseDigest(str(asset, "digest")),
                notes = plainNotes(str(o, "body") ?: ""),
            )
        }
        return best
    }

    /** Release notes as short plain text: markdown marks removed, first [max] characters. */
    fun plainNotes(body: String, max: Int = NOTES_MAX): String {
        var t = body.replace("\r\n", "\n").replace('\r', '\n')
        t = t.replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "\$1")
        t = t.replace(Regex("""(?m)^\s{0,3}#{1,6}\s*"""), "")
        t = t.replace(Regex("""(?m)^\s*[-*]\s+"""), "• ")
        t = t.replace("**", "").replace("__", "").replace("`", "")
        t = t.replace(Regex("\n{3,}"), "\n\n").trim()
        return if (t.length > max) t.take(max).trimEnd() + "…" else t
    }

    /** True when [r] is newer than the installed versionName. Unparsable installed version = never newer. */
    fun isNewer(r: Release, installed: String): Boolean = parseVersion(installed)?.let { r.version > it } ?: false

    /** The user skipped [skippedTag]: that tag and anything older stays quiet; a newer tag shows again. */
    fun isSkipped(r: Release, skippedTag: String): Boolean = parseTag(skippedTag)?.let { r.version <= it } ?: false

    /** Automatic notification: newer, not skipped, and not already announced. */
    fun shouldNotify(r: Release, installed: String, skippedTag: String, notifiedTag: String): Boolean =
        isNewer(r, installed) && !isSkipped(r, skippedTag) && r.tag != notifiedTag

    /** Time for an automatic check? (A clock that moved backwards also counts as due.) */
    fun due(lastCheckMs: Long, nowMs: Long): Boolean = lastCheckMs <= 0 || nowMs < lastCheckMs || nowMs - lastCheckMs >= INTERVAL_MS

    /** Value to store as "last check" after a failed automatic check so the next one is due in [RETRY_MS]. */
    fun lastCheckAfterFailure(nowMs: Long): Long = nowMs - (INTERVAL_MS - RETRY_MS)

    fun apkFileName(v: Version) = "jester-android-$v.apk"

    /**
     * Cached download no longer needed: not a file we named, a partial download, or a version at or below the
     * installed one. [installed] unparsable = keep everything.
     */
    fun isStaleApk(fileName: String, installed: String): Boolean {
        val inst = parseVersion(installed) ?: return false
        if (fileName.endsWith(".part")) return true
        val v = Regex("""^jester-android-(\d+\.\d+\.\d+)\.apk$""").find(fileName)?.groupValues?.get(1)?.let(::parseVersion) ?: return true
        return v <= inst
    }

    fun toJson(r: Release): String = JSONObject()
        .put("tag", r.tag).put("name", r.apkName).put("url", r.url).put("size", r.size)
        .put("sha256", r.sha256 ?: JSONObject.NULL).put("notes", r.notes).toString()

    fun fromJson(s: String?): Release? {
        if (s.isNullOrEmpty()) return null
        val o = runCatching { JSONObject(s) }.getOrNull() ?: return null
        val tag = str(o, "tag") ?: return null
        val v = parseTag(tag) ?: return null
        return Release(tag, v, str(o, "name") ?: return null, str(o, "url") ?: return null,
            o.optLong("size", 0L), parseDigest("sha256:" + (str(o, "sha256") ?: "")), o.optString("notes", ""))
    }
}
