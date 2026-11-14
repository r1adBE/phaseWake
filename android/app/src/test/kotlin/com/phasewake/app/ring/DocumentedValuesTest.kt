package com.phasewake.app.ring

import com.phasewake.app.core.Transient
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Dokumentierte Zahlen des Handys als Literale (Audit BE-3): Die übrigen Tests rechnen mit der Konstante selbst
 * (`X.CONST ± 1`); hier fällt eine Änderung des Werts auf.
 */
class DocumentedValuesTest {
    private val at = Instant.parse("2026-11-03T06:30:00Z")

    @Test
    fun ringTimeoutIsTenMinutes() {
        assertEquals(600_000L, RingRules.TIMEOUT_MS)
        val now = at.toEpochMilli()
        assertEquals(now + 600_000L + 30_000L, RingRules.resumeAt(now))
    }

    @Test
    fun restartCatchesUpForThirtyMinutes() {
        assertEquals(Duration.ofMinutes(30), BackupAlarm.MISSED_DURING_BOOT)
        fun catchesUp(after: Duration) = Transient.catchesUpAfterBoot("a", at, at.plus(after), BackupAlarm.MISSED_DURING_BOOT)
        assertTrue(catchesUp(Duration.ofMinutes(29)))
        assertTrue(catchesUp(Duration.ofMinutes(30)))
        assertFalse(catchesUp(Duration.ofMinutes(31)))
    }
}
