package com.phasewake.app.core

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Phasen-Summen, Anteile, Einordnung und Kennzahlen. Gegenstück: `StageStatsTests` (Swift), gleiche Fälle. */
class StageStatsTest {
    private val t0: Instant = Instant.parse("2026-10-27T22:00:00Z")

    private fun at(minute: Int): Instant = t0.plusSeconds(minute * 60L)

    private fun span(from: Int, to: Int, stage: SleepStage) = StageSpan(at(from), at(to), stage)

    private fun of(vararg spans: StageSpan, from: Int = 0, to: Int = spans.maxOf { (it.end.epochSecond - t0.epochSecond).toInt() } / 60) =
        StageStats.of(spans.toList(), at(from), at(to))

    private fun percents(s: NightStages) = s.totals.map { it.percent }

    @Test
    fun emptyOrInvalidIsNull() {
        assertNull(StageStats.of(emptyList(), at(0), at(60)))
        assertNull(StageStats.of(listOf(span(0, 30, SleepStage.LIGHT)), at(0), at(0)))
        assertNull(of(span(0, 60, SleepStage.UNKNOWN)))
    }

    @Test
    fun mergesTouchingBlocksOfTheSameStage() {
        val s = of(span(0, 30, SleepStage.LIGHT), span(30, 50, SleepStage.LIGHT), span(50, 80, SleepStage.DEEP))!!
        assertEquals(listOf(StageBlock(SleepStage.LIGHT, at(0), at(50)), StageBlock(SleepStage.DEEP, at(50), at(80))), s.blocks)
        val gap = of(span(0, 30, SleepStage.LIGHT), span(31, 40, SleepStage.LIGHT))!!
        assertEquals(2, gap.blocks.size)
    }

    @Test
    fun percentsAddUpToHundred() {
        val s = of(span(0, 10, SleepStage.AWAKE), span(10, 20, SleepStage.REM), span(20, 30, SleepStage.LIGHT))!!
        assertEquals(listOf(34, 33, 33, 0), percents(s))
    }

    @Test
    fun typicalNight() {
        val s = of(
            span(0, 30, SleepStage.AWAKE), span(30, 150, SleepStage.LIGHT), span(150, 240, SleepStage.DEEP),
            span(240, 300, SleepStage.REM), span(300, 420, SleepStage.LIGHT), span(420, 480, SleepStage.REM),
        )!!
        assertEquals(listOf(30L, 120L, 240L, 90L), s.totals.map { it.minutes })
        assertEquals(listOf(6, 25, 50, 19), percents(s))
        assertEquals(List(4) { StageRating.TYPICAL }, s.totals.map { it.rating })
        assertEquals(30L, s.latencyMinutes)
        assertEquals(0, s.awakenings)
        assertEquals(90L, s.longestDeepMinutes)
        assertEquals(450L, s.asleepMinutes)
        assertEquals(94, s.efficiencyPercent)
        assertEquals(StageRange(20, 25), s.total(SleepStage.REM)?.range)
        assertNull(s.total(SleepStage.UNKNOWN))
    }

    @Test
    fun countsAwakeningsOnlyBetweenSleep() {
        val s = of(
            span(0, 10, SleepStage.AWAKE), span(10, 100, SleepStage.LIGHT), span(100, 105, SleepStage.AWAKE),
            span(105, 200, SleepStage.DEEP), span(200, 210, SleepStage.AWAKE), span(210, 260, SleepStage.REM),
            span(260, 270, SleepStage.AWAKE),
        )!!
        assertEquals(2, s.awakenings)
        assertEquals(10L, s.latencyMinutes)
    }

    @Test
    fun ratingsMarkTheTypicalRange() {
        val deepAbove = of(span(0, 200, SleepStage.LIGHT), span(200, 320, SleepStage.DEEP), span(320, 400, SleepStage.REM))!!
        assertEquals(listOf(0, 20, 50, 30), percents(deepAbove))
        assertEquals(StageRating.ABOVE, deepAbove.total(SleepStage.DEEP)?.rating)
        val awakeAndLowDeep = of(span(0, 100, SleepStage.AWAKE), span(100, 400, SleepStage.LIGHT), span(400, 420, SleepStage.DEEP))!!
        assertEquals(StageRating.ABOVE, awakeAndLowDeep.total(SleepStage.AWAKE)?.rating)
        assertEquals(StageRating.BELOW, awakeAndLowDeep.total(SleepStage.DEEP)?.rating)
        val lowRem = of(span(0, 260, SleepStage.LIGHT), span(260, 340, SleepStage.DEEP), span(340, 380, SleepStage.REM), span(380, 400, SleepStage.AWAKE))!!
        assertEquals(listOf(5, 10, 65, 20), percents(lowRem))
        assertEquals(StageRating.BELOW, lowRem.total(SleepStage.REM)?.rating)
    }

