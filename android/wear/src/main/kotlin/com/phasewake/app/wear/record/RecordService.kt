package com.phasewake.app.wear.record

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.Epoch
import com.phasewake.app.shared.NightRecording
import com.phasewake.app.shared.RecordEnd
import com.phasewake.app.wear.wake.Notifications
import com.phasewake.app.wear.wake.SensorAccess
import com.phasewake.app.wear.wake.SensorSession
import com.phasewake.app.wear.wearApp
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * «Nacht aufzeichnen»: misst die ganze Nacht je 30-s-Epoche Bewegung und Puls ([SensorSession] wie im Weckfenster,
 * 25 Hz, Puls über die Health Services) und speichert sie fortlaufend ([com.phasewake.app.shared.RecordingRepository]).
 *
 * Eigener Vordergrunddienst («health»), unabhängig vom [com.phasewake.app.wear.wake.WakeService]: Wecker, Einschlaf- und
 * Fenstermessung laufen unverändert weiter, beide dürfen zugleich die Sensoren lesen. Hält einen eigenen Wakelock,
 * solange er aufzeichnet (der gewöhnliche Sensor bündelt nur 10 s; ohne Wakelock liefe sein Puffer im Tiefschlaf über).
 *
 * Endet nur von Hand (Uhr, Mitteilung, Handy), nach [NightRecording.MAX_DURATION] oder unter
 * [NightRecording.MIN_BATTERY] Akku; der Grund steht im Diagnose-Protokoll und in der Aufzeichnung. Alle
 * [FLUSH_INTERVAL] werden die neuen Epochen angehängt. Endet der Prozess, nimmt der Prüf-Alarm ([RecordControl.armCheck])
 * die Aufzeichnung wieder auf und füllt die Lücke mit leeren Epochen; dabei kann eine Epoche um bis zu 30 s versetzt sein.
 */
class RecordService : LifecycleService() {

    /** Laufende Aufzeichnung ab [start]: alle Epochen, davon [flushed] schon in der Datei. */
    internal class Active(val start: Instant, val epochs: MutableList<Epoch>, var flushed: Int) {
        var lastFlushMs = SystemClock.elapsedRealtime()
        var lastStatMs = SystemClock.elapsedRealtime()

        /** Epochen seit der letzten Zeile im Diagnose-Protokoll. */
        var statFrom = epochs.size
    }

    private val app by lazy { wearApp }
    private val handler = Handler(Looper.getMainLooper())
    internal var active: Active? = null
    private var sensors: SensorSession? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Ein Start läuft (Datei wird angelegt): kein zweiter. */
    private var starting = false

    /** Stopp, der während [starting] kam; gilt, sobald die Messung läuft. */
    private var pendingStop: RecordEnd? = null

