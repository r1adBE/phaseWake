package com.phasewake.app.wear.record

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import com.phasewake.app.core.Epoch
import com.phasewake.app.shared.NightRecording
import com.phasewake.app.shared.RecordEnd
import com.phasewake.app.wear.QuietWear
import com.phasewake.app.wear.system.StartLock
import com.phasewake.app.wear.wake.SensorAccess
import com.phasewake.app.wear.wearApp
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Vordergrund-Typ «health» der Aufzeichnung (OFFEN 4a, r1AD 16.11.2026): [RecordControl] startet den Dienst ohne die
 * Rechte dafür gar nicht; verweigert Android «health» trotzdem, geht der Dienst nur kurz als «shortService» in den
 * Vordergrund und endet (kein «systemExempted», kein START_STICKY), eine laufende Aufzeichnung endet sauber.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietWear::class, shadows = [RecordHealthTest.HealthShadow::class])
class RecordHealthTest {

    /** Wie Android: «health» verweigert, solange [refuse]; merkt sich alle Typen von startForeground. */
    @Implements(Service::class)
    class HealthShadow : ShadowService() {
        @Implementation
        override fun startForeground(id: Int, notification: Notification, foregroundServiceType: Int) {
            if (refuse && foregroundServiceType == ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH) throw SecurityException("health refused (test)")
            types += foregroundServiceType
            super.startForeground(id, notification, foregroundServiceType)
        }

