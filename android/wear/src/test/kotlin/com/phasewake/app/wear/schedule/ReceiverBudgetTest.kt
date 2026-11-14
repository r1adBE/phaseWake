package com.phasewake.app.wear.schedule

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Audit HG-6: Der Empfänger der Systemereignisse ([SystemEventReceiver]) hielt `goAsync` über alle Nebenarbeiten offen
 * (bis rund 33 s); jenseits der Frist des Systems droht ein ANR, der den Prozess samt Weckdienst beendet. Jetzt meldet
 * er fertig, sobald die Alarme stehen, spätestens nach 8 s ([launchWithin]); die Nebenarbeiten laufen danach weiter.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiverBudgetTest {

    @Test
    fun finishesWhenTheAlarmsAreSetAndSideWorkGoesOn() = runTest {
        val finished = mutableListOf<Long>()
        var sideDone = false
        backgroundScope.launchWithin(8_000L, { finished += testScheduler.currentTime }, first = { delay(1_000L) }) {
            delay(30_000L)
            sideDone = true
        }
        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(listOf(1_000L), finished, "fertig, sobald die Alarme stehen")
        advanceTimeBy(40_000L)
        runCurrent()
        assertEquals(true, sideDone, "Nebenarbeiten laufen weiter")
        assertEquals(1, finished.size, "genau einmal")
    }

    @Test
    fun finishesAfterTheBudgetWhenPlanningHangs() = runTest {
        val finished = mutableListOf<Long>()
        backgroundScope.launchWithin(8_000L, { finished += testScheduler.currentTime }, first = { awaitCancellation() }) {}
        advanceTimeBy(7_999L)
        runCurrent()
        assertEquals(emptyList(), finished)
        advanceTimeBy(2L)
        runCurrent()
        assertEquals(listOf(8_000L), finished, "spätestens nach der Gesamtfrist")
    }

    @Test
    fun finishesOnceWhenPlanningFails() = runTest {
        var finished = 0
        var sideRan = false
        // Wie der Bereich der App: Ein Fehler wird gemeldet, nicht weitergereicht.
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, _ -> })
        val job = scope.launchWithin(8_000L, { finished++ }, first = { error("Planen gescheitert") }) { sideRan = true }
        runCurrent()
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(1, finished)
        assertEquals(false, sideRan)
        assertEquals(true, job.isCancelled)
        scope.cancel()
    }
}
