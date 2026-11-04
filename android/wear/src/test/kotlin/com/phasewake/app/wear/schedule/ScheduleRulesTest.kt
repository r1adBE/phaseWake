package com.phasewake.app.wear.schedule

import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.WakePlan
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
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

    @Test
    fun zoneChangeInWindowKeepsNight() {
        // 07:00 Zürich, Fenster 30 min; um 06:40 wechselt die Zone nach Tokio (14:40).
        val zurich = ZoneId.of("Europe/Zurich")
        val tokyo = ZoneId.of("Asia/Tokyo")
        val spec = AlarmSpec(id = "a", hour = 7, minute = 0, windowMinutes = 30)
        val target = LocalDateTime.parse("2026-11-04T07:00").atZone(zurich).toInstant()
        val at = target.minus(Duration.ofMinutes(20))
        val planned = ScheduleRules.Planned("a", target, zurich)
        val kept = ScheduleRules.keptNight(planned, listOf(spec), tokyo, at, emptySet(), emptyMap())
        assertEquals(AlarmSchedule.planAt(spec, target), kept)
        // Messung läuft weiter, die Sicherung bleibt zur geplanten Zielzeit (15:00 in Tokio).
        val d = ScheduleRules.decide(kept, measuring = kept, active = kept, pending = null, now = at)
        assertFalse(d.cancelMeasuring)
        assertEquals(target, d.deadlineAt)
        // Ohne die Regel gälte morgen 07:00 Tokio, und die Messung bräche ab.
        val tomorrow = AlarmSchedule.nextPlan(spec, at, tokyo)
        assertTrue(ScheduleRules.decide(tomorrow, kept, kept, null, at).cancelMeasuring)
        // Gleiche Zone, ohne Zone, Fenster noch nicht begonnen, Nacht erledigt, Wecker gelöscht: wie bisher.
        assertNull(ScheduleRules.keptNight(planned, listOf(spec), zurich, at, emptySet(), emptyMap()))
        // Zürich → Berlin: Ortszeit gleich, ein früherer neuer Wecker unterbräche die Messung wie bisher.
        assertNull(ScheduleRules.keptNight(planned, listOf(spec), ZoneId.of("Europe/Berlin"), at, emptySet(), emptyMap()))
        assertNull(ScheduleRules.keptNight(planned.copy(zone = null), listOf(spec), tokyo, at, emptySet(), emptyMap()))
        assertNull(ScheduleRules.keptNight(planned, listOf(spec), tokyo, target.minus(Duration.ofMinutes(40)), emptySet(), emptyMap()))
        assertNull(ScheduleRules.keptNight(planned, listOf(spec), tokyo, at, emptySet(), mapOf("a" to target)))
        assertNull(ScheduleRules.keptNight(planned, emptyList(), tokyo, at, emptySet(), emptyMap()))
        // Nachfrist: kurz nach der Zielzeit noch, danach nicht mehr.
        assertEquals(kept, ScheduleRules.keptNight(planned, listOf(spec), tokyo, target.plusSeconds(30), emptySet(), emptyMap()))
        assertNull(ScheduleRules.keptNight(planned, listOf(spec), tokyo, target.plus(ScheduleRules.KEEP_GRACE), emptySet(), emptyMap()))
    }
}
