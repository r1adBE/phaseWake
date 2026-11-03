package com.phasewake.app.wear.wake

import android.app.AlarmManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import android.os.SystemClock
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.SleepOnset
import com.phasewake.app.wear.QuietWear
import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.SensorEventBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Einschlaf-Messung (Akku-Befund 03.11.2026): Regel in Stichproben, Takt der Alarme, Stichprobe mit kurzem Wakelock,
 * Zeitgrenze (höchstens 2 Std.) und Zeilen im Diagnose-Protokoll.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietWear::class)
class OnsetMonitorTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val sensors get() = shadowOf(context.getSystemService(SensorManager::class.java))
    private val logged = mutableListOf<Pair<DiagKind, String>>()
    private var monitor: OnsetMonitor? = null
    private var clock = Instant.parse("2026-11-03T21:00:00Z")

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ShadowPowerManager.clearWakeLocks()
    }

    @After
    fun tearDown() {
        monitor?.stop("test end")
    }

    private fun idle(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    private fun monitor(
        latestGiveUp: Instant = clock.plus(Duration.ofHours(8)),
        maxEnd: Instant = clock.plus(OnsetMonitor.MAX_DURATION),
        onGiveUp: () -> Unit = {},
        onOnset: (Instant) -> Unit = {},
    ): OnsetMonitor = OnsetMonitor(
        context, 0.002, latestGiveUp, maxEnd, "auto onset", { kind, text -> logged += kind to text }, onGiveUp, { clock }, onOnset,
    ).also { monitor = it }

    /** Gestellte Alarme der Messung (nach elapsedRealtime). */
    private fun ticks() = alarms.scheduledAlarms.filter { it.getType() == AlarmManager.ELAPSED_REALTIME_WAKEUP }

    private fun wakeLockHeld() = ShadowPowerManager.getLatestWakeLock()?.isHeld == true

    // --- Regel in Stichproben ---

    @Test
    fun allMeasuredIsTheRuleOfSection6() {
        val random = Random(42)
        repeat(500) {
            val motions = List(random.nextInt(0, 120)) {
                when (random.nextInt(10)) {
                    0 -> null
                    1 -> 0.01
                    else -> 0.001
                }
            }
            assertEquals(SleepOnset.find(motions, 0.002), OnsetRules.find(motions, List(motions.size) { true }, 0.002))
        }
    }

    /** Stichproben wie in der Messung: alle 10 Epochen 2 gemessene, dazwischen ungemessen (leer). */
    private fun sampled(quiet: (Int) -> Double?, samples: Int, offset: Int = 3): Pair<List<Double?>, List<Boolean>> {
        val size = offset + samples * 10
        val measured = List(size) { it >= offset && (it - offset) % 10 < 2 }
        return List(size) { if (measured[it]) quiet(it) else null } to measured
    }

    @Test
    fun samplesSpanningFifteenMinutesAreAsleep() {
        // Vier ruhige Stichproben: von der ersten (Epoche 3) bis zur letzten (Epoche 34) über 30 Epochen.
        val (motions, measured) = sampled({ 0.001 }, samples = 4)
        assertEquals(3, OnsetRules.find(motions, measured, 0.002))
        // Drei reichen nicht (nur 22 Epochen).
        val (three, measuredThree) = sampled({ 0.001 }, samples = 3)
        assertNull(OnsetRules.find(three, measuredThree, 0.002))
    }

    @Test
    fun movementEmptyEpochOrLongGapStartsOver() {
        // Bewegung in der zweiten Stichprobe: Strecke beginnt bei der dritten (Epoche 23).
        val (moved, m1) = sampled({ if (it == 14) 0.01 else 0.001 }, samples = 6)
        assertEquals(23, OnsetRules.find(moved, m1, 0.002))
        // Gemessen, aber leer (zu wenig Werte): ebenso.
        val (empty, m2) = sampled({ if (it == 14) null else 0.001 }, samples = 6)
        assertEquals(23, OnsetRules.find(empty, m2, 0.002))
        // Zwei verpasste Alarme (Lücke 28 Epochen): kein Einschlafen über die Lücke hinweg.
        val measured = List(80) { it in 0..1 || it in 30..31 || it in 40..41 || it in 50..51 }
        val motions = List(80) { if (measured[it]) 0.001 else null }
        assertNull(OnsetRules.find(motions, measured, 0.002))
    }

    // --- Takt ---

    @Test
    fun ticksLeadAnEpochBoundaryAtLeastFiveMinutesAhead() {
        val start = 1_000_000_000L
        val epoch = OnsetMonitor.EPOCH_NANOS
        for (now in listOf(start, start + 1, start + epoch / 2, start + 7 * epoch + 123)) {
            val tick = OnsetRules.nextTick(start, now)
            val lead = OnsetMonitor.SAMPLE_LEAD_MS * 1_000_000L
            assertTrue(tick >= now + OnsetMonitor.TICK_INTERVAL.toNanos(), "frühestens 5 Min später")
            assertTrue(tick < now + OnsetMonitor.TICK_INTERVAL.toNanos() + epoch, "höchstens eine Epoche mehr")
            assertEquals(0L, (tick + lead - start) % epoch, "kurz vor einer Epochengrenze")
        }
        assertEquals(0L, OnsetRules.firstFullEpoch(start, start))
        assertEquals(1L, OnsetRules.firstFullEpoch(start, start + 1))
        assertEquals(1L, OnsetRules.firstFullEpoch(start, start + epoch))
    }

    @Test
    fun sampleModeWithoutWakeUpSensorHoldsOnlyShortWakeLocks() {
        val accelerometer = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER)
        sensors.addSensor(accelerometer)
        val m = monitor()
        m.start()
        assertFalse(m.continuous)
        assertEquals(DiagKind.MEASURE_START to "auto onset sensor wake=0 fifo=-1 mode=sample", logged.single())
        // Kein Sensor und kein Wakelock bis zum ersten Alarm, der steht in höchstens 5½ Min.
        assertTrue(sensors.listeners.isEmpty(), "Sensor erst in der Stichprobe")
        assertFalse(wakeLockHeld())
        val first = ticks().single()
        assertTrue(first.isAllowWhileIdle, "auch im Ruhezustand")
        val now = SystemClock.elapsedRealtime()
        assertTrue(first.triggerAtMs in now + 300_000..now + 330_000, "${first.triggerAtMs - now}")

        // Alarm: Stichprobe mit Sensor und kurzem Wakelock, nächster Alarm gestellt.
        idle(first.triggerAtMs - SystemClock.elapsedRealtime())
        m.tick()
        assertEquals(1, sensors.listeners.size)
        assertTrue(wakeLockHeld())
        val next = ticks().single()
        assertTrue(next.triggerAtMs >= first.triggerAtMs + 300_000)

        // Nach zwei Epochen (gut 60 s) ist alles wieder aus.
        idle(OnsetMonitor.SAMPLE_LEAD_MS + 60_000 + OnsetMonitor.SAMPLE_MARGIN_MS + 10)
        assertTrue(sensors.listeners.isEmpty(), "Sensor abgemeldet")
        assertFalse(wakeLockHeld(), "Wakelock gelöst")
    }

    @Test
    fun sampledEpochsGiveTheDiagLine() {
        val accelerometer = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER)
        sensors.addSensor(accelerometer)
        val m = monitor()
        m.start()
        idle(ticks().single().triggerAtMs - SystemClock.elapsedRealtime())
        m.tick()
        // 25 Hz über die ganze Stichprobe (ruhig, kleine Schwankung unter dem Rauschen).
        val until = SystemClock.elapsedRealtime() + OnsetMonitor.SAMPLE_LEAD_MS + 60_000
        var sent = 0
        while (SystemClock.elapsedRealtime() < until) {
            val event = SensorEventBuilder.newBuilder().setSensor(accelerometer)
                .setTimestamp(SystemClock.elapsedRealtimeNanos())
                .setValues(floatArrayOf(if (sent % 2 == 0) 0.01f else 0f, 0f, 9.81f))
                .build()
            sensors.sendSensorEventToListeners(event)
            sent++
            idle(40)
        }
        idle(OnsetMonitor.SAMPLE_MARGIN_MS + 10)
        // Bis 15 Min nach dem Start weitere Alarme ohne Werte, dann die Zeile.
        while (logged.none { it.first == DiagKind.BATTERY }) {
            idle(ticks().single().triggerAtMs - SystemClock.elapsedRealtime())
            m.tick()
            idle(OnsetMonitor.SAMPLE_LEAD_MS + 60_000 + OnsetMonitor.SAMPLE_MARGIN_MS + 10)
        }
        val line = logged.single { it.first == DiagKind.BATTERY }.second
        // Zwei Epochen je Stichprobe: in der ersten Werte, in den folgenden (ohne Werte) leer.
        val samples = Regex("samples=(\\d+)").find(line)?.groupValues?.get(1)?.toInt()
        assertEquals(sent, samples, line)
        assertTrue(line.startsWith("auto onset stat wake=0 samples=$sent epochs="), line)
        val epochs = assertNotNull(Regex("epochs=(\\d+) empty=(\\d+)").find(line)).groupValues
        assertEquals(epochs[1].toInt() - 2, epochs[2].toInt(), "nur die erste Stichprobe hat Werte: $line")
        assertTrue(line.contains("motion=0.0000/0.0000 thr=0.0020"), line)
        assertTrue(Regex("wl=(\\d+)s").find(line)!!.groupValues[1].toInt() in 60..80 * 3, line)
    }

    @Test
    fun statLineIsCompact() {
        val line = OnsetRules.statLine("auto onset", false, 1500, 6, 1, 0.00123, 0.031, 0.002, 186, 43)
        assertEquals("auto onset stat wake=0 samples=1500 epochs=6 empty=1 motion=0.0012/0.0310 thr=0.0020 wl=186s bat=43", line)
        assertEquals(
            "onset stat wake=1 samples=0 epochs=0 empty=0 motion=-/- thr=0.0020 wl=0s bat=-",
            OnsetRules.statLine("onset", true, 0, 0, 0, null, null, 0.002, 0, null),
        )
    }

    // --- Zeitgrenze ---

    @Test
    fun giveUpReasonNamesTheMaxDuration() {
        val latest = clock.plus(Duration.ofHours(8))
        val max = clock.plus(OnsetMonitor.MAX_DURATION)
        assertNull(OnsetRules.giveUpReason(max.minusSeconds(1), latest, max))
        assertEquals("give up (max duration)", OnsetRules.giveUpReason(max, latest, max))
        // Weckzeit näher als 2 Std.: gewöhnliches Aufgeben 15 Min vor ihr.
        val near = clock.plus(Duration.ofMinutes(90))
        assertEquals("give up", OnsetRules.giveUpReason(near, near, max))
    }

    @Test
    fun maxDurationEndsTheMeasurementAtTheNextTick() {
        var gaveUp = 0
        val m = monitor(onGiveUp = { gaveUp++ })
        m.start()
        assertEquals(clock.plus(OnsetMonitor.MAX_DURATION), m.giveUpAt)
        m.tick()
        assertEquals(0, gaveUp)
        clock = clock.plus(OnsetMonitor.MAX_DURATION)
        m.tick()
        assertEquals(1, gaveUp)
        // So beendet «Gute Nacht» die Messung ([stopGoodNight]): Ende mit Grund im Protokoll, Alarm aufgehoben.
        m.stop(m.giveUpReason(clock))
        assertEquals(DiagKind.MEASURE_END to "auto onset give up (max duration)", logged.last())
        assertEquals(DiagKind.BATTERY, logged[logged.size - 2].first, "letzte Kennzahlen vor dem Ende")
        assertTrue(ticks().isEmpty())
        assertFalse(wakeLockHeld())
        m.tick()
        assertEquals(1, gaveUp, "nach dem Ende nichts mehr")
    }
}
