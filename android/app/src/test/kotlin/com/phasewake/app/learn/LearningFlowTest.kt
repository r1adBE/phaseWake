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
 * Lernphase am Handy: Beginn nur mit Zustimmung (Audit F-02), Zählen der Aufzeichnungen der Uhr, nach 7 Nächten die gelernten
 * Werte an die Uhr, «Verlauf löschen» von vorn, «Neu lernen» mit den bisherigen Werten bis zum Ende ([LearningRepository], [com.phasewake.app.PhaseWakeApp.updateLearning]).
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
        // Der DataStore lebt über einen Test hinaus: Lernphase ab jetzt, zugestimmt.
        runBlocking {
            app.learning.restart(since)
            app.learning.setConsent(true, since)
        }
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
        receive(recording(0, Learning.MIN_SPAN_EPOCHS - 1))
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
    fun relearnKeepsTheOldValuesUntilSevenNewNights() {
        // Gelernt aus sieben Nächten vor heute (Boden 0.02, Puls 54).
        runBlocking { app.learning.restart(since.minus(Duration.ofDays(11))) }
        for (day in -10L..-4L) receive(recording(day, 400))
        val old = assertNotNull(runBlocking { app.learning.current() }.values)
        runBlocking { app.learning.setSeen() }
        app.learningSent.clear()
        runBlocking { app.relearn() }
        val running = runBlocking { app.learning.current() }
        assertTrue(running.active)
        assertTrue(running.relearning)
        assertEquals(old, running.values, "bisherige Werte gelten weiter")
        // An die Uhr: aufzeichnen (aktiv) und weiter mit den bisherigen Werten messen.
        val sent = app.learningSent.last()
        assertTrue(sent.active)
        assertEquals(0.02, sent.floor)
        assertEquals(54.0, sent.restingHeartRate)
        // Alte Aufzeichnungen zählen nicht; nach sieben neuen Nächten ersetzen die neuen Werte die alten.
        for (day in 1L..6L) receive(recording(day, 400, floor = 0.03, pulse = 50.0))
        assertEquals(old, runBlocking { app.learning.current() }.values)
        receive(recording(7, 400, floor = 0.03, pulse = 50.0))
        val done = runBlocking { app.learning.current() }
        assertEquals(0.03, assertNotNull(done.values).floor)
        assertEquals(50.0, done.values.restingHeartRate)
        assertFalse(done.active)
        assertFalse(done.relearning)
        assertFalse(done.seen, "«Angepasst an dich» zeigt die neuen Werte")
        assertFalse(app.learningSent.last().active)
        assertEquals(0.03, app.learningSent.last().floor)
    }

    @Test
    fun repositoryRelearnWithoutValuesStartsOver() = runBlocking {
        val repo = LearningRepository(MemoryStore())
        repo.setConsent(true, since)
        repo.relearn(since.plusSeconds(60))
        assertEquals(LearningState(since.plusSeconds(60), null, false, since.plusSeconds(60).toEpochMilli(), consent = true), repo.current())
        // «Verlauf löschen» nach «Neu lernen»: Werte und Neu-lernen-Stand weg.
        repo.finish(Learning.Values(0.02, null, 12, 7), since.plusSeconds(120))
        repo.relearn(since.plusSeconds(180))
        assertTrue(repo.current().relearning)
        repo.restart(since.plusSeconds(240))
        assertEquals(LearningState(since.plusSeconds(240), null, false, since.plusSeconds(240).toEpochMilli(), consent = true), repo.current())
    }

    @Test
    fun repositoryStartsOnlyWithConsentAndFinishesOnce() = runBlocking {
        val repo = LearningRepository(MemoryStore())
        assertEquals(LearningState(null, null, false, 0L), repo.current())
        assertFalse(repo.current().active)
        assertTrue(repo.current().askConsent)
        assertTrue(repo.setConsent(true, since))
        assertFalse(repo.setConsent(true, since.plusSeconds(60)), "beginnt nur einmal")
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
        assertEquals(LearningState(since.plusSeconds(240), null, false, since.plusSeconds(240).toEpochMilli(), consent = true), repo.current())
    }

    @Test
    fun noAutomaticStartWithoutConsent() = runBlocking {
        val repo = LearningRepository(MemoryStore())
        // «Später»: gefragt, aber keine Lernphase, die Uhr zeichnet nicht auf.
        assertFalse(repo.setConsent(false, since))
        val later = repo.current()
        assertNull(later.since)
        assertFalse(later.active)
        assertFalse(later.askConsent, "nur einmal fragen")
        assertFalse(later.snapshot().active)
        // Bestehende Installation (Lernphase vor dieser Version begonnen, nie gefragt): läuft nicht, bis zugestimmt.
        val old = LearningRepository(MemoryStore())
        old.restart(since)
        assertFalse(old.current().active)
        assertTrue(old.current().askConsent)
        assertFalse(old.current().snapshot().active)
        assertFalse(old.setConsent(true, since.plusSeconds(60)), "läuft mit den bisher gezählten Nächten weiter")
        assertEquals(since, old.current().since)
        assertTrue(old.current().active)
    }

    @Test
    fun consentStartsAndSwitchOffStopsTheWatchKeepingValues() {
        runBlocking { app.setLearningConsent(false) }
        assertFalse(app.learningSent.last().active, "aus: die Uhr zeichnet nicht automatisch auf")
        // Aus: Aufzeichnungen zählen nicht, nichts wird gelernt.
        for (day in 1L..7L) receive(recording(day, 400))
        assertNull(runBlocking { app.learning.current() }.values)
        awaitDiag { it == "learn consent off" }
        // Wieder an: läuft weiter, die Nächte seit Beginn zählen, fertig nach sieben.
        runBlocking { app.setLearningConsent(true) }
        val done = runBlocking { app.learning.current() }
        val values = assertNotNull(done.values)
        assertTrue(done.consent == true)
        awaitDiag { it == "learn consent on" }
        // Aus nach dem Lernen: gelernte Werte bleiben, auch auf der Uhr.
        app.learningSent.clear()
        runBlocking { app.setLearningConsent(false) }
        assertEquals(values, runBlocking { app.learning.current() }.values)
        val sent = app.learningSent.last()
        assertFalse(sent.active)
        assertEquals(values.floor, sent.floor)
    }

    /**
     * Fix KN-7: Der Zeitpunkt der Lernphase steigt streng, auch bei zwei Änderungen in derselben Millisekunde oder nach
     * einer zurückgestellten Uhrzeit (die Uhr übernimmt nur einen neueren Stand); ein kaputter alter zählt nicht.
     */
    @Test
    fun learningStampRisesStrictly() = runBlocking {
        val repo = LearningRepository(MemoryStore())
        val at = Instant.ofEpochMilli(5_000)
        repo.setConsent(true, at)
        assertEquals(5_000L, repo.current().updatedAt)
        repo.relearn(at)
        assertEquals(5_001L, repo.current().updatedAt)
        repo.restart(at.minusSeconds(60))
        assertEquals(5_002L, repo.current().updatedAt)
        val broken = LearningRepository(
            MemoryStore(
                androidx.datastore.preferences.core.emptyPreferences().toMutablePreferences().apply {
                    this[androidx.datastore.preferences.core.longPreferencesKey("learning_updated_at")] = Long.MAX_VALUE
                },
            ),
        )
        broken.setConsent(false, at)
        assertEquals(5_000L, broken.current().updatedAt)
    }
}
