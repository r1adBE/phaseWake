package com.phasewake.app.wear.wake

import android.app.AlarmManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Looper
import android.os.SystemClock
import com.phasewake.app.core.DiagKind
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
 * Einschlaf-Messung (Akku-Befund 03.11.2026): Stichprobe mit kurzem Wakelock, Zeitgrenze (höchstens 2 Std., bei Unruhe
 * verlängert bis höchstens 4 Std., Befund 09.11.2026) und Zeilen im Diagnose-Protokoll. Die reinen Regeln (Stichproben,
 * Takt, Verlängerung) prüft [OnsetMonitorRulesTest].
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

    // --- Tragen, Ladegerät und Stichproben der Messung (Regeln: [OnsetMonitorRulesTest]) ---

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

    // --- Zeitgrenze ---

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

    @Test
    fun timeOnTheChargerMovesTheLimit() {
        // Prüfrunde 2 HG-3: 60 Min am Ladegerät verschieben die Grenze von 2 Std. um 60 Min (höchstens bis zur Grenze der
        // Verlängerungen), auch ohne Unruhe. Vorher gab die Messung nach 2 Std. auf.
        battery(80)
        var gaveUp = 0
        val start = clock
        val m = monitor(onGiveUp = { gaveUp++ }, extendCap = start.plus(OnsetMonitor.MAX_EXTENDED))
        m.start()
        context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_POWER_CONNECTED))
        idle(Duration.ofMinutes(60).toMillis())
        context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_POWER_DISCONNECTED))
        idle(10)
        clock = start.plus(OnsetMonitor.MAX_DURATION)
        m.tick()
        assertEquals(0, gaveUp)
        val until = m.giveUpAt
        assertTrue(until >= clock.plus(Duration.ofMinutes(60)) && until <= clock.plus(Duration.ofMinutes(61)), "$until")
        assertTrue(logged.last { it.first == DiagKind.MEASURE_START }.second.endsWith("(not worn)"), logged.toString())
        // Danach ohne weitere Zeit am Ladegerät und ruhig: aufgeben.
        clock = until
        m.tick()
        assertEquals(1, gaveUp)
        // Höchstens bis zur Grenze der Verlängerungen.
        val base = start.plus(OnsetMonitor.MAX_DURATION)
        val late = start.plus(Duration.ofHours(8))
        assertEquals(start.plus(Duration.ofMinutes(150)), OnsetRules.awayUntil(base, Duration.ofHours(1), late, start.plus(Duration.ofMinutes(150))))
        assertNull(OnsetRules.awayUntil(base, Duration.ZERO, late, start.plus(OnsetMonitor.MAX_EXTENDED)))
        // Ladegerät und Off-Body überlappend zählen einmal; ein offenes Ende bis jetzt.
        assertEquals(30L, OnsetRules.awayNanos(listOf(0L to 20L, 10L to 30L), 0L, 100L))
        assertEquals(30L, OnsetRules.awayNanos(listOf(50L to null), 0L, 80L))
        // Gilt die Grenze vor der Weckzeit früher, verschiebt nichts.
        assertNull(OnsetRules.awayUntil(base, Duration.ofHours(1), base.minusSeconds(1), start.plus(OnsetMonitor.MAX_EXTENDED)))
    }

    @Test
    fun unpluggedWithLowBatteryEndsTheAutomaticMeasurement() {
        // F-6: Trotz wenig Akku begonnen, weil die Uhr lud; vom Ladegerät mit weiter zu wenig Akku: Ende (einmal).
        var low = 0
        val m = OnsetMonitor(
            context, 0.002, clock.plus(Duration.ofHours(8)), clock.plus(OnsetMonitor.MAX_DURATION), "auto onset", { _, _ -> }, {},
            onLowBattery = { low++ }, now = { clock },
        ) {}.also { monitor = it }
        m.start()
        battery(AutoOnset.MIN_BATTERY_PERCENT - 5)
        context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_POWER_CONNECTED))
        idle(10)
        assertEquals(0, low)
        context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_POWER_DISCONNECTED))
        idle(10)
        assertEquals(1, low)
        // Genug geladen: weiter messen.
        m.stop("test end")
        var enough = 0
        val n = OnsetMonitor(
            context, 0.002, clock.plus(Duration.ofHours(8)), clock.plus(OnsetMonitor.MAX_DURATION), "auto onset", { _, _ -> }, {},
            onLowBattery = { enough++ }, now = { clock },
        ) {}.also { monitor = it }
        n.start()
        battery(80)
        context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_POWER_CONNECTED))
        idle(10)
        context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_POWER_DISCONNECTED))
        idle(10)
        assertEquals(0, enough)
    }

    private fun battery(percent: Int) =
        shadowOf(context.getSystemService(BatteryManager::class.java)).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, percent)
}
