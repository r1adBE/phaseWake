package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NightHistoryTest {

    private val start = Instant.parse("2026-10-01T04:30:00Z")

    private fun night(day: Long, epochs: List<Epoch> = listOf(Epoch(0.0, 52.0)), wokeMinutes: Long = 18) = NightRecord(
        alarmId = "geheim-123",
        windowStart = start.plus(Duration.ofDays(day)),
        target = start.plus(Duration.ofDays(day)).plus(Duration.ofMinutes(30)),
        wokeAt = start.plus(Duration.ofDays(day)).plus(Duration.ofMinutes(wokeMinutes)),
        reason = WakeReason.LIGHT_SLEEP,
        snoozes = 1,
        epochs = epochs,
    )

    @Test
    fun keepsNewestNinety() {
        val nights = (0L until 100L).reversed().map { night(it) }
        val kept = NightHistory.trim(nights)
        assertEquals(90, kept.size)
        assertEquals(start.plus(Duration.ofDays(10)), kept.first().windowStart)
        assertEquals(start.plus(Duration.ofDays(99)), kept.last().windowStart)
    }

    @Test
    fun minutesBeforeTarget() {
        assertEquals(12, night(0).minutesBeforeTarget)
        assertEquals(0, night(0, wokeMinutes = 35).minutesBeforeTarget)
    }

    @Test
    fun minutesAfterTarget() {
        assertEquals(0, night(0).minutesAfterTarget)
        assertEquals(0, night(0, wokeMinutes = 30).minutesAfterTarget)
        assertEquals(5, night(0, wokeMinutes = 35).minutesAfterTarget)
        // Angefangene Minuten zählen nicht (wie vor der Zielzeit): 30 s danach gilt als pünktlich.
        val halfMinute = night(0).let { it.copy(wokeAt = it.target.plusSeconds(30)) }
        assertEquals(0, halfMinute.minutesAfterTarget)
        assertEquals(0, halfMinute.minutesBeforeTarget)
    }

    @Test
    fun csvIsAnonymous() {
        val csv = NightHistory.exportCsv(listOf(night(1, listOf(Epoch(0.0021, 52.34), Epoch(null, null))).copy(feeling = 4), night(0)))
        val lines = csv.trimEnd().lines()
        assertEquals("night,window_minutes,reason,woke_minutes_before_target,snoozes,feeling,epoch,motion,hr,notes", lines[0])
        assertEquals("1,30,LIGHT_SLEEP,12,1,,0,0.000000,52.0,", lines[1])
        assertEquals("2,30,LIGHT_SLEEP,12,1,4,0,0.002100,52.3,", lines[2])
        assertEquals("2,30,LIGHT_SLEEP,12,1,4,1,,,", lines[3])
        assertFalse("geheim" in csv)
        assertFalse("2026" in csv)
    }

    @Test
    fun calibrationInputDropsNothingButOrders() {
        val motions = NightHistory.motionsForCalibration(listOf(night(2, listOf(Epoch(0.3, null))), night(1)))
        assertEquals(listOf(listOf<Double?>(0.0), listOf<Double?>(0.3)), motions)
    }

    @Test
    fun lightShareSkipsNightsWithoutEarlyWake() {
        val reasons = listOf(WakeReason.LIGHT_SLEEP, WakeReason.DEADLINE, WakeReason.AWAKE, WakeReason.EXACT, WakeReason.SHORT_NIGHT)
        val nights = reasons.mapIndexed { i, reason -> night(i.toLong()).copy(reason = reason) }
        // 1 von 3 (ohne Genau und kurze Nacht) = 33 %, nicht 1 von 5.
        assertEquals(33, NightHistory.lightShare(nights))
        assertEquals(60, NightHistory.lightShare(nights + night(9) + night(10)))
        assertEquals(null, NightHistory.lightShare(nights.filter { it.reason == WakeReason.EXACT || it.reason == WakeReason.SHORT_NIGHT }))
        assertEquals(null, NightHistory.lightShare(emptyList()))
    }
}
