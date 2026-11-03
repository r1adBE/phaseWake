package com.phasewake.app.wear.wake

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.phasewake.app.core.DetectorConfig
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.Epoch
import com.phasewake.app.core.SleepOnset
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Wartet nach «Gute Nacht» bzw. in der automatischen Einschlaf-Messung stromsparend auf das Einschlafen (Regel,
 * Abschnitt 6) und meldet die Einschlafzeit genau einmal.
 *
 * Zwei Arten zu messen ([SensorSession.continuous]):
 * - **Durchgehend**: aufweckender Sensor mit grossem Puffer, ohne Wakelock; Regel [SleepOnset.find] über alle Epochen.
 * - **Stichproben**: sonst. Alle 5 Minuten weckt ein Alarm die Uhr, sie misst [SAMPLE_EPOCHS] volle Epochen unter
 *   einem kurzen Wakelock und schläft wieder ([OnsetRules.find]). Nie ein Wakelock über die ganze Messung.
 *
 * Derselbe Alarm prüft die Zeitgrenze (ein Zeitgeber steht im Tiefschlaf still) und schreibt alle 15 Minuten eine
 * Zeile ins Diagnose-Protokoll ([OnsetRules.statLine]); das Ende der Messung steht immer im Protokoll ([stop]).
 *
 * @param latestGiveUp 15 Min vor der eingestellten Weckzeit.
 * @param maxEnd Beginn der Messung + [MAX_DURATION]. Ab dem früheren der beiden: [onGiveUp] statt [onOnset].
 * @param label «auto onset» oder «onset»: Anfang der Zeilen im Diagnose-Protokoll.
 */
