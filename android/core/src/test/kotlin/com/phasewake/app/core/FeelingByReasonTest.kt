package com.phasewake.app.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Befinden je Weckgrund für die Diagnose. Gegenstück: `FeelingByReasonTests` (Swift), gleiche Fälle. */
class FeelingByReasonTest {
    private val t0: Instant = Instant.parse("2026-11-01T05:30:00Z")

    private fun night(day: Int, reason: WakeReason, feeling: Int?): NightRecord {
        val start = t0.plusSeconds(day * 86_400L)
        return NightRecord(
            alarmId = "a", windowStart = start, target = start.plusSeconds(1800), wokeAt = start.plusSeconds(900),
            reason = reason, snoozes = 0, epochs = emptyList(), feeling = feeling,
        )
    }

    @Test
    fun countsAndAveragesPerReasonInFixedOrder() {
        val nights = listOf(
            night(0, WakeReason.LIGHT_SLEEP, 4), night(1, WakeReason.LIGHT_SLEEP, 5),
            night(2, WakeReason.DEADLINE, 2), night(3, WakeReason.AWAKE, 3), night(4, WakeReason.LIGHT_SLEEP, null),
            night(5, WakeReason.SHORT_NIGHT, 1),
        )
        val rows = FeelingByReason.of(nights)
        assertEquals(FeelingByReason.ORDER, rows.map { it.reason })
        assertEquals(listOf(2, 1, 1, 0, 1), rows.map { it.nights })
        assertEquals(4.5, rows[0].average)
        assertNull(rows[3].average)
        assertEquals(listOf("4.5", "3.0", "2.0", "-", "1.0"), rows.map { it.averageText() })
    }

    @Test
    fun averageRoundsHalfUpWithoutFloatingPoint() {
        // 77 / 20 = 3.85 → 3.9 (als Gleitkomma knapp darunter); 11 / 3 = 3.67 → 3.7; 10 / 3 = 3.33 → 3.3.
        val nights = (0 until 20).map { night(it, WakeReason.LIGHT_SLEEP, if (it < 17) 4 else 3) }
        assertEquals("3.9", FeelingByReason.of(nights)[0].averageText())
        val three = listOf(4, 4, 3).mapIndexed { i, f -> night(i, WakeReason.DEADLINE, f) }
        assertEquals("3.7", FeelingByReason.of(three)[2].averageText())
        val low = listOf(4, 3, 3).mapIndexed { i, f -> night(i, WakeReason.EXACT, f) }
        assertEquals("3.3", FeelingByReason.of(low)[3].averageText())
    }

    @Test
    fun linesNameTheReviewThreshold() {
        val nights = listOf(
            night(0, WakeReason.LIGHT_SLEEP, 4), night(1, WakeReason.DEADLINE, 3), night(2, WakeReason.EXACT, null),
        )
        assertEquals(
            listOf(
                FeelingByReason.NOTE,
                "LIGHT_SLEEP 1 nights avg 4.0",
                "AWAKE 0 nights avg -",
                "DEADLINE 1 nights avg 3.0",
                "EXACT 0 nights avg -",
                "SHORT_NIGHT 0 nights avg -",
                "all: 2 nights with feeling avg 3.5 · review thresholds from 20 (18 to go)",
            ),
            FeelingByReason.lines(nights),
        )
        val enough = (0 until FeelingByReason.REVIEW_NIGHTS).map { night(it, WakeReason.AWAKE, 5) }
        assertEquals(
            "all: 20 nights with feeling avg 5.0 · 20 reached, review thresholds",
            FeelingByReason.lines(enough).last(),
        )
    }

    @Test
    fun noFeelingNoSection() {
        assertEquals(emptyList(), FeelingByReason.lines(emptyList()))
        assertEquals(emptyList(), FeelingByReason.lines(listOf(night(0, WakeReason.LIGHT_SLEEP, null))))
    }
}
