package com.phasewake.app.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StageCheckTest {
    private val t0 = Instant.parse("2026-10-28T05:00:00Z")
    private fun at(min: Long) = t0.plusSeconds(min * 60)
    private val spans = listOf(
        StageSpan(at(0), at(20), SleepStage.DEEP),
        StageSpan(at(20), at(35), SleepStage.LIGHT),
        StageSpan(at(35), at(50), SleepStage.REM),
    )

    @Test
    fun stageAtBoundaries() {
        assertEquals(SleepStage.DEEP, StageCheck.stageAt(spans, at(0)))
        assertEquals(SleepStage.LIGHT, StageCheck.stageAt(spans, at(20)))
        assertNull(StageCheck.stageAt(spans, at(50)))
    }

    @Test
    fun verdictWithTolerance() {
        assertEquals(false, StageCheck.wokeWell(spans, at(10)))
        assertEquals(true, StageCheck.wokeWell(spans, at(25)))
        assertEquals(true, StageCheck.wokeWell(spans, at(51))) // Sitzung endete 1 Min vorher
        assertNull(StageCheck.wokeWell(spans, at(60)))
        assertNull(StageCheck.wokeWell(listOf(StageSpan(at(0), at(10), SleepStage.UNKNOWN)), at(5)))
    }

    @Test
    fun clipToWindow() {
        val clipped = StageCheck.clip(spans.reversed(), at(15), at(40))
        assertEquals(listOf(SleepStage.DEEP, SleepStage.LIGHT, SleepStage.REM), clipped.map { it.stage })
        assertEquals(at(15), clipped.first().start)
        assertEquals(at(40), clipped.last().end)
    }
}
