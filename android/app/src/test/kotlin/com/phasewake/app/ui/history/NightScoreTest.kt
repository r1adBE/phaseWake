package com.phasewake.app.ui.history

import android.content.Context
import com.phasewake.app.QuietApp
import com.phasewake.app.R
import com.phasewake.app.core.Epoch
import com.phasewake.app.core.HealthSession
import com.phasewake.app.core.HistoryItem
import com.phasewake.app.core.HistoryKind
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.ScoreFactor
import com.phasewake.app.core.SleepStage
import com.phasewake.app.core.StageSpan
import com.phasewake.app.core.WakeReason
import com.phasewake.app.shared.NightRecording
import com.phasewake.app.shared.RecordEnd
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Schlafwert und Messreihe einer Nacht in der Oberfläche: Phasen haben Vorrang, ohne Phasen zählt eine Aufzeichnung mit
 * erkanntem Einschlafen, Puls und Bewegung kommen aus der Aufzeichnung, sonst aus dem Weckfenster; Quelle als Text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietApp::class)
class NightScoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val t0: Instant = Instant.parse("2026-11-08T21:00:00Z")

    private fun at(minute: Long): Instant = t0.plus(Duration.ofMinutes(minute))

    /** Fenster 06:30–07:00 (Minute 570–600 ab 21:00 UTC), geweckt Minute 590. */
    private val night = NightRecord("a", at(570), at(600), at(590), WakeReason.LIGHT_SLEEP, 0, List(40) { Epoch(0.01, 60.0) })
    private val item = HistoryItem(HistoryKind.PHASE_WAKE, LocalDate.of(2026, 11, 8), night = night)

    private fun recording(asleepAt: Instant? = at(20), from: Long = 0) =
        NightRecording(at(from), List(1180) { Epoch(0.01, 55.0) }, at(590), RecordEnd.WAKE, bedAt = at(from), asleepAt = asleepAt)

    @Test
    fun stagesComeFirst() {
        val stages = listOf(StageSpan(at(0), at(10), SleepStage.AWAKE), StageSpan(at(10), at(590), SleepStage.LIGHT))
        val score = nightScore(nightView(item, stages), listOf(recording()), 450)!!
        assertEquals(ScoreSource.HEALTH, score.source)
        assertEquals(ScoreFactor.entries, score.result.factors.map { it.factor })
    }

    @Test
    fun recordingWithOnsetGivesDurationAndLatency() {
        val score = nightScore(nightView(item, emptyList()), listOf(recording()), 450)!!
        assertEquals(ScoreSource.RECORDING, score.source)
        assertEquals(570L, score.input.asleepMinutes)
        assertEquals(20L, score.input.latencyMinutes)
        assertEquals(listOf(ScoreFactor.DURATION, ScoreFactor.LATENCY), score.result.factors.map { it.factor })
        assertEquals(100, score.result.score)
        assertEquals(context.getString(R.string.score_source_recording), scoreSourceText(context, item, score, emptyList()))
    }

    @Test
    fun noScoreWithoutStagesOrOnset() {
        assertNull(nightScore(nightView(item, emptyList()), emptyList(), 450))
        assertNull(nightScore(nightView(item, emptyList()), listOf(recording(asleepAt = null)), 450))
        // Aufzeichnung einer anderen Nacht zählt nicht.
        assertNull(nightScore(nightView(item, emptyList()), listOf(recording(from = 2000).copy(end = at(2600))), 450))
    }

    @Test
    fun seriesFromRecordingElseWindow() {
        val full = nightSeries(item, listOf(recording()))!!
        assertTrue(full.fullNight)
        assertEquals(at(0), full.start)
        val window = nightSeries(item, emptyList())!!
        assertFalse(window.fullNight)
        assertEquals(night.windowStart, window.start)
        assertEquals(40, window.epochs.size)
    }

    @Test
    fun sessionWithoutStagesCountsItsDuration() {
        val session = HealthSession(at(0), at(400), source = null)
        val sessionItem = HistoryItem(HistoryKind.HEALTH, LocalDate.of(2026, 11, 8), session = session)
        val score = nightScore(nightView(sessionItem, emptyList()), emptyList(), 450)!!
        assertEquals(400L, score.input.asleepMinutes)
        // Bei einer Sitzung nennt der Kopf die Quelle; beim Schlafwert nicht noch einmal.
        assertNull(scoreSourceText(context, sessionItem, score, emptyList()))
    }

    @Test
    fun ownNightNamesTheHealthSource() {
        val stages = listOf(StageSpan(at(0), at(590), SleepStage.LIGHT))
        val score = nightScore(nightView(item, stages), emptyList(), 450)!!
        assertEquals(context.getString(R.string.score_source_health), scoreSourceText(context, item, score, emptyList()))
        val samsung = HealthSession(at(0), at(595), stages, source = "com.sec.android.app.shealth")
        assertEquals(
            context.getString(R.string.score_source, context.getString(R.string.health_samsung)),
            scoreSourceText(context, item, score, listOf(samsung)),
        )
    }
}
