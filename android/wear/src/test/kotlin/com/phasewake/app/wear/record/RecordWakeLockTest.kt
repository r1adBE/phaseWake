package com.phasewake.app.wear.record

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Looper
import android.os.SystemClock
import com.phasewake.app.core.Epoch
import com.phasewake.app.shared.NightRecording
import com.phasewake.app.shared.RecordEnd
import com.phasewake.app.wear.QuietWear
import com.phasewake.app.wear.diag.WatchDiag
import com.phasewake.app.wear.wake.OnsetMonitor
import com.phasewake.app.wear.wake.SensorSession
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
import org.robolectric.shadows.SensorBuilder
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowPowerManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Wakelock von «Nacht aufzeichnen» (Audit F-01): ohne freigeschaltete Diagnose in Stichproben mit kurzem Wakelock je
 * Stichprobe («wl=sample»); durchgehend mit aufweckendem Beschleunigungssensor mit grossem Puffer nur kurz je Bündel
 * («wl=batch»), sonst über die ganze Aufzeichnung («wl=hold»).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietWear::class)
class RecordWakeLockTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val app get() = context.wearApp
    private val sensors get() = shadowOf(context.getSystemService(SensorManager::class.java))
    private val controllers = mutableListOf<ServiceController<RecordService>>()
    private val since = Instant.ofEpochMilli(System.currentTimeMillis())

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ShadowPowerManager.clearWakeLocks()
        shadowOf(context.getSystemService(BatteryManager::class.java)).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 80)
    }

    @After
    fun cleanUp() {
        controllers.forEach { it.destroy() }
        runBlocking { WatchDiag.setShown(context, false) }
        app.appScope.cancel()
        File(context.filesDir, "recordings").deleteRecursively()
    }

    private fun awaitUntil(what: String, done: () -> Boolean) {
        val until = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (!done()) {
            check(System.nanoTime() < until) { what }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    /** Von Hand auf der Uhr begonnen; [diagnostics]: Diagnose freigeschaltet, also durchgehend. */
    private fun recording(diagnostics: Boolean = true): RecordService {
        runBlocking { WatchDiag.setShown(context, diagnostics) }
        val built = Robolectric.buildService(RecordService::class.java, Intent(context, RecordService::class.java).setAction(RecordService.ACTION_START))
        controllers += built
        val service = built.create().startCommand(0, 1).get()
        awaitUntil("Aufzeichnung läuft") { service.active != null }
        return service
    }

    private fun startLine(): String {
        awaitUntil("Startzeile") { app.diag.entries().any { !it.at.isBefore(since) && it.detail.startsWith("record start by watch") } }
        return app.diag.entries().last { it.detail.startsWith("record start by watch") }.detail
    }

    private fun stored(): List<NightRecording> =
        File(context.filesDir, "recordings").listFiles().orEmpty().filter { !it.name.contains('.') }.mapNotNull { NightRecording.decode(it.readText()) }

    @Test
    fun withoutWakeUpSensorHoldsTheWakeLockAsBefore() {
        sensors.addSensor(SensorBuilder.newBuilder().setType(Sensor.TYPE_ACCELEROMETER).build())
        val service = recording()
        assertFalse(service.batched)
        assertTrue(service.wakeLocks.holding)
        assertTrue(startLine().endsWith("wl=hold mode=continuous"))
        assertTrue(ShadowPowerManager.getLatestWakeLock()?.isHeld == true)
        service.onStartCommand(Intent(context, RecordService::class.java).setAction(RecordService.ACTION_STOP).putExtra(RecordService.EXTRA_REASON, RecordEnd.WATCH.code), 0, 2)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(service.wakeLocks.holding, "beim Ende gelöst")
    }

    @Test
    fun wakeUpSensorBatchesWithoutALongWakeLock() {
        sensors.addSensor(
            SensorBuilder.newBuilder().setType(Sensor.TYPE_ACCELEROMETER).setWakeUpFlag(true)
                .setFifoMaxEventCount(SensorSession.MIN_WAKE_UP_FIFO).build(),
        )
        val service = recording()
        assertTrue(service.batched)
        assertFalse(service.wakeLocks.holding, "kein langer Wakelock")
        assertTrue(startLine().endsWith("wl=batch mode=continuous"))
        assertTrue(ShadowPowerManager.getLatestWakeLock()?.isHeld != true)
        // Ein Bündel: kurzer Wakelock mit Zeitlimit; ist der Zeitgeber fällig, läuft er hier (Anhängen nach 5 Min).
        val active = assertNotNull(service.active)
        active.lastTickMs -= RecordService.TICK_MS
        active.lastFlushMs -= RecordService.FLUSH_INTERVAL.toMillis()
        service.onEpochs(List(3) { Epoch(0.02, 55.0) })
        assertTrue(ShadowPowerManager.getLatestWakeLock()?.isHeld == true, "kurzer Wakelock je Bündel")
        assertFalse(service.wakeLocks.holding)
        awaitUntil("angehängt") { stored().singleOrNull()?.epochs?.size == 3 }
    }

    @Test
    fun manualWithoutDiagnosticsRecordsInSamples() {
        sensors.addSensor(SensorBuilder.newBuilder().setType(Sensor.TYPE_ACCELEROMETER).build())
        val service = recording(diagnostics = false)
        assertTrue(assertNotNull(service.active).sampled)
        assertFalse(service.wakeLocks.holding, "kein langer Wakelock")
        assertFalse(service.batched)
        assertTrue(startLine().endsWith("wl=sample mode=sample"))
        assertTrue(sensors.listeners.isEmpty(), "zwischen den Stichproben keine Sensoren")
    }

    private fun stop(service: RecordService, reason: RecordEnd) {
        service.onStartCommand(Intent(context, RecordService::class.java).setAction(RecordService.ACTION_STOP).putExtra(RecordService.EXTRA_REASON, reason.code), 0, 2)
        awaitUntil("abgeschlossen") { stored().singleOrNull()?.endReason == reason }
        File(context.filesDir, "recordings").deleteRecursively()
    }

    private fun launch(intent: Intent): RecordService {
        val built = Robolectric.buildService(RecordService::class.java, intent)
        controllers += built
        val service = built.create().startCommand(0, 1).get()
        awaitUntil("Aufzeichnung läuft") { service.active != null }
        return service
    }

    @Test
    fun phoneDiagnosticsMeasureContinuouslyAlsoAfterAProcessEnd() {
        // Vom Handy mit Diagnose dort: durchgehend; die Marke übersteht ein Prozessende.
        val phone = launch(Intent(context, RecordService::class.java).setAction(RecordService.ACTION_START_PHONE).putExtra(RecordService.EXTRA_CONTINUOUS, true))
        val start = assertNotNull(phone.active).start
        assertFalse(assertNotNull(phone.active).sampled)
        awaitUntil("Marke gespeichert") { runBlocking { app.learnMark.isContinuous(start) } }
        stop(phone, RecordEnd.PHONE)
        awaitUntil("Marke gelöscht") { !runBlocking { app.learnMark.isContinuous(start) } }
        // Ohne Diagnose vom Handy: Stichproben.
        val sampled = launch(Intent(context, RecordService::class.java).setAction(RecordService.ACTION_START_PHONE))
        assertTrue(assertNotNull(sampled.active).sampled)
        stop(sampled, RecordEnd.PHONE)
    }

    @Test
    fun resumeKeepsTheMode() {
        val start = Instant.ofEpochMilli(System.currentTimeMillis()).minus(Duration.ofMinutes(10))
        runBlocking {
            app.recordings.begin(start)
            app.learnMark.setContinuous(start)
        }
        val resume = Intent(context, RecordService::class.java).setAction(RecordService.ACTION_RESUME)
        val continuous = launch(resume)
        assertFalse(assertNotNull(continuous.active).sampled, "durchgehend wie vor dem Prozessende")
        stop(continuous, RecordEnd.WATCH)
        runBlocking { app.recordings.begin(start.plusSeconds(60)) }
        assertTrue(assertNotNull(launch(resume).active).sampled, "ohne Marke in Stichproben")
    }

    @Test
    fun learningRecordsInSamplesWithShortWakeLocksOnly() {
        sensors.addSensor(SensorBuilder.newBuilder().setType(Sensor.TYPE_ACCELEROMETER).build())
        val built = Robolectric.buildService(
            RecordService::class.java,
            Intent(context, RecordService::class.java).setAction(RecordService.ACTION_START_LEARN)
                .putExtra(RecordService.EXTRA_UNTIL, since.plus(Duration.ofHours(8)).toEpochMilli()),
        )
        controllers += built
        val service = built.create().startCommand(0, 1).get()
        awaitUntil("Aufzeichnung der Lernphase läuft") { service.active != null }
        assertFalse(service.wakeLocks.holding, "kein langer Wakelock")
        assertFalse(service.batched)
        awaitUntil("Startzeile") { app.diag.entries().any { it.detail.startsWith("record start by learn") } }
        assertTrue(app.diag.entries().last { it.detail.startsWith("record start by learn") }.detail.endsWith("wl=sample mode=sample"))
        assertTrue(sensors.listeners.isEmpty(), "zwischen den Stichproben keine Sensoren")
    }

    @Test
    fun samplerMeasuresSixtySecondsAndBlanksTheRest() {
        val accelerometer = SensorBuilder.newBuilder().setType(Sensor.TYPE_ACCELEROMETER).build()
        val pulse = SensorBuilder.newBuilder().setType(Sensor.TYPE_HEART_RATE).build()
        sensors.addSensor(accelerometer)
        sensors.addSensor(pulse)
        val startNanos = SystemClock.elapsedRealtimeNanos()
        val session = SensorSession(context, startNanos, SystemClock::elapsedRealtimeNanos, sampled = true) {}
        val locks = RecordWakeLocks(context)
        var ticks = 0
        val sampler = LearnSampler(context, session, startNanos, locks) { ticks++ }
        session.start(listen = false)
        sampler.start()
        assertEquals(SensorSession.SOURCE_SENSOR, session.heartRateSource)
        assertTrue(sensors.listeners.isEmpty())
        sampler.tick()
        assertEquals(1, ticks, "Grenzen und Anhängen je Alarm")
        assertTrue(sampler.sampling)
        assertEquals(OnsetMonitor.SAMPLE_EPOCHS.toLong(), sampler.sampleUntil - sampler.sampleFrom)
        assertTrue(sensors.listeners.any { sensors.hasListener(it, accelerometer) })
        assertTrue(sensors.listeners.any { sensors.hasListener(it, pulse) }, "Puls nur in der Stichprobe")
        assertTrue(ShadowPowerManager.getLatestWakeLock()?.isHeld == true, "kurzer Wakelock")
        assertFalse(locks.holding)
        // Ausserhalb der Stichprobe leer, darin wie gemessen.
        val e = Epoch(0.02, 55.0)
        val empty = Epoch(null, null)
        assertEquals(listOf(empty, e, e, empty), sampler.filter(sampler.sampleFrom - 1, listOf(e, e, e, e)))
        // Ende der Stichprobe: Sensoren ab, Wakelock gelöst.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(95))
        assertFalse(sampler.sampling)
        assertTrue(sensors.listeners.isEmpty())
        assertFalse(ShadowPowerManager.getLatestWakeLock()?.isHeld == true)
        sampler.stop()
        session.stop()
    }
}
