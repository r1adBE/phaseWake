package com.phasewake.app.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Schlafwert: Punkte je Faktor, Gewichtung, fehlende Faktoren, Bewertung in Worten (PHASENERKENNUNG.md 11). */
class SleepScoreTest {
    private val t0: Instant = Instant.parse("2026-11-08T22:00:00Z")

    private fun at(minute: Int): Instant = t0.plusSeconds(minute * 60L)

    @Test
    fun weightsAddUpToHundred() {
        assertEquals(100, SleepScore.WEIGHTS.values.sum())
        assertEquals(ScoreFactor.entries.toSet(), SleepScore.WEIGHTS.keys)
    }

    @Test
    fun bandsHaveTheDocumentedLimits() {
        assertEquals(ScoreBand.EXCELLENT, SleepScore.band(100))
        assertEquals(ScoreBand.EXCELLENT, SleepScore.band(85))
        assertEquals(ScoreBand.GOOD, SleepScore.band(84))
        assertEquals(ScoreBand.GOOD, SleepScore.band(70))
        assertEquals(ScoreBand.FAIR, SleepScore.band(69))
        assertEquals(ScoreBand.FAIR, SleepScore.band(50))
        assertEquals(ScoreBand.POOR, SleepScore.band(49))
        assertEquals(ScoreBand.POOR, SleepScore.band(0))
    }

    @Test
    fun durationIsFullFromTheGoalAndZeroAtHalf() {
        assertEquals(100, SleepScore.durationPoints(450, 450))
        assertEquals(100, SleepScore.durationPoints(600, 450))
        assertEquals(0, SleepScore.durationPoints(225, 450))
        assertEquals(0, SleepScore.durationPoints(10, 450))
        // 405 von 450: (405 − 225) / 225 = 80 %.
        assertEquals(80, SleepScore.durationPoints(405, 450))
        // 337.5 wäre genau 50: 338 → 50.2 → 50.
        assertEquals(50, SleepScore.durationPoints(338, 450))
    }

    @Test
    fun deepAndRemFromTheLowerTypicalLimit() {
        // Tief üblich ab 13 %, REM ab 20 %.
        assertEquals(100, SleepScore.atLeastPoints(13, 13))
        assertEquals(100, SleepScore.atLeastPoints(30, 13))
        assertEquals(77, SleepScore.atLeastPoints(10, 13))
        assertEquals(50, SleepScore.atLeastPoints(10, 20))
        assertEquals(0, SleepScore.atLeastPoints(0, 20))
    }

    @Test
    fun awakeIsFullUpToNinePercentAndZeroFromThirty() {
        assertEquals(100, SleepScore.awakePoints(0))
        assertEquals(100, SleepScore.awakePoints(9))
        // (30 − 15) / 21 = 71.4 %.
        assertEquals(71, SleepScore.awakePoints(15))
        assertEquals(0, SleepScore.awakePoints(30))
        assertEquals(0, SleepScore.awakePoints(70))
    }

    @Test
    fun latencyIsFullUpToTwentyMinutesAndZeroFromSixty() {
        assertEquals(100, SleepScore.latencyPoints(0))
        assertEquals(100, SleepScore.latencyPoints(20))
        assertEquals(50, SleepScore.latencyPoints(40))
        assertEquals(0, SleepScore.latencyPoints(60))
        assertEquals(0, SleepScore.latencyPoints(90))
    }

    @Test
    fun allFactorsAreWeighted() {
        val r = SleepScore.of(ScoreInput(405, 450, deepPercent = 10, remPercent = 20, awakePercent = 15, latencyMinutes = 40))!!
        assertEquals(listOf(80, 77, 100, 71, 50), r.factors.map { it.points })
        // (80·35 + 77·20 + 100·20 + 71·15 + 50·10) / 100 = 79.05.
        assertEquals(79, r.score)
        assertEquals(ScoreBand.GOOD, r.band)
        assertEquals(ScoreBand.FAIR, r.factor(ScoreFactor.LATENCY)!!.band)
    }

