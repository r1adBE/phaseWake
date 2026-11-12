package com.phasewake.app.wear.record

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import com.phasewake.app.core.WakePlan
import com.phasewake.app.shared.NightRecording
import com.phasewake.app.shared.RecordEnd
import com.phasewake.app.wear.QuietWear
import com.phasewake.app.wear.wearApp
import java.io.File
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Aufzeichnung der Lernphase folgt einem verschobenen Wecker ([RecordControl.followPlan], Entscheid r1AD 13.11.2026).
 * Echte Nacht vom 12.11.2026: Start 00:27 mit Weckzeit 08:57, um 01:17 auf 09:57 verschoben – die Aufzeichnung endete
 * trotzdem um 08:57. Jetzt endet sie zur neuen Weckzeit derselben Nacht; ohne Wecker (aus, übersprungen) zur alten.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietWear::class)
class LearnFollowPlanTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val app get() = context.wearApp
    private val controllers = mutableListOf<ServiceController<RecordService>>()

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(context.getSystemService(BatteryManager::class.java)).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 80)
    }

    @After
    fun cleanUp() {
        controllers.forEach { it.destroy() }
        RecordService.clock = Instant::now
        context.wearApp.appScope.cancel()
        File(context.filesDir, "recordings").deleteRecursively()
    }

    private fun plan(target: Instant) = WakePlan("a", target.minus(Duration.ofMinutes(30)), target)

    private fun awaitUntil(what: String, done: () -> Boolean) {
        val until = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (!done()) {
            check(System.nanoTime() < until) { what }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    /** Aufzeichnung der Lernphase bis [until], wie aus [RecordControl.startForLearning]. */
    private fun learning(until: Instant): RecordService {
        val intent = Intent(context, RecordService::class.java).setAction(RecordService.ACTION_START_LEARN).putExtra(RecordService.EXTRA_UNTIL, until.toEpochMilli())
        val built = Robolectric.buildService(RecordService::class.java, intent)
        controllers += built
        val service = built.create().startCommand(0, 1).get()
        awaitUntil("Aufzeichnung der Lernphase läuft") { service.active != null }
        return service
    }

    private fun finished(): List<NightRecording> = File(context.filesDir, "recordings").listFiles().orEmpty()
        .filter { !it.name.contains('.') }.mapNotNull { NightRecording.decode(it.readText()) }.filter { it.finished }

    @Test
    fun ruleFollowsOnlyTheSameNight() {
        val now = Instant.parse("2026-11-12T00:17:00Z")
        val old = Instant.parse("2026-11-12T07:57:00Z")
        val moved = Instant.parse("2026-11-12T08:57:00Z")
        // Verschoben (01:17 Ortszeit auf 09:57): neue Weckzeit.
        assertEquals(moved, RecordControl.learnUntilFor(old, plan(moved), now))
        // Früher verschoben, noch vorn: auch.
        assertEquals(old.minusSeconds(3600), RecordControl.learnUntilFor(old, plan(old.minusSeconds(3600)), now))
        // Wecker aus oder gelöscht: alte Weckzeit.
        assertEquals(old, RecordControl.learnUntilFor(old, null, now))
        // Übersprungen, der nächste erst morgen (über 12 Std.): alte Weckzeit; genau 12 Std.: noch dieselbe Nacht.
        assertEquals(old, RecordControl.learnUntilFor(old, plan(moved.plus(Duration.ofDays(1))), now))
        assertEquals(now.plus(Duration.ofHours(12)), RecordControl.learnUntilFor(old, plan(now.plus(Duration.ofHours(12))), now))
        assertEquals(old, RecordControl.learnUntilFor(old, plan(now.plus(Duration.ofHours(12)).plusSeconds(1)), now))
        // Schon vorbei: alte Weckzeit.
        assertEquals(old, RecordControl.learnUntilFor(old, plan(now), now))
    }

    @Test
    fun movedAlarmMovesTheEndOfTheRunningRecording() {
        val old = Instant.ofEpochMilli(System.currentTimeMillis()).plus(Duration.ofHours(8))
        val moved = old.plus(Duration.ofHours(1))
        val service = learning(old)
        val start = assertNotNull(service.active).start
        val shadowApp = shadowOf(RuntimeEnvironment.getApplication())
        shadowApp.clearStartedServices()
        // Neu geplant (Wecker verschoben): Marke gespeichert, Befehl an den laufenden Dienst.
        assertEquals(moved, runBlocking { RecordControl.followPlan(context, plan(moved), Instant.now()) })
        assertEquals(LearnMark(start, moved), runBlocking { app.learnMark.get() })
        val command = assertNotNull(shadowApp.nextStartedService)
        assertEquals(RecordService.ACTION_FOLLOW_PLAN, command.action)
        service.onStartCommand(command, 0, 2)
        assertEquals(moved, service.active?.learnUntil)
        // Ein Befehl für eine andere Aufzeichnung ändert nichts.
        service.onStartCommand(Intent(command).putExtra(RecordService.EXTRA_START, start.toEpochMilli() - 1), 0, 3)
        assertEquals(moved, service.active?.learnUntil)
        // Zur alten Weckzeit läuft sie weiter, zur neuen endet sie.
        RecordService.clock = { old.plusSeconds(1) }
        service.onTick()
        assertNotNull(service.active, "alte Weckzeit: läuft weiter")
        RecordService.clock = { moved.plusSeconds(1) }
        service.onTick()
        awaitUntil("abgeschlossen zur neuen Weckzeit") { finished().isNotEmpty() }
        assertEquals(RecordEnd.WAKE, finished().single().endReason)
    }

    @Test
    fun skippedAlarmKeepsTheOldEnd() {
        val old = Instant.ofEpochMilli(System.currentTimeMillis()).plus(Duration.ofHours(8))
        val service = learning(old)
        // Übersprungen: Der nächste Wecker ist erst in der nächsten Nacht.
        assertEquals(old, runBlocking { RecordControl.followPlan(context, plan(old.plus(Duration.ofDays(1))), Instant.now()) })
        assertEquals(old, runBlocking { app.learnMark.get() }?.until)
        assertEquals(old, service.active?.learnUntil)
        RecordService.clock = { old.plusSeconds(1) }
        service.onTick()
        awaitUntil("abgeschlossen zur alten Weckzeit") { finished().isNotEmpty() }
        // Ohne laufende Aufzeichnung (keine Marke): nichts zu tun.
        awaitUntil("Marke gelöscht") { runBlocking { app.learnMark.get() } == null }
        assertNull(runBlocking { RecordControl.followPlan(context, plan(old), Instant.now()) })
    }
}
