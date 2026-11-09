package com.phasewake.app.shared

import com.google.android.gms.wearable.DataMap
import com.phasewake.app.core.Epoch
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Speicher der Aufzeichnungen (AtomicFile, DataMap: Robolectric) und ihre Pakete und Befehle im Data Layer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordingRepositoryTest {
    private val dir: File = Files.createTempDirectory("recordings").toFile()
    private val start = Instant.parse("2026-11-07T22:00:00Z")

    private fun finished(at: Instant, reason: RecordEnd = RecordEnd.WATCH) =
        NightRecording(at, listOf(Epoch(0.02, 60.0)), at.plusSeconds(30), reason)

    @Test
    fun appendsIncrementallyAndFinishes() = runTest {
        val repo = RecordingRepository(dir, Dispatchers.Unconfined)
        repo.begin(start)
        repo.append(start, listOf(Epoch(0.01, 55.0)))
        repo.append(start, listOf(Epoch(null, null), Epoch(0.2, null)))
        assertEquals(NightRecording(start, listOf(Epoch(0.01, 55.0), Epoch(null, null), Epoch(0.2, null))), repo.active())
        assertTrue(repo.load().isEmpty(), "laufende zählt nicht als abgeschlossen")
        repo.finish(start, start.plusSeconds(90), RecordEnd.CAP)
        assertNull(repo.active())
        assertEquals(listOf(RecordEnd.CAP), repo.load().map { it.endReason })
        assertEquals(3, RecordingRepository(dir, Dispatchers.Unconfined).load().single().epochs.size, "neu gelesen")
    }

    @Test
    fun halfWrittenLineIsCutBeforeTheNextAppend() = runTest {
        val repo = RecordingRepository(dir, Dispatchers.Unconfined)
        repo.begin(start)
        repo.append(start, listOf(Epoch(0.01, 55.0)))
        // Absturz mitten im Schreiben: halbe Zeile ohne Zeilenende.
        File(dir, "rec-${start.toEpochMilli()}").appendText("0.0")
        assertEquals(1, repo.active()?.epochs?.size)
        repo.append(start, listOf(Epoch(0.03, 57.0)))
        assertEquals(listOf(Epoch(0.01, 55.0), Epoch(0.03, 57.0)), repo.active()?.epochs)
    }

    @Test
    fun keepsTheLatestFourteenAndClears() = runTest {
        val repo = RecordingRepository(dir, Dispatchers.Unconfined)
        repeat(16) { repo.put(finished(start.plusSeconds(it * 86_400L))) }
        val kept = repo.load()
        assertEquals(NightRecording.MAX_KEPT, kept.size)
        assertEquals(start.plusSeconds(2 * 86_400L), kept.first().start, "die ältesten zwei sind weg")
        // «Verlauf löschen» bis zu einem Zeitpunkt; eine laufende bleibt.
        val running = start.plusSeconds(30 * 86_400L)
        repo.begin(running)
        repo.clear(before = start.plusSeconds(10 * 86_400L + 30))
        assertEquals(5, repo.load().size)
        repo.clear()
        assertTrue(repo.load().isEmpty())
        assertEquals(running, repo.active()?.start)
    }

    @Test
    fun dataLayerStatusRecordingAndCommands() {
        val status = RecordSync.statusRequest(start)
        assertEquals(DataLayer.PATH_RECORD_STATUS, status.uri.path)
        assertTrue(status.isUrgent)
        assertEquals(RecordSync.RecordStatus(start), RecordSync.readStatus(DataMap.fromByteArray(status.data!!)))
        assertEquals(RecordSync.RecordStatus(null), RecordSync.readStatus(DataMap.fromByteArray(RecordSync.statusRequest(null).data!!)))

        val recording = finished(start, RecordEnd.BATTERY)
        val request = RecordSync.recordingRequest(recording)
        assertEquals(DataLayer.PATH_RECORDING_PREFIX + start.toEpochMilli(), request.uri.path)
        assertEquals(recording, RecordSync.readRecording(DataMap.fromByteArray(request.data!!)))
        assertNull(RecordSync.readRecording(DataMap()), "ohne Inhalt")
        val running = DataMap().apply { putString(DataLayer.KEY_TEXT, NightRecording.encode(recording.copy(end = null, endReason = null))) }
        assertNull(RecordSync.readRecording(running), "nur abgeschlossene")

        assertEquals(RecordSync.Command.START, RecordSync.commandOf(RecordSync.commandPath(start = true)))
        assertEquals(RecordSync.Command.STOP, RecordSync.commandOf(RecordSync.commandPath(start = false)))
        assertNull(RecordSync.commandOf(DataLayer.PATH_STOP), "Weckstopp ist kein Aufzeichnungsbefehl")
        assertNull(RecordSync.commandOf(null))
    }

    @Test
    fun withoutWatchNothingThrows() {
        // Wie [WearSyncOfflineTest]: Ob die Dienste mit Fehler antworten oder gar nicht, ist offen; darum mit Frist.
        val sync = RecordSync(RuntimeEnvironment.getApplication())
        fun tolerant(block: suspend () -> Unit) = runBlocking { withTimeoutOrNull(2_000L) { block() } }
        tolerant { sync.publishStatus(start) }
        tolerant { sync.publishRecording(finished(start)) }
        tolerant { sync.fetchStatus() }
        tolerant { sync.fetchRecordings() }
        tolerant { sync.deleteRecordings() }
        tolerant { assertEquals(false, sync.command(start = true)) }
    }

    @Test
    fun onsetGoesIntoTheRunningRecordingOnly() = runTest {
        val repo = RecordingRepository(dir, Dispatchers.Unconfined)
        val bed = start.plusSeconds(600)
        val asleep = start.plusSeconds(1_800)
        // Ohne Datei: nichts.
        repo.appendOnset(start, bed, asleep)
        assertNull(repo.active())
        repo.begin(start)
        repo.append(start, listOf(Epoch(0.01, 55.0)))
        repo.appendOnset(start, bed, asleep)
        repo.append(start, listOf(Epoch(0.02, 54.0)))
        val running = repo.active()
        assertEquals(listOf(Epoch(0.01, 55.0), Epoch(0.02, 54.0)), running?.epochs)
        assertEquals(bed, running?.bedAt)
        assertEquals(asleep, running?.asleepAt)
        repo.finish(start, start.plusSeconds(60), RecordEnd.WAKE)
        // Abgeschlossen: keine Zeile mehr.
        repo.appendOnset(start, bed.plusSeconds(60), asleep.plusSeconds(60))
        val done = repo.load().single()
        assertEquals(asleep, done.asleepAt)
        assertEquals(RecordEnd.WAKE, done.endReason)
    }
}
