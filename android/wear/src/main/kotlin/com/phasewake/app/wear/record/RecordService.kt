package com.phasewake.app.wear.record

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.Epoch
import com.phasewake.app.shared.NightRecording
import com.phasewake.app.shared.RecordEnd
import com.phasewake.app.wear.diag.WatchDiag
import com.phasewake.app.wear.wake.SensorAccess
import com.phasewake.app.wear.wake.SensorSession
import com.phasewake.app.wear.wake.WakeService
import com.phasewake.app.wear.wearApp
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * «Nacht aufzeichnen»: misst die ganze Nacht je 30-s-Epoche Bewegung und Puls ([SensorSession] wie im Weckfenster,
 * 25 Hz, Puls über den Pulssensor der Uhr, Rückfall Health Services) und speichert sie fortlaufend ([com.phasewake.app.shared.RecordingRepository]).
 *
 * Eigener Vordergrunddienst («health»), unabhängig vom [com.phasewake.app.wear.wake.WakeService]: Wecker, Einschlaf- und
 * Fenstermessung laufen unverändert weiter, beide dürfen zugleich die Sensoren lesen. Wakelock (Audit F-01): Hat die Uhr
 * einen aufweckenden Beschleunigungssensor mit grossem Puffer ([SensorSession.continuous]), bündelt er bis 5 Minuten und
 * weckt die Uhr selbst; dann hält der Dienst nur je Bündel kurz einen Wakelock ([RecordWakeLocks.BATCH_MS]) und prüft dabei die
 * Grenzen ([onTick]). Sonst hält er einen eigenen Wakelock, solange er aufzeichnet (der gewöhnliche Sensor bündelt nur
 * 10 s). Gemessen wird in Stichproben ([LearnSampler], 60 s alle 5 Min, Entscheid r1AD 10.11.2026) mit kurzem Wakelock
 * je Stichprobe – in der Lernphase und von Hand; durchgehend nur mit freigeschalteter Diagnose auf Uhr oder Handy
 * (Audit F-01, [LearnMarkStore.setContinuous]). Diagnose: «wl=hold|batch|sample mode=continuous|sample».
 *
 * Endet beim Wecken ([RecordEnd.WAKE], von Hand begonnene spätestens dann, Audit F-01), von Hand (Uhr, Mitteilung,
 * Handy), nach [NightRecording.MAX_DURATION] oder unter [NightRecording.MIN_BATTERY] Akku; der Grund steht im
 * Diagnose-Protokoll und in der Aufzeichnung. Lernphase ([RecordControl.startForLearning], [LearnMark]): ohne Klingeln
 * zur eingestellten Weckzeit. Alle [FLUSH_INTERVAL] werden die neuen Epochen angehängt. Endet der Prozess, nimmt der
 * Prüf-Alarm ([RecordControl.armCheck]) die Aufzeichnung wieder auf und füllt die Lücke mit leeren Epochen; dabei kann
 * eine Epoche um bis zu 30 s versetzt sein. Vordergrund: [goForeground].
 */
class RecordService : LifecycleService() {

    /** Laufende Aufzeichnung ab [start]: alle Epochen, davon [flushed] schon in der Datei; [sampled]: in Stichproben. */
    internal class Active(val start: Instant, val epochs: MutableList<Epoch>, var flushed: Int, var learnUntil: Instant?, val sampled: Boolean) {
        var lastFlushMs = SystemClock.elapsedRealtime()
        var lastStatMs = SystemClock.elapsedRealtime()
        var lastTickMs = SystemClock.elapsedRealtime()
        var statFrom = epochs.size // Epochen seit der letzten Zeile im Diagnose-Protokoll.
    }

    private val app by lazy { wearApp }
    private val handler = Handler(Looper.getMainLooper())
    internal var active: Active? = null
    private var sensors: SensorSession? = null
    private var sampler: LearnSampler? = null
    internal val wakeLocks by lazy { RecordWakeLocks(this) }

