package com.phasewake.app.wear.wake

import android.app.AlarmManager
import android.content.Context
import android.os.BatteryManager
import android.os.Looper
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.DiagEntry
import com.phasewake.app.core.DiagKind
import com.phasewake.app.shared.LearningSnapshot
import com.phasewake.app.wear.record.RecordControl
import com.phasewake.app.wear.record.RecordService
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
import kotlin.test.assertEquals
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
    fun silentStopsLogTheirReason() {
        battery(80)
        val latest = assertNotNull(AlarmSchedule.nextTarget(spec, now, zone))
        runBlocking { app.onsets.set(id, latest, null) }
        val service = service()
        service.startGoodNight(id, auto = true)
        assertTrue(awaitLog("auto onset not started: already done").kind == DiagKind.MEASURE_START)
        assertNull(service.onset)
        service.startGoodNight("unbekannt-$id")
        awaitLog("onset not started: no alarm")
        assertNull(service.onset)
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

    @Test
    fun learningStartsTheRecordingWithTheMeasurement() {
        battery(80)
        runBlocking { app.learning.setIfNewer(LearningSnapshot(true, null, null, System.currentTimeMillis())) }
        val service = service()
        service.startGoodNight(id, auto = true)
        awaitLog("auto onset start, alarm at")
        assertTrue(awaitLog("learn record start bat=80").kind == DiagKind.MEASURE_START)
        // Ein exakter Alarm gleich jetzt startet die Aufzeichnung, mit der eingestellten Weckzeit als spätestem Ende.
        val latest = assertNotNull(AlarmSchedule.nextTarget(spec, now, zone))
        val start = alarmIntents().single { it.action == RecordControl.ACTION_LEARN_START }
        assertEquals(latest.toEpochMilli(), start.getLongExtra(RecordService.EXTRA_UNTIL, 0L))
        service.stopGoodNight()
    }

    @Test
    fun learningSkipsTheRecordingBelowHalfBattery() {
        battery(45)
        runBlocking { app.learning.setIfNewer(LearningSnapshot(true, null, null, System.currentTimeMillis())) }
        val service = service()
        service.startGoodNight(id, auto = true)
        assertTrue(awaitLog("learn record skipped: battery 45%").kind == DiagKind.BATTERY)
        assertNotNull(service.onset, "die Einschlaf-Messung selbst läuft (ab 30 %)")
        assertTrue(alarmIntents().none { it.action == RecordControl.ACTION_LEARN_START })
        service.stopGoodNight()
    }

    @Test
    fun withoutLearningNoRecording() {
        battery(80)
        val service = service()
        service.startGoodNight(id, auto = true)
        awaitLog("auto onset start, alarm at")
        awaitLog("auto onset sensor wake=")
        assertTrue(entries().none { it.detail.startsWith("learn record") })
        service.stopGoodNight()
    }

    @Test
    fun learningSwitchedOffOnThePhoneNoRecording() {
        battery(80)
        val at = System.currentTimeMillis()
        runBlocking {
            app.learning.setIfNewer(LearningSnapshot(true, null, null, at))
            // Schalter «Lernphase» am Handy aus (Audit F-02): neuerer Stand ohne Lernphase.
            assertTrue(app.learning.setIfNewer(LearningSnapshot(false, null, null, at + 1)))
        }
        val service = service()
        service.startGoodNight(id, auto = true)
        awaitLog("auto onset start, alarm at")
        awaitLog("auto onset sensor wake=")
        assertTrue(entries().none { it.detail.startsWith("learn record") })
        assertTrue(alarmIntents().none { it.action == RecordControl.ACTION_LEARN_START })
        service.stopGoodNight()
    }

    @Test
    fun bedtimeForTheLatency() {
        val latest = now.plus(Duration.ofHours(9))
        // «Gute Nacht»: ab dem Drücken (im Bett).
        assertEquals(now, OnsetRules.bedAt(now, automatic = false, latest = latest, sleepMinutes = 480))
        // Automatisch: Beginn 60 Min vor der Bettzeit, die Einschlafdauer zählt ab der Bettzeit (Weckzeit − Schlaf).
        assertEquals(now.plus(Duration.ofHours(1)), OnsetRules.bedAt(now, automatic = true, latest = latest, sleepMinutes = 480))
        // Begann sie erst nach der Bettzeit: ab ihrem Beginn.
        assertEquals(now, OnsetRules.bedAt(now, automatic = true, latest = now.plus(Duration.ofHours(7)), sleepMinutes = 480))
    }

    /** Aufträge der gestellten Alarme. */
    @Suppress("DEPRECATION") // ScheduledAlarm.operation: in Robolectric 4.14 ohne Getter.
    private fun alarmIntents() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
        .mapNotNull { alarm -> alarm.operation?.let { shadowOf(it).savedIntent } }
}
