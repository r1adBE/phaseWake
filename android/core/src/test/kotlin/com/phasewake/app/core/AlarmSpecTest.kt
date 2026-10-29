package com.phasewake.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Grenzwerte und abgeleitete Eigenschaften eines Weckers; Gegenstück: `AlarmSpecTests` (Swift). */
class AlarmSpecTest {
    private fun alarm() = AlarmSpec(id = "a", hour = 7, minute = 0)

    @Test
    fun limitsAreInclusive() {
        AlarmSpec(id = "a", hour = 0, minute = 0, windowMinutes = AlarmSpec.MIN_WINDOW_MINUTES)
        AlarmSpec(id = "a", hour = 23, minute = 59, windowMinutes = AlarmSpec.MAX_WINDOW_MINUTES)
        AlarmSpec(id = "a", hour = 7, minute = 0, sleepDurationMinutes = AlarmSpec.MIN_SLEEP_MINUTES)
        AlarmSpec(id = "a", hour = 7, minute = 0, sleepDurationMinutes = AlarmSpec.MAX_SLEEP_MINUTES)
        assertFailsWith<IllegalArgumentException> { AlarmSpec(id = "a", hour = -1, minute = 0) }
        assertFailsWith<IllegalArgumentException> { AlarmSpec(id = "a", hour = 7, minute = -1) }
        assertFailsWith<IllegalArgumentException> { alarm().copy(sleepDurationMinutes = 721) }
        assertFailsWith<IllegalArgumentException> { AlarmSpec(id = "", hour = 7, minute = 0) }
        assertEquals(AlarmSpec.MAX_WINDOW_MINUTES, alarm().windowMinutes)
    }

    @Test
    fun exactWindowIsZeroAndMeasuresThirtyMinutes() {
        val exact = alarm().copy(windowMinutes = AlarmSpec.EXACT_WINDOW)
        assertTrue(exact.isExact)
        assertEquals(AlarmSpec.MEASURE_MINUTES, exact.measureWindowMinutes)
        assertFalse(alarm().isExact)
        assertEquals(30, alarm().measureWindowMinutes)
        assertEquals(10, alarm().copy(windowMinutes = 10).measureWindowMinutes)
        // Zwischen 0 und 10 gibt es nichts.
        assertFailsWith<IllegalArgumentException> { alarm().copy(windowMinutes = 5) }
        assertFailsWith<IllegalArgumentException> { alarm().copy(windowMinutes = -1) }
        assertFailsWith<IllegalArgumentException> { alarm().copy(windowMinutes = 31) }
        // Der Codec schreibt 0 wie jede andere Zahl.
        assertEquals(listOf(exact), Codec.decodeAlarms(Codec.encodeAlarms(listOf(exact))))
    }

    @Test
    fun phoneBackupFollowsPhoneAlert() {
        assertFalse(alarm().copy(phone = PhoneAlert.OFF).phoneBackup)
        assertTrue(alarm().copy(phone = PhoneAlert.VIBRATE).phoneBackup)
        assertTrue(alarm().copy(phone = PhoneAlert.SOUND).phoneBackup)
    }

    @Test
    fun repeatingAlarmIsNotOneShot() {
        assertTrue(alarm().isOneShot)
        assertFalse(alarm().copy(days = setOf(java.time.DayOfWeek.MONDAY)).isOneShot)
    }

    @Test
    fun sunriseDeviceSides() {
        assertEquals(listOf(true, false, true), SunriseDevice.entries.map { it.onPhone })
        assertEquals(listOf(false, true, true), SunriseDevice.entries.map { it.onWatch })
        val window = alarm().copy(sunrise = Sunrise.WINDOW)
        assertEquals(Sunrise.WINDOW, window.phoneSunrise)
        assertEquals(Sunrise.OFF, window.watchSunrise)
        assertEquals(Sunrise.OFF, window.copy(sunriseDevice = SunriseDevice.WATCH).phoneSunrise)
        assertEquals(Sunrise.WINDOW, window.copy(sunriseDevice = SunriseDevice.WATCH).watchSunrise)
    }

    @Test
    fun sunriseBrightnessBelowZeroIsDimmest() {
        assertEquals(0.01f, Sunrise.brightness(-1f))
        assertEquals(1f, Sunrise.progress(java.time.Instant.EPOCH, java.time.Instant.EPOCH, java.time.Instant.EPOCH))
    }
}
