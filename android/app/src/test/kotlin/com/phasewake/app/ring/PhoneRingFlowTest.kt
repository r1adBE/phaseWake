package com.phasewake.app.ring

import com.phasewake.app.core.Snooze
import com.phasewake.app.core.Transient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Klingeln am Handy ([PhoneRingService]) über seine Entscheidungen in [RingRules]: Zeitgrenze → selbst schlummern,
 * solange Runden bleiben, letzte Runde → Ende; Wiederaufnahme-Alarm nur, wenn das Handy führt; Ablösen eines zweiten
 * Weckers. Feste Zeiten, ohne Gerät.
 */
class PhoneRingFlowTest {
    private val now = 1_800_000_000_000L

    @Test
    fun timeoutSnoozesWhileRoundsLeftThenStops() {
        // Das Handy führt (ohne Uhr): schlummern, solange Runden bleiben; die letzte Zeitgrenze beendet.
        assertEquals(RingRules.Timeout.SNOOZE, RingRules.timeout(ringing = true, fromWatch = false, snoozesLeft = 3))
        assertEquals(RingRules.Timeout.SNOOZE, RingRules.timeout(ringing = true, fromWatch = false, snoozesLeft = 1))
        assertEquals(RingRules.Timeout.STOP, RingRules.timeout(ringing = true, fromWatch = false, snoozesLeft = 0))
        // Die Uhr führt: Handy still, eigene Sicherung nach ihrem Schlummer-Ende; ohne Runden ebenfalls Ende.
        assertEquals(RingRules.Timeout.STOP_WITH_SAFETY, RingRules.timeout(ringing = true, fromWatch = true, snoozesLeft = 2))
        assertEquals(RingRules.Timeout.STOP, RingRules.timeout(ringing = true, fromWatch = true, snoozesLeft = 0))
        // Nichts klingelt (schon beendet oder geschlummert): nur aufräumen.
        assertEquals(RingRules.Timeout.STOP, RingRules.timeout(ringing = false, fromWatch = false, snoozesLeft = 3))
    }

    @Test
    fun wholeMorningWithoutWatchEndsAfterLastRound() {
        // Klingeln → Zeitgrenze → Schlummern → Schlummer-Alarm (bisherige Runden) → … bis zur letzten Runde.
        var done = 0
        val rounds = mutableListOf<RingRules.Timeout>()
        while (true) {
            val left = RingRules.snoozesLeft("a", fromWatch = false, watchSnoozesLeft = null, legacyWatchSnoozes = 0, snoozesDone = done)
            val timeout = RingRules.timeout(ringing = true, fromWatch = false, snoozesLeft = left)
            rounds += timeout
            if (timeout != RingRules.Timeout.SNOOZE) break
            done = RingRules.snoozeDone(left)!!
        }
        assertEquals(List(Snooze.MAX_COUNT) { RingRules.Timeout.SNOOZE } + RingRules.Timeout.STOP, rounds)
        assertNull(RingRules.snoozeDone(0), "letzte Runde: kein Schlummern mehr")
    }

    @Test
    fun snoozeRoundsComeFromTheLeader() {
        // Handy führt: allein die Zahl aus dem Schlummer- bzw. Wiederaufnahme-Alarm (übersteht ein Prozessende).
        assertEquals(Snooze.MAX_COUNT - 2, RingRules.snoozesLeft("a", false, watchSnoozesLeft = 3, legacyWatchSnoozes = 0, snoozesDone = 2))
        // Uhr führt: ihre Angabe; ältere Uhr ohne Angabe: der Zähler im Prozess.
        assertEquals(1, RingRules.snoozesLeft("a", true, watchSnoozesLeft = 1, legacyWatchSnoozes = 0, snoozesDone = 0))
        assertEquals(Snooze.MAX_COUNT - 2, RingRules.snoozesLeft("a", true, watchSnoozesLeft = null, legacyWatchSnoozes = 2, snoozesDone = 0))
        // Begrenzt auf 0 … MAX_COUNT; der Probewecker schlummert nie.
        assertEquals(Snooze.MAX_COUNT, RingRules.snoozesLeft("a", true, watchSnoozesLeft = 9, legacyWatchSnoozes = 0, snoozesDone = 0))
        assertEquals(0, RingRules.snoozesLeft("a", false, watchSnoozesLeft = null, legacyWatchSnoozes = 0, snoozesDone = 7))
        assertEquals(0, RingRules.snoozesLeft(Transient.TEST_PREFIX + "1", false, watchSnoozesLeft = null, legacyWatchSnoozes = 0, snoozesDone = 0))
    }

    @Test
    fun resumeAlarmOnlyWhenPhoneLeads() {
        // Kurz nach der Zeitgrenze: Der Ablauf im Prozess (Schlummern oder Ende) kommt immer zuerst.
        assertEquals(now + RingRules.TIMEOUT_MS + RingRules.RESUME_MARGIN_MS, RingRules.resumeAt(fromWatch = false, nowMs = now))
        assertTrue(RingRules.resumeAt(false, now)!! > now + RingRules.TIMEOUT_MS)
        // Führt die Uhr, hat sie ihren eigenen Wiederaufnahme-Alarm.
        assertNull(RingRules.resumeAt(fromWatch = true, nowMs = now))
    }

    @Test
    fun secondAlarmTakesOverAndStartsFresh() {
        // Wecker «a» klingelt (eine Runde geschlummert), «b» kommt: Ablösen, «b» beginnt mit allen Runden.
        assertEquals(RingRules.Incoming.TAKE_OVER, RingRules.incoming(true, "b", now + 300_000, "a", now))
        assertEquals(Snooze.MAX_COUNT, RingRules.snoozesLeft("b", false, watchSnoozesLeft = null, legacyWatchSnoozes = 0, snoozesDone = 0))
        // Schlummer-Ende derselben Nacht (ohne Zielzeit) ist kein Ablösen.
        assertEquals(RingRules.Incoming.SAME_NIGHT, RingRules.incoming(true, "a", null, "a", now))
        assertEquals(RingRules.Incoming.NEW_ROUND, RingRules.incoming(false, "a", null, "a", now))
    }
}
