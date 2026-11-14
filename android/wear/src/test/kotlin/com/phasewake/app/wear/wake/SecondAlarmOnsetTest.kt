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
import com.phasewake.app.wear.schedule.AutoOnset
import com.phasewake.app.wear.system.StartLock
import com.phasewake.app.wear.wearApp
import java.time.DayOfWeek
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Audit G-2: Zwei Wecker am selben Morgen. Nach dem Wecken des ersten beginnt für den zweiten keine automatische
 * Einschlaf-Messung (sie fände kein Einschlafen und sperrte danach das frühe Wecken, fail-closed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietWear::class)
class SecondAlarmOnsetTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val app get() = context.wearApp
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private var controller: ServiceController<WakeService>? = null
    private val now = Instant.ofEpochMilli(System.currentTimeMillis())
    private val zone = ZoneId.systemDefault()
    private val everyDay = DayOfWeek.entries.toSet()

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

    private fun specAt(id: String, at: Instant): AlarmSpec {
        val local = at.atZone(zone)
        return AlarmSpec(id = id, hour = local.hour, minute = local.minute, days = everyDay)
    }

    /** Gestellte Alarme der automatischen Einschlaf-Messung, als Kennung des Weckers. */
    @Suppress("DEPRECATION")
    private fun autoOnsetFor(): List<String?> = alarms.scheduledAlarms
        .filter { a -> a.operation?.let { shadowOf(it).savedIntent.action } == AutoOnset.ACTION }
        .map { AutoOnset.alarmIdFrom(shadowOf(it.operation).savedIntent) }

    @Test
    fun noAutoOnsetForSecondAlarmAfterFirstWoke() {
        val a = specAt("a", now.plus(Duration.ofMinutes(2)))
        val b = specAt("b", now.plus(Duration.ofMinutes(90)))
        runBlocking { app.alarms.upsert(a); app.alarms.upsert(b) }
        val target = assertNotNull(AlarmSchedule.nextTarget(a, now, zone))
        val plan = AlarmSchedule.planAt(a, target)
        // Nacht von «a» klingelt und wird beendet.
        val built = Robolectric.buildService(WakeService::class.java, intent(WakeService.ACTION_START, plan))
        controller = built
        val service = built.create().startCommand(0, 1).get()
        awaitUntil("Nacht nicht geladen") { service.night?.sensors != null }
        send(service, WakeService.ACTION_DEADLINE, 2, plan)
        assertEquals(WakeService.Phase.RINGING, service.night?.phase)
        send(service, WakeService.ACTION_DISMISS, 3)
        awaitUntil("Abschluss nicht fertig") { service.finishing == 0 }
        assertEquals("b", runBlocking { app.scheduler.nextAlarmPlan() }?.alarmId)
        assertTrue(autoOnsetFor().isEmpty(), "Einschlaf-Messung für den zweiten Wecker gleich nach dem ersten: ${autoOnsetFor()}")
    }

    @Test
    fun autoOnsetStillPlannedWithoutAnEndedNight() {
        // Gegenprobe: Bettzeit vorbei, aber keine Nacht hat seither geweckt → Messung beginnt gleich.
        runBlocking { app.alarms.upsert(specAt("b", now.plus(Duration.ofMinutes(90)))) }
        runBlocking { app.scheduler.reschedule() }
        assertEquals(listOf<String?>("b"), autoOnsetFor())
    }
}
