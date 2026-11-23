package com.phasewake.app.wear.sync

import android.content.Context
import android.content.Intent
import android.os.Looper
import com.google.android.gms.wearable.MessageEvent
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.Epoch
import com.phasewake.app.core.WakePlan
import com.phasewake.app.core.WakeReason
import com.phasewake.app.shared.DataLayer
import com.phasewake.app.shared.RingMessage
import com.phasewake.app.wear.QuietWear
import com.phasewake.app.wear.schedule.AlarmReceiver
import com.phasewake.app.wear.wake.WakeService
import com.phasewake.app.wear.wake.onEpochs
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * «stop» vom Handy, während die Uhr noch misst, auf dem echten Weg: Nachricht an den
 * [WatchListenerService], Befehl an den laufenden [WakeService]. Es beendet die Nacht nur, wenn es genau sie meint
 * (Wecker und Zielzeit); dann gilt sie als erledigt und wird nicht gleich wieder geplant. Sonst läuft die Messung
 * weiter.
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
        val intent = AlarmReceiver.putPlan(
            Intent(context, WakeService::class.java).setAction(WakeService.ACTION_START),
            plan,
        )
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
        assertFalse(
            stopFromPhone(service, "a", plan.target.minusSeconds(86_400).toEpochMilli()),
            "andere Zielzeit: kein Befehl",
        )
        assertEquals(WakeService.Phase.MEASURING, service.night?.phase)
        assertEquals(plan, WakeService.measuringPlan)
    }

    @Test
    fun matchingStopEndsTheNightAndMarksItDone() {
        val service = measuring()
        val n = assertNotNull(service.night)
        val measured = listOf(Epoch(0.0, 60.0), Epoch(0.01, null))
        service.onEpochs(n, measured)
        val before = Instant.now()
        assertTrue(stopFromPhone(service, "a", plan.target.toEpochMilli()))
        assertNull(service.night)
        // Erledigt bis zur Zielzeit, sonst begänne das Neuplanen dieselbe Nacht gleich wieder.
        awaitUntil("Nacht nicht als erledigt gespeichert") {
            runBlocking { context.wearApp.scheduler.doneUntil()["a"] } != null
        }
        assertEquals(plan.target, runBlocking { context.wearApp.scheduler.doneUntil()["a"] })
        // Wie die Apple Watch: die gemessene Nacht mit ihren Epochen im Verlauf, geweckt jetzt, Grund Zielzeit.
        fun stored() = runBlocking { context.wearApp.nights.load() }.lastOrNull { it.windowStart == plan.windowStart }
        awaitUntil("Nacht nicht im Verlauf") { stored() != null }
        val night = assertNotNull(stored())
        assertEquals(plan.target, night.target)
        assertEquals(WakeReason.DEADLINE, night.reason)
        assertEquals(measured, night.epochs.takeLast(measured.size))
        assertTrue(!night.wokeAt.isBefore(before) && !night.wokeAt.isAfter(Instant.now()), "Weckzeit beim Stopp")
        awaitUntil("Zeile zum Messende fehlt") {
            context.wearApp.diag.entries().any {
                it.kind == DiagKind.MEASURE_END && it.detail.endsWith(", stopped on phone")
            }
        }
    }

    /** Klingelt die Uhr (Zielzeit erreicht), über den echten Weg. */
    private fun ringing(): WakeService {
        val service = measuring()
        val deadline = Intent(context, WakeService::class.java).setAction(WakeService.ACTION_DEADLINE)
        service.onStartCommand(AlarmReceiver.putPlan(deadline, plan), 0, 3)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(WakeService.Phase.RINGING, service.night?.phase)
        return service
    }

    /** «schlummern» vom Handy mit Inhalt [data]; der Befehl geht an den laufenden [service]. */
    private fun snoozeFromPhone(service: WakeService, data: ByteArray) {
        val shadow = shadowOf(RuntimeEnvironment.getApplication())
        shadow.clearStartedServices()
        listener.onMessageReceived(Event(DataLayer.PATH_SNOOZE, data))
        service.onStartCommand(assertNotNull(shadow.nextStartedService, "an den laufenden Dienst"), 0, 4)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * Klingelt die Uhr, gilt «stop» vom Handy nur für dieselbe Nacht: gleiche Kennung und Zielzeit (auf 1 s, wie die
     * Apple Watch). Eine andere Zielzeit wird mit einer Zeile im Protokoll verworfen; ohne Zielzeit nur die Kennung.
     */
    @Test
    fun stopWhileRingingMustMatchTheTarget() {
        val service = ringing()
        assertTrue(stopFromPhone(service, "a", plan.target.minusSeconds(86_400).toEpochMilli()))
        assertEquals(WakeService.Phase.RINGING, service.night?.phase, "andere Zielzeit: klingelt weiter")
        awaitUntil("Zeile fehlt") {
            context.wearApp.diag.entries().any {
                it.kind == DiagKind.SUPPRESSED && it.detail == "phone stop for other night"
            }
        }
        assertTrue(stopFromPhone(service, "a", plan.target.toEpochMilli() + 900))
        assertNull(service.night, "dieselbe Nacht (unter 1 s): beendet")
    }

    @Test
    fun stopWhileRingingWithoutTargetChecksOnlyTheAlarm() {
        val service = ringing()
        assertTrue(stopFromPhone(service, "a", null))
        assertNull(service.night)
    }

    /**
     * «schlummern» vom Handy mit Kennung, Zielzeit und Sendezeit (wie «stop», seit 23.11.2026): nur für dieselbe Nacht;
     * ohne Inhalt (ältere Handy-App) wie bisher.
     */
    @Test
    fun snoozeFromPhoneChecksTheTargetWhenSent() {
        val service = ringing()
        val sent = System.currentTimeMillis()
        snoozeFromPhone(service, RingMessage("a", plan.target.plusSeconds(60).toEpochMilli(), sent).encode())
        assertEquals(WakeService.Phase.RINGING, service.night?.phase, "andere Zielzeit")
        snoozeFromPhone(service, RingMessage("b", plan.target.toEpochMilli(), sent).encode())
        assertEquals(WakeService.Phase.RINGING, service.night?.phase, "anderer Wecker")
        snoozeFromPhone(service, RingMessage("a", plan.target.toEpochMilli(), sent).encode())
        assertEquals(WakeService.Phase.SNOOZED, service.night?.phase)
    }

    @Test
    fun snoozeFromPhoneWithoutContentSnoozes() {
        val service = ringing()
        snoozeFromPhone(service, ByteArray(0))
        assertEquals(WakeService.Phase.SNOOZED, service.night?.phase)
    }

    @Test
    fun stopWhileMeasuringMustMatchTheNight() {
        val measuring = plan
        val target = plan.target
        assertFalse(
            WatchListenerService.ignoresStopWhileMeasuring(measuring, "a", target.toEpochMilli()),
            "genau diese Nacht",
        )
        assertTrue(
            WatchListenerService.ignoresStopWhileMeasuring(measuring, "a", target.minusSeconds(86_400).toEpochMilli()),
            "andere Zielzeit",
        )
        assertTrue(WatchListenerService.ignoresStopWhileMeasuring(measuring, "a", null), "ohne Zielzeit")
        assertTrue(
            WatchListenerService.ignoresStopWhileMeasuring(measuring, null, target.toEpochMilli()),
            "ohne Kennung",
        )
        assertTrue(
            WatchListenerService.ignoresStopWhileMeasuring(measuring, "b", target.toEpochMilli()),
            "anderer Wecker",
        )
        assertFalse(
            WatchListenerService.ignoresStopWhileMeasuring(null, "a", null),
            "ohne Messung entscheidet der Weckdienst",
        )
    }
}
