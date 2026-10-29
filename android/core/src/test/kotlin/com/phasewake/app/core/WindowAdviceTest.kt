package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowAdviceTest {
    private val spec = AlarmSpec(id = "a", hour = 7, minute = 0, windowMinutes = 15)
    private val t0 = Instant.parse("2026-10-01T05:45:00Z")

    private fun night(day: Long, reason: WakeReason, id: String = "a") = t0.plus(Duration.ofDays(day)).let {
        NightRecord(id, it, it.plusSeconds(900), it.plusSeconds(900), reason, 0, emptyList())
    }

    @Test
    fun suggestsWiderWindowWhenMostlyDeadline() {
        val history = listOf(night(0, WakeReason.DEADLINE), night(1, WakeReason.LIGHT_SLEEP), night(2, WakeReason.DEADLINE))
        assertEquals(WindowAdvice.Suggestion(25, 2, 3), WindowAdvice.suggest(spec, history))
    }

    @Test
    fun noAdviceWhenFewNightsOrMostlyGood() {
        assertNull(WindowAdvice.suggest(spec, listOf(night(0, WakeReason.DEADLINE), night(1, WakeReason.DEADLINE))))
        val mixed = listOf(night(0, WakeReason.DEADLINE), night(1, WakeReason.LIGHT_SLEEP), night(2, WakeReason.AWAKE), night(3, WakeReason.DEADLINE))
        assertNull(WindowAdvice.suggest(spec, mixed))
    }

    @Test
    fun onlyThisAlarmAndCappedAtMaximum() {
        val others = List(5) { night(it.toLong(), WakeReason.DEADLINE, id = "b") }
        assertNull(WindowAdvice.suggest(spec, others))
        val deadlines = List(3) { night(it.toLong(), WakeReason.DEADLINE) }
        assertEquals(30, WindowAdvice.suggest(spec.copy(windowMinutes = 25), deadlines)?.windowMinutes)
        assertNull(WindowAdvice.suggest(spec.copy(windowMinutes = 30), deadlines))
        // «Genau» hat bewusst kein Fenster: kein Vorschlag.
        assertNull(WindowAdvice.suggest(spec.copy(windowMinutes = AlarmSpec.EXACT_WINDOW), deadlines))
    }

    @Test
    fun exactAndShortNightsDoNotCount() {
        // Nur zwei zählbare Nächte: zu wenig für einen Vorschlag, auch wenn «Genau» und kurze Nächte zur Zielzeit endeten.
        val history = listOf(night(0, WakeReason.DEADLINE), night(1, WakeReason.EXACT), night(2, WakeReason.SHORT_NIGHT), night(3, WakeReason.DEADLINE))
        assertNull(WindowAdvice.suggest(spec, history))
        assertEquals(3, WindowAdvice.suggest(spec, history + night(4, WakeReason.LIGHT_SLEEP))?.nights)
    }
}
