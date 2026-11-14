package com.phasewake.app.wear.wake

import android.app.AlarmManager
import android.content.Context
import android.os.BatteryManager
import android.os.Looper
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.DiagEntry
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.RecorderSkip
import com.phasewake.app.shared.LearningSnapshot
import com.phasewake.app.wear.record.RecordControl
import com.phasewake.app.wear.record.RecordService
import com.phasewake.app.wear.QuietWear
import com.phasewake.app.wear.wearApp
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
    // 9 Std. vor der Weckzeit, 8 Std. Schlaf: Bettzeit in 1 Std., die automatische Messung beginnt planmässig (AP3-2).
    private val at = now.plus(Duration.ofHours(9)).atZone(zone)
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
        // Nacht-Check am Handy (Audit F-06): Die Meldung geht mit dem Uhr-Status.
        awaitSkip(RecorderSkip(latest, RecorderSkip.Reason.BATTERY))
    }

    /** Wartet, bis die gemerkte Meldung «nicht gestartet» [skip] ist (Senden ans Handy läuft im Hintergrund). */
    private fun awaitSkip(skip: RecorderSkip?) {
        val until = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (runBlocking { app.scheduler.recorderSkip() } != skip) {
            check(System.nanoTime() < until) { "Meldung nicht $skip" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    @Test
    fun enoughBatteryMeasuresAndCancelIsLogged() {
        battery(30)
        val service = service()
        val latest = assertNotNull(AlarmSchedule.nextTarget(spec, now, zone))
        // Eine Meldung «nicht gestartet» dieser Nacht gilt nicht mehr, sobald sie misst.
        app.appScope.launch { app.scheduler.noteRecorderSkip(RecorderSkip(latest, RecorderSkip.Reason.BATTERY)) }
        awaitSkip(RecorderSkip(latest, RecorderSkip.Reason.BATTERY))
        service.startGoodNight(id, auto = true)
        awaitLog("auto onset start, alarm at")
        awaitSkip(null)
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
    fun learningSkipsTheRecordingBelowLearningBattery() {
        battery(35)
        runBlocking { app.learning.setIfNewer(LearningSnapshot(true, null, null, System.currentTimeMillis())) }
        val service = service()
        service.startGoodNight(id, auto = true)
        assertTrue(awaitLog("learn record skipped: battery 35%").kind == DiagKind.BATTERY)
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
    fun windowBeginningDuringTheMeasurementCountsAsMeasured() {
        // Fail-closed (Entscheid r1AD 10.11.2026): Fenster beginnt, die Messung lief noch ohne Einschlafzeit.
        battery(80)
        val service = service()
        service.startGoodNight(id, auto = true)
        awaitLog("auto onset sensor wake=")
        val latest = assertNotNull(AlarmSchedule.nextTarget(spec, now, zone))
        val plan = AlarmSchedule.planAt(spec, latest)
        assertTrue(service.adopt(plan))
        awaitLog("auto onset no result, short night unknown")
        awaitDone(latest)
        assertTrue(runBlocking { app.onsets.measuredFor(plan) })
        // «Gute Nacht» ist danach vorbei (Stand gelöscht, gleich nach dem Merken).
        val until = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (runBlocking { app.goodNight.load() } != null) {
            check(System.nanoTime() < until) { "Stand von «Gute Nacht» nicht gelöscht" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    @Test
    fun manualCancelIsNotMeasured() {
        battery(80)
        val service = service()
        service.startGoodNight(id, auto = true)
        awaitLog("auto onset sensor wake=")
        service.stopGoodNight()
        val latest = assertNotNull(AlarmSchedule.nextTarget(spec, now, zone))
        awaitDone(latest)
        assertTrue(!runBlocking { app.onsets.measuredFor(AlarmSchedule.planAt(spec, latest)) })
    }

    @Test
    fun automaticStartAfterBedtimeLeavesOnsetUnknown() {
        // Tiefenaudit AP3-2: Weckzeit in 3 Std., 8 Std. Schlaf: Die Bettzeit ist 5 Std. vorbei. Ruhe ab jetzt hiesse nur
        // «schlief schon»: keine Messung, Einschlafzeit unbekannt, gemessen ohne Ergebnis (fail-closed).
        battery(80)
        val soon = now.plus(Duration.ofHours(3)).atZone(zone)
        val late = spec.copy(hour = soon.hour, minute = soon.minute)
        runBlocking { app.alarms.upsert(late) }
        val built = Robolectric.buildService(WakeService::class.java)
        controller = built
        val service = built.create().get()
        service.startGoodNight(id, auto = true)
        assertTrue(awaitLog("auto onset start after bedtime").kind == DiagKind.MEASURE_END)
        val latest = assertNotNull(AlarmSchedule.nextTarget(late, now, zone))
        awaitDone(latest)
        assertNull(service.onset)
        assertNull(runBlocking { app.onsets.onsetFor(AlarmSchedule.planAt(late, latest)) })
        assertTrue(runBlocking { app.onsets.measuredFor(AlarmSchedule.planAt(late, latest)) })
    }

    @Test
    fun lateStartRule() {
        // Gleiche Fälle wie `Audit93WatchTests` (Apple Watch, `RecorderOnset.startedLate`).
        val latest = now.plus(Duration.ofHours(9))
        val bedAt = OnsetRules.plannedBedAt(now, automatic = true, latest = latest, sleepMinutes = 450)
        assertEquals(latest.minus(Duration.ofMinutes(450)), bedAt)
        assertTrue(OnsetRules.startedLate(bedAt.plus(Duration.ofHours(4)), bedAt))
        assertTrue(!OnsetRules.startedLate(bedAt.minus(Duration.ofMinutes(40)), bedAt))
        assertTrue(!OnsetRules.startedLate(bedAt.plus(OnsetRules.LATE_START_TOLERANCE), bedAt))
        assertTrue(OnsetRules.startedLate(bedAt.plus(OnsetRules.LATE_START_TOLERANCE).plusSeconds(1), bedAt))
        // «Gute Nacht»: Beginn ist das Tippen; Sekundenbruchteile danach gilt die Messung, nach einem Prozessende nicht mehr.
        val tap = now.minus(Duration.ofHours(2))
        assertEquals(tap, OnsetRules.plannedBedAt(tap, automatic = false, latest = latest, sleepMinutes = 450))
        assertTrue(!OnsetRules.startedLate(tap.plusMillis(200), tap))
        assertTrue(OnsetRules.startedLate(tap.plus(Duration.ofMinutes(20)), tap))
        assertEquals(Duration.ofSeconds(60), OnsetRules.LATE_START_TOLERANCE)
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

    @Test
    fun automaticOnsetNeverBeforeBedtime() {
        // Vollaudit K-4: Weckzeit 07:30, Schlafziel 8 Std. → Bettzeit 23:30, Messung ab 22:30. Still ab 22:30 (Sofa):
        // eingeschlafen frühestens 23:30, nicht 22:30 (sonst Zielzeit 06:30 und keine Untergrenze kurzer Nächte).
        val start = Instant.parse("2026-11-10T22:30:00Z")
        val latest = Instant.parse("2026-11-11T07:30:00Z")
        val bed = OnsetRules.bedAt(start, automatic = true, latest = latest, sleepMinutes = 480)
        assertEquals(Instant.parse("2026-11-10T23:30:00Z"), bed)
        assertEquals(bed, OnsetRules.onsetAt(start, bed))
        assertEquals(bed, OnsetRules.onsetAt(Instant.parse("2026-11-10T22:45:00Z"), bed))
        // Nach der Bettzeit eingeschlafen: unverändert.
        val later = Instant.parse("2026-11-10T23:50:00Z")
        assertEquals(later, OnsetRules.onsetAt(later, bed))
        // «Gute Nacht»: Beginn der Einschlafdauer ist das Drücken, die erkannte Zeit gilt unverändert.
        val tap = OnsetRules.bedAt(start, automatic = false, latest = latest, sleepMinutes = 480)
        assertEquals(Instant.parse("2026-11-10T22:40:00Z"), OnsetRules.onsetAt(Instant.parse("2026-11-10T22:40:00Z"), tap))
    }

    /** Aufträge der gestellten Alarme. */
    @Suppress("DEPRECATION") // ScheduledAlarm.operation: in Robolectric 4.14 ohne Getter.
    private fun alarmIntents() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
        .mapNotNull { alarm -> alarm.operation?.let { shadowOf(it).savedIntent } }
}
