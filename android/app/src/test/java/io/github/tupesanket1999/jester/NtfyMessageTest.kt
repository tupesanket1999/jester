package io.github.tupesanket1999.jester

import io.github.tupesanket1999.jester.push.NtfyMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtfyMessageTest {
    // Shape of the hub's reminder pushes (docs/HUB_API.md), as delivered by ntfy's /json stream.
    private val reminder = """{"id":"sPs71M8A2T","time":1795003200,"event":"message","topic":"t","title":"❗ call mom",""" +
        """"message":"❗ call mom","priority":4,"tags":["alarm_clock"],"actions":[""" +
        """{"id":"x1","action":"http","label":"Done","url":"https://hub.example/api/act/done:a1B2-c_3.1795262400000.SIGsig-_","method":"POST","clear":true},""" +
        """{"id":"x2","action":"http","label":"Snooze 15m","url":"https://hub.example/api/act/snooze15:a1B2-c_3.1795262400000.SIG2","method":"POST","clear":true}]}"""

    @Test fun parsesHubReminder() {
        val m = NtfyMessage.parse(reminder)!!
        assertEquals("message", m.event)
        assertEquals("a1B2-c_3", m.taskId)
        assertEquals(2, m.level)
        assertEquals("call mom", m.cleanTitle)
        assertTrue(m.isReminder)
        assertEquals(4, m.priority)
    }

    @Test fun levelsFromPrefix() {
        assertEquals(1, NtfyMessage.parse("""{"event":"message","title":"⏰ x"}""")!!.level)
        assertEquals(3, NtfyMessage.parse("""{"event":"message","title":"‼️ x"}""")!!.level)
        assertEquals("x", NtfyMessage.parse("""{"event":"message","title":"‼️ x"}""")!!.cleanTitle)
    }

    @Test fun workSignalHasNoTaskId() {
        val m = NtfyMessage.parse("""{"id":"q","event":"message","title":"💼 Claude done (ws3)","tags":["briefcase"]}""")!!
        assertNull(m.taskId); assertFalse(m.isReminder)
    }

    @Test fun keepaliveAndGarbage() {
        assertEquals("keepalive", NtfyMessage.parse("""{"id":"k","time":1,"event":"keepalive","topic":"t"}""")!!.event)
        assertNull(NtfyMessage.parse("not json"))
    }

    @Test fun actUrlVariants() {
        assertEquals("abc", NtfyMessage.taskIdFromActUrl("https://h/api/act/done%3Aabc.123.sig"))
        assertNull(NtfyMessage.taskIdFromActUrl("https://h/api/act/broken"))
        assertNull(NtfyMessage.taskIdFromActUrl("https://h/other/done:abc.1.s"))
    }
}