    @Test
    fun missingFactorsAreLeftOutAndTheRestReweighted() {
        // Nur Schlafdauer und Einschlafdauer: (80·35 + 50·10) / 45 = 73.3.
        val r = SleepScore.of(ScoreInput(405, 450, latencyMinutes = 40))!!
        assertEquals(listOf(ScoreFactor.DURATION, ScoreFactor.LATENCY), r.factors.map { it.factor })
        assertEquals(73, r.score)
        assertNull(r.factor(ScoreFactor.DEEP))
        // Nur Schlafdauer: der Wert ist ihre Punktzahl.
        assertEquals(80, SleepScore.of(ScoreInput(405, 450))!!.score)
    }

    @Test
    fun withoutStagesTheScoreIsProvisionalAndAtMostGood() {
        // Ziel erreicht, schnell eingeschlafen: gerechnet 100, ohne Phasen höchstens 84 («Gut»).
        val r = SleepScore.of(ScoreInput(480, 450, latencyMinutes = 12))!!
        assertEquals(84, r.score)
        assertEquals(ScoreBand.GOOD, r.band)
        assertTrue(r.provisional)
        assertEquals(listOf(100, 100), r.factors.map { it.points })
        // Unter der Grenze bleibt der Wert, ist aber vorläufig.
        val low = SleepScore.of(ScoreInput(405, 450, latencyMinutes = 40))!!
        assertEquals(73, low.score)
        assertTrue(low.provisional)
        // Eine Phase genügt: nicht vorläufig, keine Grenze.
        val awake = SleepScore.of(ScoreInput(480, 450, awakePercent = 4))!!
        assertEquals(100, awake.score)
        assertFalse(awake.provisional)
    }

    @Test
    fun noScoreWithoutDuration() {
        assertNull(SleepScore.of(ScoreInput(null, 450, deepPercent = 20, remPercent = 22, awakePercent = 3)))
        assertNull(SleepScore.of(ScoreInput(400, 0)))
    }

    @Test
    fun perfectNightIsHundred() {
        val r = SleepScore.of(ScoreInput(480, 450, deepPercent = 18, remPercent = 22, awakePercent = 4, latencyMinutes = 12))!!
        assertEquals(100, r.score)
        assertEquals(ScoreBand.EXCELLENT, r.band)
        assertFalse(r.provisional)
    }

    @Test
    fun inputFromStages() {
        // 10 Min wach, 50 leicht, 30 tief, 20 REM, dann 10 wach: 120 Min, Schlaf 100 Min.
        val spans = listOf(
            StageSpan(at(0), at(10), SleepStage.AWAKE),
            StageSpan(at(10), at(60), SleepStage.LIGHT),
            StageSpan(at(60), at(90), SleepStage.DEEP),
            StageSpan(at(90), at(110), SleepStage.REM),
            StageSpan(at(110), at(120), SleepStage.AWAKE),
        )
        val stats = StageStats.of(spans, at(0), at(120))!!
        val input = SleepScore.inputOf(stats, 450)
        assertEquals(ScoreInput(100, 450, deepPercent = 25, remPercent = 17, awakePercent = 17, latencyMinutes = 10), input)
    }

    @Test
    fun inputFromRecording() {
        assertEquals(ScoreInput(400, 450, latencyMinutes = 15), SleepScore.inputOf(at(0), at(15), at(415), 450))
        // Beginn nach dem Einschlafen: keine Einschlafdauer; ohne Beginn ebenso.
        assertEquals(ScoreInput(400, 450), SleepScore.inputOf(at(20), at(15), at(415), 450))
        assertEquals(ScoreInput(400, 450), SleepScore.inputOf(null, at(15), at(415), 450))
        assertNull(SleepScore.inputOf(null, at(15), at(15), 450))
    }
}
