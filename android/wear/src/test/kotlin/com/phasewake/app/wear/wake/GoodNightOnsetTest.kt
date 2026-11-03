package com.phasewake.app.wear.wake

import android.content.Context
import android.os.BatteryManager
import android.os.Looper
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.DiagEntry
import com.phasewake.app.core.DiagKind
import com.phasewake.app.wear.QuietWear
import com.phasewake.app.wear.wearApp
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Automatische Einschlaf-Messung und «Gute Nacht» im [WakeService] (Akku-Befund 03.11.2026): Akku-Grenze, höchstens
 * 2 Std. auch nach dem Wiederaufnehmen, Ende immer im Diagnose-Protokoll, Nacht danach erledigt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietWear::class)
class GoodNightOnsetTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val app get() = context.wearApp
    private var controller: ServiceController<WakeService>? = null
    private val now = Instant.ofEpochMilli(System.currentTimeMillis())
    private val zone = ZoneId.systemDefault()
    private val at = now.plus(Duration.ofHours(3)).atZone(zone)
    // Speicher und Protokoll bleiben zwischen Tests derselben JVM: eigene Kennung, nur Zeilen ab Teststart.
    private val id = "onset-${System.nanoTime()}"
    private val spec = AlarmSpec(id = id, hour = at.hour, minute = at.minute, sleepDurationMinutes = 480)

    @After
    fun cleanUp() {
        controller?.destroy()
        app.appScope.cancel()
    }

    private fun service(): WakeService {
        runBlocking { app.alarms.upsert(spec) }
        val built = Robolectric.buildService(WakeService::class.java)
        controller = built
        return built.create().get()
    }

    private fun battery(percent: Int) =
        shadowOf(context.getSystemService(BatteryManager::class.java)).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, percent)

    private fun entries() = app.diag.entries().filter { !it.at.isBefore(now) }

    /** Wartet (Hintergrund und Hauptthread), bis das Diagnose-Protokoll eine Zeile mit [text] hat. */
    private fun awaitLog(text: String): DiagEntry {
        val until = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            entries().firstOrNull { it.detail.contains(text) }?.let { return it }
            check(System.nanoTime() < until) { "nicht im Protokoll: $text, sondern ${entries().map { it.detail }}" }
            Thread.sleep(5)
        }
    }

    private fun awaitDone(latest: Instant) {
        val until = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (!runBlocking { app.onsets.isDone(id, latest) }) {
            check(System.nanoTime() < until) { "Nacht nicht erledigt" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    @Test
    fun lowBatterySkipsTheAutomaticMeasurement() {
        battery(29)
        val service = service()
        service.startGoodNight(id, auto = true)
        val latest = assertNotNull(AlarmSchedule.nextTarget(spec, now, zone))
        assertTrue(awaitLog("auto onset skipped battery 29").kind == DiagKind.BATTERY)
        awaitDone(latest)
        assertNull(service.onset)
        assertTrue(entries().none { it.kind == DiagKind.MEASURE_START })
    }

    @Test
    fun enoughBatteryMeasuresAndCancelIsLogged() {
        battery(30)
        val service = service()
        service.startGoodNight(id, auto = true)
        awaitLog("auto onset start, alarm at")
        val monitor = assertNotNull(service.onset)
        // Höchstens 2 Std. ab dem Beginn, nicht erst 15 Min vor der Weckzeit.
        assertTrue(monitor.giveUpAt <= Instant.now().plus(OnsetMonitor.MAX_DURATION))
        assertTrue(monitor.giveUpAt >= now.plus(OnsetMonitor.MAX_DURATION))
        awaitLog("auto onset sensor wake=")
        service.stopGoodNight()
        assertTrue(awaitLog("auto onset cancelled").kind == DiagKind.MEASURE_END)
        awaitDone(assertNotNull(AlarmSchedule.nextTarget(spec, now, zone)))
    }

    @Test
    fun resumedAfterMaxDurationEndsWithLog() {
        // «Gute Nacht» seit über 2 Std. (Prozess war beendet): Wiederaufnehmen misst nicht mehr.
        val since = now.minus(OnsetMonitor.MAX_DURATION).minusSeconds(60)
        runBlocking { app.goodNight.set(id, since, auto = false) }
        val service = service()
        service.startGoodNight(null)
        assertTrue(awaitLog("onset give up (max duration)").kind == DiagKind.MEASURE_END)
        awaitDone(assertNotNull(AlarmSchedule.nextTarget(spec, since, zone)))
        assertNull(service.onset)
        assertTrue(runBlocking { app.onsetOverride.get() }?.alarmId != id, "die eingestellte Weckzeit gilt")
    }
}
