package com.phasewake.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Befinden auf der Uhr nach dem Wecken. Gegenstück: `FeelingAskTests` (Swift), gleiche Fälle. */
class FeelingAskTest {
    /** Nur nach echtem Wecken, auf der Uhr gestoppt, und ohne schon bekanntes Befinden. */
    @Test
    fun asksOnlyAfterARealNightStoppedOnTheWatch() {
        assertTrue(FeelingAsk.asks(recorded = true, stoppedOnWatch = true))
        // Probewecker, Powernap, nie geklingelt: keine Nacht im Verlauf.
        assertFalse(FeelingAsk.asks(recorded = false, stoppedOnWatch = true))
        // Am Handy gestoppt, Zeitgrenze ohne Reaktion, Abbruch.
        assertFalse(FeelingAsk.asks(recorded = true, stoppedOnWatch = false))
        assertFalse(FeelingAsk.asks(recorded = true, stoppedOnWatch = true, known = true))
    }

    /** 60 s ab dem Stellen; genau 60 s danach und bei rückwärts gestellter Uhr nicht mehr. */
    @Test
    fun openForSixtySeconds() {
        assertEquals(60L, FeelingAsk.SECONDS)
        val at = 1_793_250_000_000L
        assertTrue(FeelingAsk.isOpen(at, at))
        assertTrue(FeelingAsk.isOpen(at, at + 59_999))
        assertFalse(FeelingAsk.isOpen(at, at + 60_000))
        assertFalse(FeelingAsk.isOpen(at, at - 1))
    }

    /** Gespeichertes Befinden hat Vorrang; ohne gespeichertes gilt das ankommende. */
    @Test
    fun storedFeelingWins() {
        assertEquals(4, FeelingAsk.merged(stored = 4, incoming = 2))
        assertEquals(2, FeelingAsk.merged(stored = null, incoming = 2))
        assertEquals(4, FeelingAsk.merged(stored = 4, incoming = null))
        assertNull(FeelingAsk.merged(stored = null, incoming = null))
    }
}
