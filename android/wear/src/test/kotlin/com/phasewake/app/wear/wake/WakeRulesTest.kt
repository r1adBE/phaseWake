package com.phasewake.app.wear.wake

import com.phasewake.app.core.Epoch
import com.phasewake.app.core.Snooze
import com.phasewake.app.core.Transient
import com.phasewake.app.core.WakePlan
import com.phasewake.app.core.WakeReason
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Weckablauf der Uhr über seine Entscheidungen ([WakeRules]): Fenster → Zielzeit → Klingeln → Schlummern → Ende,
 * Wiederaufnahme-Alarm beim Klingeln, Warten eines zweiten Weckers, erledigt bis zur eingestellten Zeit. Feste Zeiten.
 */
class WakeRulesTest {
    private val target = Instant.parse("2026-11-03T06:30:00Z")
    private val plan = WakePlan("a", target.minus(Duration.ofMinutes(30)), target)

    @Test
    fun windowEndsWithReasonAtTarget() {
        // «Genau» weckt zur Zielzeit, eine übergangene kurze Nacht ebenso; sonst entscheidet die Erkennung.
        assertEquals(
            WakeReason.EXACT,
            WakeRules.deadlineReason(exact = true, earlyBlocked = true) { WakeReason.DEADLINE },
        )
        assertEquals(
            WakeReason.SHORT_NIGHT,
            WakeRules.deadlineReason(exact = false, earlyBlocked = true) { WakeReason.DEADLINE },
        )
        assertEquals(
            WakeReason.DEADLINE,
            WakeRules.deadlineReason(exact = false, earlyBlocked = false) { WakeReason.DEADLINE },
        )
    }

    @Test
    fun ringSnoozeUntilLastRoundThenEnd() {
        // Niemand reagiert: jede Zeitgrenze schlummert, bis keine Runde mehr bleibt; dann endet die Nacht.
        var snoozes = 0
        val steps = mutableListOf<WakeRules.Timeout>()
        while (true) {
            val step = WakeRules.timeout(WakeRules.snoozesLeft("a", snoozes))
            steps += step
            if (step == WakeRules.Timeout.DISMISS) break
            snoozes++
        }
        assertEquals(List(Snooze.MAX_COUNT) { WakeRules.Timeout.SNOOZE } + WakeRules.Timeout.DISMISS, steps)
        // Der Probewecker schlummert nie: die erste Zeitgrenze beendet.
        assertEquals(
            WakeRules.Timeout.DISMISS,
            WakeRules.timeout(WakeRules.snoozesLeft(Transient.TEST_PREFIX + "1", 0)),
        )
    }

    @Test
    fun resumeAlarmComesAfterTheRingTimeout() {
        val now = target.plusSeconds(5)
        val at = WakeRules.resumeAt(now)
        assertEquals(now.plusMillis(WakeRules.RING_TIMEOUT_MS).plus(WakeRules.RESUME_MARGIN), at)
        assertTrue(at.isAfter(now.plusMillis(WakeRules.RING_TIMEOUT_MS)), "Ablauf im Prozess kommt zuerst")
    }

    @Test
    fun secondAlarmWaitsWhileANightRings() {
        val other = WakePlan("b", target.minus(Duration.ofMinutes(20)), target.plus(Duration.ofMinutes(10)))
        assertTrue(WakeRules.adopts(null, measuring = false, plan = other), "nichts läuft")
        assertTrue(WakeRules.adopts(plan, measuring = true, plan = other), "nur eine Messung: ablösen")
        assertFalse(WakeRules.adopts(plan, measuring = false, plan = other), "klingelt oder schlummert: warten")
        assertTrue(WakeRules.adopts(plan, measuring = false, plan = plan), "dieselbe Nacht (Schlummer-Ende)")
    }

