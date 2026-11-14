package com.phasewake.app.wear.record

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import com.phasewake.app.core.Epoch
import com.phasewake.app.shared.NightRecording
import com.phasewake.app.shared.Outbox
import com.phasewake.app.shared.QueueMessages
import com.phasewake.app.shared.RecordEnd
import com.phasewake.app.shared.RecordSync
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
 * «Nacht aufzeichnen» auf der Uhr ([RecordService], [RecordControl]): Start und Stopp, Grenze nach 12 Std.,
 * Akku-Wache, fortlaufendes Speichern, Wiederaufnahme nach einem Prozessende und die Befehle vom Handy.
 * Epochen kommen direkt über [RecordService.onEpochs], ohne Sensoren. Lernphase: [RecordServiceLearnTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietWear::class)
class RecordServiceTest {
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

    /** Startet den Dienst mit [action] und gibt ihn zurück (ohne zu warten). */
    private fun launch(action: String): RecordService {
        val built = Robolectric.buildService(RecordService::class.java, intent(action))
        controllers += built
        return built.create().startCommand(0, 1).get()
    }

    private fun awaitUntil(what: String, done: () -> Boolean) {
        val until = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (!done()) {
            check(System.nanoTime() < until) { what }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    private fun recording(): RecordService {
        val service = launch(RecordService.ACTION_START)
        awaitUntil("Aufzeichnung läuft") { service.active != null }
        return service
    }

    /**
     * Gespeicherte Aufzeichnungen direkt aus den Dateien, ohne den Speicher: Dessen Sperre hält der Dienst auf dem
     * Hauptthread, den der Test beim Warten belegt (sonst warteten beide aufeinander).
     */
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

    private fun checkAlarmArmed(): Boolean = alarms.scheduledAlarms.any { it.triggerAtMs > System.currentTimeMillis() + 60_000 }

    @Test
    fun startStoresEpochsAndStopOnTheWatchFinishes() {
        val service = recording()
        assertNotNull(RecordService.since.value)
        assertTrue(RecordService.running)
        assertTrue(checkAlarmArmed(), "Prüf-Alarm für das Wiederaufnehmen")
        assertNotNull(active(), "Datei angelegt")
        service.onEpochs(listOf(Epoch(0.02, 60.0), Epoch(null, null), Epoch(0.4, null)))
        service.onStartCommand(intent(RecordService.ACTION_STOP).putExtra(RecordService.EXTRA_REASON, RecordEnd.WATCH.code), 0, 2)
        val done = awaitFinished(RecordEnd.WATCH)
        assertEquals(listOf(Epoch(0.02, 60.0), Epoch(null, null), Epoch(0.4, null)), done.epochs)
        assertNull(RecordService.since.value)
        assertNull(service.active)
        assertFalse(checkAlarmArmed(), "Prüf-Alarm aufgehoben")
    }

    @Test
    fun endsByItselfAfterTwelveHours() {
        val service = recording()
        service.onEpochs(listOf(Epoch(0.01, 55.0)))
        val start = assertNotNull(service.active).start
        RecordService.clock = { start.plus(NightRecording.MAX_DURATION).plusSeconds(5) }
        service.onTick()
        val done = awaitFinished(RecordEnd.CAP)
        assertEquals(start.plus(NightRecording.MAX_DURATION), done.end, "Ende höchstens 12 Std. nach Beginn")
    }

    @Test
    fun batteryGuardStopsAndRefusesToStart() {
        val service = recording()
        battery(14)
        service.onTick()
        awaitFinished(RecordEnd.BATTERY)
        // Unter 15 % startet keine Aufzeichnung (der Knopf zeigt dann einen Hinweis).
        assertFalse(RecordControl.startFromWatch(context))
        val refused = launch(RecordService.ACTION_START)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(refused.active)
        assertEquals(1, finished().size, "keine neue Aufzeichnung")
        assertNull(active())
    }

    @Test
    fun appendsEveryFewMinutesSoACrashLosesLittle() {
        val service = recording()
        val start = assertNotNull(service.active).start
        service.onEpochs(List(10) { Epoch(0.01 * it, 50.0) })
        // Noch keine 5 Minuten: nur im Speicher.
        service.onTick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(start, active()?.start)
        assertEquals(0, active()?.epochs?.size)
        assertNotNull(service.active).lastFlushMs -= RecordService.FLUSH_INTERVAL.toMillis()
        service.onTick()
        awaitUntil("angehängt") { active()?.epochs?.size == 10 }
    }

    @Test
    fun resumesAfterAProcessEndAndFillsTheGap() {
        val start = Instant.ofEpochMilli(System.currentTimeMillis()).minus(Duration.ofMinutes(10))
        runBlocking {
            app.recordings.begin(start)
            app.recordings.append(start, List(5) { Epoch(0.02, 58.0) })
        }
        val service = launch(RecordService.ACTION_RESUME)
        awaitUntil("wieder aufgenommen") { service.active != null }
        val active = assertNotNull(service.active)
        assertEquals(start, active.start)
        assertEquals(NightRecording.epochsUntil(start, Instant.now()), active.epochs.size, "Lücke mit leeren Epochen gefüllt")
        assertTrue(active.epochs.drop(5).all { it.motion == null && it.heartRate == null })
        service.onStartCommand(intent(RecordService.ACTION_STOP).putExtra(RecordService.EXTRA_REASON, RecordEnd.PHONE.code), 0, 2)
        val done = awaitFinished(RecordEnd.PHONE)
        assertTrue(done.epochs.size >= 20, "gespeicherte und aufgefüllte Epochen")
    }

    @Test
    fun resumeAfterTheLimitOnlyFinishes() {
        val start = Instant.ofEpochMilli(System.currentTimeMillis()).minus(Duration.ofHours(13))
        runBlocking {
            app.recordings.begin(start)
            app.recordings.append(start, listOf(Epoch(0.02, 58.0)))
        }
        val service = launch(RecordService.ACTION_RESUME)
        val done = awaitFinished(RecordEnd.CAP)
        assertNull(service.active)
        assertEquals(start.plusSeconds(30), done.end, "Ende nach der letzten gespeicherten Epoche, nicht erfunden")
        // Das Abschliessen läuft auf dem Hauptthread des Dienstes weiter (ans Handy über die prozessweite Warteschlange,
        // deren Sperre es dabei hält) und endet mit stopSelf. Abwarten: Sonst verwirft Robolectric die Fortsetzung beim
        // Testende, die Sperre bliebe für alle späteren Tests im Prozess belegt (CI 14.11.2026: «Marke gelöscht» in
        // [RecordServiceLearnTest.wakeEndsEveryRecordingLearningOffOnlyTheLearningOne] hing an Outbox.enqueue).
        awaitUntil("Dienst beendet sich") { shadowOf(service).isStoppedBySelf }
    }

    @Test
    fun orphanFinishedOnResumeReachesThePhoneEvenIfTheServiceEnds() {
        // Das System beendet den Dienst, während er eine liegengebliebene Aufzeichnung abschliesst: Sie geht trotzdem in
        // die Warteschlange ans Handy (Arbeit im Bereich der App, nicht des Dienstes).
        val start = Instant.ofEpochMilli(System.currentTimeMillis()).minus(Duration.ofHours(13))
        // Die Warteschlange ist prozessweit (auch frühere Tests): nur ein neuer Eintrag zählt.
        val before = runBlocking { Outbox.forContext(context).pending() }.map { it.id }.toSet()
        runBlocking {
            app.recordings.begin(start)
            app.recordings.append(start, listOf(Epoch(0.02, 58.0)))
        }
        launch(RecordService.ACTION_RESUME)
        // Den Hauptthread nur bis zum Abschluss der Datei laufen lassen; danach steht das Einreihen noch aus.
        val until = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (finished().none { it.start == start }) {
            check(System.nanoTime() < until) { "abgeschlossen" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        controllers.removeAt(controllers.lastIndex).destroy()
        val key = QueueMessages.KEY_RECORDING + start.toEpochMilli()
        fun queued() = runBlocking { Outbox.forContext(context).pending() }.filter { it.key == key && it.id !in before }
        awaitUntil("in der Warteschlange") { queued().isNotEmpty() }
        assertEquals(RecordEnd.CAP, NightRecording.decode(queued().single().body)?.endReason)
    }

    @Test
    fun phoneStopWithoutRunningServiceFinishesTheSavedRecording() {
        val start = Instant.ofEpochMilli(System.currentTimeMillis()).minus(Duration.ofMinutes(3))
        runBlocking {
            app.recordings.begin(start)
            app.recordings.append(start, List(4) { Epoch(0.02, 58.0) })
        }
        assertFalse(RecordService.running)
        RecordControl.fromPhone(context, RecordSync.Command.STOP)
        val done = awaitFinished(RecordEnd.PHONE)
        assertEquals(start.plusSeconds(4 * 30L), done.end, "Ende nach der letzten gespeicherten Epoche")
    }

    @Suppress("DEPRECATION") // ScheduledAlarm.operation: in Robolectric ohne Getter (wie BackupWatchTest).
    @Test
    fun phoneStartGoesThroughAnExactAlarm() {
        // Aus dem Hintergrund darf der Messdienst nicht in den Vordergrund: ein exakter Alarm gleich jetzt startet ihn.
        RecordControl.fromPhone(context, RecordSync.Command.START, continuous = true)
        val alarm = alarms.scheduledAlarms.single()
        assertTrue(alarm.triggerAtMs <= System.currentTimeMillis() + 2_000)
        // Diagnose am Handy freigeschaltet: Der Alarm trägt es zum Dienst weiter.
        val fired = shadowOf(alarm.operation).savedIntent
        assertTrue(fired.getBooleanExtra(RecordService.EXTRA_CONTINUOUS, false))
        val app = shadowOf(RuntimeEnvironment.getApplication())
        app.clearStartedServices()
        RecordReceiver().onReceive(context, fired)
        val started = assertNotNull(app.nextStartedService)
        assertEquals(RecordService.ACTION_START_PHONE, started.action)
        assertTrue(started.getBooleanExtra(RecordService.EXTRA_CONTINUOUS, false))
    }

    @Test
    fun diagnosticsReasonsAndStatLine() {
        assertEquals("manual watch", RecordService.reasonText(RecordEnd.WATCH, 50))
        assertEquals("manual phone", RecordService.reasonText(RecordEnd.PHONE, 50))
        assertEquals("cap 12h", RecordService.reasonText(RecordEnd.CAP, 50))
        assertEquals("battery 14%", RecordService.reasonText(RecordEnd.BATTERY, 14))
        assertEquals("service ended", RecordService.reasonText(RecordEnd.SERVICE, null))
        val all = List(10) { Epoch(0.01 * (it + 1), if (it % 2 == 0) 60.0 else null) }
        assertEquals(
            "record stat epochs=3 empty=1 motion=0.0200/0.0300 floor=0.0200 hr=2 bat=40",
            RecordControl.statLine(listOf(Epoch(0.01, 60.0), Epoch(null, null), Epoch(0.03, 61.0)), all, 40),
        )
    }
}
