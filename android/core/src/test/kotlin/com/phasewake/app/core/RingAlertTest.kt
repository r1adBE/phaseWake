package com.phasewake.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RingAlertTest {
    @Test
    fun visibleAppOpensDirectly() {
        assertEquals(RingRoute.DIRECT, RingAlert.route(appVisible = true, interactive = true, locked = false, fullScreenAllowed = false))
        assertEquals(RingRoute.DIRECT, RingAlert.route(appVisible = true, interactive = false, locked = true, fullScreenAllowed = true))
    }

    @Test
    fun darkOrLockedScreenGetsFullScreen() {
        assertEquals(RingRoute.FULL_SCREEN, RingAlert.route(appVisible = false, interactive = false, locked = false, fullScreenAllowed = true))
        assertEquals(RingRoute.FULL_SCREEN, RingAlert.route(appVisible = false, interactive = true, locked = true, fullScreenAllowed = true))
    }

    @Test
    fun deviceInUseOrNoPermissionGetsHeadsUp() {
        assertEquals(RingRoute.HEADS_UP, RingAlert.route(appVisible = false, interactive = true, locked = false, fullScreenAllowed = true))
        assertEquals(RingRoute.HEADS_UP, RingAlert.route(appVisible = false, interactive = false, locked = true, fullScreenAllowed = false))
    }

    @Test
    fun everyAlertGetsAFreshId() {
        assertEquals(3, RingAlert.nextId(null, 3, 4))
        assertEquals(3, RingAlert.nextId(1, 3, 4))
        assertEquals(4, RingAlert.nextId(3, 3, 4))
        assertEquals(3, RingAlert.nextId(4, 3, 4))
        var id: Int? = 1
        repeat(5) {
            val next = RingAlert.nextId(id, 3, 4)
            assertNotEquals(id, next)
            id = next
        }
        assertEquals(20, RingAlert.REALERT_SECONDS)
    }
}
