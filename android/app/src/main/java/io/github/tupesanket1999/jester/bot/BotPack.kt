package io.github.tupesanket1999.jester.bot

import org.json.JSONArray
import org.json.JSONObject

/**
 * Bot pack = bot.json + parts.px (format in docs/HUB_API.md). Pure Kotlin, no Android types,
 * so the JVM unit tests can check it pixel by pixel against tools/preview.swift.
 * Port of Pack.load in the Mac app: same parse rules, same canvas, same clipping.
 */
class BotPack(
    val name: String,
    val scale: Float,
    val fps: Double,
    val bob: Float,
    val accent: Int,
    val width: Int,
    val height: Int,
    val palette: Map<Char, Int>,
    val layers: Map<String, Layer>,
    val states: Map<String, List<List<Placement>>>,
    /** "jester" = the vector jester rig (no pixel parts); anything else = the pixel pack. */
    val rig: String = "",
) {
    val isJester: Boolean get() = rig == "jester"
    data class Layer(val x: Int, val y: Int, val rows: List<String>)
    data class Placement(val layer: String, val dx: Int = 0, val dy: Int = 0)

    /** Frames for a state, with the same fallbacks as the Mac app. */
    fun frames(state: String): List<List<Placement>> {
        states[state]?.takeIf { it.isNotEmpty() }?.let { return it }
        FALLBACK[state]?.let { fb -> states[fb]?.takeIf { it.isNotEmpty() }?.let { return it } }
        return states["idle"] ?: emptyList()
    }

    /** Composite one frame into ARGB pixels (row-major, width*height). Transparent = 0. Layers drawn in order. */
    fun render(frame: List<Placement>): IntArray {
        val out = IntArray(width * height)
        for (p in frame) {
            val l = layers[p.layer] ?: continue
            l.rows.forEachIndexed { r, row ->
                row.forEachIndexed { c, ch ->
                    if (ch == '.') return@forEachIndexed
                    val col = palette[ch] ?: return@forEachIndexed
                    val x = l.x + c + p.dx
                    val y = l.y + r + p.dy
                    if (x in 0 until width && y in 0 until height) out[y * width + x] = col
                }
            }
        }
        return out
    }

    fun render(state: String, frameIndex: Int): IntArray {
        val f = frames(state)
        return if (f.isEmpty()) IntArray(width * height) else render(f[Math.floorMod(frameIndex, f.size)])
    }

    /** Frame rate per state, as on the Mac. */
    fun fpsFor(state: String): Double = when (state) {
        "sleep" -> 1.5
        "working" -> fps * 1.5
        else -> fps
    }

    companion object {
        val STATES = listOf("idle", "thinking", "working", "waiting", "done", "error", "remind", "sleep")
        private val FALLBACK = mapOf(
            "remind" to "waiting", "sleep" to "idle", "error" to "idle", "done" to "idle",
            "thinking" to "working", "working" to "idle", "waiting" to "idle",
        )

        fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

        fun parse(manifestJson: String, partsText: String): BotPack {
            val m = JSONObject(manifestJson)
            val palette = HashMap<Char, Int>()
            val layers = HashMap<String, Layer>()
            var w = 0
            var h = 0
            var mode = ""
            var cur = ""
            var cx = 0
            var cy = 0
            var rows = ArrayList<String>()
            fun flush() {
                if (cur.isNotEmpty()) {
                    layers[cur] = Layer(cx, cy, rows)
                    w = maxOf(w, cx + (rows.maxOfOrNull { it.length } ?: 0))
                    h = maxOf(h, cy + rows.size)
                }
                cur = ""; rows = ArrayList()
            }
            for (raw in partsText.split("\n")) {
                val line = raw.trimEnd('\r')
                if (line.startsWith("#") || line.isBlank()) continue
                if (line.startsWith("@palette")) { flush(); mode = "p"; continue }
                if (line.startsWith("@layer")) {
                    flush()
                    val f = line.split(" ").filter { it.isNotEmpty() }
                    val x = f.getOrNull(2)?.toIntOrNull()
                    val y = f.getOrNull(3)?.toIntOrNull()
                    if (f.size < 4 || x == null || y == null) continue
                    cur = f[1]; cx = x; cy = y; mode = "l"
                    continue
                }
                if (mode == "p") {
                    val f = line.split(" ").filter { it.isNotEmpty() }
                    if (f.size == 4) {
                        val (r, g, b) = f.drop(1).map { it.toIntOrNull() ?: -1 }
                        if (f[0].isNotEmpty() && listOf(r, g, b).all { it in 0..255 }) palette[f[0][0]] = argb(r, g, b)
                    }
                } else if (mode == "l") rows.add(line)
            }
            flush()
            m.optJSONArray("size")?.let { if (it.length() == 2) { w = it.getInt(0); h = it.getInt(1) } }

            val states = LinkedHashMap<String, List<List<Placement>>>()
            m.optJSONObject("states")?.let { st ->
                for (key in st.keys()) {
                    val frames = st.optJSONArray(key) ?: continue
                    states[key] = (0 until frames.length()).mapNotNull { i -> frames.optJSONArray(i)?.let(::placements) }
                }
            }
            val accent = m.optJSONArray("accent")?.takeIf { it.length() == 3 }
                ?.let { argb(it.getInt(0), it.getInt(1), it.getInt(2)) } ?: argb(230, 57, 70)
            return BotPack(
                name = m.optString("name", "bot"),
                scale = m.optDouble("scale", 4.0).toFloat(),
                fps = m.optDouble("fps", 6.0),
                bob = m.optDouble("bob", 2.0).toFloat(),
                accent = accent, width = w, height = h,
                palette = palette, layers = layers, states = states, rig = m.optString("rig", ""),
            )
        }

        private fun placements(a: JSONArray): List<Placement> = (0 until a.length()).mapNotNull { i ->
            when (val item = a.get(i)) {
                is String -> Placement(item)
                is JSONObject -> Placement(item.optString("layer"), item.optInt("dx", 0), item.optInt("dy", 0))
                else -> null
            }
        }
    }
}
