package com.phasewake.app.shared

import com.phasewake.app.core.AlarmSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Dokumentierte Zahlen des Abgleichs mit Literalen (Audit BE-3): Grabstein 90 Tage (DATENSCHUTZ), Warteschlange 45 s und
 * 14 Tage, «klingeln» veraltet nach 2 Min, Gleichstand Wecker/Grabstein. Je Grenze Wert und Wert + 1, nicht nur
 * `KONSTANTE ± 1`: Eine geänderte Zahl macht einen Test rot.
 */
class SyncConstantsTest {
    private val day = 86_400_000L

    @Test
    fun tombstoneIsKeptNinetyDaysInclusive() {
        assertEquals(90 * day, AlarmStamps.TOMBSTONE_MAX_AGE_MS)
        val deleted = mapOf("x" to 1_000L)
        assertEquals(deleted, AlarmStamps.prune(deleted, 1_000L + 7_776_000_000L), "genau 90 Tage: bleibt")
        assertEquals(emptyMap(), AlarmStamps.prune(deleted, 1_000L + 7_776_000_001L), "90 Tage + 1 ms: weg")
    }

    @Test
    fun deletionWinsATieWithTheAlarm() {
        val a = AlarmSpec(id = "a", hour = 7, minute = 0)
        val alarm = AlarmSnapshot(listOf(a), 5_000L, mapOf("a" to 5_000L))
        val gone = AlarmSnapshot(emptyList(), 5_000L, deleted = mapOf("a" to 5_000L))
        assertEquals(emptyList(), AlarmStamps.merge(alarm, gone, 5_000L).alarms, "gleicher Zeitpunkt: gelöscht")
        assertEquals(emptyList(), AlarmStamps.merge(gone, alarm, 5_000L).alarms, "von beiden Seiten gleich")
        val newer = alarm.copy(stamps = mapOf("a" to 5_001L), updatedAt = 5_001L)
        assertEquals(listOf(a), AlarmStamps.merge(newer, gone, 5_001L).alarms, "1 ms neuer: der Wecker bleibt")
    }

    @Test
    fun queueResendsAfterFortyFiveSecondsAndDropsAfterFourteenDays() {
        assertEquals(45_000L, Outbox.RESEND_AFTER_MS)
        assertFalse(Outbox.resendDue(1_000L, 1_000L + 44_999L))
        assertTrue(Outbox.resendDue(1_000L, 1_000L + 45_000L))
        assertEquals(14 * day, Outbox.MAX_AGE_MS)
        assertEquals(1_209_600_000L, Outbox.MAX_AGE_MS)
    }

    @Test
    fun ringMessageIsStaleAfterTwoMinutes() {
        assertEquals(120_000L, RingMessage.MAX_AGE_MS)
        val sent = RingMessage("a", 1_000L, 1_000L)
        assertFalse(sent.isStale(1_000L + 120_000L), "genau 2 Min: noch gültig")
        assertTrue(sent.isStale(1_000L + 120_001L))
    }
}