    /** Grund, warum «health» verweigert wurde ([SensorAccess.refusal]); `null`, wenn es gelang. */
    private var refusal: String? = null
    private val tick = Runnable { onTick() }

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> begin(byPhone = false)
            ACTION_START_PHONE -> begin(byPhone = true)
            ACTION_RESUME -> resume()
            ACTION_STOP -> finish(RecordEnd.of(intent.getStringExtra(EXTRA_REASON)))
            // Vom System neu gestartet (START_STICKY), ohne Auftrag: Aus dem Hintergrund darf der Dienst nicht in den
            // Vordergrund; der Prüf-Alarm darf es und nimmt die Aufzeichnung gleich wieder auf.
            null -> {
                RecordControl.armCheck(this, Instant.now().plusSeconds(1))
                if (active == null && !starting) stopSelf(startId)
            }
        }
        return if (active != null || starting) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        // Vom System beendet, solange noch aufgezeichnet wird: Gespeichertes bleibt, Ende «service ended».
        if (active != null) finish(RecordEnd.SERVICE)
        handler.removeCallbacksAndMessages(null)
        sensors?.stop()
        releaseWakeLock()
        running = false
        super.onDestroy()
    }

    /** Neue Aufzeichnung ab jetzt (vom Knopf der Uhr oder vom Handy über [RecordControl]). */
    private fun begin(byPhone: Boolean) {
        val battery = RecordControl.battery(this)
        if (active != null || starting) {
            goForeground(active?.start ?: clock())
            return
        }
        val by = if (byPhone) "phone" else "watch"
        val low = battery != null && battery < NightRecording.MIN_BATTERY
        if (!goForeground(clock()) || low) {
            val why = if (low) "battery $battery%" else "no sensors: ${refusal.orEmpty()}"
            app.diag.log(if (low) DiagKind.BATTERY else DiagKind.MEASURE_START, "record not started ($by): $why")
            write { app.recordSync.publishStatus(null) }
            stopSelf()
            return
        }
        starting = true
        lifecycleScope.launch {
            // Eine liegengebliebene Aufzeichnung (Prozessende ohne Wiederaufnahme) zuerst abschliessen.
            RecordControl.finishOrphan(app, RecordEnd.SERVICE)
            val start = Instant.ofEpochMilli(clock().toEpochMilli())
            app.recordings.begin(start)
            starting = false
            app.diag.log(DiagKind.MEASURE_START, "record start by $by bat=${battery ?: "-"} ${SensorAccess.of(this@RecordService).line()}")
            measure(Active(start, mutableListOf(), 0))
        }
    }

    /** Nach einem Prozessende (Prüf-Alarm): gespeicherte Aufzeichnung weiterführen, Lücke mit leeren Epochen füllen. */
    private fun resume() {
        if (active != null || starting) {
            goForeground(active?.start ?: clock())
            return
        }
        starting = true
        val allowed = goForeground(clock())
        lifecycleScope.launch {
            val saved = app.recordings.active()
            starting = false
            if (saved == null) {
                stopSelf()
                return@launch
            }
            if (!allowed) {
                app.diag.log(DiagKind.MEASURE_END, "record resume refused: ${refusal.orEmpty()}")
                RecordControl.finishOrphan(app, RecordEnd.SERVICE)
                stopSelf()
                return@launch
            }
            val now = clock()
            val battery = RecordControl.battery(this@RecordService)
            NightRecording.endNow(saved.start, now, battery)?.let { reason ->
                app.diag.log(DiagKind.MEASURE_END, "record end ${reasonText(reason, battery)} (on resume) epochs=${saved.epochs.size}")
                RecordControl.finishOrphan(app, reason)
                stopSelf()
                return@launch
            }
            val epochs = saved.epochs.toMutableList()
            val gap = NightRecording.epochsUntil(saved.start, now) - epochs.size
            repeat(gap.coerceAtLeast(0)) { epochs += Epoch(null, null) }
            app.diag.log(DiagKind.MEASURE_START, "record resume gap=${gap.coerceAtLeast(0)} bat=${battery ?: "-"}")
            measure(Active(saved.start, epochs, saved.epochs.size))
        }
    }

    /** Sensoren an, Wakelock, Prüf-Alarm, Zeitgeber; Stand ans Handy. */
    private fun measure(recording: Active) {
        active = recording
        sinceState.value = recording.start
        goForeground(recording.start)
        val remaining = Duration.between(clock(), recording.start.plus(NightRecording.MAX_DURATION)).toMillis().coerceAtLeast(0L)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phasewake:record")
            // Zeitlimit als Sicherung: nie länger als die Aufzeichnung selbst dauern darf.
            .apply { acquire(remaining + WAKE_LOCK_MARGIN_MS) }
        sensors = SensorSession(this, SystemClock.elapsedRealtimeNanos(), SystemClock::elapsedRealtimeNanos) { onEpochs(it) }
            .also { it.start() }
        RecordControl.armCheck(this)
        handler.postDelayed(tick, TICK_MS)
        write { app.recordSync.publishStatus(recording.start) }
        pendingStop?.let {
            pendingStop = null
            finish(it)
        }
    }

    /** Neue Epochen; über [NightRecording.MAX_EPOCHS] hinaus nichts mehr. Intern für Tests (ohne Sensoren). */
    internal fun onEpochs(epochs: List<Epoch>) {
        val a = active ?: return
        for (epoch in epochs) if (a.epochs.size < NightRecording.MAX_EPOCHS) a.epochs += epoch
    }

    /** Jede Minute (Wakelock hält die Uhr wach): Grenzen prüfen, anhängen, Protokollzeile. Intern für Tests. */
    internal fun onTick() {
        val a = active ?: return
        val battery = RecordControl.battery(this)
        NightRecording.endNow(a.start, clock(), battery)?.let {
            finish(it, battery)
            return
        }
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - a.lastFlushMs >= FLUSH_INTERVAL.toMillis()) flush(a)
        if (nowMs - a.lastStatMs >= STAT_INTERVAL.toMillis()) logStat(a, battery)
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
    }

    /** Neue Epochen an die Datei hängen. */
    private fun flush(a: Active) {
        val chunk = a.epochs.subList(a.flushed, a.epochs.size).toList()
        a.flushed = a.epochs.size
        a.lastFlushMs = SystemClock.elapsedRealtime()
        if (chunk.isNotEmpty()) write { app.recordings.append(a.start, chunk) }
    }

    private fun logStat(a: Active, battery: Int?) {
        app.diag.log(DiagKind.BATTERY, RecordControl.statLine(a.epochs.subList(a.statFrom, a.epochs.size), a.epochs, battery))
        a.statFrom = a.epochs.size
        a.lastStatMs = SystemClock.elapsedRealtime()
    }

    /** Beendet die Aufzeichnung mit [reason]: Rest anhängen, abschliessen, ans Handy, Dienst beenden. */
    private fun finish(reason: RecordEnd, battery: Int? = RecordControl.battery(this)) {
        val a = active ?: run {
            // Kein laufender Stand im Speicher (z. B. Stopp kurz nach einem Prozessende): die Datei abschliessen.
            if (starting) {
                pendingStop = reason
            } else {
                write {
                    RecordControl.finishOrphan(app, reason)
                    app.recordSync.publishStatus(null)
                }
                stopSelf()
            }
            return
        }
        active = null
        sinceState.value = null
        handler.removeCallbacks(tick)
        sensors?.stop()
        sensors = null
        releaseWakeLock()
        RecordControl.cancelCheck(this)
        val end = minOf(clock(), a.start.plus(NightRecording.MAX_DURATION))
        logStat(a, battery)
        app.diag.log(DiagKind.MEASURE_END, "record end ${reasonText(reason, battery)} epochs=${a.epochs.size} hr=${a.epochs.count { it.heartRate != null }}")
        val rest = a.epochs.subList(a.flushed, a.epochs.size).toList()
        write {
            app.recordings.append(a.start, rest)
            app.recordings.finish(a.start, end, reason)
            app.recordings.get(a.start)?.let { app.recordSync.publishRecording(it) }
            app.recordSync.publishStatus(null)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Schreibt im Hintergrund, über das Ende des Dienstes hinaus; in Aufruf-Reihenfolge (sofort bis zur Sperre des
     * Speichers, die der Reihe nach vergibt), damit Anhängen und Abschliessen nie vertauscht werden.
     */
    private fun write(block: suspend () -> Unit) {
        app.appScope.launch(start = CoroutineStart.UNDISPATCHED) { block() }
    }

    /**
     * Vordergrund als «health» (braucht Sensorrechte). Verweigert: trotzdem kurz in den Vordergrund (als Wecker-App),
     * denn nach startForegroundService muss der Dienst das tun; dann `false` und [refusal] mit dem Grund.
     */
    // Die Diensttypen (API 34) setzt ServiceCompat auf älteren Versionen nicht; das Einbetten ist unbedenklich.
    @android.annotation.SuppressLint("InlinedApi")
    private fun goForeground(since: Instant): Boolean {
        val shown = RecordControl.notification(this, since)
        return try {
            ServiceCompat.startForeground(this, Notifications.ID_RECORD, shown, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            refusal = null
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Aufzeichnen nicht erlaubt", e)
            refusal = SensorAccess.refusal(e, SensorAccess.of(this))
            try {
                ServiceCompat.startForeground(this, Notifications.ID_RECORD, shown, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            } catch (e2: RuntimeException) {
                Log.e(TAG, "Vordergrund nicht möglich", e2)
            }
            false
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        internal const val TAG = "RecordService"
        internal const val ACTION_START = "com.phasewake.app.record.START"
        internal const val ACTION_START_PHONE = "com.phasewake.app.record.START_PHONE"
        internal const val ACTION_RESUME = "com.phasewake.app.record.RESUME"
        internal const val ACTION_STOP = "com.phasewake.app.record.STOP"
        internal const val EXTRA_REASON = "reason"

        /** Abstand des Zeitgebers (Grenzen, Anhängen, Protokoll). */
        internal const val TICK_MS = 60_000L

        /** So oft werden neue Epochen angehängt: Ein Absturz verliert höchstens so viel. */
        val FLUSH_INTERVAL: Duration = Duration.ofMinutes(5)

        /** Abstand der Zeilen im Diagnose-Protokoll. */
        val STAT_INTERVAL: Duration = Duration.ofMinutes(60)
        private const val WAKE_LOCK_MARGIN_MS = 10 * 60_000L

        /** Läuft der Dienst in diesem Prozess? */
        @Volatile
        var running: Boolean = false
            private set

        private val sinceState = MutableStateFlow<Instant?>(null)

        /** Beginn der laufenden Aufzeichnung in diesem Prozess; `null`, wenn keine läuft (für die Startseite). */
        val since: StateFlow<Instant?> = sinceState.asStateFlow()

        /** Uhrzeit; nur für Tests ersetzbar (Grenze nach 12 Std.). */
        @Volatile
        internal var clock: () -> Instant = Instant::now

        /** Grund fürs Diagnose-Protokoll: «manual watch», «manual phone», «cap 12h», «battery 14%», «service ended». */
        fun reasonText(reason: RecordEnd, battery: Int?): String = when (reason) {
            RecordEnd.WATCH -> "manual watch"
            RecordEnd.PHONE -> "manual phone"
            RecordEnd.CAP -> "cap ${NightRecording.MAX_DURATION.toHours()}h"
            RecordEnd.BATTERY -> "battery ${battery ?: "-"}%"
            RecordEnd.SERVICE -> "service ended"
        }
    }
}
