package com.phasewake.app.wear.wake

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import com.phasewake.app.core.Snooze
import com.phasewake.app.core.WakeDetector
import com.phasewake.app.core.WakePlan
import com.phasewake.app.wear.QuietWear
import com.phasewake.app.wear.schedule.AlarmReceiver
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
    }

    private fun intent(action: String, withPlan: Boolean = false): Intent {
        val intent = Intent(context, WakeService::class.java).setAction(action)
        return if (withPlan) AlarmReceiver.putPlan(intent, plan) else intent
    }

    /** Ein weiterer Auftrag an den laufenden Dienst, wie vom System zugestellt. */
    private fun send(service: WakeService, action: String, startId: Int, withPlan: Boolean = false) {
        service.onStartCommand(intent(action, withPlan), 0, startId)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Zeitpunkte aller gestellten Alarme (Robolectric 4.14 nennt den PendingIntent nur über ein veraltetes Feld). */
    private fun triggers(): List<Long> = alarms.scheduledAlarms.map { it.triggerAtMs }

    /** Gestellte Alarme zwischen [from] und [to] (beide eingeschlossen). */
    private fun between(from: Instant, to: Instant): List<Long> = triggers().filter { it in from.toEpochMilli()..to.toEpochMilli() }

    /** Wartet, bis die Nacht Wecker und Schwellen geladen hat (Speicher im Hintergrund, danach Hauptthread). */
    private fun awaitLoaded(service: WakeService, initial: WakeDetector) {
        val until = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (service.night?.detector === initial) {
            check(System.nanoTime() < until) { "Nacht nicht geladen" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

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
        awaitLoaded(service, measuring.detector)

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
}