    @Test
    fun nightIsDoneUntilConfiguredTime() {
        // Einschlaf-Modus weckte 40 Min früher: erledigt bis zur eingestellten Zeit, sonst begänne die Nacht neu.
        val early = target.minus(Duration.ofMinutes(40))
        assertEquals(target, WakeRules.doneUntil(early, target))
        assertEquals(target, WakeRules.doneUntil(target, target))
        assertEquals(early, WakeRules.doneUntil(early, null))
        // Die nächste Nacht (über 16 Std. später) zählt nicht.
        assertEquals(target, WakeRules.doneUntil(target, target.plus(Duration.ofDays(1))))
    }

    @Test
    fun startDelayLine() {
        // Verzögerung bis zum Start des Dienstes, dazu bis zum Empfang des Alarms.
        assertEquals(
            "watch start delay 47.0s (alarm 0.3s)",
            WakeRules.startDelayLine(1_000_000L, 1_000_300L, 1_047_000L),
        )
        assertEquals("watch start delay 1.2s", WakeRules.startDelayLine(1_000_000L, 0L, 1_001_249L))
    }

    @Test
    fun epochsBeforeTheSensorsStartAreNotMeasured() {
        // Der Dienst startet 47 s nach Fensterbeginn: Epoche 0 ist vorbei und bleibt leer, Epoche 1 nicht.
        val start = plan.windowStart
        assertEquals(1, WakeRules.unmeasuredEpochs(start, start.plusSeconds(47)))
        assertEquals(0, WakeRules.unmeasuredEpochs(start, start.plusSeconds(29)))
        assertEquals(1, WakeRules.unmeasuredEpochs(start, start.plusSeconds(30)))
        assertEquals(4, WakeRules.unmeasuredEpochs(start, start.plusSeconds(2 * 60 + 1)))
        // Vor dem Fensterbeginn (Uhr ungenau): keine.
        assertEquals(0, WakeRules.unmeasuredEpochs(start, start.minusSeconds(5)))
    }

    /**
     * «stop» oder «schlummern» vom Handy gilt nur für dieselbe Nacht (Zielzeit auf 1 s); fehlende Angaben widersprechen
     * nicht. Dieselben Fälle wie `testPhoneCommandBelongsToThisNightOnly` der Apple Watch.
     */
    @Test
    fun phoneCommandBelongsToThisNightOnly() {
        val own = target.plusSeconds(600).toEpochMilli()
        fun same(id: String?, time: Long?) = WakeRules.isThisNight(id, time, "d", own)
        assertTrue(same("d", own))
        assertTrue(same("d", own + 900))
        assertFalse(same("d", own + 1000))
        assertFalse(same("x", own))
        assertFalse(same("d", own + 86_400_000L))
        assertTrue(same(null, null))
        assertTrue(WakeRules.isThisNight("x", target.toEpochMilli(), null, null))
        // Ältere Handy-Version ohne Zielzeit: nur die Kennung.
        assertTrue(same("d", null))
        assertFalse(same("x", null))
    }

    @Test
    fun otherNightLineNamesTheCommand() {
        assertEquals("phone stop for other night", WakeRules.otherNightLine(WakeService.ACTION_DISMISS))
        assertEquals("phone snooze for other night", WakeRules.otherNightLine(WakeService.ACTION_PHONE_SNOOZE))
        assertEquals("phone task for other night", WakeRules.otherNightLine(WakeService.ACTION_TASK_SHOWN))
    }

    /** Zeile zum Ende der Messung; dieselben Fälle wie `testMeasureEndLineCountsHeartRateEpochs` der Apple Watch. */
    @Test
    fun measureEndLineCountsHeartRateEpochs() {
        val epochs = listOf(Epoch(0.1, 58.0), Epoch(0.2, null))
        assertEquals(
            "watch 2 epochs hr=1, floor 0.01, cancelled",
            WakeRules.measureEndLine(epochs, "floor 0.01", ", cancelled"),
        )
        assertEquals("watch 0 epochs hr=0, s", WakeRules.measureEndLine(emptyList(), "s", ""))
    }
}
