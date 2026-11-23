package io.github.tupesanket1999.jester.bot

import io.github.tupesanket1999.jester.JesterApp
import android.content.Context
import java.io.File

/** Bot packs: the bundled pixel pack, plus packs downloaded from the hub (`/api/bots/:name`) into filesDir/bots. */
object BotRepo {
    const val BUNDLED = "pixel"
    /** The vector jester: the default bot (prefs.bot "" or "jester"). The pixel robot is chosen as [BUNDLED]. */
    const val JESTER = "jester"

    /** Placeholder pack for the jester rig: only the accent colour (bubble outline) is used. */
    val jesterPack = BotPack("jester", 4f, 6.0, 0f, BotPack.argb(230, 57, 70), 0, 0, emptyMap(), emptyMap(), emptyMap(), rig = "jester")

    fun load(ctx: Context): BotPack {
        val name = JesterApp.get(ctx).prefs.bot
        if (name.isEmpty() || name == JESTER) return jesterPack
        if (name == BUNDLED) return bundled(ctx)
        if (name.isNotEmpty()) {
            val dir = File(ctx.filesDir, "bots/" + safe(name))
            runCatching { return BotPack.parse(File(dir, "bot.json").readText(), File(dir, "parts.px").readText()) }
        }
        return bundled(ctx)
    }

    fun bundled(ctx: Context): BotPack = ctx.assets.let { a ->
        BotPack.parse(
            a.open("bots/$BUNDLED/bot.json").bufferedReader().use { it.readText() },
            a.open("bots/$BUNDLED/parts.px").bufferedReader().use { it.readText() },
        )
    }

    /** Download and validate a pack from the hub; on success it becomes the chosen bot. Call off the main thread. */
    fun download(ctx: Context, name: String): Boolean {
        val app = JesterApp.get(ctx)
        val (manifest, parts) = app.hub.bot(name) ?: return false
        val pack = runCatching { BotPack.parse(manifest, parts) }.getOrNull() ?: return false
        if (pack.width == 0 || pack.frames("idle").isEmpty()) return false
        val dir = File(ctx.filesDir, "bots/" + safe(name)).apply { mkdirs() }
        File(dir, "bot.json").writeText(manifest)
        File(dir, "parts.px").writeText(parts)
        app.prefs.bot = name
        app.botVersion.value++
        return true
    }

    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9_-]"), "_")
}
