package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InsightsTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val start = Instant.parse("2026-10-28T05:30:00Z")

    private fun night(epochs: List<Epoch>, feeling: Int? = null, daysAgo: Long = 0) = NightRecord(
        alarmId = "a",
        windowStart = start.minus(Duration.ofDays(daysAgo)),
        target = start.minus(Duration.ofDays(daysAgo)).plusSeconds(1800),
        wokeAt = start.minus(Duration.ofDays(daysAgo)).plusSeconds(1200),
        reason = WakeReason.LIGHT_SLEEP,
        snoozes = 0,
        epochs = epochs,
        feeling = feeling,
    )

    private fun epochs(n: Int, motion: (Int) -> Double?, hr: (Int) -> Double?) = List(n) { Epoch(motion(it), hr(it)) }

    @Test
    fun qualityFollowsSignalShares() {
        assertEquals(DataQuality.FULL, NightQuality.of(night(epochs(12, { 0.001 }, { 55.0 }))))
        assertEquals(DataQuality.MOTION_ONLY, NightQuality.of(night(epochs(12, { 0.001 }, { if (it < 5) 55.0 else null }))))
        assertEquals(DataQuality.PARTIAL, NightQuality.of(night(epochs(12, { if (it < 8) 0.001 else null }, { 55.0 }))))
        assertEquals(DataQuality.NONE, NightQuality.of(night(epochs(12, { null }, { 55.0 }))))
        assertEquals(DataQuality.NONE, NightQuality.of(night(epochs(2, { 0.001 }, { 55.0 }))))
        // Die Aufwärmphase zählt nicht: zwei leere Epochen am Anfang ändern nichts.
        assertEquals(DataQuality.FULL, NightQuality.of(night(epochs(12, { if (it < 2) null else 0.001 }, { 55.0 }))))
    }

    private fun at(text: String) = LocalDateTime.parse(text).toInstant(ZoneOffset.UTC)
    private fun span(from: String, to: String, stage: SleepStage) = StageSpan(at(from), at(to), stage)

    @Test
    fun sleepIsGroupedIntoNights() {
        val spans = listOf(
            span("2026-10-26T23:10", "2026-10-27T03:00", SleepStage.LIGHT),
            span("2026-10-27T03:00", "2026-10-27T03:20", SleepStage.AWAKE),
            span("2026-10-27T03:20", "2026-10-27T06:40", SleepStage.DEEP),
            // Kurz, aber eigene Strecke: ohne Mindestdauer (Nickerchen filtert vorher HealthHistory).
            span("2026-10-27T14:00", "2026-10-27T14:40", SleepStage.LIGHT),
            span("2026-10-28T00:30", "2026-10-28T06:30", SleepStage.REM),
        )
        val nights = SleepSummary.group(spans)
        assertEquals(3, nights.size)
        assertEquals(SleepSummary(at("2026-10-26T23:10"), at("2026-10-27T06:40"), 230 + 200), nights[0])
        assertEquals(40, nights[1].asleepMinutes)
        assertEquals(360, nights[2].asleepMinutes)
        assertEquals(emptyList(), SleepSummary.group(listOf(span("2026-10-27T01:00", "2026-10-27T07:00", SleepStage.AWAKE))))
    }

    @Test
    fun trendsCompareWeeksAndNeedEnoughNights() {
        val now = Instant.parse("2026-10-28T08:00:00Z")
        fun sleep(daysAgo: Long, minutes: Long, bedHour: Int, bedMinute: Int): SleepSummary {
            val end = now.minus(Duration.ofDays(daysAgo)).minus(Duration.ofHours(1))
            val bed = end.atZone(zone).toLocalDate().minusDays(1).atTime(bedHour, bedMinute).toInstant(ZoneOffset.UTC)
            return SleepSummary(bed, end, minutes)
        }
        val sleeps = listOf(
            sleep(0, 420, 23, 0), sleep(1, 400, 23, 30), sleep(2, 440, 23, 0), // diese Woche: Ø 420
            sleep(8, 460, 22, 30), sleep(9, 440, 23, 0), sleep(10, 450, 23, 30), // Vorwoche: Ø 450
        )
        val nights = listOf(night(emptyList(), 4, 0), night(emptyList(), 3, 1), night(emptyList(), 5, 2), night(emptyList(), 2, 8))
        val trends = Trends.compute(nights, sleeps, now, zone)
        assertEquals(420, trends.asleepThisWeek)
        assertEquals(450, trends.asleepLastWeek)
        assertEquals(-30, trends.asleepChange)
        assertEquals(4.0, trends.feelingThisWeek)
        assertNull(trends.feelingLastWeek) // nur eine Bewertung
        // Einschlafzeiten 23:00, 23:30, 23:00, 22:30, 23:00, 23:30 → Mittel 23:05, Abweichung Ø 16 Min.
        assertEquals(16, trends.bedtimeSpreadMinutes)

        val few = Trends.compute(nights.take(2), sleeps.take(2), now, zone)
        assertNull(few.asleepThisWeek)
        assertNull(few.asleepChange)
        assertNull(few.bedtimeSpreadMinutes)
    }

    @Test
    fun bedtimeAroundMidnightIsNotSplit() {
        val now = Instant.parse("2026-10-28T08:00:00Z")
        val sleeps = (0L until 5L).map { d ->
            val bed = Instant.parse("2026-10-27T23:50:00Z").minus(Duration.ofDays(d)).plus(Duration.ofMinutes(if (d % 2 == 0L) 0 else 20))
            SleepSummary(bed, bed.plus(Duration.ofHours(7)), 400)
        }
        // 23:50 und 00:10 abwechselnd: Abweichung um 10 Min, nicht um 12 Stunden.
        assertEquals(9, Trends.compute(emptyList(), sleeps, now, zone).bedtimeSpreadMinutes)
    }

    @Test
    fun emptyNightHasNoQualityAndChangeNeedsBothWeeks() {
        assertEquals(DataQuality.NONE, NightQuality.of(night(epochs(2, { 0.01 }, { 60.0 }))))
        assertEquals(DataQuality.NONE, NightQuality.of(night(epochs(10, { null }, { 60.0 }))))
        assertNull(Trends(420, null, null, null, null).asleepChange)
        assertNull(Trends(null, 400, null, null, null).asleepChange)
        assertEquals(20L, Trends(420, 400, null, null, null).asleepChange)
    }
}

