package com.phasewake.app.wear.sync

import android.content.Context
import android.content.Intent
import android.os.Looper
import com.google.android.gms.wearable.MessageEvent
import com.phasewake.app.core.WakePlan
import com.phasewake.app.shared.DataLayer
import com.phasewake.app.shared.RingMessage
import com.phasewake.app.wear.QuietWear
import com.phasewake.app.wear.schedule.AlarmReceiver
import com.phasewake.app.wear.wake.WakeService
import com.phasewake.app.wear.wearApp
import java.time.Duration
import java.time.Instant
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * «stop» vom Handy, während die Uhr noch misst (Audit A-7, AN-7, AN-8), auf dem echten Weg: Nachricht an den
 * [WatchListenerService], Befehl an den laufenden [WakeService]. Es beendet die Nacht nur, wenn es genau sie meint
 * (Wecker und Zielzeit); dann gilt sie als erledigt und wird nicht gleich wieder geplant. Sonst läuft die Messung weiter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietWear::class)
class WatchStopTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val now = Instant.ofEpochMilli(System.currentTimeMillis())
    private val plan = WakePlan("a", now.minus(Duration.ofMinutes(1)), now.plus(Duration.ofMinutes(29)))
    private val listener by lazy { Robolectric.buildService(WatchListenerService::class.java).get() }
    private var controller: ServiceController<WakeService>? = null

    /** Nachricht des Handys unter [path] mit Inhalt [data]. */
    private class Event(private val path: String, private val data: ByteArray) : MessageEvent {
        override fun getRequestId(): Int = 0
        override fun getPath(): String = path
        override fun getData(): ByteArray = data
        override fun getSourceNodeId(): String = "phone"
    }

    @Before
    fun exactAlarms() = ShadowAlarmManager.setCanScheduleExactAlarms(true)

    @After
    fun cleanUp() {
        controller?.destroy()
        context.wearApp.appScope.cancel()
    }

    /** Startet den Weckdienst für [plan] (Messen im Fenster). */
    private fun measuring(): WakeService {
        val intent = AlarmReceiver.putPlan(Intent(context, WakeService::class.java).setAction(WakeService.ACTION_START), plan)
        val built = Robolectric.buildService(WakeService::class.java, intent)
        controller = built
        return built.create().startCommand(0, 1).get().also { assertEquals(plan, WakeService.measuringPlan) }
    }

    /** «stop» vom Handy für [alarmId] und [target]; ein dabei gestarteter Befehl geht an den laufenden [service]. */
    private fun stopFromPhone(service: WakeService, alarmId: String, target: Long?): Boolean {
        val shadow = shadowOf(RuntimeEnvironment.getApplication())
        shadow.clearStartedServices()
        val sent = System.currentTimeMillis()
        listener.onMessageReceived(Event(DataLayer.PATH_STOP, RingMessage(alarmId, target, sent).encode()))
        val command = shadow.nextStartedService ?: return false
        service.onStartCommand(command, 0, 2)
        shadowOf(Looper.getMainLooper()).idle()
        return true
    }

    private fun awaitUntil(what: String, done: () -> Boolean) {
        val until = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (!done()) {
            check(System.nanoTime() < until) { what }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    @Test
    fun stopForAnotherTargetKeepsMeasuring() {
        val service = measuring()
        assertFalse(stopFromPhone(service, "a", plan.target.minusSeconds(86_400).toEpochMilli()), "andere Zielzeit: kein Befehl")
        assertEquals(WakeService.Phase.MEASURING, service.night?.phase)
        assertEquals(plan, WakeService.measuringPlan)
    }

    @Test
    fun matchingStopEndsTheNightAndMarksItDone() {
        val service = measuring()
        assertTrue(stopFromPhone(service, "a", plan.target.toEpochMilli()))
        assertNull(service.night)
        // Audit AN-7: erledigt bis zur Zielzeit, sonst begänne das Neuplanen dieselbe Nacht gleich wieder.
        awaitUntil("Nacht nicht als erledigt gespeichert") { runBlocking { context.wearApp.scheduler.doneUntil()["a"] } != null }
        assertEquals(plan.target, runBlocking { context.wearApp.scheduler.doneUntil()["a"] })
    }

    @Test
    fun stopWhileMeasuringMustMatchTheNight() {
        val measuring = plan
        val target = plan.target
        assertFalse(WatchListenerService.ignoresStopWhileMeasuring(measuring, "a", target.toEpochMilli()), "genau diese Nacht")
        assertTrue(WatchListenerService.ignoresStopWhileMeasuring(measuring, "a", target.minusSeconds(86_400).toEpochMilli()), "andere Zielzeit")
        assertTrue(WatchListenerService.ignoresStopWhileMeasuring(measuring, "a", null), "ohne Zielzeit")
        assertTrue(WatchListenerService.ignoresStopWhileMeasuring(measuring, null, target.toEpochMilli()), "ohne Kennung")
        assertTrue(WatchListenerService.ignoresStopWhileMeasuring(measuring, "b", target.toEpochMilli()), "anderer Wecker")
        assertFalse(WatchListenerService.ignoresStopWhileMeasuring(null, "a", null), "ohne Messung entscheidet der Weckdienst")
    }
}
