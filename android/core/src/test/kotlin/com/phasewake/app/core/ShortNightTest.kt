package com.phasewake.app.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Untergrenze für kurze Nächte und Plan bei «Genau»; Gegenstück: `ShortNightTests` (Swift). */
class ShortNightTest {
    private val zurich = ZoneId.of("Europe/Zurich")

    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(zurich).toInstant()

    @Test
    fun unknownOnsetNeverBlocks() {
        assertFalse(ShortNight.blocksEarly(null, at("2026-10-29T06:40"), SleepGoal.DEFAULT_MINUTES))
    }

    @Test
    fun blocksBelowGoalMinusOneHour() {
        val onset = at("2026-10-29T00:30")
        // Ziel 7½ h: Grenze 6½ h nach dem Einschlafen, also 07:00.
        assertTrue(ShortNight.blocksEarly(onset, at("2026-10-29T06:40"), 450))
        assertTrue(ShortNight.blocksEarly(onset, at("2026-10-29T06:59"), 450))
        assertFalse(ShortNight.blocksEarly(onset, at("2026-10-29T07:00"), 450))
        assertFalse(ShortNight.blocksEarly(onset, at("2026-10-29T07:10"), 450))
        // Ziel 6 h: Grenze 5 h.
        assertFalse(ShortNight.blocksEarly(onset, at("2026-10-29T05:30"), 360))
    }

    @Test
    fun lastTenMinutesBeforeTheTargetAreNotBlocked() {
        // Fall r1AD 10.11.2026: eingeschlafen 01:51, Zielzeit 07:57, Ziel 7½ h (Grenze 08:21).
        val onset = at("2026-11-10T01:51")
        val target = at("2026-11-10T07:57")
        assertEquals(10, ShortNight.LATE_MINUTES)
        assertTrue(ShortNight.blocksEarly(onset, at("2026-11-10T07:32"), 450, target), "Moment um 07:32 übergangen")
        assertTrue(ShortNight.blocksEarly(onset, at("2026-11-10T07:46"), 450, target))
        assertFalse(ShortNight.blocksEarly(onset, at("2026-11-10T07:47"), 450, target), "ab 07:47 weckt ein neuer Moment")
        assertFalse(ShortNight.blocksEarly(onset, at("2026-11-10T07:55"), 450, target))
        // Ohne Zielzeit wie bisher bis zum Schluss gesperrt.
        assertTrue(ShortNight.blocksEarly(onset, at("2026-11-10T07:50"), 450))
        assertFalse(ShortNight.blocksEarly(null, at("2026-11-10T07:32"), 450, target))
    }

    @Test
    fun autoOnsetStartsOneHourBeforeBedtime() {
        val target = at("2026-10-29T07:00")
        // Bettzeit 23:30 (Ziel 7½ h), Messung ab 22:30.
        assertEquals(at("2026-10-28T22:30"), ShortNight.autoOnsetStart(target, 450, null))
        // Einschlaf-Modus: Schlafdauer statt Ziel.
        assertEquals(at("2026-10-29T00:00"), ShortNight.autoOnsetStart(target, 450, 360))
    }

    @Test
    fun exactPlanMeasuresThirtyMinutesButIsFlagged() {
        val exact = AlarmSpec(id = "a", hour = 7, minute = 0, windowMinutes = AlarmSpec.EXACT_WINDOW)
        val plan = AlarmSchedule.nextPlan(exact, at("2026-10-29T01:00"), zurich)!!
        assertEquals(at("2026-10-29T07:00"), plan.target)
        assertEquals(at("2026-10-29T06:30"), plan.windowStart)
        assertTrue(plan.exact)
        val normal = AlarmSchedule.nextPlan(exact.copy(windowMinutes = 20), at("2026-10-29T01:00"), zurich)!!
        assertEquals(at("2026-10-29T06:40"), normal.windowStart)
        assertFalse(normal.exact)
        assertEquals(normal, AlarmSchedule.planAt(exact.copy(windowMinutes = 20), at("2026-10-29T07:00")))
    }

    @Test
    fun newReasonsCountAsTarget() {
        assertTrue(WakeReason.EXACT.atTarget)
        assertTrue(WakeReason.SHORT_NIGHT.atTarget)
        assertTrue(WakeReason.DEADLINE.atTarget)
        assertFalse(WakeReason.LIGHT_SLEEP.atTarget)
        assertFalse(WakeReason.AWAKE.atTarget)
    }
}
