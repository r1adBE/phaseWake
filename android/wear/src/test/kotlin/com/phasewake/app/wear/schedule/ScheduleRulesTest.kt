package com.phasewake.app.wear.schedule

import com.phasewake.app.core.WakePlan
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Neuplanen der Uhr ([AlarmScheduler.reschedule] über [ScheduleRules]): Plan stellen, laufende Nacht unberührt,
 * Messung eines überholten Plans beenden, ausstehendes Wecken von vor einem Neustart behalten. Feste Zeiten.
 */
class ScheduleRulesTest {
    private val now = Instant.parse("2026-11-03T05:00:00Z")
    private val plan = WakePlan("a", Instant.parse("2026-11-03T06:00:00Z"), Instant.parse("2026-11-03T06:30:00Z"))
    private val other = WakePlan("b", Instant.parse("2026-11-03T04:30:00Z"), Instant.parse("2026-11-03T04:50:00Z"))

    @Test
    fun plansWindowAndDeadline() {
        val d = ScheduleRules.decide(plan, measuring = null, active = null, pending = null, now = now)
        assertEquals(ScheduleRules.Decision(false, false, null, plan.windowStart, plan.target), d)
        // Fenster schon begonnen: Start gleich (über einen Alarm), Zielzeit bleibt.
        val late = ScheduleRules.decide(plan, null, null, null, plan.windowStart.plusSeconds(60))
        assertEquals(plan.windowStart.plusSeconds(61), late.windowAt)
        // Kein Wecker: nichts stellen.
        assertEquals(ScheduleRules.Decision(false, false, null, null, null), ScheduleRules.decide(null, null, null, null, now))
    }

    @Test
    fun runningNightKeepsItsAlarms() {
        // Klingelt oder schlummert «b»: nichts anfassen, kein anderer Wecker.
        val d = ScheduleRules.decide(plan, measuring = null, active = other, pending = null, now = now)
        assertTrue(d.keepRunning)
        assertNull(d.windowAt)
        assertNull(d.deadlineAt)
        // Misst «b», gilt aber «a»: Messung beenden und «a» planen.
        val switched = ScheduleRules.decide(plan, measuring = other, active = other, pending = null, now = now)
        assertTrue(switched.cancelMeasuring)
        assertFalse(switched.keepRunning)
        assertEquals(plan.target, switched.deadlineAt)
        // Misst genau den gültigen Plan: weiter.
        assertFalse(ScheduleRules.decide(plan, plan, plan, null, now).cancelMeasuring)
    }

    @Test
    fun pendingRingFromBeforeRestartStays() {
        // Vor dem Neustart geschlummert (Ende 05:05): bleibt stehen, der neue Plan folgt danach.
        val snoozed = DirectBoot.Saved(other, Instant.parse("2026-11-03T05:05:00Z"), false, "")
        val d = ScheduleRules.decide(plan, null, null, snoozed, now)
        assertEquals(snoozed.at, d.pendingRingAt)
        assertEquals(plan.target, d.deadlineAt)
        // Schon vorbei (während des Neustarts verpasst): gleich.
        val missed = snoozed.copy(at = now.minus(Duration.ofMinutes(1)))
        assertEquals(now.plus(ScheduleRules.IMMEDIATE), ScheduleRules.decide(plan, null, null, missed, now).pendingRingAt)
        // Gehört zum Plan, der ohnehin gilt: kein eigener Alarm.
        assertNull(ScheduleRules.decide(other, null, null, snoozed.copy(plan = other), now).pendingRingAt)
    }

    @Test
    fun pendingRingOnlyWhenStillDue() {
        val snoozed = DirectBoot.Saved(other, Instant.parse("2026-11-03T05:05:00Z"), false, "")
        assertTrue(ScheduleRules.pendingRing(snoozed, now, catchesUp = true, doneUntil = null, exists = true))
        assertFalse(ScheduleRules.pendingRing(snoozed, now, catchesUp = false, doneUntil = null, exists = true), "zu spät")
        assertFalse(ScheduleRules.pendingRing(snoozed, now, true, doneUntil = other.target, exists = true), "erledigt")
        assertFalse(ScheduleRules.pendingRing(snoozed, now, true, null, exists = false), "Wecker gelöscht oder aus")
        // Noch vor der Zielzeit und nicht geschlummert: plant das normale Neuplanen.
        val ahead = DirectBoot.Saved(plan, plan.target, false, "")
        assertFalse(ScheduleRules.pendingRing(ahead, now, true, null, true))
    }
}
