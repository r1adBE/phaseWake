package com.phasewake.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompanionPickTest {
    private val buds = PairedDevice("00:00:00:00:00:01", "Galaxy Buds3 Pro", wearable = false)
    private val watch = PairedDevice("00:00:00:00:00:02", "Galaxy Watch8 Classic (1A2B)", wearable = true)
    private val band = PairedDevice("00:00:00:00:00:03", "Fit Band", wearable = true)
    private val car = PairedDevice("00:00:00:00:00:04", null, wearable = false)

    @Test
    fun sameNameAsConnectedWatchWins() {
        assertEquals(watch, CompanionPick.choose(listOf(buds, band, watch), listOf(" galaxy watch8 classic (1a2b) ")))
    }

    @Test
    fun partOfTheNameCountsWhenNoneIsEqual() {
        assertEquals(watch, CompanionPick.choose(listOf(buds, band, watch, car), listOf("Galaxy Watch8 Classic")))
        val exact = PairedDevice("00:00:00:00:00:05", "Galaxy Watch8 Classic", wearable = true)
        assertEquals(exact, CompanionPick.choose(listOf(watch, exact), listOf("Galaxy Watch8 Classic")))
    }

    @Test
    fun singleWearableWithoutNameMatch() {
        assertEquals(watch, CompanionPick.choose(listOf(buds, watch, car), emptyList()))
        assertEquals(watch, CompanionPick.choose(listOf(buds, watch), listOf("Pixel Watch 3", "")))
    }

    @Test
    fun noneOrSeveralWearablesLeaveTheChoiceToTheUser() {
        assertNull(CompanionPick.choose(emptyList(), listOf("Galaxy Watch8 Classic (1A2B)")))
        assertNull(CompanionPick.choose(listOf(buds, car), emptyList()))
        assertNull(CompanionPick.choose(listOf(watch, band), emptyList()))
        assertNull(CompanionPick.choose(listOf(PairedDevice("00:00:00:00:00:06", " ", wearable = false)), listOf(" ")))
    }
}
