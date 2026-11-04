package com.phasewake.app.wear.wake

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.wear.QuietWear
import com.phasewake.app.wear.schedule.AlarmReceiver
import com.phasewake.app.wear.wearApp
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
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
 * Zeitzonenwechsel im laufenden Weckfenster (Review 04.11. b, P1): Die Uhr misst weiter, und die Sicherung bleibt zur
 * geplanten absoluten Zielzeit – auch beim zweiten Neuplanen nach dem Wechsel (die Zone der Planung bleibt gespeichert).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietWear::class)
class ZoneChangeNightTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private var controller: ServiceController<WakeService>? = null
    private val originalZone: TimeZone = TimeZone.getDefault()

    private companion object {
        /**
         * Eigene Kennung: Andere Tests hinterlassen im DataStore (einer pro Prozess) für «a» einen Nachhol-Zeitraum und
         * «erledigt bis»; je nach Reihenfolge der Tests nähme das Neuplanen sonst deren Nacht statt dieser.
         */
        const val ID = "zone-change"
    }

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Zurich"))
        // Der DataStore lebt über andere Tests hinaus: kein Probewecker, Powernap oder Einschlaf-Plan von dort.
        runBlocking {
            context.wearApp.transient.clear()
            context.wearApp.onsetOverride.clear()
        }
    }

    @After
    fun cleanUp() {
        TimeZone.setDefault(originalZone)
        controller?.destroy()
        context.wearApp.appScope.cancel()
        runBlocking { context.wearApp.alarms.remove(ID) }
    }

    @Test
    fun zoneChangeWhileMeasuringKeepsNightAndDeadline() {
        val app = context.wearApp
        val zurich = ZoneId.of("Europe/Zurich")
        val now = Instant.now()
        // Zielzeit in 10 Minuten (Zürich), Fenster 30 Minuten: Das Fenster läuft schon.
        val at = now.plus(Duration.ofMinutes(10)).atZone(zurich)
        val spec = AlarmSpec(id = ID, hour = at.hour, minute = at.minute, windowMinutes = 30)
        runBlocking { app.alarms.upsert(spec) }
        val plan = assertNotNull(AlarmSchedule.nextPlan(spec, now, zurich))
        assertEquals(plan, runBlocking { app.scheduler.reschedule() })

        // Messung für diesen Plan starten, wie über den Alarm zum Fensterbeginn.
        val start = AlarmReceiver.putPlan(Intent(context, WakeService::class.java).setAction(WakeService.ACTION_START), plan)
        val built = Robolectric.buildService(WakeService::class.java, start)
        controller = built
        val service = built.create().startCommand(0, 1).get()
        assertEquals(plan, WakeService.measuringPlan)

        // Zone wechselt nach Tokio: Dort läge der nächste Termin erst viel später.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        assertTrue(AlarmSchedule.nextTarget(spec, Instant.now(), ZoneId.of("Asia/Tokyo")) != plan.target)
        val started = shadowOf(RuntimeEnvironment.getApplication())
        started.clearStartedServices()
        repeat(2) { assertEquals(plan, runBlocking { app.scheduler.reschedule() }, "Neuplanen ${it + 1}") }
        shadowOf(Looper.getMainLooper()).idle()

        // Kein Abbruch der Messung, Sicherung zur geplanten Zielzeit, Startseite zeigt dieselbe Nacht.
        val commands = generateSequence { started.nextStartedService }.map { it.action }.toList()
        assertTrue(WakeService.ACTION_CANCEL !in commands, "Messung abgebrochen: $commands")
        assertEquals(WakeService.Phase.MEASURING, service.night?.phase)
        assertEquals(plan, WakeService.measuringPlan)
        assertTrue(plan.target.toEpochMilli() in alarms.scheduledAlarms.map { it.triggerAtMs }, "Sicherung zur Zielzeit")
        assertEquals(plan, runBlocking { app.scheduler.nextPlan() })
    }
}
