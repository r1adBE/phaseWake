package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lernphase ([Learning]): gezählte Nächte, gelernte Werte, Schlafenszeit-Empfehlung. */
class LearningTest {
    private val zone = ZoneId.of("Europe/Zurich")

    /** 23:00 Ortszeit am 1. + [day] November 2026. */
    private fun evening(day: Int): Instant = Instant.parse("2026-11-01T22:00:00Z").plus(Duration.ofDays(day.toLong()))

    private fun night(day: Int, epochs: Int, motion: Double? = 0.02, hr: Double? = 55.0, bedAt: Instant? = null, asleepAt: Instant? = null) =
        Learning.Night(evening(day), List(epochs) { Epoch(motion, hr) }, bedAt, asleepAt)

    @Test
    fun nightCountsFromThreeHoursOfMotion() {
        assertTrue(Learning.counts(night(0, Learning.MIN_SPAN_EPOCHS)))
        assertFalse(Learning.counts(night(0, Learning.MIN_SPAN_EPOCHS - 1)), "durchgehend, aber unter 3 Std.")
        assertFalse(Learning.counts(night(0, 0)))
        // Epochen ohne Bewegung (Uhr abgelegt, Lücken) zählen nicht.
        val gaps = Learning.Night(evening(0), List(400) { Epoch(if (it < 100) null else 0.02, 55.0) })
        assertFalse(Learning.counts(gaps))
        assertEquals(300, Learning.measuredEpochs(gaps))
    }

    /** Stichproben wie in der Lernphase: je 5 Min (10 Epochen) 2 gemessene Epochen, die übrigen leer. */
    private fun sampled(samples: Int, hr: Double? = 55.0) =
        Learning.Night(evening(0), List(samples * 10 - 8) { if (it % 10 < 2) Epoch(0.02, hr) else Epoch(null, null) })

    @Test
    fun sampledNightCountsFromThirtySixSamplesOverThreeHours() {
        // 36 Stichproben = 72 Epochen über 35 · 10 + 2 = 352 Epochen: noch keine 3 Std.
        assertEquals(72, Learning.measuredEpochs(sampled(36)))
        assertFalse(Learning.counts(sampled(36)))
        // 37 Stichproben: 74 Epochen über 362 Epochen.
        assertTrue(Learning.counts(sampled(37)))
        // Über 3 Std. verteilt, aber zu wenige Werte (eine Stichprobe je 15 Min).
        val sparse = Learning.Night(evening(0), List(400) { if (it % 30 < 2) Epoch(0.02, 55.0) else Epoch(null, null) })
        assertFalse(Learning.counts(sparse))
        // Ruhepuls aus Stichproben: ab 30 Stichproben (60 Epochen mit Puls).
        assertNull(Learning.nightRestingHeartRate(sampled(29)))
        assertEquals(55.0, Learning.nightRestingHeartRate(sampled(30)))
        // Grundrauschen aus den gemessenen Epochen, die leeren zählen nicht.
        assertEquals(0.02, Learning.nightFloor(sampled(37)))
    }

    @Test
    fun countsAtExactlyTheLimits() {
        // Audit c3b8b01 BE-1d: genau 72 gemessene Epochen über genau 360 zählt; eine weniger von beidem nicht.
        fun night(measured: Int, span: Int): Learning.Night {
            val at = (0 until measured).map { if (it == measured - 1) span - 1 else it }.toSet()
            return Learning.Night(evening(0), List(span) { if (it in at) Epoch(0.02, 55.0) else Epoch(null, null) })
        }
        assertEquals(72, Learning.MIN_MEASURED_EPOCHS)
        assertEquals(360, Learning.MIN_SPAN_EPOCHS)
        assertTrue(Learning.counts(night(72, 360)))
        assertFalse(Learning.counts(night(71, 360)))
        assertFalse(Learning.counts(night(72, 359)))
        assertEquals(72, Learning.measuredEpochs(night(72, 360)))
    }

    @Test
    fun countsOneNightPerDateFromTheStart() {
        val since = evening(1)
        val nights = listOf(
            night(0, 500), // vor dem Beginn der Lernphase
            night(1, 400),
            // Zweite Aufzeichnung derselben Nacht (00:30): zählt einmal, die längere gilt.
            Learning.Night(evening(1).plus(Duration.ofMinutes(90)), List(450) { Epoch(0.03, 55.0) }),
            night(2, 100), // zu kurz
            night(3, 360),
        )
        val counted = Learning.counted(nights, since, zone)
        assertEquals(listOf(evening(1).plus(Duration.ofMinutes(90)), evening(3)), counted.map { it.start })
        assertEquals(3, Learning.progress(counted.size))
        assertEquals(1, Learning.progress(0))
        assertEquals(Learning.NIGHTS, Learning.progress(9))
        assertFalse(Learning.finished(6))
        assertTrue(Learning.finished(7))
    }

