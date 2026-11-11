package com.phasewake.app.wear.schedule

import android.app.AlarmManager
import android.content.Context
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.shared.phaseWakeStore
import com.phasewake.app.wear.QuietWear
import com.phasewake.app.wear.wearApp
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Neuplanen der Uhr (Audit A-3): Die System-Alarme stehen, bevor Nebenarbeiten mit den Wear-Diensten laufen. Hängt oder
 * scheitert der Status ans Handy, sind Fensterbeginn und Zielzeit trotzdem gestellt, und [AlarmScheduler.reschedule]
 * kehrt nach der Frist zurück.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietWear::class)
class SchedulerSideWorkTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))

    private companion object {
        const val ID = "side-work"
    }

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        DirectBoot.clear(context)
        runBlocking {
            context.wearApp.transient.clear()
            context.wearApp.onsetOverride.clear()
        }
    }

    @After
    fun cleanUp() {
        context.wearApp.appScope.cancel()
        runBlocking { context.wearApp.alarms.remove(ID) }
    }

    private fun scheduler(publish: suspend () -> Unit): AlarmScheduler {
        val app = context.wearApp
        return AlarmScheduler(context, app.alarms, app.onsetOverride, app.phaseWakeStore, app.sync, app.holidays, app.transient, statusPublisher = publish)
    }

    /** Wecker in gut drei Stunden; sein Plan. */
    private fun alarm() = runBlocking {
        val zone = ZoneId.systemDefault()
        val at = Instant.now().plus(Duration.ofHours(3)).atZone(zone)
        val spec = AlarmSpec(id = ID, hour = at.hour, minute = at.minute)
        context.wearApp.alarms.upsert(spec)
        assertNotNull(AlarmSchedule.nextPlan(spec, Instant.now(), zone))
    }

    private fun assertAlarmsSet(plan: com.phasewake.app.core.WakePlan) {
        val triggers = alarms.scheduledAlarms.map { it.triggerAtMs }
        assertTrue(plan.target.toEpochMilli() in triggers, "Zielzeit gestellt: $triggers")
        assertTrue(plan.windowStart.toEpochMilli() in triggers, "Fensterbeginn gestellt: $triggers")
    }

    @Test
    fun hangingStatusDoesNotBlockTheAlarms() {
        val plan = alarm()
        // Wear-Dienste antworten nie: Früher hing das Neuplanen vor dem Stellen der Alarme.
        val result = runBlocking { withTimeout(30_000L) { scheduler { awaitCancellation() }.reschedule() } }
        assertEquals(plan, result)
        assertAlarmsSet(plan)
    }

    @Test
    fun alarmsAreSetBeforeSideWorkStarts() {
        // Audit AN-8: Reihenfolge festhalten – beim Start der ersten Nebenarbeit mit den Wear-Diensten stehen die Alarme.
        val plan = alarm()
        var atSideWork: List<Long>? = null
        runBlocking { withTimeout(30_000L) { scheduler { atSideWork = alarms.scheduledAlarms.map { it.triggerAtMs } }.reschedule() } }
        val seen = assertNotNull(atSideWork, "Nebenarbeit lief")
        assertTrue(plan.target.toEpochMilli() in seen, "Zielzeit vor der Nebenarbeit gestellt: $seen")
        assertTrue(plan.windowStart.toEpochMilli() in seen, "Fensterbeginn vor der Nebenarbeit gestellt: $seen")
    }

    @Test
    fun failingStatusDoesNotBlockTheAlarms() {
        val plan = alarm()
        val result = runBlocking { withTimeout(30_000L) { scheduler { error("Wear-Dienste fehlen") }.reschedule() } }
        assertEquals(plan, result)
        assertAlarmsSet(plan)
    }
}
