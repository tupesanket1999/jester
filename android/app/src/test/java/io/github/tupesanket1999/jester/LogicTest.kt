package io.github.tupesanket1999.jester

import io.github.tupesanket1999.jester.logic.DeepLink
import io.github.tupesanket1999.jester.logic.Link
import io.github.tupesanket1999.jester.logic.Looks
import io.github.tupesanket1999.jester.logic.NotifText
import io.github.tupesanket1999.jester.logic.Parsed
import io.github.tupesanket1999.jester.logic.PushTag
import io.github.tupesanket1999.jester.logic.QuickParse
import io.github.tupesanket1999.jester.logic.ReplyAction
import io.github.tupesanket1999.jester.logic.ReplyParser
import io.github.tupesanket1999.jester.logic.TextClean
import io.github.tupesanket1999.jester.logic.VoiceMode
import io.github.tupesanket1999.jester.logic.VoicePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Quick-add parser cases (no AI): NOW = 2026-11-18 14:00 (a Wednesday). */
class QuickParseTest {
    private val zone = ZoneId.of("UTC")
    private val now = LocalDateTime.of(2026, 11, 18, 14, 0).atZone(zone).toInstant()
    private fun p(s: String) = QuickParse.parse(s, now, zone)

    @Test fun relativeMinutes() { val r = p("call mom in 2 min"); assertEquals("call mom", r.title); assertEquals("2026-11-18 14:02", r.due) }
    @Test fun tomorrowAt6pmStripsRemindMe() { val r = p("remind me to buy milk tomorrow at 6pm"); assertEquals("buy milk", r.title); assertEquals("2026-11-19 18:00", r.due) }
    @Test fun bareTimePassedMeansTomorrow() = assertEquals("2026-11-19 09:00", p("stretch 9am").due)
    @Test fun bareTimeLaterToday() = assertEquals("2026-11-18 18:30", p("standup notes 18:30").due)
    @Test fun weekdays() {
        assertEquals("2026-11-20 09:00", p("pay rent friday").due)
        assertEquals("2026-11-25 11:00", p("dentist next wednesday 11am").due)
    }
    @Test fun dayMonthRollsYear() {
        assertEquals("2026-11-19 09:00", p("renew passport 19 nov").due)
        assertEquals("2027-01-10 09:00", p("tax filing 10 jan").due)
    }
    @Test fun recurrenceAndPriority() {
        val a = p("water plants every day 8am")
        assertEquals("water plants", a.title); assertEquals("every day", a.recur); assertEquals("2026-11-19 08:00", a.due)
        val b = p("gym every monday 7am !")
        assertEquals("every monday", b.recur); assertEquals("important", b.priority)
        val c = p("call bank !! today 5pm")
        assertEquals("urgent", c.priority); assertEquals("2026-11-18 17:00", c.due)
    }
    @Test fun plainTodo() = assertEquals(Parsed("buy coffee beans"), p("buy coffee beans"))
    @Test fun tonightIs8pm() = assertEquals("2026-11-18 20:00", p("call dad tonight").due)

    @Test fun chips() {
        val labels = QuickParse.chips(p("call mom tomorrow 6pm !"), now, zone).map { it.label }
        assertEquals(listOf("Tomorrow", "18:00", "Important"), labels)
        assertEquals(listOf("Repeats every day", "08:00"), QuickParse.chips(p("water plants every day 8am"), now, zone).map { it.label })
        assertTrue(QuickParse.chips(p("buy coffee beans"), now, zone).isEmpty())
        assertEquals("Friday", QuickParse.chips(p("pay rent friday"), now, zone).first().label)
    }
}

class PushAndNotifTest {
    private val zone = ZoneId.of("UTC")
    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 11, 19, h, m).atZone(zone).toInstant()

    @Test fun tagParse() {
        assertEquals(PushTag("4w1q4j0", "2026-11-19 13:52", 3), PushTag.parse(listOf("alarm_clock", "tt:4w1q4j0@2026-11-19T13:52#3")))
        assertNull(PushTag.parse(listOf("alarm_clock")))
        assertNull(PushTag.parse(listOf("tt:bad id@2026-11-19T13:52#1")))
        assertNull(PushTag.parse(listOf("tt:x@2026-11-19 13:52#1")))
    }

    @Test fun titles() {
        assertEquals("Reminder: Jester test ping", NotifText.title("⏰ Jester test ping", urgent = false))
        assertEquals("URGENT: Renew passport", NotifText.title("Renew passport ‼️", urgent = true))
        assertTrue(NotifText.title("x".repeat(80), false).length <= "Reminder: ".length + 48)
    }

    @Test fun bodies() {
        assertEquals("now · 1:52 PM", NotifText.body("2026-11-19 13:52", 1, at(13, 52), zone))
        assertEquals("20 min late · 3rd ping", NotifText.body("2026-11-19 13:52", 3, at(14, 12), zone))
        assertEquals("in 8 min · 2:00 PM", NotifText.body("2026-11-19 14:00", 1, at(13, 52), zone))
        assertEquals("2 h late · 2nd ping", NotifText.body("2026-11-19 11:00", 2, at(13, 30), zone))
        assertEquals("Reminder", NotifText.body(null, 1, at(13, 0), zone))
        assertEquals("11th", NotifText.ordinal(11)); assertEquals("22nd", NotifText.ordinal(22))
    }

    @Test fun emojiStripped() = assertEquals("call mom now", TextClean.noEmoji("📞 call mom ⏰ now ‼️"))

    @Test fun replies() {
        val now = LocalDateTime.of(2026, 11, 19, 14, 0)
        assertEquals(ReplyAction.Done, ReplyParser.parse("Done", now))
        assertEquals(ReplyAction.Done, ReplyParser.parse("ok!", now))
        assertEquals(ReplyAction.Snooze(30), ReplyParser.parse("30 min", now))
        assertEquals(ReplyAction.Snooze(10), ReplyParser.parse("10", now))
        assertEquals(ReplyAction.Snooze(120), ReplyParser.parse("2h", now))
        assertEquals(ReplyAction.Snooze(420), ReplyParser.parse("Tonight", now))
        assertEquals(ReplyAction.Snooze(60), ReplyParser.parse("tonight", LocalDateTime.of(2026, 11, 19, 22, 0)))
        assertEquals(ReplyAction.Snooze(19 * 60), ReplyParser.parse("tomorrow", now))
        assertEquals(ReplyAction.Snooze(15), ReplyParser.parse("snooze", now))
        assertEquals(ReplyAction.Unknown, ReplyParser.parse("what is this", now))
        assertEquals(ReplyAction.Snooze(7 * 1440), ReplyParser.parse("99999", now))
    }
}

