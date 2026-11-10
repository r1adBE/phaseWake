package com.phasewake.app.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Meldung «Messung vor dem Schlafen nicht gestartet» (Audit F-06), gleich wie `RecorderSkipTests` (Swift). */
class RecorderSkipTest {
    private val target = Instant.ofEpochMilli(1_800_000_000_000L)

    @Test
    fun encodesAndDecodes() {
        val skip = RecorderSkip(target, RecorderSkip.Reason.BATTERY)
        assertEquals("1800000000000|battery", skip.encode())
        assertEquals(skip, RecorderSkip.decode(skip.encode()))
        assertEquals(RecorderSkip(target, RecorderSkip.Reason.MOTION), RecorderSkip.decode(" 1800000000000|motion\n"))
    }

    @Test
    fun unreadableOrUnknownIsNull() {
        assertNull(RecorderSkip.decode(null))
        assertNull(RecorderSkip.decode(""), "leer: keine Meldung")
        assertNull(RecorderSkip.decode("1800000000000"))
        assertNull(RecorderSkip.decode("x|battery"))
        assertNull(RecorderSkip.decode("0|battery"))
        assertNull(RecorderSkip.decode("1800000000000|later"), "unbekannter Grund einer neueren Uhr")
    }

    @Test
    fun appliesUntilTheTargetTime() {
        val skip = RecorderSkip(target, RecorderSkip.Reason.BATTERY)
        assertTrue(skip.appliesAt(target.minusSeconds(8 * 3600)))
        assertTrue(skip.appliesAt(target.minusMillis(1)))
        assertFalse(skip.appliesAt(target), "Nacht vorbei")
    }
}