        companion object {
            @Volatile
            var refuse = false
            val types: MutableList<Int> = java.util.Collections.synchronizedList(mutableListOf())
        }
    }

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val app get() = context.wearApp
    private val shadowApp get() = shadowOf(RuntimeEnvironment.getApplication())
    private val controllers = mutableListOf<ServiceController<RecordService>>()

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        StartLock.resetForTest()
        HealthShadow.refuse = false
        HealthShadow.types.clear()
        shadowOf(context.getSystemService(BatteryManager::class.java)).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 80)
        shadowApp.clearStartedServices()
    }

    @After
    fun cleanUp() {
        controllers.forEach { it.destroy() }
        HealthShadow.refuse = false
        RecordService.clock = Instant::now
        StartLock.resetForTest()
        runBlocking { withTimeout(10_000) { app.appScope.coroutineContext.job.cancelAndJoin() } }
        File(context.filesDir, "recordings").deleteRecursively()
    }

    private fun grant(vararg permissions: String) = shadowOf(context as Application).grantPermissions(*permissions)

    private fun intent(action: String) = Intent(context, RecordService::class.java).setAction(action)

    /** Dienst ohne Auftrag erzeugen; Aufträge gibt der Test über `onStartCommand` (mit Rückgabewert). */
    private fun created(): RecordService {
        val built = Robolectric.buildService(RecordService::class.java, intent(RecordService.ACTION_START))
        controllers += built
        return built.create().get()
    }

    private fun awaitUntil(what: String, done: () -> Boolean) {
        val until = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (!done()) {
            check(System.nanoTime() < until) { what }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    private fun stored(): List<NightRecording> =
        File(context.filesDir, "recordings").listFiles().orEmpty().filter { !it.name.contains('.') }.mapNotNull { NightRecording.decode(it.readText()) }

    private fun logged(prefix: String) = app.diag.entries().any { it.detail.startsWith(prefix) }

    @Test
    fun withoutRightsNoServiceStarts() {
        assertTrue(RecordControl.startFromWatch(context), "kein Akku-Hinweis")
        StartLock.acquire(context)
        assertFalse(RecordControl.startService(context, RecordService.ACTION_START_LEARN))
        assertFalse(StartLock.held, "Start-Wakelock gelöst")
        assertNull(shadowApp.nextStartedService, "kein Dienststart")
        awaitUntil("Zeile im Protokoll") { logged("record not started (watch): no sensors: hr=no bg=no act=no") }
        awaitUntil("record not started (learn): no sensors:") { logged("record not started (learn): no sensors:") }
        // Puls nur «während der Nutzung»: Knopf auf der Uhr ja, aus dem Hintergrund (Handy, Lernphase) nein.
        grant(SensorAccess.heartRatePermission(Build.VERSION.SDK_INT))
        assertFalse(RecordControl.startService(context, RecordService.ACTION_START_PHONE))
        assertNull(shadowApp.nextStartedService)
        assertTrue(RecordControl.startFromWatch(context))
        assertEquals(RecordService.ACTION_START, assertNotNull(shadowApp.nextStartedService).action)
        grant(assertNotNull(SensorAccess.backgroundPermission(Build.VERSION.SDK_INT)))
        assertTrue(RecordControl.startService(context, RecordService.ACTION_START_PHONE))
        assertEquals(RecordService.ACTION_START_PHONE, assertNotNull(shadowApp.nextStartedService).action)
    }

    @Test
    fun healthRuleByVersion() {
        val none = SensorAccess(heartRate = false, background = false, activity = false, sdk = 35)
        assertFalse(none.allowsHealth(fromBackground = false))
        assertTrue(none.copy(sdk = 33).allowsHealth(fromBackground = true), "vor Android 14 ohne Prüfung des Typs")
        assertTrue(none.copy(activity = true).allowsHealth(fromBackground = true))
        assertTrue(none.copy(heartRate = true).allowsHealth(fromBackground = false))
        assertFalse(none.copy(heartRate = true).allowsHealth(fromBackground = true))
        assertTrue(none.copy(heartRate = true, background = true, sdk = 36).allowsHealth(fromBackground = true))
    }

    @Test
    fun resumeWithoutRightsFinishesTheSavedRecording() {
        val start = Instant.ofEpochMilli(System.currentTimeMillis()).minus(Duration.ofMinutes(5))
        runBlocking {
            app.recordings.begin(start)
            app.recordings.append(start, List(3) { Epoch(0.02, 58.0) })
        }
        RecordControl.resume(context)
        assertNull(shadowApp.nextStartedService)
        awaitUntil("abgeschlossen") { stored().singleOrNull()?.finished == true }
        assertEquals(RecordEnd.SERVICE, stored().single().endReason)
        awaitUntil("record resume refused: no sensors") { logged("record resume refused: no sensors") }
    }

    @Test
    fun healthRefusedAtStartUsesShortServiceAndEnds() {
        HealthShadow.refuse = true
        val service = created()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent(RecordService.ACTION_START), 0, 1))
        assertEquals(listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE), HealthShadow.types.toList())
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertNull(service.active)
        assertTrue(stored().isEmpty(), "keine Aufzeichnung angelegt")
        awaitUntil("Zeile im Protokoll") { logged("record not started (watch): no sensors: SecurityException") }
        // Läuft der Rückfall ab, endet der Dienst ohne Absturz.
        service.onTimeout(1)
    }

    @Test
    fun furtherStartWithHealthRefusedEndsTheRecording() {
        val service = created()
        assertEquals(Service.START_STICKY, service.onStartCommand(intent(RecordService.ACTION_START), 0, 1))
        awaitUntil("Aufzeichnung läuft") { service.active != null }
        service.onEpochs(listOf(Epoch(0.03, 61.0), Epoch(null, null)))
        HealthShadow.refuse = true
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent(RecordService.ACTION_START_PHONE), 0, 2))
        assertNull(service.active, "endet sofort, misst nicht unter dem Rückfall weiter")
        awaitUntil("abgeschlossen") { stored().singleOrNull()?.finished == true }
        val done = stored().single()
        assertEquals(RecordEnd.SERVICE, done.endReason)
        assertEquals(listOf(Epoch(0.03, 61.0), Epoch(null, null)), done.epochs)
        awaitUntil("record health refused: SecurityException") { logged("record health refused: SecurityException") }
        assertFalse(ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED in HealthShadow.types)
    }

    @Test
    fun normalCaseUnchanged() {
        val service = created()
        assertEquals(Service.START_STICKY, service.onStartCommand(intent(RecordService.ACTION_START), 0, 1))
        awaitUntil("Aufzeichnung läuft") { service.active != null }
        // Weiterer Start-Befehl mit «health»: läuft weiter.
        assertEquals(Service.START_STICKY, service.onStartCommand(intent(RecordService.ACTION_START_PHONE), 0, 2))
        assertNotNull(service.active)
        assertTrue(HealthShadow.types.all { it == ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH })
        assertNull(service.refusal)
        service.onStartCommand(intent(RecordService.ACTION_STOP).putExtra(RecordService.EXTRA_REASON, RecordEnd.WATCH.code), 0, 3)
        awaitUntil("abgeschlossen") { stored().singleOrNull()?.finished == true }
        assertEquals(RecordEnd.WATCH, stored().single().endReason)
    }
}
