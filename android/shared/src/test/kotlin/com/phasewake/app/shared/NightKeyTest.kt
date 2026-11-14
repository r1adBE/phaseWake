package com.phasewake.app.shared

import com.phasewake.app.core.BatteryMark
import com.phasewake.app.core.BatteryPoint
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.WakeReason
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class NightKeyTest {

    private val start = Instant.parse("2026-10-01T04:30:00Z")

    private fun night(alarmId: String, feeling: Int? = null, wokeMinutes: Long = 20) = NightRecord(
        alarmId = alarmId,
        windowStart = start,
        target = start.plus(Duration.ofMinutes(30)),
        wokeAt = start.plus(Duration.ofMinutes(wokeMinutes)),
        reason = WakeReason.LIGHT_SLEEP,
        snoozes = 0,
        epochs = emptyList(),
        feeling = feeling,
    )

    @Test
    fun twoAlarmsWithTheSameWindowAreTwoNights() {
        val merged = mergeNight(listOf(night("a")), night("b"))
        assertEquals(listOf("a", "b"), merged.map { it.alarmId })
    }

    @Test
    fun sameNightIsReplacedAndKeepsFeeling() {
        val merged = mergeNight(listOf(night("a", feeling = 4), night("b", feeling = 2)), night("a", wokeMinutes = 25))
        assertEquals(2, merged.size)
        val a = merged.single { it.alarmId == "a" }
        assertEquals(start.plus(Duration.ofMinutes(25)), a.wokeAt)
        assertEquals(4, a.feeling)
        assertEquals(2, merged.single { it.alarmId == "b" }.feeling)
    }

    @Test
    fun newFeelingWins() {
        assertEquals(5, mergeNight(listOf(night("a", feeling = 4)), night("a", feeling = 5)).single().feeling)
    }

    @Test
    fun storedBatteryIsKept() {
        val phone = listOf(BatteryPoint(start, 50, false, BatteryMark.START), BatteryPoint(start.plusSeconds(1800), 48, false, BatteryMark.END))
        val watch = listOf(BatteryPoint(start, 90, false, BatteryMark.START), BatteryPoint(start.plusSeconds(1800), 80, false, BatteryMark.END))
        // Die Uhr schickt dieselbe Nacht noch einmal (mit ihrem Akku): der des Handys bleibt.
        val merged = mergeNight(listOf(night("a").copy(phoneBattery = phone)), night("a").copy(watchBattery = watch)).single()
        assertEquals(phone, merged.phoneBattery)
        assertEquals(watch, merged.watchBattery)
        // Ohne Akku noch einmal: beide bleiben.
        assertEquals(merged, mergeNight(listOf(merged), night("a")).single())
    }
}
