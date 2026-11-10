package com.phasewake.app.wear.record

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.phasewake.app.core.Epoch
import com.phasewake.app.wear.wake.OnsetMonitor
import com.phasewake.app.wear.wake.OnsetRules
import com.phasewake.app.wear.wake.SensorSession

/**
 * Aufzeichnung der Lernphase in Stichproben (Entscheid r1AD 10.11.2026), wie die Stichproben der Einschlaf-Messung
 * ([OnsetMonitor]): Alle 5 Minuten weckt ein exakter Alarm die Uhr; sie misst [OnsetMonitor.SAMPLE_EPOCHS] volle Epochen
 * (60 s) Bewegung und Puls unter einem kurzen Wakelock ([RecordWakeLocks.short]) und schläft wieder. Dazwischen sind die
 * Sensoren ab; die Epochen dort werden leer gespeichert ([filter]), das Format bleibt gleich. Derselbe Alarm ruft
 * [onTick] (Grenzen, Anhängen, Protokoll), denn ein Zeitgeber steht im Tiefschlaf still.
 *
 * @param session Sitzung mit `sampled = true`, gestartet ohne Sensoren.
 * @param startNanos Beginn der [session] (`SystemClock.elapsedRealtimeNanos()`), Epoche 0.
 */
internal class LearnSampler(
    private val context: Context,
    private val session: SensorSession,
    private val startNanos: Long,
    private val wakeLocks: RecordWakeLocks,
    private val onTick: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    /** Epochen der laufenden bzw. letzten Stichprobe: [sampleFrom] einschliesslich bis [sampleUntil] ausschliesslich. */
    internal var sampleFrom = 0L
        private set
    internal var sampleUntil = 0L
        private set

    /** Läuft gerade eine Stichprobe (Sensoren an, kurzer Wakelock)? */
    internal var sampling = false
        private set
    private var running = false
    private val endSample = Runnable { endSample() }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = tick()
    }

    /** Erste Stichprobe beim nächsten Alarm (auf eine Epochengrenze ausgerichtet). */
    fun start() {
        if (running) return
        running = true
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_TICK), ContextCompat.RECEIVER_NOT_EXPORTED)
        scheduleTick()
    }

    /** Beendet die Stichproben: laufende ohne Abschliessen beenden, Alarm aufheben. */
    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(endSample)
        if (sampling) finishSample(close = false)
        context.getSystemService(AlarmManager::class.java).cancel(tickIntent())
        try {
            context.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            Log.w(RecordService.TAG, "Empfänger der Stichproben war nicht angemeldet", e)
        }
    }

    /**
     * Gelieferte Epochen der [session] ab ihrem Index [first]: ausserhalb einer Stichprobe leer (auch die angebrochene
     * Epoche vor ihrem Beginn).
     */
    fun filter(first: Long, epochs: List<Epoch>): List<Epoch> =
        epochs.mapIndexed { i, epoch -> if (first + i in sampleFrom until sampleUntil) epoch else EMPTY }

    /** Alle 5 Minuten (Alarm, weckt die Uhr): [onTick], Stichprobe, nächster Alarm. Intern für Tests. */
    internal fun tick() {
        if (!running) return
        onTick()
        if (!running) return
        if (!sampling) beginSample()
        scheduleTick()
    }

    private fun beginSample() {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        sampleFrom = OnsetRules.firstFullEpoch(startNanos, nowNanos)
        sampleUntil = sampleFrom + OnsetMonitor.SAMPLE_EPOCHS
        val delayMs = (startNanos + sampleUntil * OnsetMonitor.EPOCH_NANOS - nowNanos) / 1_000_000L + OnsetMonitor.SAMPLE_MARGIN_MS
        sampling = true
        // Zeitlimit als Sicherung: Auch wenn das Ende ausbliebe, hält der Wakelock nicht länger als die Stichprobe.
        wakeLocks.short(delayMs + WAKE_LOCK_SLACK_MS)
        session.resume()
        handler.postDelayed(endSample, delayMs)
    }

    private fun endSample() {
        if (sampling) finishSample(close = true)
    }

    /** Stichprobe beenden: Epochen abschliessen ([close]), Sensoren ab, Wakelock lösen. */
    private fun finishSample(close: Boolean) {
        if (close) session.closeUntil(startNanos + sampleUntil * OnsetMonitor.EPOCH_NANOS)
        session.pause()
        sampling = false
        wakeLocks.releaseShort()
    }

    private fun scheduleTick() {
        val at = OnsetRules.nextTick(startNanos, SystemClock.elapsedRealtimeNanos()) / 1_000_000L
        val alarms = context.getSystemService(AlarmManager::class.java)
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, tickIntent())
        } catch (e: SecurityException) {
            // Ohne exakte Alarme ungenau, aber auch im Ruhezustand der Uhr.
            Log.w(RecordService.TAG, "Kein exakter Alarm für die Stichproben", e)
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, tickIntent())
        }
    }

    private fun tickIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_TICK, Intent(ACTION_TICK).setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private val EMPTY = Epoch(null, null)
        private const val WAKE_LOCK_SLACK_MS = 10_000L
        private const val REQUEST_TICK = 16
        internal const val ACTION_TICK = "com.phasewake.app.action.RECORD_SAMPLE_TICK"
    }
}
