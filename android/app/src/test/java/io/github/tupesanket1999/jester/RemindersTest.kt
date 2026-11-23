package io.github.tupesanket1999.jester

import io.github.tupesanket1999.jester.reminders.Reminders
import io.github.tupesanket1999.jester.reminders.ShownLog
import io.github.tupesanket1999.jester.reminders.TaskLite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class RemindersTest {
    private val zone = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2026, 11, 18, 17, 0, 0, 0, zone).toInstant().toEpochMilli()

    private val tasks = listOf(
        TaskLite("a", "call mom", false, "2026-11-18 18:00"),
        TaskLite("b", "milk", true, "2026-11-18 18:30"),          // done
        TaskLite("c", "far", false, "2026-11-19 17:01"),          // just past 24 h
        TaskLite("d", "edge", false, "2026-11-19 17:00"),         // exactly 24 h
        TaskLite("e", "late", false, "2026-11-18 16:50"),         // 10 min late: within grace
        TaskLite("f", "old", false, "2026-11-18 16:30"),          // 30 min late: skip
        TaskLite("g", "no due", false, null),
        TaskLite("h", "bad due", false, "tomorrow"),
        TaskLite("i", "soon", false, "2026-11-18 17:05"),
    )

    @Test fun plansNext24HoursSortedNotDone() {
        assertEquals(listOf("e", "i", "a", "d"), Reminders.plan(tasks, now, zone).map { it.id })
    }

    @Test fun floatingTimeFollowsDeviceTimezone() {
        assertEquals("2026-11-19T18:00:00Z", java.time.Instant.ofEpochMilli(Reminders.dueToEpochMs("2026-11-19 18:00", zone)!!).toString())
        assertEquals("2026-11-19T23:00:00Z", java.time.Instant.ofEpochMilli(Reminders.dueToEpochMs("2026-11-19 18:00", ZoneId.of("America/New_York"))!!).toString())
        // DST gap (London, 2026-03-29 01:30 does not exist): moves forward an hour, never lost
        assertEquals("2026-03-29T01:30:00Z", java.time.Instant.ofEpochMilli(Reminders.dueToEpochMs("2026-03-29 01:30", ZoneId.of("Europe/London"))!!).toString())
    }

    @Test fun alarmSkippedWhenPushAlreadyShownIt() {
        val shown = ShownLog()
        assertTrue(Reminders.shouldPostAlarm("a", "2026-11-18 18:00", shown, tasks))
        shown.mark(Reminders.key("a", "2026-11-18 18:00"), 1, now)            // push arrived first
        assertFalse(Reminders.shouldPostAlarm("a", "2026-11-18 18:00", shown, tasks))
        assertEquals(listOf("e", "i", "d"), Reminders.plan(tasks, now, zone, shown).map { it.id })
    }

    @Test fun recurringNextDueIsANewReminder() {
        val shown = ShownLog()
        shown.mark(Reminders.key("a", "2026-11-18 18:00"), 1, now)
        val moved = listOf(TaskLite("a", "call mom", false, "2026-11-19 18:00"))
        assertTrue(Reminders.shouldPostAlarm("a", "2026-11-19 18:00", shown, moved))
    }

    @Test fun alarmSkippedWhenDoneOrMovedInCache() {
        val shown = ShownLog()
        assertFalse(Reminders.shouldPostAlarm("b", "2026-11-18 18:30", shown, tasks))           // done
        assertFalse(Reminders.shouldPostAlarm("a", "2026-11-18 17:30", shown, tasks))           // due moved to 18:00
        assertTrue(Reminders.shouldPostAlarm("zz", "2026-11-18 17:30", shown, tasks))          // unknown: remind
        assertTrue(Reminders.shouldPostAlarm("a", "2026-11-18 18:00", shown, null))            // no cache: remind
    }

    @Test fun pushAfterAlarmIsSilentUnlessEscalated() {
        val shown = ShownLog()
        val k = Reminders.key("a", "2026-11-18 18:00")
        assertTrue(Reminders.shouldAlertPush("a", "2026-11-18 18:00", 1, shown))
        shown.mark(k, 1, now)                                                                    // alarm showed it
        assertFalse(Reminders.shouldAlertPush("a", "2026-11-18 18:00", 1, shown))
        assertTrue(Reminders.shouldAlertPush("a", "2026-11-18 18:00", 2, shown))               // re-nag at 10 min
        shown.mark(k, 2, now)
        assertFalse(Reminders.shouldAlertPush("a", "2026-11-18 18:00", 2, shown))
        assertEquals(2, shown.level(k))
        shown.mark(k, 1, now)
        assertEquals(2, shown.level(k))                                                          // never goes down
        assertTrue(Reminders.shouldAlertPush("x", null, 1, shown))                              // unknown due: alert
    }

    @Test fun shownLogRoundTripAndPrune() {
        val s = ShownLog()
        s.mark("a@2026-11-18 18:00", 2, 1000)
        s.mark("b@2026-11-10 09:00", 1, 0)
        val back = ShownLog.parse(s.serialize() + "\ngarbage line")
        assertEquals(2, back.level("a@2026-11-18 18:00"))
        assertTrue(back.wasShown("b@2026-11-10 09:00"))
        back.prune(nowMs = 3 * 24 * 3600_000L + 500)
        assertFalse(back.wasShown("b@2026-11-10 09:00"))
        assertTrue(back.wasShown("a@2026-11-18 18:00"))
    }
}
