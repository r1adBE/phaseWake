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
import com.phasewake.app.core.NoiseFloor
import com.phasewake.app.core.SleepOnset
import java.time.Duration
import java.time.Instant

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
 * Ruhig heisst unter der wirksamen Leicht-Schwelle: [threshold], angehoben nach dem Grundrauschen der bisher gemessenen
 * Epochen ([NoiseFloor]; solange es zu wenige sind, das gelernte [personalFloor]); bei jeder neuen Epoche neu berechnet
 * und auf die ganze Strecke angewendet.
 *
 * Spätes Einschlafen (Befund 09.11.2026): Ist [maxEnd] erreicht und war in den letzten 15 Min eine gemessene Epoche
 * unruhig ([OnsetRules.restless]), verlängert sich die Messung um je [EXTEND_STEP] statt aufzugeben, bis höchstens
 * [extendCap], nicht unter [com.phasewake.app.wear.schedule.AutoOnset.MIN_BATTERY_PERCENT] Akku
 * ([OnsetRules.extendUntil]); Zeile «… extended until …» im Diagnose-Protokoll.
 *
 * @param threshold Leicht-Schwelle (Startwert oder kalibriert).
 * @param latestGiveUp 15 Min vor der eingestellten Weckzeit.
 * @param maxEnd Beginn der Messung + [MAX_DURATION]. Ab dem früheren der beiden: [onGiveUp] statt [onOnset].
 * @param label «auto onset» oder «onset»: Anfang der Zeilen im Diagnose-Protokoll.
 * @param extendCap Späteste Verlängerung: Beginn + [MAX_EXTENDED], höchstens Beginn des Weckfensters; `null` = keine.
 */
class OnsetMonitor(
    private val context: Context,
    private val threshold: Double,
    private val latestGiveUp: Instant,
    private var maxEnd: Instant,
    private val label: String,
    private val log: (DiagKind, String) -> Unit,
    private val onGiveUp: () -> Unit,
    private val extendCap: Instant? = null,
    /** Gelerntes Grundrauschen der Lernphase ([com.phasewake.app.core.DetectorConfig.personalFloor]); `null` = keines. */
    private val personalFloor: Double? = null,
    private val now: () -> Instant = Instant::now,
    private val onOnset: (Instant) -> Unit,
) {
    /** Ab dann wird nicht mehr gewartet (ausser die Messung verlängert sich, siehe [extendCap]). */
    val giveUpAt: Instant get() = minOf(latestGiveUp, maxEnd)

    private val handler = Handler(Looper.getMainLooper())
    private var started: Instant = now()
    private var startNanos = 0L
    private val motions = mutableListOf<Double?>()
    private val measured = mutableListOf<Boolean>()
    private var session: SensorSession? = null
    private var reported = false
    private var ended = false

    /**
     * Grundrauschen der bisher gemessenen Epochen ([NoiseFloor.floor]); `null`, solange es zu wenige sind. Nach dem Ende
     * gilt es als Vorwissen im Weckfenster derselben Nacht ([OnsetFloorStore], Vollaudit K-1).
     */
    var floor: Double? = null
        private set

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
            extendOrGiveUp()
            if (reported) return
        }
        val first = s.closedEpochs - epochs.size
        epochs.forEachIndexed { i, epoch ->
            val index = first + i
            val inSample = s.continuous || index in sampleFrom until sampleUntil
            motions += epoch.motion
            measured += inSample
            if (inSample) period.add(epoch.motion)
        }
        floor = NoiseFloor.floor(motions.filterIndexed { i, _ -> measured[i] }, personalFloor)
        val quiet = NoiseFloor.light(threshold, floor)
        val index = if (s.continuous) SleepOnset.find(motions, quiet) else OnsetRules.find(motions, measured, quiet)
        if (index == null) return
        reported = true
        onOnset(started.plus(Duration.ofSeconds(index.toLong() * DetectorConfig.EPOCH_SECONDS)))
    }

    private fun giveUp() {
        if (reported) return
        reported = true
        onGiveUp()
    }

    /** Zeitgrenze erreicht, falls sie es ist: verlängern ([OnsetRules.extendUntil]) oder aufgeben. */
    fun checkLimit() {
        if (session == null || ended || reported) return
        if (!now().isBefore(giveUpAt)) extendOrGiveUp()
    }

    /** An der Zeitgrenze: bei Unruhe in den letzten 15 Min verlängern, sonst ([onGiveUp]) aufgeben. */
    private fun extendOrGiveUp() {
        if (reported) return
        val cap = extendCap
        val at = now()
        val restless = OnsetRules.restless(motions, measured, NoiseFloor.light(threshold, floor))
        val battery = battery()
        val until = cap?.let { OnsetRules.extendUntil(at, maxEnd, latestGiveUp, it, restless, battery) }
        if (until == null) {
            // Nur die Akku-Wache verhindert die Verlängerung: eigene Zeile, die Zeile zum Ende behält ihren Grund.
            if (cap != null && OnsetRules.extendUntil(at, maxEnd, latestGiveUp, cap, restless, null) != null) {
                log(DiagKind.BATTERY, "$label not extended: battery $battery")
            }
            giveUp()
            return
        }
        maxEnd = until
        log(DiagKind.MEASURE_START, "$label extended until $until")
    }

    /** Alle 5 Minuten (Alarm, weckt die Uhr): Zeitgrenze, Protokollzeile, Stichprobe, nächster Alarm. */
    internal fun tick() {
        val s = session ?: return
        if (ended || reported) return
        if (!now().isBefore(giveUpAt)) {
            extendOrGiveUp()
            if (reported) return
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
        val battery = battery()
        val ongoing = if (sampling) SystemClock.elapsedRealtime() - wakeLockSince else 0L
        log(
            DiagKind.BATTERY,
            OnsetRules.statLine(
                label, s.continuous, s.samples - period.samplesAtStart, period.epochs, period.empty, period.mean(), period.max,
                NoiseFloor.light(threshold, floor), floor, (period.wakeLockMs + ongoing) / 1_000L, battery,
            ),
        )
        if (sampling) wakeLockSince = SystemClock.elapsedRealtime()
        period.reset(SystemClock.elapsedRealtime(), s.samples)
    }

    /** Akkustand in Prozent, oder `null`, wenn unbekannt. */
    private fun battery(): Int? = context.getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }

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

        /** Längste Einschlaf-Messung mit Verlängerungen bei spätem Einschlafen, ab ihrem Beginn. */
        val MAX_EXTENDED: Duration = Duration.ofHours(4)

        /** Eine Verlängerung: so lange wie eine Zeile im Diagnose-Protokoll ([STAT_INTERVAL]). */
        val EXTEND_STEP: Duration = Duration.ofMinutes(15)

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
