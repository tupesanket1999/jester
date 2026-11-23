package io.github.tupesanket1999.jester

import io.github.tupesanket1999.jester.bot.BotPack
import io.github.tupesanket1999.jester.bot.BotPack.Companion.argb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class BotPackTest {
    private val dir = File("src/main/assets/bots/pixel")
    private val pack = BotPack.parse(File(dir, "bot.json").readText(), File(dir, "parts.px").readText())

    private fun px(state: String, frame: Int, x: Int, y: Int) = pack.render(state, frame)[y * pack.width + x]

    // palette from parts.px
    private val K = argb(18, 11, 13); private val C = argb(230, 57, 70); private val R = argb(255, 77, 94)
    private val B = argb(242, 167, 160); private val A = argb(224, 165, 92); private val Y = argb(255, 214, 120)
    private val V = argb(40, 22, 26); private val W = argb(255, 236, 232); private val G = argb(120, 230, 190)

    @Test fun parsesManifestAndParts() {
        assertEquals("pixel", pack.name)
        assertEquals(24, pack.width); assertEquals(29, pack.height) // flame_l (5 rows at y=24) extends the 24x28 body by one row, as on the Mac
        assertEquals(11, pack.palette.size); assertEquals(18, pack.layers.size)
        assertEquals(8, pack.states.size)
        assertEquals(4f, pack.scale); assertEquals(6.0, pack.fps, 0.0); assertEquals(2f, pack.bob)
        assertEquals(C, pack.accent)
        assertEquals(BotPack.Layer(19, 8, pack.layers["arm_r_up"]!!.rows), pack.layers["arm_r_up"])
        assertEquals(10, pack.layers["arm_r_up"]!!.rows.size)
    }

    @Test fun idleFramePixels() {
        assertEquals(0, px("idle", 0, 0, 0))               // transparent corner
        assertEquals(A, px("idle", 0, 11, 0))              // antenna tip (tip_on off in idle)
        assertEquals(K, px("idle", 0, 11, 1))
        assertEquals(R, px("idle", 0, 4, 4))
        assertEquals(W, px("idle", 0, 7, 8))               // eyes_open white
        assertEquals(W, px("idle", 0, 7, 9))
        assertEquals(K, px("idle", 0, 3, 16))              // arm_l
        assertEquals(C, px("idle", 0, 3, 17))
        assertEquals(B, px("idle", 0, 3, 19))
        assertEquals(A, px("idle", 0, 11, 24))             // flame_s ".RAAR."
        assertEquals(R, px("idle", 0, 10, 24))
        assertEquals(Y, px("idle", 1, 11, 24))             // flame_m "RAYYAR"
        assertEquals(0, px("idle", 0, 11, 26))             // flame_s has 2 rows only
        assertEquals(V, px("idle", 3, 7, 8))               // blink: top row closed
        assertEquals(W, px("idle", 3, 7, 9))
        assertEquals(R, px("thinking", 0, 11, 0))          // tip_on lights the antenna
        assertEquals(G, px("working", 0, 7, 9))            // eyes_focus green
    }

    @Test fun offsetsAndClipping() {
        assertEquals(0, px("idle", 0, 3, 15))
        assertEquals(K, px("working", 0, 3, 15))           // arm_l dy=-1
        assertEquals(B, px("waiting", 0, 22, 9))           // arm_r_up "..KBK" row 1
        assertEquals(B, px("waiting", 1, 23, 9))           // dx=1 shifts it right; the K at x=24 is clipped
        assertEquals(24 * 29, pack.render("waiting", 1).size)
        assertEquals(R, px("thinking", 1, 12, 28))          // flame_l tail lives in the 29th row
    }

    @Test fun stateFallbacks() {
        assertEquals(pack.frames("idle"), pack.frames("no-such-state"))
        val noRemind = BotPack.parse(
            """{"name":"t","states":{"idle":[["a"]],"waiting":[["a","b"]]}}""",
            "@palette\nX 1 2 3\n@layer a 0 0\nX.\n@layer b 1 0\nX\n",
        )
        assertEquals(noRemind.frames("waiting"), noRemind.frames("remind"))
        assertEquals(noRemind.frames("idle"), noRemind.frames("sleep"))
        assertEquals(2, noRemind.width); assertEquals(1, noRemind.height)
        assertEquals(argb(1, 2, 3), noRemind.render("remind", 0)[1])
        assertEquals(1.5, noRemind.fpsFor("sleep"), 0.0)
        assertEquals(9.0, noRemind.fpsFor("working"), 0.0)
    }

    @Test fun crlfAndCommentsIgnored() {
        val p = BotPack.parse("""{"states":{"idle":[["a"]]}}""", "# c\r\n@palette\r\nX 9 9 9\r\n\r\n@layer a 1 1\r\nX\r\n")
        assertEquals(argb(9, 9, 9), p.render("idle", 0)[1 * p.width + 1])
        assertEquals(2, p.width)
    }

    /**
     * Every pixel of the first two frames of every state matches tools/preview.swift's contact sheet
     * (rendered on this Mac at 2x, converted from Display P3 to sRGB with `sips`, so allow +-3 per channel).
     */
    @Test fun matchesReferenceRendererPixelForPixel() {
        val img = TestPng.read(File("src/test/resources/reference-preview-srgb.png"))
        val bg = argb(92, 84, 98)
        val S = 6; val pad = 12; val cellW = 24 * S + pad * 2; val cellH = 28 * S + pad * 2 + 22 // preview.swift hard-codes a 24x28 cell; row 28 lands in its padding
        val k = img.width / (cellW * BotPack.STATES.count { pack.states.containsKey(it) }) // backing scale (2 on retina)
        assertEquals(2, k)
        var checked = 0
        BotPack.STATES.filter { pack.states.containsKey(it) }.forEachIndexed { i, st ->
            pack.states[st]!!.take(2).forEachIndexed { f, frame ->
                val out = pack.render(frame)
                for (y in 0 until pack.height) for (x in 0 until pack.width) {
                    val want = out[y * pack.width + x].let { if (it == 0) bg else it }
                    val ix = (i * cellW + pad + x * S + S / 2) * k
                    val iy = (f * cellH + pad + 22 + y * S + S / 2) * k
                    val got = img.rgb(ix, iy)
                    val ok = (0..2).all { sh -> abs(((want shr (sh * 8)) and 255) - ((got shr (sh * 8)) and 255)) <= 3 }
                    assertTrue("state=$st frame=$f x=$x y=$y want=${Integer.toHexString(want)} got=${Integer.toHexString(got)}", ok)
                    checked++
                }
            }
        }
        assertEquals((7 * 2 + 1) * 24 * 29, checked) // 7 states x 2 frames + sleep x 1
    }
}
