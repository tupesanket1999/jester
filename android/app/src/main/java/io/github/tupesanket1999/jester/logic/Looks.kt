package io.github.tupesanket1999.jester.logic

/** The six outfits from the approved mockup (colors are the mockup's CSS tokens, ARGB). Pure data, no Android types. */
data class Tokens(
    val dark: Boolean,
    val bg: Long, val surface: Long, val line: Long, val hair: Long,
    val text: Long, val muted: Long, val muted2: Long,
    val accent: Long, val onAccent: Long, val accentSoft: Long, val accentInk: Long,
    val hot: Long, val onHot: Long, val hotSurface: Long, val hotLine: Long, val hotInk: Long,
    val ok: Long, val hatL: Long, val hatR: Long, val bell: Long, val face: Long, val cheek: Long,
    val nav: Long, val toastBg: Long, val toastText: Long, val toastAction: Long,
    val alarmBg: Long, val alarmText: Long, val alarmInk: Long, val alarmLine: Long, val alarmBtn: Long, val alarmBtnText: Long,
    val trackOff: Long, val knobOff: Long,
)

object Looks {
    val ORDER = listOf("circus", "neon", "mango", "matcha", "bubble", "sea")
    val NAMES = mapOf(
        "circus" to "Midnight Circus", "neon" to "Neon Arcade", "mango" to "Mango Sunset",
        "matcha" to "Matcha Terminal", "bubble" to "Bubblegum Pop", "sea" to "Deep Sea Disco",
    )
    val VIBES = mapOf(
        "circus" to "Velvet night, two-tone hat.", "neon" to "80s arcade at 2 AM.", "mango" to "Light, warm, morning person.",
        "matcha" to "Hacker green, calm focus.", "bubble" to "Pink, loud, unapologetic.", "sea" to "Glowing, chill, late night.",
    )

    /** Auto outfit: Mango 06–12, Circus 12–21, Deep Sea 21–06. */
    fun auto(hour: Int): String = when (hour) {
        in 6..11 -> "mango"
        in 12..20 -> "circus"
        else -> "sea"
    }

    fun next(id: String): String = ORDER[(ORDER.indexOf(id).coerceAtLeast(0) + 1) % ORDER.size]

    fun get(id: String): Tokens = ALL[id] ?: ALL.getValue("circus")

    private fun c(hex: String): Long = 0xFF000000L or hex.removePrefix("#").toLong(16)

