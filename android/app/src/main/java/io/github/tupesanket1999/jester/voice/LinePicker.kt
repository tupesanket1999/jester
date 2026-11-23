package io.github.tupesanket1999.jester.voice

import kotlin.random.Random

/** Picks a random line per pool, never the same line twice in a row for that pool (mirrors Mac `Voice.pick`). */
class LinePicker(private val random: Random = Random.Default) {
    private val lastLine = HashMap<String, String>()

    fun pick(pool: String, lines: List<String>): String {
        val choices = if (lines.size > 1) lines.filter { it != lastLine[pool] } else lines
        val line = if (choices.isEmpty()) "" else choices[random.nextInt(choices.size)]
        lastLine[pool] = line
        return line
    }
}