    @Test
    fun restingHeartRateIsTenthPercentileWithEnoughPulse() {
        val pulses = List(200) { Epoch(0.02, 50.0 + it % 20) } // je 10 Werte 50 … 69
        // n = 200: Position 20 → der 20. kleinste Wert ist 51.
        assertEquals(51.0, Learning.nightRestingHeartRate(Learning.Night(evening(0), pulses)))
        assertNull(Learning.nightRestingHeartRate(night(0, 400, hr = null)))
        val few = Learning.Night(evening(0), List(400) { Epoch(0.02, if (it < Learning.MIN_HEART_RATE_EPOCHS - 1) 55.0 else null) })
        assertNull(Learning.nightRestingHeartRate(few))
    }

    @Test
    fun latencyFromBedToAsleepCapped() {
        val bed = evening(0)
        assertEquals(12, Learning.nightLatencyMinutes(night(0, 1, bedAt = bed, asleepAt = bed.plus(Duration.ofSeconds(12 * 60 + 59)))))
        assertEquals(Learning.MAX_LATENCY_MINUTES, Learning.nightLatencyMinutes(night(0, 1, bedAt = bed, asleepAt = bed.plus(Duration.ofHours(2)))))
        // Eingeschlafen vor der geplanten Bettzeit: wann ins Bett gegangen wurde, ist unbekannt.
        assertNull(Learning.nightLatencyMinutes(night(0, 1, bedAt = bed, asleepAt = bed.minusSeconds(60))))
        assertNull(Learning.nightLatencyMinutes(night(0, 1)))
    }

    @Test
    fun learnsMediansAndLeavesTooFewValuesOut() {
        val bed = evening(0)
        val nights = listOf(0.010, 0.020, 0.030, 0.025).mapIndexed { i, floor ->
            Learning.Night(
                evening(i), List(400) { Epoch(floor, if (i < 2) null else 50.0 + i) },
                bedAt = bed, asleepAt = if (i == 0) null else bed.plus(Duration.ofMinutes(10L * i)),
            )
        }
        val values = Learning.learn(nights)
        assertEquals(0.0225, values.floor!!, 1e-12) // Median von 0.010, 0.020, 0.025, 0.030
        assertNull(values.restingHeartRate, "nur 2 Nächte mit Puls")
        assertEquals(20, values.latencyMinutes) // 10, 20, 30
        assertEquals(4, values.nights)
        val more = Learning.learn(nights + Learning.Night(evening(5), List(400) { Epoch(0.02, 60.0) }))
        assertEquals(53.0, more.restingHeartRate) // Median von 52, 53, 60
    }

    @Test
    fun medianOfEvenAndOddCounts() {
        assertNull(Learning.median(emptyList()))
        assertEquals(2.0, Learning.median(listOf(3.0, 1.0, 2.0)))
        assertEquals(2.5, Learning.median(listOf(4.0, 1.0, 2.0, 3.0)))
    }

    @Test
    fun appliesLearnedValuesToTheConfig() {
        val values = Learning.Values(0.02, 52.0, 10, 7)
        val config = Learning.withLearned(Calibration.calibrate(emptyList()), values)
        assertEquals(0.02, config.personalFloor)
        assertEquals(52.0, config.restingHeartRate)
        assertEquals(DetectorConfig.DEFAULT_LIGHT_THRESHOLD, config.lightThreshold)
        assertEquals(DetectorConfig(), Learning.withLearned(DetectorConfig(), null))
        assertTrue(Learning.raisesThresholds(0.02))
        assertFalse(Learning.raisesThresholds(0.0004))
        assertFalse(Learning.raisesThresholds(null))
    }

    @Test
    fun bedtimeRangeEndsAtGoalPlusLatencyBeforeTarget() {
        val target = Instant.parse("2026-11-10T06:00:00Z") // 07:00 Zürich
        // 07:00 − 7½ Std. − 12 Min = 23:18 → abgerundet 23:15, frühestens 22:45.
        val (from, until) = Learning.bedtimeRange(target, 450, 12)
        assertEquals(Instant.parse("2026-11-09T22:15:00Z"), until)
        assertEquals(Instant.parse("2026-11-09T21:45:00Z"), from)
        // Ohne gelernte Einschlafdauer: die Bettzeit selbst (23:30).
        assertEquals(Instant.parse("2026-11-09T22:30:00Z"), Learning.bedtimeRange(target, 450, null).second)
    }
}
