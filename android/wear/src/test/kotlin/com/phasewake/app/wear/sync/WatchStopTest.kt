package com.phasewake.app.wear.sync

import com.phasewake.app.core.WakePlan
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * «stop» vom Handy, während die Uhr noch misst (Audit A-7): beendet die Nacht nur, wenn es genau sie meint (Wecker und
 * Zielzeit); sonst läuft die Messung weiter. Ausserhalb der Messung entscheidet wie bisher der Weckdienst.
 */
class WatchStopTest {
    private val target = Instant.parse("2026-11-12T06:00:00Z")
    private val measuring = WakePlan("a", target.minusSeconds(1_800), target)

    @Test
    fun stopWhileMeasuringMustMatchTheNight() {
        assertFalse(WatchListenerService.ignoresStopWhileMeasuring(measuring, "a", target.toEpochMilli()), "genau diese Nacht")
        assertTrue(WatchListenerService.ignoresStopWhileMeasuring(measuring, "a", target.minusSeconds(86_400).toEpochMilli()), "andere Zielzeit")
        assertTrue(WatchListenerService.ignoresStopWhileMeasuring(measuring, "a", null), "ohne Zielzeit")
        assertTrue(WatchListenerService.ignoresStopWhileMeasuring(measuring, null, target.toEpochMilli()), "ohne Kennung")
        assertTrue(WatchListenerService.ignoresStopWhileMeasuring(measuring, "b", target.toEpochMilli()), "anderer Wecker")
    }

    @Test
    fun withoutMeasuringNothingIsIgnoredHere() {
        assertFalse(WatchListenerService.ignoresStopWhileMeasuring(null, "a", null))
        assertFalse(WatchListenerService.ignoresStopWhileMeasuring(null, null, null))
    }
}
