package com.phasewake.app.learn

import android.content.Context
import com.phasewake.app.MemoryStore
import com.phasewake.app.QuietApp
import com.phasewake.app.core.Epoch
import com.phasewake.app.core.Learning
import com.phasewake.app.phoneApp
import com.phasewake.app.shared.NightRecording
import com.phasewake.app.shared.RecordEnd
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lernphase am Handy: Beginn nach der Installation, Zählen der Aufzeichnungen der Uhr, nach 7 Nächten die gelernten
 * Werte an die Uhr, «Neu lernen» bzw. «Verlauf löschen» von vorn ([LearningRepository], [com.phasewake.app.PhaseWakeApp.updateLearning]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietApp::class)
class LearningFlowTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val app get() = context.phoneApp as QuietApp
    private val since = Instant.ofEpochMilli(System.currentTimeMillis())

    @Before
    fun setUp() {
        File(context.filesDir, "recordings").deleteRecursively()
        // Der DataStore lebt über einen Test hinaus: Lernphase ab jetzt.
        runBlocking { app.learning.restart(since) }
        app.learningSent.clear()
    }

    @After
    fun cleanUp() {
        app.appScope.cancel()
        File(context.filesDir, "recordings").deleteRecursively()
    }

    /** Aufzeichnung der Nacht [day] Tage nach [since] mit [epochs] Epochen mit Bewegung und Puls. */
    private fun recording(day: Long, epochs: Int, floor: Double = 0.02, pulse: Double = 54.0): NightRecording {
        val start = since.plus(Duration.ofDays(day)).plusSeconds(60)
        return NightRecording(
            start, List(epochs) { Epoch(floor, pulse) }, start.plusSeconds(epochs * 30L), RecordEnd.WAKE,
            bedAt = start, asleepAt = start.plus(Duration.ofMinutes(14)),
        )
    }

    private fun receive(recording: NightRecording) = runBlocking {
        app.recordings.put(recording)
        app.updateLearning(recording)
    }

    private fun diag() = app.diag.entries().filter { !it.at.isBefore(since) }.map { it.detail }

    /** Wartet, bis das Diagnose-Protokoll (schreibt im Hintergrund) eine Zeile hat, für die [match] gilt. */
    private fun awaitDiag(match: (String) -> Boolean) {
        val until = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (diag().none(match)) {
            check(System.nanoTime() < until) { "nicht im Protokoll: ${diag()}" }
            Thread.sleep(5)
        }
    }

    @Test
    fun sevenCountedNightsFinishAndTellTheWatch() {
        receive(recording(0, Learning.MIN_MEASURED_EPOCHS - 1))
        awaitDiag { it == "learn night not counted epochs=359 0/7" }
        for (day in 1L..6L) receive(recording(day, 400))
        assertNull(runBlocking { app.learning.current() }.values)
        awaitDiag { it == "learn night counted 6/7" }
        assertTrue(app.learningSent.isEmpty(), "noch nichts an die Uhr")
        receive(recording(7, 400, floor = 0.03, pulse = 50.0))
        val state = runBlocking { app.learning.current() }
        val values = assertNotNull(state.values)
        assertEquals(Learning.NIGHTS, values.nights)
        assertEquals(0.02, values.floor)
        assertEquals(54.0, values.restingHeartRate)
        assertEquals(14, values.latencyMinutes)
        assertFalse(state.active)
        assertFalse(state.seen)
        // An die Uhr: Lernphase vorbei, gelernte Werte für den Detektor.
        val sent = app.learningSent.single()
        assertFalse(sent.active)
        assertEquals(0.02, sent.floor)
        assertEquals(54.0, sent.restingHeartRate)
        awaitDiag { it == "learn done active=0 floor=0.0200 hr=set lat=14" }
        // Weitere Aufzeichnungen ändern nichts mehr.
        receive(recording(8, 400, floor = 0.04))
        assertEquals(values, runBlocking { app.learning.current() }.values)
    }

    @Test
    fun restartForgetsTheValuesAndCountsOnlyNewNights() {
        // Lernphase seit elf Tagen, sieben Nächte vor heute.
        runBlocking { app.learning.restart(since.minus(Duration.ofDays(11))) }
        for (day in -10L..-4L) receive(recording(day, 400))
        assertNotNull(runBlocking { app.learning.current() }.values)
        val restartedAt = Instant.now()
        runBlocking { app.restartLearning() }
        val state = runBlocking { app.learning.current() }
        assertNull(state.values)
        assertTrue(state.active)
        assertFalse(assertNotNull(state.since).isBefore(restartedAt.minusMillis(1)))
        assertTrue(app.learningSent.last().active)
        assertNull(app.learningSent.last().floor)
        // Die alten Aufzeichnungen liegen vor dem Neubeginn: zählen nicht.
        runBlocking { app.updateLearning() }
        assertNull(runBlocking { app.learning.current() }.values)
    }

    @Test
    fun repositoryStartsOnceAndFinishesOnce() = runBlocking {
        val repo = LearningRepository(MemoryStore())
        assertEquals(LearningState(null, null, false, 0L), repo.current())
        assertFalse(repo.current().active)
        assertTrue(repo.ensureStarted(since))
        assertFalse(repo.ensureStarted(since.plusSeconds(60)), "nur nach der Installation bzw. dem ersten Start")
        assertEquals(since, repo.current().since)
        assertTrue(repo.current().active)
        val values = Learning.Values(0.02, null, 12, 7)
        assertTrue(repo.finish(values, since.plusSeconds(120)))
        assertFalse(repo.finish(values.copy(floor = 0.03), since.plusSeconds(180)), "einmal fertig")
        assertEquals(values, repo.current().values)
        assertEquals(since.plusSeconds(120).toEpochMilli(), repo.current().updatedAt)
        repo.setSeen()
        assertTrue(repo.current().seen)
        repo.restart(since.plusSeconds(240))
        assertEquals(LearningState(since.plusSeconds(240), null, false, since.plusSeconds(240).toEpochMilli()), repo.current())
    }
}
