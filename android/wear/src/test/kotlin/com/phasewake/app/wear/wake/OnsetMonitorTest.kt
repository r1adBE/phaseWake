package com.phasewake.app.wear.wake

import android.app.AlarmManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Looper
import android.os.SystemClock
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.SleepOnset
import com.phasewake.app.wear.QuietWear
import com.phasewake.app.wear.schedule.AutoOnset
import java.time.Duration
import java.time.Instant
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorBuilder
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowPowerManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Einschlaf-Messung (Akku-Befund 03.11.2026): Regel in Stichproben, Takt der Alarme, Stichprobe mit kurzem Wakelock,
 * Zeitgrenze (höchstens 2 Std., bei Unruhe verlängert bis höchstens 4 Std., Befund 09.11.2026) und Zeilen im
 * Diagnose-Protokoll.
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
        extendCap: Instant? = null,
        onOnset: (Instant) -> Unit = {},
    ): OnsetMonitor = OnsetMonitor(
        context, 0.002, latestGiveUp, maxEnd, "auto onset", { kind, text -> logged += kind to text }, onGiveUp,
        extendCap = extendCap, now = { clock }, onOnset = onOnset,
    ).also { monitor = it }

    /** Gestellte Alarme der Messung (nach elapsedRealtime). */
    private fun ticks() = alarms.scheduledAlarms.filter { it.getType() == AlarmManager.ELAPSED_REALTIME_WAKEUP }

    private fun wakeLockHeld() = ShadowPowerManager.getLatestWakeLock()?.isHeld == true

    // --- Regel in Stichproben ---

    @Test
    fun allMeasuredNeedsTheLongerSampledStretch() {
        // Audit c3b8b01 E-4: Stichproben verlangen 25 Min (30 + 20 Epochen) statt 15; auch wenn alle Epochen gemessen sind.
        val still = List(50) { 0.001 }
        assertEquals(0, OnsetRules.find(still, List(50) { true }, 0.002))
        assertNull(OnsetRules.find(still.take(49), List(49) { true }, 0.002))
        assertEquals(0, SleepOnset.find(still.take(30), 0.002))
    }

    /** Stichproben wie in der Messung: alle 10 Epochen 2 gemessene, dazwischen ungemessen (leer). */
    private fun sampled(quiet: (Int) -> Double?, samples: Int, offset: Int = 3): Pair<List<Double?>, List<Boolean>> {
        val size = offset + samples * 10
        val measured = List(size) { it >= offset && (it - offset) % 10 < 2 }
        return List(size) { if (measured[it]) quiet(it) else null } to measured
    }

    @Test
    fun samplesSpanningTwentyFiveMinutesAreAsleep() {
        // Sechs ruhige Stichproben: von der ersten (Epoche 3) bis zur letzten (Epoche 54) über 50 Epochen (E-4).
        val (motions, measured) = sampled({ 0.001 }, samples = 6)
        assertEquals(3, OnsetRules.find(motions, measured, 0.002))
        // Fünf reichen nicht mehr (nur 42 Epochen; früher genügten vier über 30).
        val (five, measuredFive) = sampled({ 0.001 }, samples = 5)
        assertNull(OnsetRules.find(five, measuredFive, 0.002))
    }

    @Test
    fun oneSingleTwitchIsTolerated() {
        // E-4: Ein Zucker in einer Epoche der zweiten Stichprobe (Epoche 14, Epoche 13 ruhig) unterbricht nicht.
        val (twitch, m1) = sampled({ if (it == 14) 0.01 else 0.001 }, samples = 6)
        assertEquals(3, OnsetRules.find(twitch, m1, 0.002))
        // Ein zweiter Zucker (Epoche 34) beginnt von vorn: ab der Stichprobe nach dem ersten (Epoche 23).
        val (two, m2) = sampled({ if (it == 14 || it == 34) 0.01 else 0.001 }, samples = 9)
        assertEquals(23, OnsetRules.find(two, m2, 0.002))
    }

    @Test
    fun movementEmptyEpochOrLongGapStartsOver() {
        // Bewegung in beiden Epochen der zweiten Stichprobe: Strecke beginnt bei der dritten (Epoche 23).
        val (moved, m1) = sampled({ if (it == 13 || it == 14) 0.01 else 0.001 }, samples = 8)
        assertEquals(23, OnsetRules.find(moved, m1, 0.002))
        // Gemessen, aber leer (zu wenig Werte): ebenso.
        val (empty, m2) = sampled({ if (it == 14) null else 0.001 }, samples = 8)
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
        val lead = OnsetMonitor.SAMPLE_LEAD_MS * 1_000_000L
        for (now in listOf(start, start + 1, start + epoch / 2, start + 7 * epoch + 123)) {
            val tick = OnsetRules.nextTick(start, now)
            assertTrue(tick >= now + OnsetMonitor.TICK_INTERVAL.toNanos() - epoch / 2 - lead, "5 Min, höchstens eine halbe Epoche früher")
            assertTrue(tick <= now + OnsetMonitor.TICK_INTERVAL.toNanos() + epoch / 2, "höchstens eine halbe Epoche später")
            assertEquals(0L, (tick + lead - start) % epoch, "kurz vor einer Epochengrenze")
        }
        // Erster Alarm nach dem Start: Stichprobe ab Epoche 10, nicht 11.
        assertEquals(start + 10 * epoch - lead, OnsetRules.nextTick(start, start + 1_000_000L))
        assertEquals(0L, OnsetRules.firstFullEpoch(start, start))
        assertEquals(1L, OnsetRules.firstFullEpoch(start, start + 1))
        assertEquals(1L, OnsetRules.firstFullEpoch(start, start + epoch))
    }

    @Test
    fun lateAlarmsKeepTenEpochsBetweenSamples() {
        // Audit c3b8b01 E-3: Jeder Alarm kommt etwas zu spät (0 bis 1,9 s): Die Stichproben liegen trotzdem genau
        // 10 Epochen auseinander (früher 11, also alle 5½ Min, und 3 Std. ergaben 65 statt 72 gemessene Epochen).
        val start = 1_000_000_000L
        val epoch = OnsetMonitor.EPOCH_NANOS
        var now = start + 3_000_000L
        val samples = mutableListOf<Long>()
        repeat(36) { i ->
            val tick = OnsetRules.nextTick(start, now)
            now = tick + (i % 20) * 100_000_000L
            samples += OnsetRules.firstFullEpoch(start, now)
        }
        assertEquals((1..36).map { it * 10L }, samples)
        assertEquals(OnsetRules.TICK_EPOCHS, 10L)
    }

    @Test
    fun epochsOnTheChargerAreEmpty() {
        // Audit c3b8b01 E-5: Netzteil an bei 95 s, ab bei 200 s (Beginn 1 s): Epochen 3 bis 6 berühren die Zeit am Ladegerät.
        val start = 1_000_000_000L
        val second = 1_000_000_000L
        val times: List<Pair<Long, Long?>> = listOf(Pair(start + 95 * second, start + 200 * second))
        assertEquals((0L..8L).map { it in 3L..6L }, (0L..8L).map { OnsetRules.charging(it, start, times) })
        // Noch angeschlossen: alle Epochen ab dem Anschliessen.
        assertTrue(OnsetRules.charging(1_000L, start, listOf(start + 95 * second to null)))
        assertFalse(OnsetRules.charging(2L, start, listOf(start + 95 * second to null)))
        // Leer heisst: Die ruhige Strecke beginnt erst nach dem Ladegerät (wie eine leere Epoche).
        val motions = List(60) { if (it in 3..6) null else 0.001 }
        assertEquals(7, SleepOnset.find(motions, 0.002))
    }

    @Test
    fun offBodySensorMarksTheWatchAsNotWorn() {
        // Entscheid r1AD 14.11.2026 (E-5): Off-Body-Sensor 0 = nicht am Handgelenk, 1 = getragen; Zeiten wie am Ladegerät.
        assertEquals(true, OnsetRules.offBody(0f))
        assertEquals(false, OnsetRules.offBody(1f))
        assertNull(OnsetRules.offBody(null))
        assertNull(OnsetRules.offBody(0.5f))
        val offBody = SensorBuilder.newBuilder().setType(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT).build()
        sensors.addSensor(offBody)
        val m = monitor()
        m.start()
        assertEquals(1, sensors.listeners.size, "Off-Body-Sensor angemeldet")
        fun send(value: Float) {
            sensors.sendSensorEventToListeners(
                SensorEventBuilder.newBuilder(offBody, floatArrayOf(value)).setTimestamp(SystemClock.elapsedRealtimeNanos()).build(),
            )
            idle(10)
        }
        send(1f)
        send(0f)
        send(0f)
        send(1f)
        assertEquals(
            listOf(DiagKind.BATTERY to "auto onset not worn, waiting", DiagKind.BATTERY to "auto onset worn again"),
            logged.filter { it.first == DiagKind.BATTERY },
        )
        m.stop("test end")
        assertTrue(sensors.listeners.isEmpty(), "beim Ende abgemeldet")
    }

    @Test
    fun withoutOffBodySensorOnlyTheChargerCounts() {
        val m = monitor()
        m.start()
        assertTrue(sensors.listeners.isEmpty())
        assertTrue(logged.none { it.second.contains("worn") })
    }

    @Test
    fun chargerIsLogged() {
        val m = monitor()
        m.start()
        context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_POWER_CONNECTED))
        idle(10)
        context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_POWER_DISCONNECTED))
        idle(10)
        assertTrue(DiagKind.BATTERY to "auto onset charging, waiting" in logged, logged.toString())
        assertTrue(DiagKind.BATTERY to "auto onset charging ended" in logged, logged.toString())
    }

    @Test
    fun sampleModeWithoutWakeUpSensorHoldsOnlyShortWakeLocks() {
        val accelerometer = SensorBuilder.newBuilder().setType(Sensor.TYPE_ACCELEROMETER).build()
        sensors.addSensor(accelerometer)
        val m = monitor()
        m.start()
        assertFalse(m.continuous)
        assertEquals(DiagKind.MEASURE_START to "auto onset sensor wake=0 fifo=-1 mode=sample", logged.single())
        // Kein Sensor und kein Wakelock bis zum ersten Alarm, der steht kurz vor Epoche 10 (5 Min, E-3).
        assertTrue(sensors.listeners.isEmpty(), "Sensor erst in der Stichprobe")
        assertFalse(wakeLockHeld())
        val first = ticks().single()
        assertTrue(first.isAllowWhileIdle, "auch im Ruhezustand")
        val now = SystemClock.elapsedRealtime()
        assertTrue(first.triggerAtMs in now + 290_000..now + 300_000, "${first.triggerAtMs - now}")

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
        val accelerometer = SensorBuilder.newBuilder().setType(Sensor.TYPE_ACCELEROMETER).build()
        sensors.addSensor(accelerometer)
        val m = monitor()
        m.start()
        idle(ticks().single().triggerAtMs - SystemClock.elapsedRealtime())
        m.tick()
        // 25 Hz über die ganze Stichprobe (ruhig, kleine Schwankung unter dem Rauschen).
        val until = SystemClock.elapsedRealtime() + OnsetMonitor.SAMPLE_LEAD_MS + 60_000
        var sent = 0
        while (SystemClock.elapsedRealtime() < until) {
            val event = SensorEventBuilder.newBuilder(accelerometer, floatArrayOf(if (sent % 2 == 0) 0.01f else 0f, 0f, 9.81f))
                .setTimestamp(SystemClock.elapsedRealtimeNanos())
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
        val line = OnsetRules.statLine("auto onset", false, 1500, 6, 1, 0.0234, 0.031, 0.05, 0.02, 186, 43)
        assertEquals("auto onset stat wake=0 samples=1500 epochs=6 empty=1 motion=0.0234/0.0310 thr=0.0500 floor=0.0200 wl=186s bat=43", line)
        assertEquals(
            "onset stat wake=1 samples=0 epochs=0 empty=0 motion=-/- thr=0.0020 floor=- wl=0s bat=-",
            OnsetRules.statLine("onset", true, 0, 0, 0, null, null, 0.002, null, 0, null),
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

    // --- Verlängerung bei spätem Einschlafen (Befund 09.11.2026) ---

    @Test
    fun restlessLooksAtTheLastFifteenMinutesOnly() {
        val quiet = List(40) { 0.001 }
        assertFalse(OnsetRules.restless(quiet, List(40) { true }, 0.1))
        // Bewegung vor 31 Epochen zählt nicht mehr, vor 30 schon.
        assertFalse(OnsetRules.restless(quiet.mapIndexed { i, m -> if (i == 9) 0.2 else m }, List(40) { true }, 0.1))
        assertTrue(OnsetRules.restless(quiet.mapIndexed { i, m -> if (i == 10) 0.2 else m }, List(40) { true }, 0.1))
        // Ungemessen oder leer: nicht unruhig.
        assertFalse(OnsetRules.restless(quiet.mapIndexed { i, m -> if (i == 39) 0.2 else m }, List(40) { it != 39 }, 0.1))
        assertFalse(OnsetRules.restless(quiet.mapIndexed { i, m -> if (i == 39) null else m }, List(40) { true }, 0.1))
    }

    @Test
    fun extendUntilFollowsUnrestCapAlarmAndBattery() {
        val start = clock
        val max = start.plus(OnsetMonitor.MAX_DURATION)
        val alarmLimit = start.plus(Duration.ofHours(6))
        val cap = start.plus(OnsetMonitor.MAX_EXTENDED)
        val at = max.plusSeconds(10)
        assertEquals(at.plus(OnsetMonitor.EXTEND_STEP), OnsetRules.extendUntil(at, max, alarmLimit, cap, true, 80))
        // Vor der Höchstdauer, ruhig oder wenig Akku: keine Verlängerung; unbekannter Akku hindert nicht.
        assertNull(OnsetRules.extendUntil(max.minusSeconds(1), max, alarmLimit, cap, true, 80))
        assertNull(OnsetRules.extendUntil(at, max, alarmLimit, cap, false, 80))
        assertNull(OnsetRules.extendUntil(at, max, alarmLimit, cap, true, AutoOnset.MIN_BATTERY_PERCENT - 1))
        assertEquals(at.plus(OnsetMonitor.EXTEND_STEP), OnsetRules.extendUntil(at, max, alarmLimit, cap, true, null))
        // Höchstens bis zur Grenze (4 Std. bzw. Beginn des Weckfensters), danach aufgeben.
        assertEquals(cap, OnsetRules.extendUntil(cap.minusSeconds(60), cap.minusSeconds(60), alarmLimit, cap, true, 80))
        assertNull(OnsetRules.extendUntil(cap, cap, alarmLimit, cap, true, 80))
        // Grenze vor der Weckzeit erreicht (nicht die Höchstdauer): wie bisher aufgeben.
        assertNull(OnsetRules.extendUntil(at, max, max.minusSeconds(60), cap, true, 80))
    }

    /** Durchgehende Messung (aufweckender Sensor mit grossem Puffer) mit [epochs] unruhigen Epochen ab dem Beginn. */
    private fun restlessMonitor(epochs: Int, extendCap: Instant, onGiveUp: () -> Unit): OnsetMonitor {
        val wakeUp = SensorBuilder.newBuilder().setType(Sensor.TYPE_ACCELEROMETER).setWakeUpFlag(true)
            .setFifoMaxEventCount(SensorSession.MIN_WAKE_UP_FIFO).build()
        sensors.addSensor(wakeUp)
        val m = monitor(onGiveUp = onGiveUp, extendCap = extendCap)
        m.start()
        assertTrue(m.continuous)
        val t0 = SystemClock.elapsedRealtimeNanos()
        // 25 Hz, abwechselnd still und 1 m/s² daneben; ein Wert in der Epoche danach schliesst die letzte ab.
        val count = epochs * 750 + 1
        for (k in 0 until count) {
            val event = SensorEventBuilder.newBuilder(wakeUp, floatArrayOf(if (k % 2 == 0) 1f else 0f, 0f, 9.81f))
                .setTimestamp(t0 + k * 40_000_000L)
                .build()
            sensors.sendSensorEventToListeners(event)
        }
        return m
    }

    @Test
    fun restlessAtTheMaxDurationExtendsUpToTheCap() {
        battery(80)
        var gaveUp = 0
        val start = clock
        val cap = start.plus(OnsetMonitor.MAX_DURATION).plus(Duration.ofMinutes(20))
        val m = restlessMonitor(epochs = 3, extendCap = cap, onGiveUp = { gaveUp++ })
        clock = start.plus(OnsetMonitor.MAX_DURATION)
        m.tick()
        assertEquals(0, gaveUp)
        val first = clock.plus(OnsetMonitor.EXTEND_STEP)
        assertEquals(first, m.giveUpAt)
        assertEquals(DiagKind.MEASURE_START to "auto onset extended until $first", logged.last { it.first == DiagKind.MEASURE_START })
        // Zweite Verlängerung nur noch bis zur Grenze, dann aufgeben; das Ende behält seinen Grund.
        clock = first
        m.checkLimit()
        assertEquals(cap, m.giveUpAt)
        clock = cap
        m.tick()
        assertEquals(1, gaveUp)
        m.stop(m.giveUpReason(clock))
        assertEquals(DiagKind.MEASURE_END to "auto onset give up (max duration)", logged.last())
    }

    @Test
    fun lowBatteryOrQuietDoesNotExtend() {
        battery(AutoOnset.MIN_BATTERY_PERCENT - 1)
        var gaveUp = 0
        val start = clock
        val m = restlessMonitor(epochs = 3, extendCap = start.plus(OnsetMonitor.MAX_EXTENDED), onGiveUp = { gaveUp++ })
        clock = start.plus(OnsetMonitor.MAX_DURATION)
        m.tick()
        assertEquals(1, gaveUp)
        assertEquals(DiagKind.BATTERY to "auto onset not extended: battery ${AutoOnset.MIN_BATTERY_PERCENT - 1}", logged.last())
        assertTrue(logged.none { it.second.contains("extended until") })

        // Ruhig (keine Werte): aufgeben wie bisher, ohne Zeile zur Verlängerung.
        m.stop("test end")
        logged.clear()
        battery(80)
        var quietGaveUp = 0
        clock = start
        val quiet = monitor(onGiveUp = { quietGaveUp++ }, extendCap = start.plus(OnsetMonitor.MAX_EXTENDED))
        quiet.start()
        clock = start.plus(OnsetMonitor.MAX_DURATION)
        quiet.tick()
        assertEquals(1, quietGaveUp)
        assertTrue(logged.none { it.second.contains("extended") })
    }

    private fun battery(percent: Int) =
        shadowOf(context.getSystemService(BatteryManager::class.java)).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, percent)
}
