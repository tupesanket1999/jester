package io.github.tupesanket1999.jester

import io.github.tupesanket1999.jester.logic.Updates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    private val hex = "a".repeat(63) + "B"

    private fun asset(name: String, url: String? = "https://github.com/x/y/releases/download/t/$name", digest: String? = "sha256:$hex", size: Long = 1000) =
        """{"name":"$name","size":$size,"browser_download_url":${url?.let { "\"$it\"" } ?: "null"},"digest":${digest?.let { "\"$it\"" } ?: "null"}}"""

    private fun rel(tag: String, assets: String, draft: Boolean = false, pre: Boolean = false, body: String? = "Notes for $tag") =
        """{"tag_name":"$tag","draft":$draft,"prerelease":$pre,"body":${body?.let { "\"$it\"" } ?: "null"},"assets":[$assets]}"""

    private fun list(vararg r: String) = "[" + r.joinToString(",") + "]"

    private fun apk(v: String) = asset("jester-android-$v.apk")

    @Test fun newestAndroidTagBeatsMacTagsAndDrafts() {
        val j = list(
            rel("mac-v9.0.0", asset("Jester-9.0.0.dmg")),
            rel("android-v0.7.0", apk("0.7.0"), draft = true),
            rel("android-v0.5.0", apk("0.5.0")),
            rel("android-v0.6.0", apk("0.6.0")),
            rel("android-v0.4.0", apk("0.4.0")),
        )
        val r = Updates.pick(j)!!
        assertEquals("android-v0.6.0", r.tag)
        assertEquals(Updates.Version(0, 6, 0), r.version)
        assertEquals("jester-android-0.6.0.apk", r.apkName)
        assertEquals("Notes for android-v0.6.0", r.notes)
    }

    @Test fun highestVersionWinsRegardlessOfListOrder() {
        val j = list(rel("android-v0.9.9", apk("0.9.9")), rel("android-v0.10.0", apk("0.10.0")), rel("android-v0.9.10", apk("0.9.10")))
        assertEquals("android-v0.10.0", Updates.pick(j)!!.tag)
    }

    @Test fun prereleaseCounts() {
        val j = list(rel("android-v0.5.0", apk("0.5.0")), rel("android-v0.6.0-rc", apk("0.6.0"), pre = true), rel("android-v0.6.0", apk("0.6.0"), pre = true))
        val r = Updates.pick(j)!!
        assertEquals("android-v0.6.0", r.tag) // the -rc tag does not match the strict pattern; the prerelease with a clean tag does
    }

    @Test fun releaseWithoutApkAssetIsSkipped() {
        val j = list(
            rel("android-v0.6.0", asset("notes.txt") + "," + asset("jester-mac-0.6.0.zip")),
            rel("android-v0.5.0", apk("0.5.0")),
        )
        assertEquals("android-v0.5.0", Updates.pick(j)!!.tag)
        assertNull(Updates.pick(list(rel("android-v0.6.0", asset("other.apk")))))
        assertNull(Updates.pick(list(rel("android-v0.6.0", ""))))
    }

    @Test fun nonHttpsDownloadUrlIsRejected() {
        assertNull(Updates.pick(list(rel("android-v0.6.0", asset("jester-android-0.6.0.apk", url = "http://x/y.apk")))))
        assertNull(Updates.pick(list(rel("android-v0.6.0", asset("jester-android-0.6.0.apk", url = null)))))
    }

    @Test fun noMatchOrBadJson() {
        assertNull(Updates.pick("[]"))
        assertNull(Updates.pick("not json"))
        assertNull(Updates.pick("""{"message":"API rate limit exceeded"}"""))
        assertNull(Updates.pick(list(rel("v1.0.0", apk("1.0.0")), rel("mac-v1.0.0", apk("1.0.0")), rel("android-v1.0", apk("1.0.0")))))
    }

    @Test fun assetFieldsAndMissingDigest() {
        val r = Updates.pick(list(rel("android-v0.6.0", asset("jester-android-0.6.0.apk", size = 12345678))))!!
        assertEquals(12345678L, r.size)
        assertEquals(hex.lowercase(), r.sha256)
        assertEquals("https://github.com/x/y/releases/download/t/jester-android-0.6.0.apk", r.url)
        val old = Updates.pick(list(rel("android-v0.6.0", asset("jester-android-0.6.0.apk", digest = null), body = null)))!!
        assertNull(old.sha256)
        assertEquals("", old.notes)
    }

    @Test fun digestParsing() {
        assertEquals(hex.lowercase(), Updates.parseDigest("sha256:$hex"))
        assertNull(Updates.parseDigest(null))
        assertNull(Updates.parseDigest(""))
        assertNull(Updates.parseDigest("sha1:" + "a".repeat(40)))
        assertNull(Updates.parseDigest("sha256:abc"))
        assertNull(Updates.parseDigest(hex))
        assertNull(Updates.parseDigest("sha256:" + "z".repeat(64)))
    }

    @Test fun versionCompareIsNumeric() {
        val v = { s: String -> Updates.parseVersion(s)!! }
        assertTrue(v("0.10.0") > v("0.9.9"))
        assertTrue(v("1.0.0") > v("0.99.99"))
        assertTrue(v("0.4.1") > v("0.4.0"))
        assertEquals(0, v("0.4.0").compareTo(v("0.4.0")))
        assertEquals(Updates.Version(0, 4, 0), v("0.4.0-debug"))
        assertNull(Updates.parseVersion("abc"))
        assertNull(Updates.parseVersion("1.2"))
        assertNull(Updates.parseVersion("99999999999.0.0"))
        assertEquals(Updates.Version(1, 2, 3), Updates.parseTag("android-v1.2.3"))
        assertNull(Updates.parseTag("mac-v1.2.3"))
        assertNull(Updates.parseTag("android-v1.2.3-rc1"))
        assertNull(Updates.parseTag("xandroid-v1.2.3"))
    }

    private fun release(tag: String) = Updates.pick(list(rel(tag, apk(tag.removePrefix("android-v")))))!!

    @Test fun isNewerAgainstInstalled() {
        assertTrue(Updates.isNewer(release("android-v0.10.0"), "0.9.9"))
        assertFalse(Updates.isNewer(release("android-v0.4.0"), "0.4.0"))
        assertFalse(Updates.isNewer(release("android-v0.3.0"), "0.4.0"))
        assertFalse(Updates.isNewer(release("android-v0.5.0"), "garbage"))
    }

    @Test fun skippedTagLogic() {
        val r5 = release("android-v0.5.0")
        val r6 = release("android-v0.6.0")
        assertTrue(Updates.isSkipped(r5, "android-v0.5.0"))
        assertFalse(Updates.isSkipped(r6, "android-v0.5.0")) // a newer tag shows again
        assertTrue(Updates.isSkipped(r5, "android-v0.6.0"))
        assertFalse(Updates.isSkipped(r5, ""))
        assertFalse(Updates.shouldNotify(r5, "0.4.0", "android-v0.5.0", ""))
        assertTrue(Updates.shouldNotify(r6, "0.4.0", "android-v0.5.0", ""))
        assertFalse(Updates.shouldNotify(r6, "0.4.0", "", "android-v0.6.0")) // announced once
        assertFalse(Updates.shouldNotify(r5, "0.5.0", "", ""))               // not newer
    }

    @Test fun checkIsDueOncePerDay() {
        val day = 24 * 3600_000L
        assertTrue(Updates.due(0, 1_000))
        assertFalse(Updates.due(1_000_000, 1_000_000 + day - 1))
        assertTrue(Updates.due(1_000_000, 1_000_000 + day))
        assertTrue(Updates.due(5_000_000, 1_000)) // clock went backwards
        val now = 10 * day
        val afterFail = Updates.lastCheckAfterFailure(now)
        assertFalse(Updates.due(afterFail, now + Updates.RETRY_MS - 1))
        assertTrue(Updates.due(afterFail, now + Updates.RETRY_MS))
    }

    @Test fun notesArePlainAndShort() {
        assertEquals("What's new\n• Faster\n• link text", Updates.plainNotes("## What's new\r\n- **Faster**\r\n- [link text](https://x.y/z)\r\n\r\n\r\n\r\n"))
        val long = Updates.plainNotes("x".repeat(2000))
        assertEquals(Updates.NOTES_MAX + 1, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test fun staleApkCleanup() {
        assertTrue(Updates.isStaleApk("jester-android-0.4.0.apk", "0.4.0"))
        assertTrue(Updates.isStaleApk("jester-android-0.3.9.apk", "0.4.0"))
        assertFalse(Updates.isStaleApk("jester-android-0.5.0.apk", "0.4.0"))
        assertTrue(Updates.isStaleApk("jester-android-0.5.0.apk.part", "0.4.0"))
        assertTrue(Updates.isStaleApk("whatever.tmp", "0.4.0"))
        assertFalse(Updates.isStaleApk("jester-android-0.3.0.apk", "garbage"))
        assertEquals("jester-android-0.5.0.apk", Updates.apkFileName(Updates.Version(0, 5, 0)))
    }

    @Test fun releaseJsonRoundTrip() {
        val r = Updates.pick(list(rel("android-v0.6.0", apk("0.6.0"))))!!
        assertEquals(r, Updates.fromJson(Updates.toJson(r)))
        val noDigest = r.copy(sha256 = null)
        assertEquals(noDigest, Updates.fromJson(Updates.toJson(noDigest)))
        assertNull(Updates.fromJson(""))
        assertNull(Updates.fromJson("{}"))
        assertNotNull(Updates.fromJson(Updates.toJson(r)))
    }
}
