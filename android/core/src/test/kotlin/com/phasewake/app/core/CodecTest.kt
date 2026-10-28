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
            phoneBackup = false,
            label = "Arbeit | früh, 100 %",
        ),
        AlarmSpec(id = "b", hour = 9, minute = 30, enabled = false, sleepDurationMinutes = 450),
    )

    @Test
    fun alarmsRoundTrip() {
        assertEquals(alarms, Codec.decodeAlarms(Codec.encodeAlarms(alarms)))
        assertEquals(emptyList(), Codec.decodeAlarms(Codec.encodeAlarms(emptyList())))
    }

    @Test
    fun alarmsFormatIsStable() {
        val text = Codec.encodeAlarms(alarms.take(1))
        assertEquals("phasewake-alarms 1\n3f2a-77|6|30|MO,FR|25|1|0||Arbeit+%7C+fr%C3%BCh%2C+100+%25\n", text)
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
        assertFailsWith<CodecException> { Codec.decodeAlarms("phasewake-alarms 1\nx|25|0|||30|1|1||") }
        assertFailsWith<CodecException> { Codec.decodeAlarms("phasewake-alarms 1\nx|6|0|XX|30|1|1||") }
        assertFailsWith<CodecException> { Codec.decodeAlarms("phasewake-alarms 1\nx|6") }
        assertFailsWith<CodecException> { Codec.decodeNight("phasewake-night 1\na|1|2|3|NOPE|0\n") }
        assertFailsWith<CodecException> { Codec.decodeNight("phasewake-alarms 1\n") }
    }
}
