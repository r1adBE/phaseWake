package com.phasewake.app.shared

import com.phasewake.app.core.Epoch
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «Nacht aufzeichnen»: Textformat, Grenzen (12 Std., Akku) und CSV-Export ([NightRecording]). */
class NightRecordingTest {
    private val start = Instant.parse("2026-11-07T22:00:00Z")
    private val epochs = listOf(Epoch(0.0213, 61.5), Epoch(null, null), Epoch(0.5, null), Epoch(null, 58.0))

    @Test
    fun roundTripRunningAndFinished() {
        val running = NightRecording(start, epochs)
        assertEquals(running, NightRecording.decode(NightRecording.encode(running)))
        assertFalse(running.finished)
        val done = running.copy(end = start.plusSeconds(120), endReason = RecordEnd.PHONE)
        assertEquals(done, NightRecording.decode(NightRecording.encode(done)))
        assertTrue(done.finished)
        assertEquals(Duration.ofMinutes(2), done.duration)
    }

    @Test
    fun appendedFileReadsLikeEncoded() {
        // Wie die Uhr schreibt: Kopf, Epochen in Stücken, Schlusszeile.
        val text = NightRecording.header(start) + NightRecording.epochLines(epochs.take(2)) + NightRecording.epochLines(epochs.drop(2)) +
            NightRecording.endLine(start.plusSeconds(150), RecordEnd.CAP)
        assertEquals(NightRecording(start, epochs, start.plusSeconds(150), RecordEnd.CAP), NightRecording.decode(text))
    }

    @Test
    fun toleratesCrashWhileWriting() {
        // Halbe letzte Zeile (Absturz beim Schreiben) und kaputte Zeile fallen weg, der Rest bleibt.
        val text = NightRecording.header(start) + NightRecording.epochLines(epochs.take(1)) + "x;y\n" + "0.03;6"
        assertEquals(NightRecording(start, epochs.take(1)), NightRecording.decode(text))
        assertNull(NightRecording.decode("phasewake-night 1\n"), "fremdes Format")
        assertNull(NightRecording.decode(NightRecording.HEADER + "\n"), "ohne Beginn")
        // Unbekannter Grund aus einer neueren Version.
        val unknown = NightRecording.header(start) + "end=${start.plusSeconds(30).toEpochMilli()},future\n"
        assertEquals(RecordEnd.SERVICE, NightRecording.decode(unknown)?.endReason)
    }

    @Test
    fun endsAfterTwelveHoursOrOnLowBattery() {
        assertNull(NightRecording.endNow(start, start.plus(Duration.ofHours(11)), 80))
        assertNull(NightRecording.endNow(start, start, null), "Akku unbekannt")
        assertEquals(RecordEnd.CAP, NightRecording.endNow(start, start.plus(Duration.ofHours(12)), 80))
        assertEquals(RecordEnd.BATTERY, NightRecording.endNow(start, start.plusSeconds(60), 14))
        assertNull(NightRecording.endNow(start, start.plusSeconds(60), 15), "15 % reicht noch")
        assertEquals(1440, NightRecording.MAX_EPOCHS)
    }

    @Test
    fun epochsUntilForTheGapAfterAProcessEnd() {
        assertEquals(0, NightRecording.epochsUntil(start, start.minusSeconds(5)))
        assertEquals(20, NightRecording.epochsUntil(start, start.plusSeconds(10 * 60 + 29)))
        assertEquals(NightRecording.MAX_EPOCHS, NightRecording.epochsUntil(start, start.plus(Duration.ofHours(20))))
    }

    @Test
    fun csvWithoutDatesNumberedOldestFirst() {
        val later = NightRecording(start.plus(Duration.ofDays(1)), listOf(Epoch(0.1, 70.04)), start.plus(Duration.ofDays(1)).plusSeconds(30), RecordEnd.BATTERY)
        val first = NightRecording(start, epochs.take(2), start.plusSeconds(60), RecordEnd.WATCH)
        val running = NightRecording(start.plus(Duration.ofDays(2)), epochs)
        val csv = NightRecording.exportCsv(listOf(later, running, first))
        assertEquals(
            "recording,end_reason,epoch,motion,hr\n" +
                "1,watch,0,0.021300,61.5\n" +
                "1,watch,1,,\n" +
                "2,battery,0,0.100000,70.0\n",
            csv,
        )
        assertFalse(csv.contains("2026"), "keine Datumsangaben")
    }
}