    @Test
    fun singleStageIsTheWholeNight() {
        val s = of(span(0, 100, SleepStage.LIGHT))!!
        assertEquals(listOf(0, 0, 100, 0), percents(s))
    }

    @Test
    fun efficiencyCountsUnknownAsSleepAndGapsAsRange() {
        val s = of(span(0, 40, SleepStage.LIGHT), span(40, 60, SleepStage.UNKNOWN), span(80, 100, SleepStage.AWAKE), to = 100)!!
        assertEquals(60, s.efficiencyPercent)
        assertEquals(60L, s.asleepMinutes)
        assertNull(s.latencyMinutes)
        assertEquals(0, s.awakenings)
    }

    @Test
    fun clipsToTheRange() {
        val s = StageStats.of(listOf(span(-30, 30, SleepStage.DEEP)), at(0), at(60))!!
        assertEquals(30L, s.total(SleepStage.DEEP)?.minutes)
        assertEquals(StageBlock(SleepStage.DEEP, at(0), at(30)), s.blocks.single())
    }

    @Test
    fun blockAtFindsTheBlock() {
        val s = of(span(0, 30, SleepStage.LIGHT), span(30, 50, SleepStage.LIGHT), span(50, 80, SleepStage.DEEP))!!
        assertEquals(SleepStage.LIGHT, s.blockAt(at(49))?.stage)
        assertEquals(SleepStage.DEEP, s.blockAt(at(50))?.stage)
        assertNull(s.blockAt(at(80)))
    }

    @Test
    fun forNightUsesTheHypnogramRange() {
        val night = NightRecord("a", at(420), at(450), at(440), WakeReason.LIGHT_SLEEP, 0, emptyList())
        val stages = listOf(span(0, 200, SleepStage.LIGHT), span(200, 300, SleepStage.DEEP), span(300, 445, SleepStage.REM))
        val s = StageStats.forNight(stages, night)!!
        assertEquals(at(0), s.start)
        assertEquals(at(445), s.end)
        assertNull(StageStats.forNight(emptyList(), night))
    }

    @Test
    fun forSessionUsesTheWholeSession() {
        val session = HealthSession(at(0), at(100), listOf(span(10, 90, SleepStage.LIGHT)))
        val s = StageStats.forSession(session)!!
        assertEquals(80, s.efficiencyPercent)
        assertNull(StageStats.forSession(HealthSession(at(0), at(100))))
    }

    @Test
    fun weekAverageIsTrailing() {
        assertEquals(
            listOf(1.0, 1.0, 2.0, 2.0, 2.0, 2.0, 2.0, 3.0, 3.0),
            ChartData.weekAverage(listOf(1.0, null, 3.0, null, null, null, null, null, null)),
        )
        assertEquals(listOf<Double?>(null), ChartData.weekAverage(listOf(null)))
        assertEquals(listOf(7, 14, 30), ChartData.PERIODS)
    }

    @Test
    fun historyKeysAndLookup() {
        val night = NightRecord("a", Instant.ofEpochMilli(1000), Instant.ofEpochMilli(2000), Instant.ofEpochMilli(1500), WakeReason.AWAKE, 0, emptyList())
        val date = LocalDate.of(2026, 10, 26)
        val own = HistoryItem(HistoryKind.PHASE_WAKE, date, night = night)
        val health = HistoryItem(HistoryKind.HEALTH, date, session = HealthSession(at(0), at(60)))
        assertEquals("n:a:1000", own.key)
        assertEquals("h:2026-10-26", health.key)
        assertEquals(own, HealthHistory.itemFor(listOf(own, health), date))
        assertNull(HealthHistory.itemFor(listOf(own), date.plusDays(1)))
    }
}
