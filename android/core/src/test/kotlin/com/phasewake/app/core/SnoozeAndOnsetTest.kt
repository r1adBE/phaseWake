package com.phasewake.app.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnoozeAndOnsetTest {

    @Test
    fun snoozeThreeTimes() {
        assertTrue(Snooze.canSnooze(0))
        assertTrue(Snooze.canSnooze(2))
        assertFalse(Snooze.canSnooze(3))
        assertEquals(Instant.parse("2026-10-28T06:05:00Z"), Snooze.nextRing(Instant.parse("2026-10-28T06:00:00Z")))
    }

    @Test
    fun onsetIsStartOfFirstStillRun() {
        val restless = List(10) { 0.05 }
        val shortRest = List(20) { 0.0 }
        val still = List(30) { 0.0 }
        assertEquals(31, SleepOnset.find(restless + shortRest + listOf<Double?>(null) + still))
    }

    @Test
    fun noOnsetWhileRestless() {
        assertNull(SleepOnset.find(List(29) { 0.0 }))
        assertNull(SleepOnset.find(List(60) { 0.002 })) // genau auf der Schwelle ist nicht ruhig
    }
}