class OnsetMonitor(
    private val context: Context,
    private val threshold: Double,
    private val latestGiveUp: Instant,
    private val maxEnd: Instant,
    private val label: String,
    private val log: (DiagKind, String) -> Unit,
    private val onGiveUp: () -> Unit,
    private val now: () -> Instant = Instant::now,
    private val onOnset: (Instant) -> Unit,
) {
    /** Ab dann wird nicht mehr gewartet. */
    val giveUpAt: Instant = minOf(latestGiveUp, maxEnd)

    private val handler = Handler(Looper.getMainLooper())
    private var started: Instant = now()
    private var startNanos = 0L
    private val motions = mutableListOf<Double?>()
    private val measured = mutableListOf<Boolean>()
    private var session: SensorSession? = null
    private var reported = false
    private var ended = false

    /** Epochen der laufenden bzw. letzten Stichprobe: [sampleFrom] einschliesslich bis [sampleUntil] ausschliesslich. */
    private var sampleFrom = 0L
    private var sampleUntil = 0L
    private var sampling = false
    private var sampleWakeLock: PowerManager.WakeLock? = null
    private var wakeLockSince = 0L

    private val period = Period()

    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = tick()
    }
    private val endSample = Runnable { endSample() }

    /** Ob gerade durchgehend gemessen wird (sonst Stichproben). */
    val continuous: Boolean get() = session?.continuous ?: false

    fun start() {
        if (session != null || ended) return
        started = now()
        startNanos = SystemClock.elapsedRealtimeNanos()
        val s = SensorSession(context, startNanos, SystemClock::elapsedRealtimeNanos, lowPower = true) { epochs -> onEpochs(epochs) }
        session = s
        period.reset(SystemClock.elapsedRealtime(), 0L)
        log(DiagKind.MEASURE_START, "$label sensor wake=${if (s.continuous) 1 else 0} fifo=${s.wakeUpFifo} mode=${if (s.continuous) "continuous" else "sample"}")
        s.start(listen = s.continuous)
        ContextCompat.registerReceiver(context, tickReceiver, IntentFilter(ACTION_TICK), ContextCompat.RECEIVER_NOT_EXPORTED)
        scheduleTick()
    }

    /**
     * Beendet die Messung und schreibt ihr Ende ins Diagnose-Protokoll: [reason], sonst «give up» (Zeitgrenze erreicht),
     * «window began» (der Weckdienst läuft weiter, das Fenster hat begonnen) oder «service ended».
     */
    fun stop(reason: String? = null) {
        if (ended) return
        ended = true
        val s = session ?: return
        val why = reason ?: giveUpReason(now()) ?: if (WakeService.running) "window began" else "service ended"
        handler.removeCallbacks(endSample)
        if (sampling) finishSample(close = false)
        logStat()
        log(DiagKind.MEASURE_END, "$label $why")
        context.getSystemService(AlarmManager::class.java).cancel(tickIntent())
        try {
            context.unregisterReceiver(tickReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Empfänger war nicht angemeldet", e)
        }
        s.stop()
        session = null
    }

    /** «give up» bzw. «give up (max duration)», wenn [at] die Zeitgrenze erreicht hat; sonst `null`. */
    fun giveUpReason(at: Instant): String? = OnsetRules.giveUpReason(at, latestGiveUp, maxEnd)

    private fun onEpochs(epochs: List<Epoch>) {
        val s = session ?: return
        if (reported) return
        if (!now().isBefore(giveUpAt)) {
            giveUp()
            return
        }
        val first = s.closedEpochs - epochs.size
        epochs.forEachIndexed { i, epoch ->
            val index = first + i
            val inSample = s.continuous || index in sampleFrom until sampleUntil
            motions += epoch.motion
            measured += inSample
            if (inSample) period.add(epoch.motion)
        }
        val index = if (s.continuous) SleepOnset.find(motions, threshold) else OnsetRules.find(motions, measured, threshold)
        if (index == null) return
        reported = true
        onOnset(started.plus(Duration.ofSeconds(index.toLong() * DetectorConfig.EPOCH_SECONDS)))
    }

    private fun giveUp() {
        if (reported) return
        reported = true
        onGiveUp()
    }

    /** Alle 5 Minuten (Alarm, weckt die Uhr): Zeitgrenze, Protokollzeile, Stichprobe, nächster Alarm. */
    internal fun tick() {
        val s = session ?: return
        if (ended || reported) return
        if (!now().isBefore(giveUpAt)) {
            giveUp()
            return
        }
        if (SystemClock.elapsedRealtime() - period.sinceMs >= STAT_INTERVAL.toMillis()) logStat()
        if (!s.continuous && !sampling) beginSample(s)
        scheduleTick()
    }

    private fun beginSample(s: SensorSession) {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        sampleFrom = OnsetRules.firstFullEpoch(startNanos, nowNanos)
        sampleUntil = sampleFrom + SAMPLE_EPOCHS
        val endNanos = startNanos + sampleUntil * EPOCH_NANOS
        val delayMs = (endNanos - nowNanos) / 1_000_000L + SAMPLE_MARGIN_MS
        sampling = true
        wakeLockSince = SystemClock.elapsedRealtime()
        sampleWakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phasewake:onset-sample")
            // Zeitlimit als Sicherung: Auch wenn das Ende ausbliebe, hält der Wakelock nicht länger als die Stichprobe.
            .apply { acquire(delayMs + WAKE_LOCK_SLACK_MS) }
        s.resume()
        handler.postDelayed(endSample, delayMs)
    }

    private fun endSample() {
        if (sampling) finishSample(close = true)
    }

    /** Stichprobe beenden: Epochen abschliessen ([close]), Sensor abmelden, Wakelock lösen. */
    private fun finishSample(close: Boolean) {
        val s = session
        if (close && s != null) s.closeUntil(startNanos + sampleUntil * EPOCH_NANOS)
        s?.pause()
        sampling = false
        period.wakeLockMs += SystemClock.elapsedRealtime() - wakeLockSince
        sampleWakeLock?.takeIf { it.isHeld }?.release()
        sampleWakeLock = null
    }

    private fun logStat() {
        val s = session ?: return
        val battery = context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        val ongoing = if (sampling) SystemClock.elapsedRealtime() - wakeLockSince else 0L
        log(
            DiagKind.BATTERY,
            OnsetRules.statLine(
                label, s.continuous, s.samples - period.samplesAtStart, period.epochs, period.empty, period.mean(), period.max,
                threshold, (period.wakeLockMs + ongoing) / 1_000L, battery,
            ),
        )
        if (sampling) wakeLockSince = SystemClock.elapsedRealtime()
        period.reset(SystemClock.elapsedRealtime(), s.samples)
    }

    private fun scheduleTick() {
        val at = OnsetRules.nextTick(startNanos, SystemClock.elapsedRealtimeNanos()) / 1_000_000L
        val alarms = context.getSystemService(AlarmManager::class.java)
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, tickIntent())
        } catch (e: SecurityException) {
            // Ohne exakte Alarme ungenau, aber auch im Ruhezustand der Uhr.
            Log.w(TAG, "Kein exakter Alarm für die Einschlaf-Messung", e)
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, tickIntent())
        }
    }

    private fun tickIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_TICK, Intent(ACTION_TICK).setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Kennzahlen seit der letzten Zeile im Diagnose-Protokoll (nur gemessene Epochen). */
    private class Period {
        var sinceMs = 0L
        var samplesAtStart = 0L
        var epochs = 0
        var empty = 0
        var sum = 0.0
        var count = 0
        var max: Double? = null
        var wakeLockMs = 0L

        fun add(motion: Double?) {
            epochs++
            if (motion == null) {
                empty++
                return
            }
            sum += motion
            count++
            max = maxOf(max ?: motion, motion)
        }

        fun mean(): Double? = if (count > 0) sum / count else null

        fun reset(nowMs: Long, samples: Long) {
            sinceMs = nowMs
            samplesAtStart = samples
            epochs = 0
            empty = 0
            sum = 0.0
            count = 0
            max = null
            wakeLockMs = 0L
        }
    }

    companion object {
        private const val TAG = "OnsetMonitor"

        /** Längste Einschlaf-Messung (automatisch und «Gute Nacht»), danach gilt die eingestellte Weckzeit. */
        val MAX_DURATION: Duration = Duration.ofHours(2)

        /** Abstand der Alarme: Zeitgrenze prüfen und (ohne durchgehende Messung) eine Stichprobe nehmen. */
        val TICK_INTERVAL: Duration = Duration.ofMinutes(5)

        /** Abstand der Zeilen im Diagnose-Protokoll. */
        val STAT_INTERVAL: Duration = Duration.ofMinutes(15)

        /** Volle Epochen je Stichprobe (60 s). */
        const val SAMPLE_EPOCHS = 2

        /** So lange vor einer Epochengrenze weckt der Alarm, damit der Sensor zu ihrem Beginn liefert. */
        const val SAMPLE_LEAD_MS = 2_000L

        /** Nach dem Ende der Stichprobe noch so lange auf letzte Werte warten. */
        const val SAMPLE_MARGIN_MS = 1_000L
        private const val WAKE_LOCK_SLACK_MS = 10_000L
        private const val REQUEST_TICK = 7
        internal const val ACTION_TICK = "com.phasewake.app.action.ONSET_TICK"
        internal const val EPOCH_NANOS = DetectorConfig.EPOCH_SECONDS * 1_000_000_000L
    }
}