class DeepLinkTest {
    private val tok = "x".repeat(20) // obviously fake
    @Test fun pair() {
        val url = DeepLink.pairUrl("https://hub.example.dev", "topic_abc123", tok)
        assertEquals(Link.Pair("https://hub.example.dev", "topic_abc123", tok, DeepLink.DEFAULT_NTFY), DeepLink.parse(url))
        val withNtfy = DeepLink.pairUrl("https://hub.example.dev/", "topic_abc123", tok, "https://ntfy.example.org")
        assertEquals("https://ntfy.example.org", (DeepLink.parse(withNtfy) as Link.Pair).ntfy)
    }
    @Test fun pairRejects() {
        assertNull(DeepLink.parse(DeepLink.pairUrl("http://hub.example.dev", "topic_abc123", tok)))
        assertNull(DeepLink.parse(DeepLink.pairUrl("https://user@hub.example.dev", "topic_abc123", tok)))
        assertNull(DeepLink.parse(DeepLink.pairUrl("https://localhost", "topic_abc123", tok)))
        assertNull(DeepLink.parse(DeepLink.pairUrl("https://hub.example.dev", "bad topic!", tok)))
        assertNull(DeepLink.parse(DeepLink.pairUrl("https://hub.example.dev", "topic_abc123", "short")))
        assertNull(DeepLink.parse("jester://pair?hub=https%3A%2F%2Fa.dev&hub=https%3A%2F%2Fb.dev&topic=topic_abc123&token=$tok"))
        assertNull(DeepLink.parse("https://pair?hub=x"))
        assertNull(DeepLink.parse("jester://nope?x=1"))
    }
    @Test fun actions() {
        assertEquals(Link.Add("buy milk 6pm"), DeepLink.parse("jester://add?text=buy%20milk%206pm"))
        assertEquals(Link.Ask("what is left"), DeepLink.parse("jester://ask?text=what+is+left"))
        assertEquals(Link.Focus(25), DeepLink.parse("jester://focus?min=25"))
        assertNull(DeepLink.parse("jester://focus?min=999"))
        assertNull(DeepLink.parse("jester://add?text="))
        assertNull(DeepLink.parse("jester://add?text=" + "a".repeat(1001)))
    }
}

class LooksAndVoiceTest {
    @Test fun autoTheme() {
        assertEquals("mango", Looks.auto(6)); assertEquals("mango", Looks.auto(11))
        assertEquals("circus", Looks.auto(12)); assertEquals("circus", Looks.auto(20))
        assertEquals("sea", Looks.auto(21)); assertEquals("sea", Looks.auto(3))
        assertEquals("neon", Looks.next("circus")); assertEquals("circus", Looks.next("sea"))
        assertEquals(6, Looks.ALL.size)
        assertEquals(Looks.get("circus"), Looks.get("nonsense"))
    }

    @Test fun voicePolicy() {
        val q0 = 23 * 60; val q1 = 7 * 60
        assertTrue(VoicePolicy.inQuiet(23 * 60 + 30, q0, q1)); assertTrue(VoicePolicy.inQuiet(6 * 60, q0, q1))
        assertFalse(VoicePolicy.inQuiet(12 * 60, q0, q1)); assertFalse(VoicePolicy.inQuiet(5, 0, 0))
        val noon = 12 * 60
        assertTrue(VoicePolicy.shouldSpeak(VoiceMode.EARBUDS, false, true, noon, q0, q1))
        assertFalse(VoicePolicy.shouldSpeak(VoiceMode.EARBUDS, true, false, noon, q0, q1))
        assertTrue(VoicePolicy.shouldSpeak(VoiceMode.URGENT, true, false, noon, q0, q1))
        assertFalse(VoicePolicy.shouldSpeak(VoiceMode.URGENT, false, true, noon, q0, q1))
        assertTrue(VoicePolicy.shouldSpeak(VoiceMode.ALWAYS, false, false, noon, q0, q1))
        assertFalse(VoicePolicy.shouldSpeak(VoiceMode.ALWAYS, true, true, 0, q0, q1)) // quiet hours win
        assertFalse(VoicePolicy.shouldSpeak(VoiceMode.OFF, true, true, noon, q0, q1))
        assertTrue(VoicePolicy.shouldSpeak(VoiceMode.OFF, false, false, 0, q0, q1, explicit = true))
        assertEquals("Done. Passport saga over", VoicePolicy.clean("✅ **Done.** Passport saga over"))
    }
}
