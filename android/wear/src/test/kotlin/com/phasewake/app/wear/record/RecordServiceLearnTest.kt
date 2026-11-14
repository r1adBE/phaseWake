package com.phasewake.app.wear.record

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import com.phasewake.app.core.Epoch
import com.phasewake.app.core.Learning
import com.phasewake.app.shared.LearningSnapshot
import com.phasewake.app.shared.NightRecording
import com.phasewake.app.shared.RecordEnd
import com.phasewake.app.wear.QuietWear
import com.phasewake.app.wear.diag.WatchDiag
import com.phasewake.app.wear.wearApp
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
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
 * «Nacht aufzeichnen» in der Lernphase ([RecordService], [RecordControl], [LearnMark]): Start über den Alarm, Ende beim
 * Wecken, zur Weckzeit ohne Klingeln und wenn die Lernphase am Handy ausgeschaltet wird. Aus [RecordServiceTest]
 * ausgelagert (Zeilengrenze), mit denselben Hilfen und demselben Aufräumen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietWear::class)
class RecordServiceLearnTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val app get() = context.wearApp
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val controllers = mutableListOf<ServiceController<RecordService>>()

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        battery(80)
    }

    @After
    fun cleanUp() {
        // Alle Dienste beenden: [RecordService.running] liegt im Begleitobjekt und gälte sonst im nächsten Test.
        controllers.forEach { it.destroy() }
        runBlocking { WatchDiag.setShown(context, false) }
        RecordService.clock = Instant::now
        // Beenden und abwarten (z. B. die Mitteilung von [LearnNotice.skipped]); sonst käme sie verspätet im nächsten Test an.
        runBlocking { withTimeout(10_000) { context.wearApp.appScope.coroutineContext.job.cancelAndJoin() } }
        File(context.filesDir, "recordings").deleteRecursively()
    }

    private fun battery(percent: Int) =
        shadowOf(context.getSystemService(BatteryManager::class.java)).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, percent)

    private fun intent(action: String) = Intent(context, RecordService::class.java).setAction(action)

    private fun awaitUntil(what: String, done: () -> Boolean) {
        val until = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (!done()) {
            check(System.nanoTime() < until) { what }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    /** Startet den Dienst mit [intent] und gibt ihn zurück (ohne zu warten). */
    private fun launchWith(intent: Intent): RecordService {
        val built = Robolectric.buildService(RecordService::class.java, intent)
        controllers += built
        return built.create().startCommand(0, 1).get()
    }

    /** Aufzeichnung der Lernphase bis [until] (eingestellte Weckzeit). */
    private fun learning(until: Instant): RecordService {
        val service = launchWith(intent(RecordService.ACTION_START_LEARN).putExtra(RecordService.EXTRA_UNTIL, until.toEpochMilli()))
        awaitUntil("Aufzeichnung der Lernphase läuft") { service.active != null }
        return service
    }

    private fun recording(): RecordService {
        val service = launchWith(intent(RecordService.ACTION_START))
        awaitUntil("Aufzeichnung läuft") { service.active != null }
        return service
    }

    /** Gespeicherte Aufzeichnungen direkt aus den Dateien, ohne den Speicher (Grund: [RecordServiceTest]). */
    private fun stored(): List<NightRecording> =
        File(context.filesDir, "recordings").listFiles().orEmpty().filter { !it.name.contains('.') }.mapNotNull { NightRecording.decode(it.readText()) }

    private fun finished(): List<NightRecording> = stored().filter { it.finished }

    private fun active(): NightRecording? = stored().firstOrNull { !it.finished }

    private fun awaitFinished(reason: RecordEnd): NightRecording {
        awaitUntil("abgeschlossen mit $reason") { active() == null && finished().isNotEmpty() }
        val done = finished().single()
        assertEquals(reason, done.endReason)
        return done
    }

    @Test
    fun learningStartSkipsBelowLearningBatteryAndWhileRecording() {
        battery(38)
        assertFalse(RecordControl.startForLearning(context, Instant.now().plus(Duration.ofHours(8))))
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertEquals("battery 38%", RecordControl.learnSkipReason(recording = false, battery = 38))
        assertEquals("recording runs", RecordControl.learnSkipReason(recording = true, battery = 80))
        assertNull(RecordControl.learnSkipReason(recording = false, battery = null), "unbekannter Akku hindert nicht")
        assertNull(RecordControl.learnSkipReason(recording = false, battery = Learning.MIN_BATTERY))
        // Prüfrunde 2 F-6: Am Ladegerät nicht wegen des Akkus auslassen.
        assertNull(RecordControl.learnSkipReason(recording = false, battery = 38, charging = true))
        val power = shadowOf(context.getSystemService(BatteryManager::class.java))
        power.setIsCharging(true)
        assertTrue(RecordControl.startForLearning(context, Instant.now().plus(Duration.ofHours(8))))
        power.setIsCharging(false)
        battery(80)
        val until = Instant.ofEpochMilli(System.currentTimeMillis()).plus(Duration.ofHours(8))
        assertTrue(RecordControl.startForLearning(context, until))
        // Wie vom Handy: ein exakter Alarm gleich jetzt startet den Messdienst mit der Weckzeit.
        val alarm = alarms.scheduledAlarms.single()
        assertTrue(alarm.triggerAtMs <= System.currentTimeMillis() + 2_000)
        val shadowApp = shadowOf(RuntimeEnvironment.getApplication())
        shadowApp.clearStartedServices()
        RecordReceiver().onReceive(
            context,
            Intent(context, RecordReceiver::class.java).setAction(RecordControl.ACTION_LEARN_START).putExtra(RecordService.EXTRA_UNTIL, until.toEpochMilli()),
        )
        val started = assertNotNull(shadowApp.nextStartedService)
        assertEquals(RecordService.ACTION_START_LEARN, started.action)
        assertEquals(until.toEpochMilli(), started.getLongExtra(RecordService.EXTRA_UNTIL, 0L))
    }

    @Test
    fun wakeEndsEveryRecordingLearningOffOnlyTheLearningOne() {
        val manual = recording()
        // Lernphase am Handy aus: Eine von Hand begonnene läuft weiter.
        manual.onStartCommand(intent(RecordService.ACTION_END_LEARN).putExtra(RecordService.EXTRA_REASON, RecordEnd.PHONE.code), 0, 2)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(manual.active, "von Hand begonnen: läuft weiter")
        // Geweckt: Sie endet spätestens jetzt (Audit F-01).
        manual.onStartCommand(intent(RecordService.ACTION_END_LEARN), 0, 3)
        awaitFinished(RecordEnd.WAKE)
        File(context.filesDir, "recordings").deleteRecursively()

        val until = Instant.ofEpochMilli(System.currentTimeMillis()).plus(Duration.ofHours(8))
        val service = learning(until)
        val start = assertNotNull(service.active).start
        assertEquals(until, service.active?.learnUntil)
        awaitUntil("Marke gespeichert") { runBlocking { app.learnMark.get() } == LearnMark(start, until) }
        service.onEpochs(listOf(Epoch(0.02, 55.0)))
        service.onStartCommand(intent(RecordService.ACTION_END_LEARN), 0, 2)
        val done = awaitFinished(RecordEnd.WAKE)
        assertEquals(listOf(Epoch(0.02, 55.0)), done.epochs)
        awaitUntil("Marke gelöscht") { runBlocking { app.learnMark.get() } == null }
    }

    @Test
    fun learningRecordingEndsAtTheAlarmTimeWithoutRing() {
        val until = Instant.ofEpochMilli(System.currentTimeMillis()).plus(Duration.ofHours(8))
        val service = learning(until)
        RecordService.clock = { until.minusSeconds(60) }
        service.onTick()
        assertNotNull(service.active, "vor der Weckzeit")
        RecordService.clock = { until.plusSeconds(1) }
        service.onTick()
        awaitFinished(RecordEnd.WAKE)
        assertTrue(RecordControl.endsAtTarget(until, until, nightRunning = false))
        assertFalse(RecordControl.endsAtTarget(until, until, nightRunning = true), "klingelt noch: endet mit dem Wecken")
        assertFalse(RecordControl.endsAtTarget(until.minusMillis(1), until, nightRunning = false))
        assertEquals("wake", RecordService.reasonText(RecordEnd.WAKE, null))
    }

    @Test
    fun learningSwitchedOffOnThePhoneEndsOnlyTheLearningRecording() {
        val until = Instant.ofEpochMilli(System.currentTimeMillis()).plus(Duration.ofHours(8))
        val service = learning(until)
        // Noch an: nichts geschieht.
        RecordControl.onLearningChanged(context, LearningSnapshot(true, null, null, 1L))
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(service.active)
        // Aus (Audit F-02): Der Befehl an den laufenden Dienst beendet sie als «am Handy gestoppt».
        val shadowApp = shadowOf(RuntimeEnvironment.getApplication())
        shadowApp.clearStartedServices()
        RecordControl.onLearningChanged(context, LearningSnapshot(false, null, null, 2L))
        val command = assertNotNull(shadowApp.nextStartedService)
        assertEquals(RecordService.ACTION_END_LEARN, command.action)
        service.onStartCommand(command, 0, 2)
        awaitFinished(RecordEnd.PHONE)
    }

    @Test
    fun withoutRunningServiceWakeFinishesEveryRecordingLearningOffOnlyItsOwn() {
        val start = Instant.ofEpochMilli(System.currentTimeMillis()).minus(Duration.ofMinutes(3))
        runBlocking {
            app.recordings.begin(start)
            app.recordings.append(start, List(4) { Epoch(0.02, 58.0) })
            app.learnMark.set(LearnMark(start.minusSeconds(600), start.plus(Duration.ofHours(8))))
        }
        assertFalse(RecordService.running)
        // Lernphase aus, Marke einer anderen Aufzeichnung: diese bleibt.
        RecordControl.endLearning(context, RecordEnd.PHONE)
        awaitUntil("Marke gelöscht") { runBlocking { app.learnMark.get() } == null }
        assertNotNull(active())
        // Geweckt: auch ohne Marke abgeschlossen (von Hand begonnen, Audit F-01).
        RecordControl.endLearning(context)
        val done = awaitFinished(RecordEnd.WAKE)
        assertEquals(start.plusSeconds(4 * 30L), done.end)
    }
}
