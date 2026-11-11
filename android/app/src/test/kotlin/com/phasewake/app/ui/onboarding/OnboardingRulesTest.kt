package com.phasewake.app.ui.onboarding

import com.phasewake.app.ui.settings.HealthState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Audit N-1: «Weiter» auf der Seite «Erster Wecker» legte bei wiederholter Einrichtung jedes Mal einen weiteren Wecker
 * an. Jetzt nur, wenn noch keiner besteht und hier noch keiner angelegt wurde. Dazu das Bild je Schritt (Gestaltung 2).
 */
class OnboardingRulesTest {

    private fun model(hasAlarm: Boolean, canCreate: Boolean = true) = OnboardingModel(
        check = null,
        health = HealthState.UNAVAILABLE,
        canCreateAlarm = canCreate,
        hasAlarm = hasAlarm,
        testTarget = null,
        onFix = {},
        onConnectHealth = {},
        onCreateAlarm = {},
        onStartTest = {},
    )

    @Test
    fun firstSetupCreatesShownAlarm() = assertTrue(createsOnNext(createdHere = false, model(hasAlarm = false)))

    @Test
    fun repeatedSetupWithAlarmCreatesNothing() = assertFalse(createsOnNext(createdHere = false, model(hasAlarm = true)))

    @Test
    fun createdHereNotTwice() = assertFalse(createsOnNext(createdHere = true, model(hasAlarm = true)))

    @Test
    fun limitReachedCreatesNothing() = assertFalse(createsOnNext(createdHere = false, model(hasAlarm = false, canCreate = false)))

    @Test
    fun glyphMoonWandersRightWithEachStep() {
        val positions = (0..STEPS).map { glyphPosition(it, STEPS) }
        assertEquals(positions.sorted().distinct(), positions)
        assertEquals(0.1f, positions.first())
        assertEquals(0.9f, positions.last(), 1e-6f)
        assertEquals(0.5f, glyphPosition(3, 1))
        assertEquals(0.1f, glyphPosition(-2, STEPS))
    }
}