/** Reine Regeln der Einschlaf-Messung in Stichproben, ohne Android (Tests: `OnsetRulesTest`). */
internal object OnsetRules {
    /**
     * Längste Lücke ungemessener Epochen (10 Min), die eine ruhige Strecke nicht unterbricht. Regulär liegen 8 dazwischen;
     * so übersteht eine Strecke einen verspäteten Alarm, nicht aber eine längere Pause.
     */
    const val MAX_GAP_EPOCHS = 20

    /**
     * Einschlafen in Stichproben: wie [SleepOnset.find], aber ungemessene Epochen ([measured] `false`) unterbrechen die
     * Strecke nicht und zählen nicht als ruhig. Die Strecke ruhiger gemessener Epochen (Bewegung vorhanden und unter
     * [threshold]) muss mindestens [SleepOnset.STILL_EPOCHS] Epochen (15 Min) umfassen, von der ersten bis zur letzten.
     * Eine gemessene leere oder bewegte Epoche oder eine Lücke über [MAX_GAP_EPOCHS] beginnt von vorn. Sind alle Epochen
     * gemessen, gilt genau [SleepOnset.find].
     * @return Index der ersten Epoche der Strecke, oder `null`.
     */
    fun find(motions: List<Double?>, measured: List<Boolean>, threshold: Double): Int? {
        var runStart = -1
        var lastMeasured = -1
        for ((index, motion) in motions.withIndex()) {
            if (!measured.getOrElse(index) { false }) continue
            if (runStart >= 0 && index - lastMeasured - 1 > MAX_GAP_EPOCHS) runStart = -1
            lastMeasured = index
            if (motion != null && motion < threshold) {
                if (runStart < 0) runStart = index
                if (index - runStart + 1 >= SleepOnset.STILL_EPOCHS) return runStart
            } else {
                runStart = -1
            }
        }
        return null
    }

