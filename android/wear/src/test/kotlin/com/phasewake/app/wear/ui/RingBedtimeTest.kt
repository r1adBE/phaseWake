package com.phasewake.app.wear.ui

import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Einschlafpunkt auf dem Ring der Galaxy Watch mit gelernter Einschlafdauer (O-03, wie am Handy und auf der Apple Watch). */
class RingBedtimeTest {
    private val alarm = AlarmSpec(id = "a", hour = 7, minute = 0)
    private val target = Instant.parse("2026-11-15T06:00:00Z")
    private val plan = AlarmSchedule.planAt(alarm, target)

    @Test
    fun learnedLatencyMovesTheBedtimeEarlier() {
        assertEquals(Instant.parse("2026-11-14T22:30:00Z"), ringBedtime(plan, alarm, 450, null))
        assertEquals(Instant.parse("2026-11-14T22:16:00Z"), ringBedtime(plan, alarm, 450, 14))
    }

    @Test
    fun noPointWithoutSavedAlarmOrWhenItDoesNotFit() {
        // Powernap oder Probewecker: kein gespeicherter Wecker.
        assertNull(ringBedtime(plan, null, 450, 14))
        assertNull(ringBedtime(null, alarm, 450, 14))
        // Schlafziel plus Einschlafdauer reicht über das Zifferblatt (12 Std.).
        assertNull(ringBedtime(plan, alarm, 700, 30))
    }
}