    /** Aufweckender Sensor bündelt, kein langer Wakelock ([measure]); nicht in Stichproben. */
    internal val batched: Boolean get() = sampler == null && sensors?.continuous == true

    /** Ein Start läuft (Datei wird angelegt): kein zweiter. */
    private var starting = false

    /** Stopp, der während [starting] kam; gilt, sobald die Messung läuft. */
    private var pendingStop: RecordEnd? = null

    /** Grund, warum «health» verweigert wurde ([SensorAccess.refusal]); `null`, wenn es gelang ([goForeground]). */
    internal var refusal: String? = null
    private val tick = Runnable { onTick() }

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> begin("watch")
            ACTION_START_PHONE -> begin("phone", phoneDiag = intent.getBooleanExtra(EXTRA_CONTINUOUS, false))
            ACTION_START_LEARN -> begin("learn", intent.getLongExtra(EXTRA_UNTIL, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli))
            ACTION_RESUME -> resume()
            ACTION_STOP -> finish(RecordEnd.of(intent.getStringExtra(EXTRA_REASON)))
            // Geweckt: jede Aufzeichnung endet; Lernphase am Handy aus: nur die der Lernphase.
            ACTION_END_LEARN -> {
                val reason = intent.getStringExtra(EXTRA_REASON)?.let(RecordEnd::of) ?: RecordEnd.WAKE
                if (active?.learnUntil != null || (active != null && reason == RecordEnd.WAKE)) finish(reason)
            }
            // Wecker verschoben: Weckzeit der laufenden Aufzeichnung der Lernphase nachführen (nur dieselbe Aufzeichnung).
            ACTION_FOLLOW_PLAN -> active?.takeIf { it.learnUntil != null && it.start.toEpochMilli() == intent.getLongExtra(EXTRA_START, 0L) }
                ?.let { a -> intent.getLongExtra(EXTRA_UNTIL, 0L).takeIf { it > 0 }?.let { a.learnUntil = Instant.ofEpochMilli(it) } }
            // Vom System neu gestartet (START_STICKY), ohne Auftrag: Aus dem Hintergrund darf der Dienst nicht in den
            // Vordergrund; der Prüf-Alarm darf es und nimmt die Aufzeichnung gleich wieder auf.
            null -> {
                RecordControl.armCheck(this, Instant.now().plusSeconds(1))
                if (active == null && !starting) stopSelf(startId)
            }
        }
        if (!starting) wakeLocks.startDone() // Lädt ein Start noch, erst in [measure] (eigener Wakelock steht).
        return if (active != null || starting) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        // Vom System beendet, solange noch aufgezeichnet wird: Gespeichertes bleibt, Ende «service ended».
        if (active != null) finish(RecordEnd.SERVICE)
        handler.removeCallbacksAndMessages(null)
        sampler?.stop()
        sensors?.stop()
        wakeLocks.release()
        running = false
        super.onDestroy()
    }

    /**
     * Neue Aufzeichnung ab jetzt (vom Knopf der Uhr, vom Handy oder von der Lernphase über [RecordControl]; [by] fürs
     * Diagnose-Protokoll). Lernphase: [learnUntil] = eingestellte Weckzeit, siehe [LearnMark]. Von Hand durchgehend nur
     * mit freigeschalteter Diagnose ([WatchDiag.shown], vom Handy [phoneDiag]).
     */
    private fun begin(by: String, learnUntil: Instant? = null, phoneDiag: Boolean = false) {
        val battery = RecordControl.battery(this)
        if (active != null || starting) {
            goForeground(active?.start ?: clock())
            return
        }
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
            val continuous = learnUntil == null && (phoneDiag || WatchDiag.shown(this@RecordService).first())
            app.recordings.begin(start)
            if (continuous) app.learnMark.setContinuous(start)
            // Ohne gespeicherte Marke endet sie trotzdem beim Wecken, solange der Dienst läuft (nur nach einem Prozessende nicht).
            var until = learnUntil
            if (learnUntil != null) {
                try {
                    app.learnMark.set(LearnMark(start, learnUntil))
                    // Seit dem Auftrag neu geplant (Wecker verschoben): gleich folgen; spätere Änderungen sehen die Marke.
                    until = RecordControl.followPlan(this@RecordService, app.scheduler.nextAlarmPlan(), clock()) ?: learnUntil
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "Marke der Lernphase nicht gespeichert oder nicht nachgeführt", e)
                }
            }
            starting = false
            measure(Active(start, mutableListOf(), 0, until, sampled = !continuous), "record start by $by bat=${battery ?: "-"} ${SensorAccess.of(this@RecordService).line()}")
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
            // Aufzeichnung der Lernphase: endet weiter beim Wecken.
            val learnUntil = app.learnMark.get()?.takeIf { it.start == saved.start }?.until
            val sampled = learnUntil != null || !app.learnMark.isContinuous(saved.start)
            measure(Active(saved.start, epochs, saved.epochs.size, learnUntil, sampled), "record resume gap=${gap.coerceAtLeast(0)} bat=${battery ?: "-"}")
        }
    }

    /**
     * Sensoren an, Wakelock (nur ohne aufweckenden Sensor, siehe Klasse), Prüf-Alarm, Zeitgeber; Stand ans Handy.
     * [startLine] kommt ins Diagnose-Protokoll, ergänzt um die Quelle des Pulses («hrsrc=sensor|measure|none»,
     * [SensorSession.heartRateSource]) und den Wakelock («wl=hold|batch»).
     */
    private fun measure(recording: Active, startLine: String) {
        active = recording
        sinceState.value = recording.start
        goForeground(recording.start)
        val sampled = recording.sampled
        val startNanos = SystemClock.elapsedRealtimeNanos()
        val session = SensorSession(this, startNanos, SystemClock::elapsedRealtimeNanos, batch = !sampled, sampled = sampled) { epochs ->
            val s = sampler
            onEpochs(if (s != null) s.filter((sensors?.closedEpochs ?: 0L) - epochs.size, epochs) else epochs)
        }
        sensors = session
        val wakeUp = session.continuous
        // Ohne aufweckenden Sensor und ohne Stichproben: Wakelock höchstens so lange, wie die Aufzeichnung dauern darf.
        if (!wakeUp && !sampled) {
            val remaining = Duration.between(clock(), recording.start.plus(NightRecording.MAX_DURATION)).toMillis().coerceAtLeast(0L)
            wakeLocks.hold(remaining + WAKE_LOCK_MARGIN_MS)
        }
        session.start(listen = !sampled)
        if (sampled) sampler = LearnSampler(this, session, startNanos, wakeLocks) { onTick() }.also { it.start() }
        wakeLocks.startDone()
        val mode = if (sampled) "wl=sample mode=sample" else "wl=${if (wakeUp) "batch" else "hold"} mode=continuous"
        app.diag.log(DiagKind.MEASURE_START, "$startLine hrsrc=${session.heartRateSource} $mode")
        com.phasewake.app.wear.wake.nightBatteryBegin(this, null, null) // Akku je Nacht: Messbeginn (r1AD 14.11.2026).
        RecordControl.armCheck(this)
        handler.postDelayed(tick, TICK_MS)
        write { app.recordSync.publishStatus(recording.start) }
        pendingStop?.let {
            pendingStop = null
            finish(it)
        }
    }

    /**
     * Neue Epochen; über [NightRecording.MAX_EPOCHS] hinaus nichts mehr. Modus «batch» ([batched]): kurzer Wakelock und,
     * wenn fällig, der Zeitgeber (sein Handler steht im Tiefschlaf still). Intern für Tests (ohne Sensoren).
     */
    internal fun onEpochs(epochs: List<Epoch>) {
        val a = active ?: return
        for (epoch in epochs) if (a.epochs.size < NightRecording.MAX_EPOCHS) a.epochs += epoch
        if (!batched) return
        wakeLocks.batch()
        if (SystemClock.elapsedRealtime() - a.lastTickMs >= TICK_MS) onTick()
    }

    /**
     * Jede Minute (Wakelock hält die Uhr wach; im Modus «batch» je Bündel aus [onEpochs]): Grenzen prüfen, anhängen,
     * Protokollzeile. Intern für Tests.
     */
    internal fun onTick() {
        val a = active ?: return
        a.lastTickMs = SystemClock.elapsedRealtime()
        val battery = RecordControl.battery(this)
        NightRecording.endNow(a.start, clock(), battery)?.let {
            finish(it, battery)
            return
        }
        // Lernphase ohne Klingeln (z. B. Wecker übersprungen): zur Weckzeit beenden (folgt einem verschobenen Wecker, followPlan).
        val until = a.learnUntil
        if (until != null && RecordControl.endsAtTarget(clock(), until, WakeService.activePlan != null)) {
            finish(RecordEnd.WAKE, battery)
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
        sampler?.stop()
        sampler = null
        sensors?.stop()
        sensors = null
        wakeLocks.release()
        RecordControl.cancelCheck(this)
        val end = minOf(clock(), a.start.plus(NightRecording.MAX_DURATION))
        logStat(a, battery)
        app.diag.log(DiagKind.MEASURE_END, "record end ${reasonText(reason, battery)} epochs=${a.epochs.size} hr=${a.epochs.count { it.heartRate != null }}")
        com.phasewake.app.wear.wake.nightBatteryRecordEnded(this)
        val rest = a.epochs.subList(a.flushed, a.epochs.size).toList()
        write {
            app.recordings.append(a.start, rest)
            app.recordings.finish(a.start, end, reason)
            app.recordings.get(a.start)?.let { app.recordSync.publishRecording(it) }
            app.recordSync.publishStatus(null)
            app.learnMark.clear(a.start)
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

    companion object {
        internal const val TAG = "RecordService"
        internal const val ACTION_START = "com.phasewake.app.record.START"
        internal const val ACTION_START_PHONE = "com.phasewake.app.record.START_PHONE"
        internal const val ACTION_START_LEARN = "com.phasewake.app.record.START_LEARN"
        internal const val ACTION_RESUME = "com.phasewake.app.record.RESUME"
        internal const val ACTION_STOP = "com.phasewake.app.record.STOP"

        /** Geweckt (jede Aufzeichnung) bzw. Lernphase aus (nur die der Lernphase), siehe [RecordControl.endLearning]. */
        internal const val ACTION_END_LEARN = "com.phasewake.app.record.END_LEARN"
        internal const val EXTRA_REASON = "reason"

        /** Start vom Handy: Diagnose dort freigeschaltet, also durchgehend messen. */
        internal const val EXTRA_CONTINUOUS = "continuous"

        /** Lernphase: eingestellte Weckzeit (ms), zu der die Aufzeichnung ohne Klingeln endet. */
        internal const val EXTRA_UNTIL = "until"

        /** Wecker verschoben: neue Weckzeit [EXTRA_UNTIL] für die Aufzeichnung ab [EXTRA_START] (ms, [RecordControl.followPlan]). */
        internal const val ACTION_FOLLOW_PLAN = "com.phasewake.app.record.FOLLOW_PLAN"
        internal const val EXTRA_START = "start"

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

        /**
         * Grund fürs Diagnose-Protokoll: «manual watch», «manual phone», «cap 12h», «battery 14%», «service ended»,
         * «wake» (Lernphase).
         */
        fun reasonText(reason: RecordEnd, battery: Int?): String = when (reason) {
            RecordEnd.WATCH -> "manual watch"
            RecordEnd.PHONE -> "manual phone"
            RecordEnd.CAP -> "cap ${NightRecording.MAX_DURATION.toHours()}h"
            RecordEnd.BATTERY -> "battery ${battery ?: "-"}%"
            RecordEnd.SERVICE -> "service ended"
            RecordEnd.WAKE -> "wake"
        }
    }
}
