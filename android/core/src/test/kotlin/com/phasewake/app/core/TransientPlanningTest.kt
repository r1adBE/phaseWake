package com.phasewake.app.core

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Welcher Plan gilt (echter Wecker oder vorübergehender) und wann ein Probewecker/Powernap abgelehnt wird. Gegenstück: `TransientPlanningTests` (Swift). */
class TransientPlanningTest {
    private val zurich = ZoneId.of("Europe/Zurich")
    private val now = LocalDateTime.parse("2026-10-29T05:45").atZone(zurich).toInstant()
    private val alarm = AlarmSpec(id = "a", hour = 7, minute = 0, windowMinutes = 30, days = setOf(java.time.DayOfWeek.THURSDAY))
    private val real = AlarmSchedule.nextPlan(alarm, now, zurich)!!

    @Test
    fun napNeverDisplacesEarlierAlarm() {
        // Nap von 90 Min bis 7:15, Wecker 7:00: der Wecker gilt.
        val nap = Transient.nap(null, now, zurich, 90, "1")
        assertEquals(real, Transient.choose(real, nap, now))
        val short = Transient.nap(null, now, zurich, 20, "2")
        assertEquals(short.plan, Transient.choose(real, short, now))
        assertEquals(short.plan, Transient.choose(null, short, now))
        assertEquals(real, Transient.choose(real, null, now))
        assertNull(Transient.choose(null, null, now))
    }

    @Test
    fun staleTransientIsNotPlannedAgain() {
        // Uhr war aus und kommt 1,5 h nach dem Ende des Naps zurück: nicht mehr klingeln.
        val nap = Transient.nap(null, now, zurich, 20, "1")
        val target = nap.plan.target
        assertTrue(Transient.isPlannable(nap, target))
        assertTrue(Transient.isPlannable(nap, target.plus(Transient.PLAN_AFTER_TARGET).minusMillis(1)))
        assertFalse(Transient.isPlannable(nap, target.plus(Transient.PLAN_AFTER_TARGET)))
        assertNull(Transient.choose(null, nap, target.plusSeconds(90 * 60)))
        // Die Weckart gilt dagegen noch (Klingeln, Stopp).
        assertTrue(Transient.isActive(nap, target.plusSeconds(90 * 60)))
    }

    @Test
    fun missedTransientDoesNotRingAfterBoot() {
        // Powernap bis 15:00, Uhr um 14:50 aus, um 15:20 wieder an: nicht nachklingeln.
        val nap = Transient.nap(null, now, zurich, 20, "1")
        val at = nap.plan.target
        val missed = java.time.Duration.ofMinutes(30)
        assertTrue(Transient.catchesUpAfterBoot(nap.plan.alarmId, at, at.minusSeconds(60), missed))
        assertTrue(Transient.catchesUpAfterBoot(nap.plan.alarmId, at, at.plus(Transient.PLAN_AFTER_TARGET).minusMillis(1), missed))
        assertFalse(Transient.catchesUpAfterBoot(nap.plan.alarmId, at, at.plus(Transient.PLAN_AFTER_TARGET), missed))
        assertFalse(Transient.catchesUpAfterBoot(nap.plan.alarmId, at, at.plusSeconds(20 * 60), missed))
        val test = Transient.test(null, now, zurich, "2")
        assertFalse(Transient.catchesUpAfterBoot(test.plan.alarmId, test.plan.target, test.plan.target.plusSeconds(61), missed))
        // Geschlummerter Powernap: Grenze ab dem Schlummer-Ende.
        val snoozeEnd = at.plusSeconds(9 * 60)
        assertTrue(Transient.catchesUpAfterBoot(nap.plan.alarmId, snoozeEnd, snoozeEnd.plusSeconds(30), missed))
        // Gespeicherter Wecker: weiter bis 30 Min (einschliesslich).
        assertTrue(Transient.catchesUpAfterBoot("a", real.target, real.target.plus(missed), missed))
        assertFalse(Transient.catchesUpAfterBoot("a", real.target, real.target.plus(missed).plusMillis(1), missed))
        // Handy-Schlummersicherung ohne Kennung (leer): wie ein gespeicherter Wecker.
        assertTrue(Transient.catchesUpAfterBoot("", real.target, real.target.plusSeconds(20 * 60), missed))
        // Zweitwecker am Handy für einen Powernap: ebenfalls nur 60 s.
        assertFalse(Transient.catchesUpAfterBoot(Transient.NAP_PREFIX + "x", real.target, real.target.plusSeconds(61), missed))
    }

    @Test
    fun lateTestIsNotConfirmed() {
        // Die Uhr bestätigt nur, was sie noch plant (isPlannable), nicht alles, was noch gilt (isActive).
        val test = Transient.test(null, now, zurich, "1")
        val late = test.plan.target.plusSeconds(5 * 60)
        assertTrue(Transient.isActive(test, late))
        assertFalse(Transient.isPlannable(test, late))
        assertNull(Transient.choose(null, test, late))
    }

    @Test
    fun conflictWhileAlarmRunsOrBeforeWindow() {
        val test = Transient.test(null, now, zurich, "1")
        assertNull(Transient.conflict(test, real, nightActive = false, now = now))
        assertNull(Transient.conflict(test, null, nightActive = false, now = now))
        assertEquals(TransientConflict.ALARM_RUNNING, Transient.conflict(test, null, nightActive = true, now = now))
        // Im Fenster 6:30–7:00.
        val inWindow = real.windowStart.plusSeconds(600)
        val later = Transient.test(null, inWindow, zurich, "2")
        assertEquals(TransientConflict.ALARM_RUNNING, Transient.conflict(later, real, nightActive = false, now = inWindow))
        assertEquals(TransientConflict.ALARM_RUNNING, Transient.conflict(later, real, false, real.windowStart))
        // Nap bis 7:15 überdeckt das Fenster ab 6:30.
        val nap = Transient.nap(null, now, zurich, 90, "3")
        assertEquals(TransientConflict.ALARM_SOON, Transient.conflict(nap, real, nightActive = false, now = now))
        // Nap genau bis zum Fensterbeginn: erlaubt.
        val fits = Transient.nap(null, real.windowStart.minusSeconds(45 * 60), zurich, 45, "4")
        assertNull(Transient.conflict(fits, real, false, real.windowStart.minusSeconds(45 * 60)))
        // Nach der Zielzeit (Nacht erledigt, nächster Plan ist morgen): erlaubt.
        assertNull(Transient.conflict(Transient.test(null, real.target, zurich, "5"), AlarmSchedule.nextPlan(alarm, real.target, zurich), false, real.target))
    }
}
