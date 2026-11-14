package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Audit c3b8b01 BE-3: Dokumentierte Grenzen der Erkennung mit festen Zahlen (Wert − 1 / Wert / Wert + 1), nicht nur über
 * die Konstante selbst: Ändert sich eine Zahl, wird ein Test rot. Gleiche Fälle in Swift (`DetectionLimitsTests`).
 */
class DetectionLimitsTest {
    private val start = Instant.parse("2026-11-01T22:00:00Z")

    @Test
    fun learningBatteryWarnsBelowForty() {
        val record = Instant.parse("2026-11-01T23:00:00Z")
        val at = LearnNights.warnAt(record)
        assertTrue(LearnNights.shouldWarn(true, 39, false, at, record))
        assertFalse(LearnNights.shouldWarn(true, 40, false, at, record))
    }

    @Test
    fun latencyIsCappedAtSixtyMinutes() {
        fun latency(minutes: Long) = Learning.nightLatencyMinutes(
            Learning.Night(start, emptyList(), bedAt = start, asleepAt = start.plus(Duration.ofMinutes(minutes))),
        )
        assertEquals(59, latency(59))
        assertEquals(60, latency(60))
        assertEquals(60, latency(61))
    }

    @Test
    fun learnedValuesAreValidOnlyInTheirRanges() {
        // Grundrauschen 0 bis 1 m/s², Ruhepuls 25 bis 150 (K-6).
        assertNull(Learning.validFloor(-0.0001))
        assertEquals(0.0, Learning.validFloor(0.0))
        assertEquals(1.0, Learning.validFloor(1.0))
        assertNull(Learning.validFloor(1.0001))
        assertNull(Learning.validRestingHeartRate(24.99))
        assertEquals(25.0, Learning.validRestingHeartRate(25.0))
        assertEquals(150.0, Learning.validRestingHeartRate(150.0))
        assertNull(Learning.validRestingHeartRate(150.01))
    }

    @Test
    fun exactWindowIsZeroAndSleepAtLeastSixtyMinutes() {
        assertTrue(AlarmSpec(id = "a", hour = 7, minute = 0, windowMinutes = 0).isExact)
        assertFalse(AlarmSpec(id = "a", hour = 7, minute = 0, windowMinutes = 10).isExact)
        assertFailsWith<IllegalArgumentException> { AlarmSpec(id = "a", hour = 7, minute = 0, sleepDurationMinutes = 59) }
        assertEquals(60, AlarmSpec(id = "a", hour = 7, minute = 0, sleepDurationMinutes = 60).sleepDurationMinutes)
    }

    @Test
    fun pulseUpTo250IsPlausible() {
        val a = HeartRateAccumulator()
        a.add(250.0)
        assertEquals(250.0, a.closeEpoch())
        a.add(250.0001)
        assertNull(a.closeEpoch())
    }

    /** [count] Differenzen von je 1 m/s² im Abstand von 40 ms. */
    private fun MotionEnergyAccumulator.diffs(count: Int) {
        for (i in 0..count) add(i * 40_000_000L, if (i % 2 == 0) 0.0 else 1.0, 0.0, 0.0)
    }

    @Test
    fun motionNeedsThreeHundredDifferences() {
        val few = MotionEnergyAccumulator()
        few.diffs(299)
        assertNull(few.closeEpoch())
        val enough = MotionEnergyAccumulator()
        enough.diffs(300)
        assertEquals(0.95, enough.closeEpoch()!!, 1e-12)
    }

    @Test
    fun motionIntervalIsThirtyFiveMillisecondsAndGapOneSecond() {
        fun one(dtNanos: Long): Double? = MotionEnergyAccumulator(minDiffs = 1).run {
            add(0L, 0.0, 0.0, 0.0)
            add(dtNanos, 1.0, 0.0, 0.0)
            closeEpoch()
        }
        assertNull(one(34_999_999L))
        assertEquals(0.95, one(35_000_000L)!!, 1e-12)
        assertEquals(0.95, one(1_000_000_000L)!!, 1e-12)
        assertNull(one(1_000_000_001L))
    }
}