    val ALL: Map<String, Tokens> = mapOf(
        "circus" to Tokens(true,
            c("#0F1016"), c("#191B24"), c("#2C2F3D"), c("#232634"), c("#F2F1EC"), c("#A3A6B6"), c("#8A8EA0"),
            c("#9D8CFF"), c("#14151D"), c("#262338"), c("#C9BFFF"),
            c("#FF9B4A"), c("#1D1209"), c("#2A1E16"), c("#5A3A22"), c("#FFC79E"),
            c("#5ED6B4"), c("#9D8CFF"), c("#FF9B4A"), c("#F5C542"), c("#DCE3F2"), c("#FF8FA3"),
            c("#14151D"), c("#ECEAF7"), c("#14151D"), c("#5B47D6"),
            c("#1D1209"), c("#FFF4EA"), c("#FFD9BC"), c("#6B4A30"), c("#FF9B4A"), c("#1D1209"),
            c("#3A3D4C"), c("#A3A6B6")),
        "neon" to Tokens(true,
            c("#07060B"), c("#14111C"), c("#2E2640"), c("#221B30"), c("#F7F3FF"), c("#B4A9C9"), c("#9A8FB0"),
            c("#FF3EA5"), c("#1A0010"), c("#33142A"), c("#FF9AD0"),
            c("#FFE14D"), c("#1A1600"), c("#221F0A"), c("#5C5214"), c("#FFF09A"),
            c("#26F0F1"), c("#FF3EA5"), c("#26F0F1"), c("#FFE14D"), c("#E9E4FF"), c("#FF3EA5"),
            c("#0C0A12"), c("#26F0F1"), c("#04181A"), c("#8A005A"),
            c("#141100"), c("#FFFBE0"), c("#FFF09A"), c("#5C5214"), c("#FFE14D"), c("#141100"),
            c("#2E2640"), c("#B4A9C9")),
        "mango" to Tokens(false,
            c("#FFF6EC"), c("#FFFFFF"), c("#F1DCC6"), c("#F4E6D8"), c("#2B1630"), c("#6E5468"), c("#7A6274"),
            c("#7A2E8E"), c("#FFFFFF"), c("#F3E3F6"), c("#6A2280"),
            c("#C2410C"), c("#FFFFFF"), c("#FFE9DA"), c("#F7C4A0"), c("#9A3412"),
            c("#2F9E6B"), c("#7A2E8E"), c("#FF8A00"), c("#FFC233"), c("#FFE7D1"), c("#FF7F9E"),
            c("#FFFFFF"), c("#2B1630"), c("#FFF6EC"), c("#FFB86B"),
            c("#7C2D12"), c("#FFF6EC"), c("#FFD8B8"), c("#B4532A"), c("#FFB86B"), c("#3B1405"),
            c("#B9A7B5"), c("#FFFFFF")),
        "matcha" to Tokens(true,
            c("#0A100D"), c("#131C17"), c("#24342B"), c("#1B2822"), c("#E8F5E9"), c("#9DB8A6"), c("#86A08F"),
            c("#9BE15D"), c("#0B1606"), c("#1E2D17"), c("#C3F09A"),
            c("#FFB547"), c("#1F1300"), c("#2A2112"), c("#5A4420"), c("#FFD79A"),
            c("#9BE15D"), c("#9BE15D"), c("#FFB547"), c("#FFE08A"), c("#D9F2DF"), c("#FF9FB0"),
            c("#0D1511"), c("#9BE15D"), c("#0B1606"), c("#2E5A0E"),
            c("#1A1405"), c("#FFF6E5"), c("#FFD79A"), c("#5A4420"), c("#FFB547"), c("#1F1300"),
            c("#24342B"), c("#9DB8A6")),
        "bubble" to Tokens(false,
            c("#FFF0F6"), c("#FFFFFF"), c("#F6CFE0"), c("#FBE3EE"), c("#2A1020"), c("#7A4D63"), c("#865A70"),
            c("#D6246E"), c("#FFFFFF"), c("#FCE1EE"), c("#A3164F"),
            c("#1F6FEB"), c("#FFFFFF"), c("#E3EEFF"), c("#B9D3FF"), c("#1849A9"),
            c("#12A27A"), c("#D6246E"), c("#3BA6FF"), c("#FFC233"), c("#FFE3EF"), c("#FF8CC0"),
            c("#FFFFFF"), c("#2A1020"), c("#FFF0F6"), c("#FF8CC0"),
            c("#0F2B66"), c("#F0F6FF"), c("#B9D3FF"), c("#2F5BB0"), c("#FF8CC0"), c("#2A1020"),
            c("#C9A3B5"), c("#FFFFFF")),
        "sea" to Tokens(true,
            c("#061321"), c("#0D2033"), c("#1B3550"), c("#15304A"), c("#E6F4FF"), c("#9DB6CC"), c("#87A2BA"),
            c("#38E1D3"), c("#032421"), c("#0F3138"), c("#8FF2E9"),
            c("#FF7A6B"), c("#2A0703"), c("#2A1820"), c("#5A2E33"), c("#FFB8AE"),
            c("#38E1D3"), c("#38E1D3"), c("#FF7A6B"), c("#FFD166"), c("#D4ECFF"), c("#FF9FB0"),
            c("#081829"), c("#38E1D3"), c("#032421"), c("#0B5C55"),
            c("#2A0F10"), c("#FFF0EE"), c("#FFB8AE"), c("#6A2E33"), c("#FF7A6B"), c("#2A0703"),
            c("#1B3550"), c("#9DB6CC")),
    )

    val TONES = listOf("Cheeky", "Hype me up", "Roast me", "Zen")

    /** The hero line on Today, by tone and how many things are left. */
    fun quip(tone: String, left: Int): String = when (tone) {
        "Hype me up" -> if (left == 0) "Clean sweep. Absolute legend." else "$left to go. You are unstoppable today."
        "Roast me" -> if (left == 0) "Nothing left? Suspicious. Did you cheat?" else "$left left. Even I am getting tired of reminding you."
        "Zen" -> if (left == 0) "All done. Breathe out." else "$left left. One at a time."
        else -> if (left == 0) "All clear. I am almost proud of you." else "$left left today. Let us not make it ${left + 1}."
    }

    fun mood(left: Int): String = when {
        left == 0 -> "SMUG"
        left > 3 -> "NERVOUS"
        else -> "CHEEKY"
    }
}

enum class VoiceMode(val label: String) { OFF("Off"), EARBUDS("Earbuds only"), URGENT("Urgent only"), ALWAYS("Always") }

object VoicePolicy {
    /** Minutes since midnight; quiet window may wrap midnight (23:00–07:00). Equal start/end = no quiet hours. */
    fun inQuiet(minute: Int, start: Int, end: Int): Boolean = when {
        start == end -> false
        start < end -> minute in start until end
        else -> minute >= start || minute < end
    }

    /** [explicit] = the speaker button: always speaks. Otherwise quiet hours mute, then the mode decides. */
    fun shouldSpeak(mode: VoiceMode, urgent: Boolean, earbuds: Boolean, minute: Int, quietStart: Int, quietEnd: Int, explicit: Boolean = false): Boolean {
        if (explicit) return true
        if (inQuiet(minute, quietStart, quietEnd)) return false
        return when (mode) {
            VoiceMode.OFF -> false
            VoiceMode.EARBUDS -> earbuds
            VoiceMode.URGENT -> urgent
            VoiceMode.ALWAYS -> true
        }
    }

    /** Text for TTS: no emoji, no markdown symbols, capped. */
    fun clean(text: String): String = TextClean.noEmoji(text).replace(Regex("""[*_`#>|~]"""), " ").replace(Regex("""\s+"""), " ").trim().take(400)
}
