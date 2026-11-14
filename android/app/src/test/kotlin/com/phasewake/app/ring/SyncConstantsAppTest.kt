package com.phasewake.app.ring

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Dokumentierte Zahlen des Zweitweckers mit Literalen (Audit BE-3): Nachfrist 2 Min, «keine Uhr mehr» nach 7 Tagen ohne
 * Meldung. Je Grenze Wert und Wert + 1: Eine geänderte Zahl macht einen Test rot.
 */
class SyncConstantsAppTest {
    @Test
    fun backupWaitsTwoMinutesForTheWatch() {
        assertEquals(120_000L, BackupAlarm.GRACE.toMillis())
    }

    @Test
    fun watchCountsAsGoneAfterSevenDaysWithoutStatus() {
        assertEquals(Duration.ofDays(7), RingRules.WATCH_GONE)
        val grace = Duration.ofMinutes(2)
        val seen = 1_000L
        assertEquals(grace, RingRules.backupGrace(seen, seen + 604_800_000L, grace), "genau 7 Tage: Uhr gilt noch")
        assertEquals(Duration.ZERO, RingRules.backupGrace(seen, seen + 604_800_001L, grace), "7 Tage + 1 ms: ohne Uhr")
    }
}
