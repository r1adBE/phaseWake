package com.phasewake.app.shared

import com.phasewake.app.core.Epoch
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Grenze für Inhalte der Gegenseite (Audit S-2). */
class PeerLimitTest {
    @Test
    fun readsUpToTheLimit() {
        val data = ByteArray(40_000) { (it % 251).toByte() }
        assertContentEquals(data, PeerLimit.read(ByteArrayInputStream(data), max = 40_000))
        assertContentEquals(ByteArray(0), PeerLimit.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun abortsAboveTheLimitWithoutReadingEverything() {
        var served = 0L
        // Endloser Datenstrom: ohne Grenze liefe der Speicher voll.
        val endless = object : InputStream() {
            override fun read(): Int = 0.also { served++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int = len.also { served += it }
        }
        assertNull(PeerLimit.read(endless, max = 100_000))
        assertTrue(served < 200_000, "gelesen: $served")
    }

    @Test
    fun fitsChecksSize() {
        assertTrue(PeerLimit.fits(null))
        assertTrue(PeerLimit.fits(ByteArray(10), max = 10))
        assertFalse(PeerLimit.fits(ByteArray(11), max = 10))
    }

    /** Die längste Aufzeichnung (12 Std., ungünstige Zahlen) liegt weit unter der Grenze. */
    @Test
    fun longestRecordingIsFarBelowTheLimit() {
        val epochs = List(NightRecording.MAX_EPOCHS) { Epoch(motion = 1.2345678901234567E-5 + it, heartRate = 62.333333333333336) }
        val start = Instant.parse("2026-11-10T22:00:00Z")
        val text = NightRecording.encode(NightRecording(start, epochs, start.plus(NightRecording.MAX_DURATION), null, null, null))
        val bytes = text.encodeToByteArray().size
        assertEquals(1440, NightRecording.MAX_EPOCHS)
        assertTrue(bytes < 100_000, "Grösse: $bytes")
        assertTrue(bytes * 50 < PeerLimit.MAX_BYTES)
    }
}
