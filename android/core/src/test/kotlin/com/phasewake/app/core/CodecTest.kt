package com.phasewake.app.core

import java.time.DayOfWeek
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CodecTest {

    private val alarms = listOf(
        AlarmSpec(
            id = "3f2a-77",
            hour = 6,
            minute = 30,
            days = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY),
            windowMinutes = 25,
            enabled = true,
            phone = PhoneAlert.VIBRATE,
            watchSound = true,
            sound = "content://media/internal/audio/media/42",
            label = "Arbeit | früh, 100 %",
        ),
        AlarmSpec(id = "b", hour = 9, minute = 30, enabled = false, phone = PhoneAlert.OFF, sleepDurationMinutes = 450, sunrise = Sunrise.WINDOW),
        AlarmSpec(id = "c", hour = 7, minute = 0, wakeTask = WakeTask.MATH),
        AlarmSpec(id = "d", hour = 6, minute = 15, windowMinutes = 20, phone = PhoneAlert.OFF, sunrise = Sunrise.RING, sunriseDevice = SunriseDevice.WATCH),
    )

    @Test
    fun alarmsRoundTrip() {
        assertEquals(alarms, Codec.decodeAlarms(Codec.encodeAlarms(alarms)))
        assertEquals(emptyList(), Codec.decodeAlarms(Codec.encodeAlarms(emptyList())))
    }

    @Test
    fun alarmsFormatIsStable() {
        val text = Codec.encodeAlarms(alarms.take(1))
        assertEquals("phasewake-alarms 1\n3f2a-77|6|30|MO,FR|25|1|VIBRATE|1||content%3A%2F%2Fmedia%2Finternal%2Faudio%2Fmedia%2F42|Arbeit+%7C+fr%C3%BCh%2C+100+%25|OFF|NONE|PHONE|0|0\n", text)
        assertEquals("phasewake-alarms 1\nc|7|0||30|1|SOUND|0||||OFF|MATH|PHONE|0|0\n", Codec.encodeAlarms(alarms.drop(2).take(1)))
        assertEquals("phasewake-alarms 1\nd|6|15||20|1|OFF|0||||RING|NONE|WATCH|0|0\n", Codec.encodeAlarms(alarms.drop(3)))
    }

    @Test
    fun olderFormatWithoutWakeTaskIsRead() {
        // 12 Felder (vor der Weckaufgabe): Halten.
        val old = Codec.decodeAlarms("phasewake-alarms 1\nc|7|0||30|1|SOUND|0||||OFF\n").single()
        assertEquals(WakeTask.NONE, old.wakeTask)
        assertEquals(alarms[2].copy(wakeTask = WakeTask.NONE), old)
        // Neuere Version mit weiteren Feldern: gelesen, was diese kennt.
        assertEquals(WakeTask.MATH, Codec.decodeAlarms("phasewake-alarms 1\nc|7|0||30|1|SOUND|0||||OFF|MATH|neu\n").single().wakeTask)
    }

    @Test
    fun sunriseDeviceIsTolerant() {
        // 13 Felder (vor der Gerätewahl): Handy.
        val old = Codec.decodeAlarms("phasewake-alarms 1\nd|6|15||20|1|OFF|0||||RING|NONE\n").single()
        assertEquals(alarms[3].copy(sunriseDevice = SunriseDevice.PHONE), old)
        // Unbekannter Wert aus einer neueren Version: Handy; weitere Felder werden ignoriert.
        assertEquals(SunriseDevice.PHONE, Codec.decodeAlarms("phasewake-alarms 1\nd|6|15||20|1|OFF|0||||RING|NONE|LAMP\n").single().sunriseDevice)
        assertEquals(SunriseDevice.BOTH, Codec.decodeAlarms("phasewake-alarms 1\nd|6|15||20|1|OFF|0||||RING|NONE|BOTH|neu\n").single().sunriseDevice)
    }

    @Test
    fun sunrisePerDevice() {
        val spec = alarms[3]
        assertEquals(Sunrise.OFF, spec.phoneSunrise)
        assertEquals(Sunrise.RING, spec.watchSunrise)
        assertEquals(Sunrise.RING, spec.copy(sunriseDevice = SunriseDevice.BOTH).phoneSunrise)
        assertEquals(Sunrise.RING, spec.copy(sunriseDevice = SunriseDevice.BOTH).watchSunrise)
        assertEquals(Sunrise.OFF, spec.copy(sunriseDevice = SunriseDevice.PHONE).watchSunrise)
    }

    @Test
    fun olderFormatWithoutSunriseIsRead() {
        val old = Codec.decodeAlarms("phasewake-alarms 1\nb|9|30||30|0|OFF|0|450||\n")
        assertEquals(listOf(alarms[1].copy(sunrise = Sunrise.OFF)), old)
    }

    @Test
    fun unknownValuesFromNewerVersionsFallBackToDefaults() {
        val read = Codec.decodeAlarms("phasewake-alarms 1\nx|6|0||30|1|LOUD|0||||MOON|extra\n").single()
        assertEquals(PhoneAlert.SOUND, read.phone)
        assertEquals(Sunrise.OFF, read.sunrise)
        assertEquals(WakeTask.NONE, read.wakeTask)
    }

    @Test
    fun nightWithFeelingAndOlderFormat() {
        val text = "phasewake-night 1\na|0|1800000|1080000|DEADLINE|0\n0.0;52.0\n"
        val old = Codec.decodeNight(text)
        assertEquals(null, old.feeling)
        val rated = old.copy(feeling = 5)
        assertEquals(rated, Codec.decodeNight(Codec.encodeNight(rated)))
        assertEquals("phasewake-night 1\na|0|1800000|1080000|DEADLINE|0|5\n0.0;52.0\n", Codec.encodeNight(rated))
        assertFailsWith<CodecException> { Codec.decodeNight("phasewake-night 1\na|0|1800000|1080000|DEADLINE|0|9\n\n") }
    }

    @Test
    fun nightFromNewerVersionWithExtraFieldIsRead() {
        val read = Codec.decodeNight("phasewake-night 1\na|0|1800000|1080000|DEADLINE|0|4|extra\n0.0;52.0\n")
        assertEquals(4, read.feeling)
        assertEquals(1, read.epochs.size)
        assertFailsWith<CodecException> { Codec.decodeNight("phasewake-night 1\na|0|1800000|1080000|DEADLINE\n\n") }
    }

    @Test
    fun nightRoundTrip() {
        val night = NightRecord(
            alarmId = "3f2a-77",
            windowStart = Instant.parse("2026-10-28T05:30:00Z"),
            target = Instant.parse("2026-10-28T06:00:00Z"),
            wokeAt = Instant.parse("2026-10-28T05:47:30Z"),
            reason = WakeReason.LIGHT_SLEEP,
            snoozes = 2,
            epochs = listOf(Epoch(0.0, 52.0), Epoch(0.000213456789123, null), Epoch(null, 55.583333333333336), Epoch(null, null)),
        )
        assertEquals(night, Codec.decodeNight(Codec.encodeNight(night)))
        assertEquals(night.copy(epochs = emptyList()), Codec.decodeNight(Codec.encodeNight(night.copy(epochs = emptyList()))))
    }

    @Test
    fun brokenInputIsReported() {
        assertFailsWith<CodecException> { Codec.decodeAlarms("") }
        // Kaputte Zeilen fallen einzeln weg, statt alle Wecker mitzunehmen.
        assertEquals(emptyList(), Codec.decodeAlarms("phasewake-alarms 1\nx|25|0||30|1|SOUND|0|||"))
        assertEquals(emptyList(), Codec.decodeAlarms("phasewake-alarms 1\nx|6|0|XX|30|1|SOUND|0|||"))
        assertEquals(emptyList(), Codec.decodeAlarms("phasewake-alarms 1\nx|6"))
        assertEquals(listOf("b"), Codec.decodeAlarms("phasewake-alarms 1\nx|6\nb|9|30||30|0|OFF|0|450||\n").map { it.id })
        assertFailsWith<CodecException> { Codec.decodeNight("phasewake-night 1\na|1|2|3|NOPE|0\n") }
        assertFailsWith<CodecException> { Codec.decodeNight("phasewake-alarms 1\n") }
    }
}