class MissHintTest {
    private val spec = AlarmSpec(id = "a", hour = 7, minute = 0, windowMinutes = 30)
    private val start = Instant.parse("2026-10-20T05:30:00Z")

    private fun night(day: Long, reason: WakeReason = WakeReason.DEADLINE, hr: Double? = 55.0, motion: Double? = 0.0005, id: String = "a") =
        NightRecord(
            alarmId = id,
            windowStart = start.plus(Duration.ofDays(day)),
            target = start.plus(Duration.ofDays(day)).plusSeconds(1800),
            wokeAt = start.plus(Duration.ofDays(day)).plusSeconds(1800),
            reason = reason,
            snoozes = 0,
            epochs = List(60) { Epoch(motion, hr) },
        )

    @Test
    fun needsThreeDeadlineNightsWithFullWindow() {
        val three = listOf(night(0), night(1), night(2))
        assertEquals(MissHint.CALM, MissHint.of(spec, three))
        assertNull(MissHint.of(spec, three.take(2)))
        assertNull(MissHint.of(spec.copy(windowMinutes = 20), three))
        assertNull(MissHint.of(spec, three + night(3, WakeReason.LIGHT_SLEEP)))
        assertNull(MissHint.of(null, three))
        // Nächte anderer Wecker zählen nicht.
        assertNull(MissHint.of(spec, listOf(night(0), night(1), night(2, id = "b"))))
    }

    @Test
    fun causeFollowsDataQuality() {
        assertEquals(MissHint.HEART_MISSING, MissHint.of(spec, listOf(night(0, hr = null), night(1, hr = null), night(2))))
        assertEquals(MissHint.GAPS, MissHint.of(spec, listOf(night(0, motion = null), night(1, motion = null), night(2, hr = null))))
    }
}

class RobustnessTest {
    @Test
    fun invalidSamplesAreIgnored() {
        val motion = MotionEnergyAccumulator(minDiffs = 1)
        motion.add(0, 0.0, 0.0, 9.81)
        motion.add(40_000_000, Double.NaN, 0.0, 9.81)
        motion.add(80_000_000, 0.0, Double.POSITIVE_INFINITY, 9.81)
        motion.add(120_000_000, 0.2, 0.0, 9.81)
        val value = motion.closeEpoch()
        assertEquals(0.15, value!!, 1e-9) // nur der gültige Schritt 0 → 0.2, minus Rauschen 0.05

        val hr = HeartRateAccumulator()
        hr.add(Double.NaN)
        hr.add(Double.POSITIVE_INFINITY)
        hr.add(60.0)
        assertEquals(60.0, hr.closeEpoch())
    }

    @Test
    fun timeJumpDoesNotProduceEndlessEpochs() {
        val a = EpochAssembler(startNanos = 0)
        val tooLate = (EpochAssembler.MAX_EPOCHS + 1) * 30_000_000_000L
        assertEquals(emptyList(), a.addMotion(tooLate, 0.0, 0.0, 9.81))
        assertEquals(emptyList(), a.advance(tooLate, 0))
        assertEquals(0, a.currentIndex)
        assertEquals(3, a.advance(3 * 30_000_000_000L, 0).size)
    }
}
