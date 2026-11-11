package com.phasewake.app.shared

import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.TimeBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Fix KN-4: unplausible Zeitpunkte ([TimeBounds]) im Abgleich der Wecker, wie `AlarmStampsTests` (Swift). */
class AlarmStampsBoundsTest {
    private val alarm = AlarmSpec(id = "a", hour = 7, minute = 0)

    @Test
    fun implausibleListStampDropsTheMessage() {
        assertNull(AlarmStamps.checked(AlarmSnapshot(listOf(alarm), Long.MAX_VALUE)))
        assertNull(AlarmStamps.checked(AlarmSnapshot(listOf(alarm), -1L)))
        val read = AlarmStamps.checked(
            AlarmSnapshot(listOf(alarm), 9L, stamps = mapOf("a" to Long.MAX_VALUE), deleted = mapOf("z" to Long.MIN_VALUE, "y" to 4L)),
        )!!
        assertEquals(emptyMap(), read.stamps)
        assertEquals(9L, read.stampOf("a"))
        assertEquals(mapOf("y" to 4L), read.deleted)
    }

    @Test
    fun storedMapsAndPruneSkipImplausibleTimes() {
        assertEquals(mapOf("y" to 4L), AlarmStamps.decodeMap("a=${Long.MAX_VALUE}\nz=${Long.MIN_VALUE}\ny=4"))
        assertEquals(emptyMap(), AlarmStamps.prune(mapOf("z" to Long.MIN_VALUE, "x" to Long.MAX_VALUE), now = 10L))
        assertEquals(mapOf("y" to 4L), AlarmStamps.prune(mapOf("z" to Long.MIN_VALUE, "y" to 4L), now = 10L))
    }

    @Test
    fun nextStampIsSaturating() {
        assertEquals(5L, TimeBounds.next(5L, Long.MAX_VALUE))
        assertEquals(10L, TimeBounds.next(5L, 9L))
        assertEquals(5L, TimeBounds.next(5L, null))
    }
}
