package io.github.tupesanket1999.jester

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.util.zip.Inflater

/** Minimal PNG reader for tests (8-bit RGB/RGBA, non-interlaced): javax.imageio is not on the Android test classpath. */
class TestPng(val width: Int, val height: Int, private val bpp: Int, private val rows: Array<ByteArray>) {
    /** 0xFFRRGGBB */
    fun rgb(x: Int, y: Int): Int {
        val r = rows[y]; val i = x * bpp
        return (0xFF shl 24) or ((r[i].toInt() and 255) shl 16) or ((r[i + 1].toInt() and 255) shl 8) or (r[i + 2].toInt() and 255)
    }

    companion object {
        fun read(f: File): TestPng {
            val inp = DataInputStream(f.inputStream().buffered())
            inp.skipBytes(8)
            var w = 0; var h = 0; var colorType = 0
            val idat = ByteArrayOutputStream()
            while (true) {
                val len = inp.readInt()
                val type = String(ByteArray(4).also { inp.readFully(it) }, Charsets.US_ASCII)
                val data = ByteArray(len).also { inp.readFully(it) }
                inp.readInt() // crc
                when (type) {
                    "IHDR" -> {
                        w = java.nio.ByteBuffer.wrap(data, 0, 4).int; h = java.nio.ByteBuffer.wrap(data, 4, 4).int
                        require(data[8].toInt() == 8 && data[12].toInt() == 0) { "only 8-bit non-interlaced" }
                        colorType = data[9].toInt()
                    }
                    "IDAT" -> idat.write(data)
                    "IEND" -> break
                }
            }
            val bpp = when (colorType) { 2 -> 3; 6 -> 4; else -> error("colour type $colorType") }
            val stride = w * bpp
            val raw = ByteArray((stride + 1) * h)
            Inflater().apply { setInput(idat.toByteArray()); var n = 0; while (n < raw.size && !finished()) n += inflate(raw, n, raw.size - n); end() }
            val rows = Array(h) { ByteArray(stride) }
            var prev = ByteArray(stride)
            for (y in 0 until h) {
                val filter = raw[y * (stride + 1)].toInt()
                val line = rows[y]
                System.arraycopy(raw, y * (stride + 1) + 1, line, 0, stride)
                for (x in 0 until stride) {
                    val a = if (x >= bpp) line[x - bpp].toInt() and 255 else 0
                    val b = prev[x].toInt() and 255
                    val c = if (x >= bpp) prev[x - bpp].toInt() and 255 else 0
                    val add = when (filter) {
                        0 -> 0; 1 -> a; 2 -> b; 3 -> (a + b) / 2
                        4 -> { val p = a + b - c; val pa = Math.abs(p - a); val pb = Math.abs(p - b); val pc = Math.abs(p - c)
                            if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c }
                        else -> error("filter $filter")
                    }
                    line[x] = ((line[x].toInt() and 255) + add).toByte()
                }
                prev = line
            }
            return TestPng(w, h, bpp, rows)
        }
    }
}
