package com.phasewake.app.wear.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** Prüfrunde 2 B-7, Galaxy Watch wie Apple Watch: «Gute Nacht» aufheben fragt zuerst; starten wie bisher. */
class GoodNightTapTest {
    @Test
    fun cancelAsksFirst() {
        assertEquals(GoodNightTap.CONFIRM_CANCEL, goodNightTap(active = true, tooFar = false))
        // Läuft sie, zählt «zu weit weg» nicht: Aufheben bleibt möglich, mit Rückfrage.
        assertEquals(GoodNightTap.CONFIRM_CANCEL, goodNightTap(active = true, tooFar = true))
    }

    @Test
    fun startOnlyWhenNotTooFar() {
        assertEquals(GoodNightTap.START, goodNightTap(active = false, tooFar = false))
        assertEquals(GoodNightTap.NONE, goodNightTap(active = false, tooFar = true))
    }
}
