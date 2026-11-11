package com.phasewake.app.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RingMessageTest {

    @Test
    fun roundTrip() {
        val message = RingMessage(alarmId = "a1", target = 1_800_000L, sentAt = 1_790_000L, snoozesLeft = 2)
        assertEquals("a1|1800000|1790000|2|", message.encode().decodeToString())
        assertEquals(message, RingMessage.decode(message.encode()))
        val snoozed = RingMessage(alarmId = "a1", target = 1_800_000L, sentAt = 1_790_000L, snoozeUntil = 2_100_000L)
        assertEquals("a1|1800000|1790000||2100000", snoozed.encode().decodeToString())
        assertEquals(snoozed, RingMessage.decode(snoozed.encode()))
    }

    @Test
    fun snoozeSafetyNinetySecondsAfterSnoozeEnd() {
        val snoozed = RingMessage(alarmId = "a1", target = 1_800_000L, snoozeUntil = 2_100_000L)
        assertEquals(2_190_000L, snoozed.snoozeSafetyAt(2_000_000L))
        // Verspätet zugestellt: Sicherung schon vorbei, die Uhr hat inzwischen wieder geklingelt.
        assertNull(snoozed.snoozeSafetyAt(2_190_000L))
        // Endgültiges «stop»: keine Sicherung.
        assertNull(RingMessage(alarmId = "a1", target = 1_800_000L).snoozeSafetyAt(0L))
    }

    @Test
    fun sameNightToleratesMissingFields() {
        val ring = RingMessage(alarmId = "a1", target = 1_800_000L)
        assertTrue(ring.sameNight("a1", 1_800_000L))
        assertTrue(ring.sameNight(null, null))
        assertTrue(ring.sameNight("a1", null))
        assertTrue(RingMessage(alarmId = null).sameNight("a1", 1_800_000L))
        assertFalse(ring.sameNight("b2", 1_800_000L))
        assertFalse(ring.sameNight("a1", 1_900_000L))
    }

    @Test
    fun missingFieldsStayNull() {
        val stop = RingMessage(alarmId = "a1", target = 1_800_000L, sentAt = 1_790_000L)
        assertEquals(stop, RingMessage.decode(stop.encode()))
        assertEquals(RingMessage(alarmId = null), RingMessage.decode(RingMessage(alarmId = null).encode()))
    }

    @Test
    fun withoutSendTimeFromOutdatedApp() {
        // Veraltete Apps schickten nur die Kennung (oder nichts): ohne Sendezeit, der Empfänger verwirft sie.
        assertFalse(RingMessage.decode("a1".encodeToByteArray()).isCurrent())
        assertFalse(RingMessage.decode(ByteArray(0)).isCurrent())
        assertFalse(RingMessage.decode(null).isCurrent())
        assertTrue(RingMessage(alarmId = "a1", sentAt = 5L).isCurrent())
        // «klingeln» braucht dazu Kennung und Schlummer-Runden.
        assertTrue(RingMessage("a1", 1_800_000L, 5L, snoozesLeft = 2).isCompleteRing())
        assertFalse(RingMessage("a1", 1_800_000L, 5L).isCompleteRing())
        assertFalse(RingMessage(null, 1_800_000L, 5L, snoozesLeft = 2).isCompleteRing())
        assertFalse(RingMessage("a1", 1_800_000L, null, snoozesLeft = 2).isCompleteRing())
    }

    @Test
    fun unreadableNumbersAndExtraFieldsAreIgnored() {
        assertEquals(
            RingMessage(alarmId = "a1", target = null, sentAt = 5L, snoozesLeft = null),
            RingMessage.decode("a1|x|5||neu".encodeToByteArray()),
        )
    }

    @Test
    fun staleOnlyWithSendTimeOlderThanMaxAge() {
        val sent = RingMessage(alarmId = "a1", sentAt = 1_000_000L)
        assertEquals(false, sent.isStale(1_000_000L + RingMessage.MAX_AGE_MS))
        assertEquals(true, sent.isStale(1_000_001L + RingMessage.MAX_AGE_MS))
        assertEquals(false, RingMessage(alarmId = "a1").isStale(Long.MAX_VALUE))
    }

    @Test
    fun implausibleTimesCountAsMissing() {
        // Audit AN-5: Long.MAX_VALUE als Sende- oder Zielzeit lief beim Rechnen über; jetzt wie fehlend, «stop» verworfen.
        val max = Long.MAX_VALUE
        val decoded = RingMessage.decode("a1|$max|$max|2|$max".encodeToByteArray())
        assertEquals(RingMessage(alarmId = "a1", snoozesLeft = 2), decoded)
        assertEquals(false, decoded.isCurrent())
        assertEquals(RingMessage(alarmId = "a1", target = null, sentAt = null), RingMessage.decode("a1|-5|-1".encodeToByteArray()))
    }
}
