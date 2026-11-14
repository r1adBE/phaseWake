package com.phasewake.app.wear.wake

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.WakePlan
import com.phasewake.app.wear.QuietWear
import com.phasewake.app.wear.schedule.AlarmReceiver
import com.phasewake.app.wear.system.StartLock
import com.phasewake.app.wear.wearApp
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Auto-Aus des Einmal-Weckers am Nachtende (Fix G-1): nur die Fassung, die geklingelt hat. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietWear::class)
class OneShotAutoOffTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val app get() = context.wearApp
    private var controller: ServiceController<WakeService>? = null
    private val now = Instant.ofEpochMilli(System.currentTimeMillis())
    private val zone = ZoneId.systemDefault()

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(context.getSystemService(BatteryManager::class.java)).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 80)
        StartLock.resetForTest()
    }

    @After
    fun cleanUp() {
        controller?.destroy()
        app.appScope.cancel()
        StartLock.resetForTest()
    }

    private fun intent(action: String, of: WakePlan? = null): Intent {
        val intent = Intent(context, WakeService::class.java).setAction(action)
        return if (of != null) AlarmReceiver.putPlan(intent, of) else intent
    }

    private fun send(service: WakeService, action: String, startId: Int, of: WakePlan? = null) {
        service.onStartCommand(intent(action, of), 0, startId)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitUntil(what: String, done: () -> Boolean) {
        val until = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (!done()) {
            check(System.nanoTime() < until) { what }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    private fun specAt(at: Instant): AlarmSpec {
        val local = at.atZone(zone)
        return AlarmSpec(id = "a", hour = local.hour, minute = local.minute)
    }

    /** Einmal-Wecker klingelt und schlummert; [during] ändert ihn im Schlummern; danach wird das Klingeln beendet. */
    private fun ringSnoozeDismiss(during: (target: Instant) -> Unit): AlarmSpec {
        val spec = specAt(now.plus(Duration.ofMinutes(29)))
        runBlocking { app.alarms.upsert(spec) }
        val target = assertNotNull(AlarmSchedule.nextTarget(spec, now, zone))
        val plan = AlarmSchedule.planAt(spec, target)
        val built = Robolectric.buildService(WakeService::class.java, intent(WakeService.ACTION_START, plan))
        controller = built
        val service = built.create().startCommand(0, 1).get()
        awaitUntil("Nacht nicht geladen") { service.night?.sensors != null }
        send(service, WakeService.ACTION_DEADLINE, 2, plan)
        assertEquals(WakeService.Phase.RINGING, service.night?.phase)
        send(service, WakeService.ACTION_SNOOZE, 3)
        assertEquals(WakeService.Phase.SNOOZED, service.night?.phase)
        during(target)
        send(service, WakeService.ACTION_DISMISS, 4)
        awaitUntil("Abschluss nicht fertig") { service.finishing == 0 }
        return runBlocking { app.alarms.current().alarms.single { it.id == "a" } }
    }

    @Test
    fun oneShotMovedDuringSnoozeStaysEnabled() {
        val stored = ringSnoozeDismiss { target -> runBlocking { app.alarms.upsert(specAt(target.plus(Duration.ofMinutes(30)))) } }
        assertTrue(stored.enabled, "der verschobene Einmal-Wecker bleibt eingeschaltet")
        assertNotNull(runBlocking { app.scheduler.nextPlan() }, "und ist für die neue Zeit geplant")
    }

    @Test
    fun unchangedOneShotIsSwitchedOff() {
        val stored = ringSnoozeDismiss { }
        assertFalse(stored.enabled)
    }

    @Test
    fun labelChangedDuringSnoozeStillSwitchesOff() {
        // Dieselbe Weckzeit: Das ist der Wecker, der geklingelt hat.
        val stored = ringSnoozeDismiss { runBlocking { app.alarms.update("a") { it.copy(label = "Zug") } } }
        assertFalse(stored.enabled)
        assertEquals("Zug", stored.label)
    }
}
