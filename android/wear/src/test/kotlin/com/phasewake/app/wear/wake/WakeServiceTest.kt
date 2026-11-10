package com.phasewake.app.wear.wake

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Epoch
import com.phasewake.app.core.Snooze
import com.phasewake.app.core.WakePlan
import com.phasewake.app.core.WakeReason
import com.phasewake.app.shared.OnsetShift
import com.phasewake.app.wear.QuietWear
import com.phasewake.app.wear.schedule.AlarmReceiver
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ablauf im [WakeService] der Uhr (Review K-10): Start für einen Plan → Messen im Fenster mit Zielzeit-Alarm; Klingeln
 * → Wiederaufnahme-Alarm; Schlummern → Schlummer-Ende ersetzt ihn; Beenden → beide Alarme weg. Die Zeiten kommen aus
 * der Systemuhr: geprüft wird gegen die Grenzen vor und nach jedem Schritt, damit der Test nie vom Takt abhängt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietWear::class)
class WakeServiceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private var controller: ServiceController<WakeService>? = null

    /** Auf Millisekunden gerundet wie im Intent. */
    private val now = Instant.ofEpochMilli(System.currentTimeMillis())
    private val plan = WakePlan("a", now.minus(Duration.ofMinutes(1)), now.plus(Duration.ofMinutes(29)))

    @Before
    fun exactAlarms() = ShadowAlarmManager.setCanScheduleExactAlarms(true)

    @After
    fun cleanUp() {
        // Zustand liegt auch im Begleitobjekt: beenden, damit der nächste Test still beginnt.
        controller?.destroy()
        // Abschluss im Hintergrund (Speichern, Neuplanen) nicht in den nächsten Test hineinlaufen lassen.
        context.wearApp.appScope.cancel()
        // Der DataStore ist einer pro Prozess und lebt über den Test hinaus: Wecker, Ausnahme und Einschlafzeit leeren.
        runBlocking {
            context.wearApp.alarms.remove("a")
            context.wearApp.onsetOverride.clear()
            context.wearApp.onsets.set("a", Instant.EPOCH, null)
        }
    }

    private fun intent(action: String, withPlan: Boolean = false, of: WakePlan = plan): Intent {
        val intent = Intent(context, WakeService::class.java).setAction(action)
        return if (withPlan) AlarmReceiver.putPlan(intent, of) else intent
    }

    /** Ein weiterer Auftrag an den laufenden Dienst, wie vom System zugestellt. */
    private fun send(service: WakeService, action: String, startId: Int, withPlan: Boolean = false, of: WakePlan = plan) {
        service.onStartCommand(intent(action, withPlan, of), 0, startId)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Startet den Dienst für [of] und wartet, bis die Nacht geladen ist (Messen im Fenster). */
    private fun startNight(of: WakePlan = plan): WakeService {
        val built = Robolectric.buildService(WakeService::class.java, intent(WakeService.ACTION_START, withPlan = true, of = of))
        controller = built
        val service = built.create().startCommand(0, 1).get()
        awaitLoaded(service)
        return service
    }

    /**
     * Befehl vom Handy auf demselben Weg wie aus [com.phasewake.app.wear.sync.WatchListenerService]:
     * [WakeService.command] mit der Kennung aus der Nachricht, zugestellt an den laufenden Dienst.
     */
    private fun fromPhone(service: WakeService, action: String, alarmId: String?, startId: Int) {
        val app = shadowOf(RuntimeEnvironment.getApplication())
        app.clearStartedServices()
        WakeService.command(context, action, remoteAlarmId = alarmId)
        service.onStartCommand(assertNotNull(app.nextStartedService, "an den laufenden Dienst"), 0, startId)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Wartet auf Arbeit im Hintergrund (Speicher, Neuplanen), bis [done] gilt. */
    private fun awaitUntil(what: String, done: () -> Boolean) {
        val until = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (!done()) {
            check(System.nanoTime() < until) { what }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    /** Zeitpunkte aller gestellten Alarme (Robolectric 4.14 nennt den PendingIntent nur über ein veraltetes Feld). */
    private fun triggers(): List<Long> = alarms.scheduledAlarms.map { it.triggerAtMs }

    /** Gestellte Alarme zwischen [from] und [to] (beide eingeschlossen). */
    private fun between(from: Instant, to: Instant): List<Long> = triggers().filter { it in from.toEpochMilli()..to.toEpochMilli() }

    /**
     * Wartet, bis die Nacht Wecker und Schwellen geladen hat (Speicher im Hintergrund, danach Hauptthread): Erst dann
     * starten die Sensoren. Nicht am Wechsel des Detektors erkennbar, denn `startCommand` lässt den Hauptthread schon
     * laufen, und das Laden kann vor dem ersten Blick fertig sein (so schlug der Test sonst gelegentlich fehl).
     */
    private fun awaitLoaded(service: WakeService) = awaitUntil("Nacht nicht geladen") { service.night?.sensors != null }

    @Test
    fun startRingSnoozeStop() {
        // Start für den Plan: Messen im Fenster, Zielzeit-Alarm steht, noch kein Schlummer-Ende.
        val built = Robolectric.buildService(WakeService::class.java, intent(WakeService.ACTION_START, withPlan = true))
        controller = built
        val service = built.create().startCommand(0, 1).get()
        val measuring = assertNotNull(service.night)
        assertEquals(WakeService.Phase.MEASURING, measuring.phase)
        assertEquals(plan, WakeService.measuringPlan)
        assertEquals(plan, WakeService.activePlan)
        assertEquals(listOf(plan.target.toEpochMilli()), triggers())
        awaitLoaded(service)

        // Klingeln (Zielzeit-Alarm mit Plan): Wiederaufnahme-Alarm kurz nach der Zeitgrenze des Klingelns.
        val beforeRing = Instant.now()
        send(service, WakeService.ACTION_DEADLINE, 2, withPlan = true)
        val afterRing = Instant.now()
        assertEquals(WakeService.Phase.RINGING, service.night?.phase)
        assertNull(WakeService.measuringPlan)
        assertEquals(plan, WakeService.activePlan)
        val resumeFrom = WakeRules.resumeAt(beforeRing)
        val resumeTo = WakeRules.resumeAt(afterRing)
        assertEquals(1, between(resumeFrom, resumeTo).size, "Wiederaufnahme-Alarm")
        assertTrue(plan.target.toEpochMilli() in triggers(), "Zielzeit-Alarm bleibt")

        // Schlummern: das Schlummer-Ende ersetzt die Wiederaufnahme (derselbe Alarm, nur einer).
        val beforeSnooze = Instant.now()
        send(service, WakeService.ACTION_SNOOZE, 3)
        val afterSnooze = Instant.now()
        assertEquals(WakeService.Phase.SNOOZED, service.night?.phase)
        assertEquals(1, service.night?.snoozes)
        val snoozeFrom = Snooze.nextRing(beforeSnooze)
        val snoozeTo = Snooze.nextRing(afterSnooze)
        assertEquals(1, between(snoozeFrom, snoozeTo).size, "Schlummer-Ende")
        assertEquals(emptyList(), between(resumeFrom, resumeTo), "Wiederaufnahme ersetzt")

        // Beenden: keine Nacht mehr, Zielzeit-Alarm und Schlummer-Ende aufgehoben.
        send(service, WakeService.ACTION_DISMISS, 4)
        assertNull(service.night)
        assertNull(WakeService.activePlan)
        assertFalse(plan.target.toEpochMilli() in triggers(), "Zielzeit-Alarm aufgehoben")
        assertEquals(emptyList(), between(snoozeFrom, snoozeTo), "Schlummer-Ende aufgehoben")
    }

    @Test
    fun ringTimeoutSnoozesWhileRoundsLeftThenEnds() {
        val service = startNight()
        // Jede Klingel-Runde ohne Reaktion: von selbst schlummern, solange Runden bleiben; die letzte endet.
        for (round in 0..Snooze.MAX_COUNT) {
            val action = if (round == 0) WakeService.ACTION_DEADLINE else WakeService.ACTION_SNOOZE_END
            val beforeRing = Instant.now()
            send(service, action, round + 2, withPlan = true)
            val afterRing = Instant.now()
            val resumeFrom = WakeRules.resumeAt(beforeRing)
            val resumeTo = WakeRules.resumeAt(afterRing)
            assertEquals(WakeService.Phase.RINGING, service.night?.phase)
            assertEquals(1, between(resumeFrom, resumeTo).size, "Wiederaufnahme-Alarm Runde $round")

            val beforeTimeout = Instant.now()
            service.ringTimeout.run()
            val afterTimeout = Instant.now()
            val snoozeFrom = Snooze.nextRing(beforeTimeout)
            val snoozeTo = Snooze.nextRing(afterTimeout)
            assertEquals(emptyList(), between(resumeFrom, resumeTo), "Wiederaufnahme ersetzt oder aufgehoben")
            if (round < Snooze.MAX_COUNT) {
                assertEquals(WakeService.Phase.SNOOZED, service.night?.phase)
                assertEquals(round + 1, service.night?.snoozes)
                assertEquals(1, between(snoozeFrom, snoozeTo).size, "Schlummer-Ende Runde $round")
            } else {
                // Letzte Runde: kein neues Schlummern, die Nacht endet (das Handy klingelt als Sicherung weiter).
                assertNull(service.night)
                assertNull(WakeService.activePlan)
                assertEquals(emptyList(), between(snoozeFrom, snoozeTo), "kein Schlummer-Ende")
                assertFalse(plan.target.toEpochMilli() in triggers(), "Zielzeit-Alarm aufgehoben")
            }
        }
    }

    @Test
    fun phoneSnoozeAndStopOnlyForThisNight() {
        val service = startNight()
        send(service, WakeService.ACTION_DEADLINE, 2, withPlan = true)

        // Am Handy geschlummert, aber für einen anderen Wecker: gilt nicht dieser Nacht.
        fromPhone(service, WakeService.ACTION_PHONE_SNOOZE, "b", 3)
        assertEquals(WakeService.Phase.RINGING, service.night?.phase)
        assertEquals(0, service.night?.snoozes)
        // Für diese Nacht: schlummern.
        fromPhone(service, WakeService.ACTION_PHONE_SNOOZE, "a", 4)
        assertEquals(WakeService.Phase.SNOOZED, service.night?.phase)
        assertEquals(1, service.night?.snoozes)

        send(service, WakeService.ACTION_SNOOZE_END, 5, withPlan = true)
        assertEquals(WakeService.Phase.RINGING, service.night?.phase)
        // Am Handy beendet, aber ein anderer Wecker: Die Nacht bleibt.
        fromPhone(service, WakeService.ACTION_DISMISS, "b", 6)
        assertEquals(WakeService.Phase.RINGING, service.night?.phase)
        assertEquals(plan, WakeService.activePlan)
        // Ältere Handy-Version ohne Kennung: gilt der laufenden Nacht.
        fromPhone(service, WakeService.ACTION_DISMISS, null, 7)
        assertNull(service.night)
        assertFalse(plan.target.toEpochMilli() in triggers(), "Zielzeit-Alarm aufgehoben")
    }

    @Test
    fun asleepSetsEarlierTargetAndTellsThePhone() {
        val app = context.wearApp as QuietWear
        val zone = ZoneId.systemDefault()
        val at = now.plus(Duration.ofHours(3)).atZone(zone)
        val spec = AlarmSpec(id = "a", hour = at.hour, minute = at.minute, sleepDurationMinutes = 480)
        runBlocking { app.alarms.upsert(spec) }
        val latest = assertNotNull(AlarmSchedule.nextTarget(spec, now, zone))
        // 8 Std. Schlafdauer ab dem Einschlafen: zwei Stunden vor der eingestellten Weckzeit.
        val asleep = latest.minus(Duration.ofHours(10))
        val expected = AlarmSchedule.planAt(spec, asleep.plus(Duration.ofMinutes(480)))

        val built = Robolectric.buildService(WakeService::class.java)
        controller = built
        built.create().get().onAsleep(spec, latest, asleep)
        awaitUntil("Neu geplant mit früherer Zielzeit") { app.onsetShifts.isNotEmpty() && expected.target.toEpochMilli() in triggers() }

        assertEquals(expected, runBlocking { app.onsetOverride.get() })
        assertEquals(asleep, runBlocking { app.onsets.onsetFor(expected) })
        val shift = app.onsetShifts.single()
        assertEquals(OnsetShift("a", latest.toEpochMilli(), expected.target.toEpochMilli(), shift.at), shift)
        // Sicherung zur früheren Zielzeit, nicht zur eingestellten Weckzeit; die Startseite zeigt denselben Plan.
        assertFalse(latest.toEpochMilli() in triggers(), "eingestellte Weckzeit")
        assertEquals(expected, runBlocking { app.scheduler.nextPlan() })
    }

    /** Aufwärmen (zwei Epochen, Basispuls 60), danach die Epochen [after]. */
    private fun epochs(vararg after: Epoch): List<Epoch> = listOf(Epoch(0.0, 60.0), Epoch(0.0, 60.0)) + after

    /** Zwei Epochen mit Bewegung über der Wach-Schwelle. */
    private val awake = epochs(Epoch(0.5, 60.0), Epoch(0.5, 60.0))

    @Test
    fun wakesEarlyWhenAwake() {
        val service = startNight()
        val n = assertNotNull(service.night)
        val before = Instant.now()
        service.onEpochs(n, awake)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(WakeService.Phase.RINGING, n.phase)
        assertEquals(WakeReason.AWAKE, n.reason)
        assertEquals(1, between(WakeRules.resumeAt(before), WakeRules.resumeAt(Instant.now())).size, "Wiederaufnahme-Alarm")
    }

    @Test
    fun wakesEarlyInLightSleep() {
        val service = startNight()
        val n = assertNotNull(service.night)
        // Leichte Bewegung und Puls 10 % über dem Basispuls.
        service.onEpochs(n, epochs(Epoch(0.01, 66.0), Epoch(0.01, 66.0)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(WakeService.Phase.RINGING, n.phase)
        assertEquals(WakeReason.LIGHT_SLEEP, n.reason)
    }

    @Test
    fun exactNeverEarly() {
        val exact = plan.copy(exact = true)
        val service = startNight(exact)
        val n = assertNotNull(service.night)
        service.onEpochs(n, awake)
        assertEquals(WakeService.Phase.MEASURING, n.phase)
        assertEquals(awake, n.epochs.takeLast(awake.size), "für den Verlauf gemessen")
        send(service, WakeService.ACTION_DEADLINE, 2, withPlan = true, of = exact)
        assertEquals(WakeService.Phase.RINGING, n.phase)
        assertEquals(WakeReason.EXACT, n.reason)
    }

    @Test
    fun shortNightNeverEarly() {
        // Vor einer Stunde eingeschlafen: weit unter Schlafziel minus Spielraum.
        val asleep = now.minus(Duration.ofHours(1))
        runBlocking { context.wearApp.onsets.set("a", plan.target, asleep) }
        val service = startNight()
        val n = assertNotNull(service.night)
        assertEquals(asleep, n.asleepAt)
        service.onEpochs(n, awake)
        assertEquals(WakeService.Phase.MEASURING, n.phase)
        assertTrue(n.earlyBlocked)
        // Weiter unruhig: übergangen, nicht nachgeholt; die Erkennung läuft weiter.
        service.onEpochs(n, awake)
        assertEquals(WakeService.Phase.MEASURING, n.phase)
        send(service, WakeService.ACTION_DEADLINE, 2, withPlan = true)
        assertEquals(WakeService.Phase.RINGING, n.phase)
        assertEquals(WakeReason.SHORT_NIGHT, n.reason)
    }

    @Test
    fun shortNightWakesForANewMomentInTheLastTenMinutes() {
        // Zielzeit in 5 Min (innerhalb von ShortNight.LATE_MINUTES), vor einer Stunde eingeschlafen (Entscheid 10.11.2026).
        val late = WakePlan("a", now.minus(Duration.ofMinutes(25)), now.plus(Duration.ofMinutes(5)))
        runBlocking { context.wearApp.onsets.set("a", late.target, now.minus(Duration.ofHours(1))) }
        val service = startNight(late)
        val n = assertNotNull(service.night)
        service.onEpochs(n, awake)
        assertEquals(WakeService.Phase.RINGING, n.phase)
        assertEquals(WakeReason.AWAKE, n.reason)
        assertFalse(n.earlyBlocked)
    }
}
