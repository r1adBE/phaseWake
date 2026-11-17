package com.phasewake.app.health

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Health Connect höchstens alle 5 Min neu lesen (Audit PERF-05), ausser nach einer Erteilung der Berechtigung, ohne
 * Zugriff dazwischen oder für einen längeren Zeitraum. Feste Zeiten.
 */
class HealthReadGateTest {
    private val now = Instant.parse("2026-11-17T07:00:00Z")
    private val from = now.minus(Duration.ofDays(92))

    @Test
    fun firstReadThenAtMostEveryFiveMinutes() {
        val gate = HealthReadGate()
        assertTrue(gate.due(now, from, grant = 0), "noch nie gelesen")
        gate.mark(now, from, 0)
        // Fortsetzen, Verlauf öffnen: innert 5 Min nicht noch einmal.
        val soon = now.plus(HealthReadGate.MIN_INTERVAL).minusSeconds(1)
        assertFalse(gate.due(soon, from.plusSeconds(299), grant = 0))
        assertTrue(gate.due(now.plus(HealthReadGate.MIN_INTERVAL), from.plusSeconds(300), grant = 0))
    }

    @Test
    fun grantLongerRangeClockBackAndResetReadAtOnce() {
        val gate = HealthReadGate()
        gate.mark(now, from, 0)
        val soon = now.plusSeconds(10)
        assertTrue(gate.due(soon, from, grant = 1), "Berechtigung eben erteilt")
        assertTrue(gate.due(soon, from.minus(Duration.ofDays(30)), grant = 0), "ältere Nächte: weiter zurück")
        assertTrue(gate.due(now.minusSeconds(60), from, grant = 0), "Uhr zurückgestellt")
        gate.reset()
        assertTrue(gate.due(soon, from, grant = 0), "ohne Zugriff zurückgesetzt")
        gate.mark(soon, from, 1)
        assertFalse(gate.due(soon.plusSeconds(1), from, grant = 1), "dieselbe Erteilung zählt nur einmal")
    }
}