    /** Erste Epoche, die bei [nowNanos] noch ganz vor uns liegt (Beginn ≥ jetzt). */
    fun firstFullEpoch(startNanos: Long, nowNanos: Long): Long {
        val elapsed = (nowNanos - startNanos).coerceAtLeast(0L)
        return (elapsed + OnsetMonitor.EPOCH_NANOS - 1) / OnsetMonitor.EPOCH_NANOS
    }

    /**
     * Nächster Alarm (elapsedRealtime in ns): frühestens [OnsetMonitor.TICK_INTERVAL] nach [nowNanos], und zwar
     * [OnsetMonitor.SAMPLE_LEAD_MS] vor einer Epochengrenze, damit die Stichprobe eine volle Epoche früh beginnt.
     */
    fun nextTick(startNanos: Long, nowNanos: Long): Long {
        val lead = OnsetMonitor.SAMPLE_LEAD_MS * 1_000_000L
        val boundary = firstFullEpoch(startNanos, nowNanos + OnsetMonitor.TICK_INTERVAL.toNanos() + lead)
        return startNanos + boundary * OnsetMonitor.EPOCH_NANOS - lead
    }

    /** «give up (max duration)» bzw. «give up», wenn [at] die frühere der beiden Grenzen erreicht hat; sonst `null`. */
    fun giveUpReason(at: Instant, latestGiveUp: Instant, maxEnd: Instant): String? = when {
        at.isBefore(minOf(latestGiveUp, maxEnd)) -> null
        !maxEnd.isAfter(latestGiveUp) -> "give up (max duration)"
        else -> "give up"
    }

    /**
     * Eine Zeile je 15 Min fürs Diagnose-Protokoll (Ursachensuche, Akku-Befund 03.11.2026): aufweckender Sensor (1/0),
     * erhaltene Werte, gemessene Epochen, davon leer, mittlere/grösste Bewegung, Schwelle, Wakelock in Sekunden, Akku.
     */
    fun statLine(
        label: String,
        wakeUp: Boolean,
        samples: Long,
        epochs: Int,
        empty: Int,
        mean: Double?,
        max: Double?,
        threshold: Double,
        wakeLockSeconds: Long,
        battery: Int?,
    ): String {
        fun f(v: Double?) = v?.let { String.format(Locale.ROOT, "%.4f", it) } ?: "-"
        return "$label stat wake=${if (wakeUp) 1 else 0} samples=$samples epochs=$epochs empty=$empty " +
            "motion=${f(mean)}/${f(max)} thr=${f(threshold)} wl=${wakeLockSeconds}s bat=${battery ?: "-"}"
    }
}
