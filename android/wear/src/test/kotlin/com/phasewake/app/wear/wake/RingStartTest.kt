package com.phasewake.app.wear.wake

import android.content.Context
import android.content.Intent
import android.os.Looper
import com.phasewake.app.core.Epoch
import com.phasewake.app.core.WakePlan
import com.phasewake.app.wear.QuietWear
import com.phasewake.app.wear.schedule.AlarmReceiver
import com.phasewake.app.wear.system.StartLock
import com.phasewake.app.wear.wearApp
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.cancel
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
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Beginn des Klingelns auf der Uhr (Befund 13.11.2026): Der neue Wakelock steht, bevor der alte gelöst wird; zuerst
 * Wakelock, Vibration, Weck-Mitteilung und Weckbildschirm, Sensoren und Sonnenaufgang erst im nächsten Durchlauf.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietWear::class)
class RingStartTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var controller: ServiceController<WakeService>? = null
    private val now = Instant.ofEpochMilli(System.currentTimeMillis())
    private val plan = WakePlan("a", now.minus(Duration.ofMinutes(1)), now.plus(Duration.ofMinutes(29)))

    @Before
    fun setUp() {
        StartLock.resetForTest()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun cleanUp() {
        controller?.destroy()
        context.wearApp.appScope.cancel()
        StartLock.resetForTest()
    }

    /** Startet den Dienst für [plan] und wartet, bis die Sensoren laufen (Messen im Fenster). */
    private fun startNight(): WakeService {
        val intent = AlarmReceiver.putPlan(Intent(context, WakeService::class.java).setAction(WakeService.ACTION_START), plan)
        val built = Robolectric.buildService(WakeService::class.java, intent)
        controller = built
        val service = built.create().startCommand(0, 1).get()
        val until = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (service.night?.sensors == null) {
            check(System.nanoTime() < until) { "Nacht nicht geladen" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        // Die Tests liefern Epochen ab dem Start der Sensoren, nicht die leeren davor (E-1, `WakeServiceTest`).
        service.night?.unmeasured = 0
        return service
    }

    @Test
    fun swapAcquiresTheNewOneBeforeReleasingTheOld() {
        val calls = mutableListOf<String>()
        val next = swapHeld("alt", { calls += "neu nehmen"; "neu" }) { calls += "$it lösen" }
        assertEquals("neu", next)
        assertEquals(listOf("neu nehmen", "alt lösen"), calls)
        // Ohne bisherigen nur nehmen.
        calls.clear()
        val none: String? = null
        swapHeld(none, { calls += "neu nehmen"; "neu" }) { calls += "$it lösen" }
        assertEquals(listOf("neu nehmen"), calls)
    }

    @Test
    fun holdWakeLockKeepsOneHeldAndReleasesTheStartLock() {
        val service = startNight()
        val first = assertNotNull(service.wakeLock)
        assertTrue(first.isHeld)
        StartLock.acquire(context)
        service.holdWakeLock(60_000L)
        val second = assertNotNull(service.wakeLock)
        assertNotSame(first, second)
        assertTrue(second.isHeld, "neuer gehalten")
        assertFalse(first.isHeld, "alter gelöst")
        assertFalse(StartLock.held)
    }

    @Test
    fun ringAlertsFirstAndStopsSensorsAfterwards() {
        val service = startNight()
        val n = assertNotNull(service.night)
        // Wach: Klingeln aus der Epoche heraus, ohne den Hauptthread weiterlaufen zu lassen.
        service.onEpochs(n, listOf(Epoch(0.0, 60.0), Epoch(0.0, 60.0), Epoch(0.5, 60.0), Epoch(0.5, 60.0)))
        assertEquals(WakeService.Phase.RINGING, n.phase)
        val steps = assertNotNull(service.lastRingSteps)
        assertEquals(listOf("lock", "vib", "notif", "activity", "rest"), steps.names, "Sensoren noch nicht aus")
        assertTrue(service.wakeLock?.isHeld == true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("lock", "vib", "notif", "activity", "rest", "queue", "sensors"), steps.names)
        assertTrue(steps.line().startsWith("ring steps lock="), steps.line())
    }

    @Test
    fun ringStepsLine() {
        var clock = 100L
        val steps = RingSteps { clock }
        clock += 3
        steps.mark("lock")
        clock += 40
        steps.mark("notif")
        assertEquals("ring steps lock=3ms notif=40ms", steps.line())
    }
}
