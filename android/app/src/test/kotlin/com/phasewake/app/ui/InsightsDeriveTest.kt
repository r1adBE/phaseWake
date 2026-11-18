package com.phasewake.app.ui

import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Auswertungen: abbrechbar zwischen den Teilschritten, und ein schon gerechnetes Ergebnis wird übernommen. */
class InsightsDeriveTest {
    private fun inputs(goal: Int = 480) = InsightInputs(
        nights = emptyList(), sessions = emptyList(), clearedAt = null, healthFeelings = emptyMap(),
        notes = emptyMap(), sleepGoal = goal, alarms = emptyList(), routineItems = emptyList(),
        routineTicks = emptyMap(), latency = null, zone = ZoneId.of("Europe/Zurich"),
    )

    @Test
    fun checkpointRunsBetweenTheSteps() {
        var calls = 0
        val result = derive(inputs()) { calls++ }
        assertEquals(4, calls)
        assertTrue(result.history.isEmpty())
    }

    @Test
    fun cancelledCalculationStopsAtTheNextStep() {
        var calls = 0
        assertFailsWith<CancellationException> {
            derive(inputs()) {
                calls++
                throw CancellationException("überholt")
            }
        }
        // Nach dem ersten Teilschritt ist Schluss: Trends, Notizen und Diagramme werden nicht mehr gerechnet.
        assertEquals(1, calls)
    }

    @Test
    fun eagerResultIsTakenOnlyForTheSameInputs() {
        val eager = EagerDerived()
        assertNull(eager.takeFor(inputs()))
        val first = derive(inputs())
        eager.last = first
        assertSame(first, eager.takeFor(inputs()))
        assertNull(eager.takeFor(inputs()), "nur einmal übernommen")
        eager.last = first
        assertNull(eager.takeFor(inputs(goal = 420)))
    }

    @Test
    fun changeAndBackComputesTheCurrentState() {
        // Schlafziel hin und zurück: Für das Zurück gilt nicht mehr das Ergebnis vom Start (Uhrzeit von damals).
        val eager = EagerDerived()
        eager.last = derive(inputs())
        assertNull(eager.takeFor(inputs(goal = 420)))
        assertNull(eager.takeFor(inputs()))
    }
}
