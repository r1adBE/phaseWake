package com.phasewake.app.wear.wake

import android.content.Context
import android.content.Intent
import android.os.Looper
import com.phasewake.app.core.Codec
import com.phasewake.app.core.FeelingAsk
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.Transient
import com.phasewake.app.core.WakePlan
import com.phasewake.app.core.WakeReason
import com.phasewake.app.shared.Outbox
import com.phasewake.app.shared.QueueMessages
import com.phasewake.app.wear.QuietWear
import com.phasewake.app.wear.schedule.AlarmReceiver
import com.phasewake.app.wear.wearApp
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.async
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
 * Befinden nach dem Wecken auf der Galaxy Watch ([FeelingPrompt], [FeelingAsk]): gefragt nur nach echtem Wecken und
 * Stopp auf der Uhr; die Wahl landet in derselben Nacht im Verlauf der Uhr und in der Warteschlange fürs Handy
 * (gleicher Schlüssel wie die Nacht), auch wenn sie vor dem Abschluss kommt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietWear::class)
class FeelingPromptTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val now = Instant.ofEpochMilli(System.currentTimeMillis())
    private val plan = WakePlan("a", now.minus(Duration.ofMinutes(1)), now.plus(Duration.ofMinutes(29)))
    private var controller: ServiceController<WakeService>? = null

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        FeelingPrompt.reset()
    }

    @After
    fun cleanUp() {
        controller?.destroy()
        context.wearApp.appScope.cancel()
        FeelingPrompt.reset()
    }

    /** Klingelt für [of] (Start, dann Zielzeit-Alarm), über den echten Weg. */
    private fun ringing(of: WakePlan = plan): WakeService {
        val start =
            AlarmReceiver.putPlan(Intent(context, WakeService::class.java).setAction(WakeService.ACTION_START), of)
        val built = Robolectric.buildService(WakeService::class.java, start)
        controller = built
        val service = built.create().startCommand(0, 1).get()
        val deadline = Intent(context, WakeService::class.java).setAction(WakeService.ACTION_DEADLINE)
        service.onStartCommand(AlarmReceiver.putPlan(deadline, of), 0, 2)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(WakeService.Phase.RINGING, service.night?.phase)
        return service
    }

    private fun send(service: WakeService, intent: Intent) {
        service.onStartCommand(intent, 0, 3)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitUntil(what: String, done: () -> Boolean) {
        val until = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (!done()) {
            check(System.nanoTime() < until) { what }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    private fun queuedNight(of: WakePlan = plan): NightRecord? {
        val key = QueueMessages.KEY_NIGHT + of.windowStart.toEpochMilli() + "-" + of.alarmId
        val entry = runBlocking { Outbox.forContext(context).pending() }.lastOrNull { it.key == key }
        return entry?.let { Codec.decodeNight(it.body) }
    }

    /** Stopp auf der Uhr: Der Stopp läuft ganz (Nacht weg, gespeichert, eingereiht), danach steht die Frage. */
    @Test
    fun stopOnWatchAsksAndTheChoiceTravelsWithTheNight() {
        val service = ringing()
        val before = System.currentTimeMillis()
        send(service, Intent(context, WakeService::class.java).setAction(WakeService.ACTION_DISMISS))
        assertNull(service.night, "Stopp unverändert")
        val ask = assertNotNull(FeelingPrompt.current(System.currentTimeMillis()))
        assertEquals("a", ask.alarmId)
        assertEquals(plan.windowStart, ask.windowStart)
        assertTrue(ask.askedAtMs >= before)
        awaitUntil("Abschluss nicht fertig") { service.finishing == 0 }
        assertNull(queuedNight()?.feeling, "zuerst die Nacht ohne Befinden")

        assertTrue(runBlocking { FeelingPrompt.choose(context.wearApp, ask, 4) })
        assertNull(FeelingPrompt.current(System.currentTimeMillis()), "beantwortet: geschlossen")
        val stored = runBlocking { context.wearApp.nights.load() }.single { it.windowStart == plan.windowStart }
        assertEquals(4, stored.feeling, "im Verlauf der Uhr")
        val queued = assertNotNull(queuedNight())
        assertEquals(4, queued.feeling, "dieselbe Nacht mit Befinden fürs Handy")
        assertEquals(WakeReason.DEADLINE, queued.reason)
    }

    /** Am Handy gestoppt (Kennung genannt): keine Frage, die Startseite des Handys fragt. */
    @Test
    fun stopFromPhoneDoesNotAsk() {
        val service = ringing()
        // Auf demselben Weg wie aus dem WatchListenerService: Befehl mit Kennung an den laufenden Dienst.
        val shadow = shadowOf(RuntimeEnvironment.getApplication())
        shadow.clearStartedServices()
        WakeService.command(context, WakeService.ACTION_DISMISS, remoteAlarmId = "a")
        send(service, assertNotNull(shadow.nextStartedService, "an den laufenden Dienst"))
        assertNull(service.night)
        assertNull(FeelingPrompt.current(System.currentTimeMillis()))
    }

    /** Zeitgrenze ohne Reaktion bzw. Zeitlimit der Nacht: niemand ist da, keine Frage. */
    @Test
    fun endWithoutUserDoesNotAsk() {
        val service = ringing()
        service.onHardLimit()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(service.night)
        assertNull(FeelingPrompt.current(System.currentTimeMillis()))
    }

    /** Probewecker: kein Eintrag im Verlauf, also keine Frage. */
    @Test
    fun testAlarmDoesNotAsk() {
        val test = WakePlan(Transient.TEST_PREFIX + "1", plan.windowStart, plan.target)
        val service = ringing(test)
        send(service, Intent(context, WakeService::class.java).setAction(WakeService.ACTION_DISMISS))
        assertNull(service.night)
        assertNull(FeelingPrompt.current(System.currentTimeMillis()))
    }

    /**
     * Eine Wahl vor dem Abschluss wartet, bis die Nacht gespeichert und eingereiht ist; sonst ersetzte dessen Fassung
     * ohne Befinden die Wahl. Ohne Nacht: nichts gespeichert.
     */
    @Test
    fun earlyChoiceWaitsForTheNight() = runBlocking {
        val app = context.wearApp
        FeelingPrompt.ask("a", plan.windowStart, System.currentTimeMillis())
        val ask = assertNotNull(FeelingPrompt.current(System.currentTimeMillis()))
        val choice = async { FeelingPrompt.choose(app, ask, 2, waitMs = 5_000) }
        val night = NightRecord("a", plan.windowStart, plan.target, now, WakeReason.LIGHT_SLEEP, 0, emptyList())
        app.nights.add(night)
        app.sync.publishNight(night)
        FeelingPrompt.nightQueued(night)
        assertTrue(choice.await())
        assertEquals(2, queuedNight()?.feeling)
        val other = FeelingPrompt.Ask("b", plan.windowStart, System.currentTimeMillis())
        assertFalse(FeelingPrompt.choose(app, other, 3, waitMs = 50), "ohne Nacht")
    }

    /** Die Frage gilt 60 s ab dem Stopp; eine neuere bleibt beim Schliessen der älteren offen. */
    @Test
    fun askExpiresAndCloseKeepsANewerOne() {
        val at = System.currentTimeMillis()
        FeelingPrompt.ask("a", plan.windowStart, at)
        val first = assertNotNull(FeelingPrompt.current(at))
        assertNull(FeelingPrompt.current(at + FeelingAsk.MILLIS))
        FeelingPrompt.ask("b", plan.windowStart, at + 1)
        FeelingPrompt.close(first)
        assertEquals("b", FeelingPrompt.current(at + 1)?.alarmId)
    }
}
