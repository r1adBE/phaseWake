package com.phasewake.app.ui.home

import com.phasewake.app.R
import com.phasewake.app.core.AlarmSpec
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Hinweis der Lernphase je Weckerart (Audit O-02): «Gute Nacht» nur, wo die Uhr den Knopf zeigt. */
class LearnAutoHintTest {
    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-11-14T20:00:00Z")
    private val clock = AlarmSpec(id = "c", hour = 7, minute = 0)
    private val sleep = AlarmSpec(id = "s", hour = 8, minute = 0, sleepDurationMinutes = 450)

    @Test
    fun goodNightOnlyForSleepModeAsNextAlarm() {
        assertFalse(goodNightOffered(listOf(clock), now, zone, emptySet(), emptyMap()))
        assertTrue(goodNightOffered(listOf(sleep), now, zone, emptySet(), emptyMap()))
        // Der nächste Wecker zählt (wie auf der Uhr): Uhrzeit-Wecker 07:00 vor dem Einschlaf-Wecker 08:00.
        assertFalse(goodNightOffered(listOf(clock, sleep), now, zone, emptySet(), emptyMap()))
        assertFalse(goodNightOffered(emptyList(), now, zone, emptySet(), emptyMap()))
    }

    @Test
    fun hintTextPerAlarmKind() {
        assertEquals(R.string.learn_auto_hint, learnAutoHint(goodNight = true))
        assertEquals(R.string.learn_auto_hint_clock, learnAutoHint(goodNight = false))
    }
}
