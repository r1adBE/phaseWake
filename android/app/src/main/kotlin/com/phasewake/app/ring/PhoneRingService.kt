package com.phasewake.app.ring

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.PhoneAlert
import com.phasewake.app.core.Snooze
import com.phasewake.app.core.Sunrise
import com.phasewake.app.core.TestStep
import com.phasewake.app.core.WakeTask
import com.phasewake.app.phoneApp
import com.phasewake.app.shared.DataLayer
import com.phasewake.app.shared.RingMessage
import com.phasewake.app.transient.Transients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Zweitwecker des Handys: Weckton, der in 30 Sekunden sanft lauter wird, dazu Vibration.
 * Klingelt, wenn die Uhr den Weckmoment meldet oder (als Sicherung) die Uhr sich nicht meldet.
 */
class PhoneRingService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val sound by lazy { PhoneRingSound(this, handler) }

    private val timeout = Runnable { onTimeout() }
    private val alert by lazy { PhoneRingAlert(this, handler) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> start(
                intent.getStringExtra(EXTRA_ALARM_ID),
                fromWatch = intent.getBooleanExtra(EXTRA_FROM_WATCH, false),
                target = if (intent.hasExtra(EXTRA_TARGET)) intent.getLongExtra(EXTRA_TARGET, 0L) else null,
                watchSnoozesLeft = if (intent.hasExtra(EXTRA_SNOOZES_LEFT)) intent.getIntExtra(EXTRA_SNOOZES_LEFT, 0) else null,
                snoozesDone = intent.getIntExtra(EXTRA_SNOOZES_DONE, 0),
            )
            ACTION_STOP -> stop(notifyWatch = true)
            ACTION_STOP_SILENT -> stop(notifyWatch = false)
            ACTION_SNOOZE -> snooze()
            ACTION_TYPING -> if (state.value is State.Ringing) sound.duckForTyping()
            ACTION_TASK_SHOWN -> taskShown()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Vom System beendet, während die Aufgabe zu sehen war: Die Uhr erlaubt wieder Halten.
        taskGone(state.value)
        release()
        state.value = State.Idle
        super.onDestroy()
    }

    /**
     * Schlummer-Runden: Bei der Uhr zählt deren Angabe [watchSnoozesLeft] (ältere Uhren schicken keine:
     * dann der Zähler im Prozess), beim Handy allein [snoozesDone] aus dem Schlummer-Alarm. So stimmt
     * die Zahl auch, wenn der Dienst zwischendurch beendet wurde.
     *
     * Klingelt schon eine andere Nacht (zwei Wecker kurz nacheinander), löst diese sie ab ([RingRules.Incoming.TAKE_OVER]):
     * Kennung, Zielzeit, Ton und Aufgabe wechseln, die erste Nacht gilt als geweckt (sie hat schon geklingelt). So
     * bleibt kein Wecker stumm und keiner wird still als erledigt geführt.
     */
    private fun start(alarmId: String?, fromWatch: Boolean, target: Long?, watchSnoozesLeft: Int?, snoozesDone: Int) {
        phoneApp.backup.cancelSnooze()
        // Einschlafklänge überdecken nie das Wecken (enden sonst schon zu Beginn des Weckfensters).
        com.phasewake.app.sounds.SleepSoundService.stop(this)
        val ringing = state.value as? State.Ringing
        val incoming = RingRules.incoming(ringing != null, alarmId, target, lastAlarmId, lastTarget)
        if (ringing != null && incoming == RingRules.Incoming.SAME_NIGHT) {
            alert.post(newRound = false)
            // Zweite Weckmeldung derselben Nacht (z. B. nach einem Neustart des Weckdienstes der Uhr):
            // Restzahl der Uhr übernehmen, sonst schlummerten Handy und Uhr unterschiedlich oft.
            if (fromWatch && watchSnoozesLeft != null) {
                // Die Uhr führt nun: Sie hat ihren eigenen Wiederaufnahme-Alarm (der des Handys ist oben schon aufgehoben).
                state.value = ringing.copy(fromWatch = true, snoozesLeft = watchSnoozesLeft.coerceIn(0, Snooze.MAX_COUNT))
                if (lastAlarmId == null) lastAlarmId = alarmId
                if (lastTarget == null) lastTarget = target
            }
            // Führt weiter das Handy: Wiederaufnahme-Alarm neu stellen (oben aufgehoben).
            (state.value as? State.Ringing)?.let { armResume(it.fromWatch, lastAlarmId ?: alarmId, Snooze.MAX_COUNT - it.snoozesLeft) }
            return
        }
        alert.post(newRound = true)
        if (ringing != null) {
            // Ablösen: Ton, Erinnerungen und Zeitgrenze der ersten Nacht enden; eine Aufgabe der Uhr ist hier weg.
            taskGone(ringing)
            release()
            state.value = State.Idle
        }
        val afterSnooze = state.value is State.Snoozed
        if (afterSnooze) state.value = State.Idle
        // Probewecker: ohne Schlummern (er prüft genau einen Weckvorgang).
        val left = RingRules.snoozesLeft(alarmId, fromWatch, watchSnoozesLeft, legacyWatchSnoozes(), snoozesDone)
        state.value = State.Ringing(fromWatch = fromWatch, snoozesLeft = left)
        alert.show(if (fromWatch) "with watch" else "backup")
        handler.postDelayed(timeout, RingRules.TIMEOUT_MS)
        armResume(fromWatch, alarmId, snoozesDone)
        // Nach dem Schlummern am Handy kommt keine Zielzeit mit: die der ersten Runde behalten.
        lastTarget = target ?: lastTarget.takeIf { afterSnooze && lastAlarmId == alarmId }
        lastAlarmId = alarmId
        phoneApp.appScope.launch(Dispatchers.Main) {
            // Vor dem ersten Entsperren nach einem Neustart sind die Wecker nicht lesbar: Standard-Weckton.
            val unlocked = BackupAlarm.isUnlocked(this@PhoneRingService)
            val spec = if (unlocked) phoneApp.specFor(alarmId) else null
            val took = if (ringing != null) " (took over)" else ""
            phoneApp.diag.log(DiagKind.RING, if (fromWatch) "phone with watch$took" else "phone backup$took")
            if (unlocked) phoneApp.testRuns.record(alarmId, TestStep.PHONE_RANG, java.time.Instant.now())
            // Nur der Sonnenaufgang für das Handy; leuchtet er nur auf der Uhr, bleibt der Bildschirm normal.
            val sunrise = spec?.phoneSunrise ?: Sunrise.OFF
            // Weckaufgabe nur mit Handy-Wecker; ohne ihn (oder vor dem Entsperren) gilt Halten, wie auf der Uhr.
            val task = spec?.takeIf { it.phone != PhoneAlert.OFF }?.wakeTask ?: WakeTask.NONE
            if (task != WakeTask.NONE) (state.value as? State.Ringing)?.let { state.value = it.copy(wakeTask = task) }
            if (sunrise != Sunrise.OFF) {
                // Läuft schon ein Licht ab Weckfenster, macht der Weckbildschirm dort weiter.
                val from = SunriseLight.progressFor(alarmId)
                (state.value as? State.Ringing)?.let { state.value = it.copy(sunrise = sunrise, lightFrom = from) }
            }
            when (spec?.phone ?: PhoneAlert.SOUND) {
                // Ohne Ton und Vibration bleibt nur das Licht – oder gar nichts.
                PhoneAlert.OFF -> if (sunrise == Sunrise.OFF) stop(notifyWatch = false)
                PhoneAlert.VIBRATE -> vibrate()
                PhoneAlert.SOUND -> {
                    if (state.value is State.Ringing) sound.play(spec?.sound.orEmpty())
                    vibrate()
                }
            }
        }
    }

    private fun snooze() {
        val ringing = state.value as? State.Ringing ?: return
        val done = RingRules.snoozeDone(ringing.snoozesLeft) ?: return
        release()
        if (ringing.fromWatch) {
            // Die Uhr führt die Nacht: sie schlummert und meldet sich in 5 Minuten wieder.
            legacySnoozes = done
            legacySnoozedAt = System.currentTimeMillis()
            val alarmId = lastAlarmId
            val safetyAt = Snooze.nextRing(java.time.Instant.now()).plusMillis(RingMessage.SNOOZE_SAFETY_MS)
            phoneApp.appScope.launch {
                // Zuerst die eigene Sicherung: Erreicht die Bitte die Uhr nicht oder klingelt sie danach
                // nicht wieder, klingelt das Handy kurz nach dem Schlummer-Ende selbst.
                phoneApp.backup.snoozeSafety(alarmId, safetyAt, done)
                phoneApp.sync.message(DataLayer.CAPABILITY_WATCH, DataLayer.PATH_SNOOZE)
            }
            // Die Aufgabe ist hier weg; geht die Schlummer-Bitte verloren, erlaubt die Uhr so trotzdem gleich Halten.
            taskGone(ringing)
            state.value = State.Idle
            stopSelf()
        } else {
            state.value = State.Snoozed(snoozesLeft = Snooze.MAX_COUNT - done, sunrise = ringing.sunrise, wakeTask = ringing.wakeTask)
            // Über einen System-Wecker statt eines Handlers: Der steht im Tiefschlaf des Handys still.
            phoneApp.backup.snoozeUntil(Snooze.nextRing(java.time.Instant.now()), lastAlarmId, done)
        }
    }

    /**
     * Stirbt der Prozess mitten im Klingeln des Handys (ohne Uhr), startet nichts das Klingeln neu. Darum wie auf der Uhr
     * ein Alarm kurz nach der Zeitgrenze ([RingRules.resumeAt]; bis dahin schlummert oder endet die Runde im laufenden
     * Prozess), abgelegt wie das Schlummern am Handy ([PhoneSnooze], übersteht auch einen Neustart). Führt die Uhr
     * ([fromWatch]), keiner: Sie hat ihren eigenen. Schlummern ersetzt ihn (gleicher Alarm), Beenden hebt ihn auf
     * ([BackupAlarm.cancelSnooze]).
     */
    private fun armResume(fromWatch: Boolean, alarmId: String?, snoozesDone: Int) {
        val at = RingRules.resumeAt(fromWatch, System.currentTimeMillis()) ?: return
        phoneApp.backup.snoozeUntil(java.time.Instant.ofEpochMilli(at), alarmId, snoozesDone.coerceIn(0, Snooze.MAX_COUNT))
    }

    /**
     * Niemand hat reagiert. Führt die Uhr die Nacht und hat sie noch Runden, schlummert sie von selbst
     * (ohne «stop» ans Handy): dann wie beim Schlummern auf der Uhr eine eigene Sicherung kurz nach dem
     * Schlummer-Ende, falls die Uhr danach nicht wieder klingelt. Ein neues «klingeln» hebt sie auf.
     * Führt das Handy (ohne Uhr), schlummert es wie die Uhr von selbst, solange Runden bleiben; erst
     * die letzte Zeitgrenze beendet.
     */
    private fun onTimeout() {
        val ringing = state.value as? State.Ringing
        when (RingRules.timeout(ringing != null, ringing?.fromWatch == true, ringing?.snoozesLeft ?: 0)) {
            RingRules.Timeout.SNOOZE -> {
                phoneApp.diag.log(DiagKind.SNOOZE, "phone timeout")
                snooze()
                return
            }
            RingRules.Timeout.STOP_WITH_SAFETY -> {
                val alarmId = lastAlarmId
                val done = RingRules.snoozeDone(ringing?.snoozesLeft ?: 0) ?: Snooze.MAX_COUNT
                val safetyAt = Snooze.nextRing(java.time.Instant.now()).plusMillis(RingMessage.SNOOZE_SAFETY_MS)
                phoneApp.appScope.launch { phoneApp.backup.snoozeSafety(alarmId, safetyAt, done) }
            }
            RingRules.Timeout.STOP -> Unit
        }
        stop(notifyWatch = false)
    }

    private fun stop(notifyWatch: Boolean) {
        val before = state.value
        val wasFromWatch = (before as? State.Ringing)?.fromWatch == true
        legacySnoozes = 0
        release()
        phoneApp.backup.cancelSnooze()
        // Am Handy beendet: auch keine Sicherung nach dem Schlummern mehr (nicht beim stillen Stopp
        // auf «stop» der Uhr hin, der kann ein Schlummern sein).
        if (notifyWatch) phoneApp.backup.cancelSnoozeSafety(lastAlarmId).also { Transients.endedHere(phoneApp, lastAlarmId) }
        SunriseLight.stop(this)
        state.value = State.Idle
        // Still beendet (Zeitgrenze, Handy-Weckart «Aus», «stop» der Uhr): Die Aufgabe ist weg, die Uhr erlaubt wieder Halten.
        if (!notifyWatch) taskGone(before)
        // Auch beim Zweitwecker des Handys (oder dessen Schlummern): Die Uhr merkt die Nacht als erledigt
        // und holt sie nicht nach, falls sie gerade eine andere Nacht führt (zwei Wecker am selben Morgen).
        if (notifyWatch && before != State.Idle && (wasFromWatch || lastAlarmId != null)) {
            val message = RingMessage(alarmId = lastAlarmId, target = lastTarget, sentAt = System.currentTimeMillis())
            phoneApp.appScope.launch { phoneApp.sync.message(DataLayer.CAPABILITY_WATCH, DataLayer.PATH_STOP, message.encode()) }
        }
        stopSelf()
    }

    /** Ob die Uhr diese Nacht führt und hier die Weckaufgabe zu sehen ist (nur dann gilt die Bestätigung). */
    private fun watchTask(s: State): Boolean = s is State.Ringing && s.fromWatch && s.wakeTask == WakeTask.MATH

    /**
     * Der Weckbildschirm zeigt die Aufgabe ([PhoneRingActivity], sofort und alle [DataLayer.TASK_SHOWN_REFRESH_MS]):
     * der Uhr bestätigen. Erst damit schaltet sie das Halten ab; bleibt die Bestätigung aus, gilt dort wieder Halten.
     */
    private fun taskShown() {
        if (!watchTask(state.value)) return
        val message = RingMessage(alarmId = lastAlarmId, target = lastTarget, sentAt = System.currentTimeMillis()).encode()
        phoneApp.appScope.launch { phoneApp.sync.message(DataLayer.CAPABILITY_WATCH, DataLayer.PATH_TASK_SHOWN, message) }
    }

    /** Das Klingeln mit Aufgabe endet ungelöst: der Uhr melden, damit sie nicht bis zum Ablauf der Bestätigung wartet. */
    private fun taskGone(before: State) {
        if (!watchTask(before)) return
        val message = RingMessage(alarmId = lastAlarmId, target = lastTarget, sentAt = System.currentTimeMillis()).encode()
        phoneApp.appScope.launch { phoneApp.sync.message(DataLayer.CAPABILITY_WATCH, DataLayer.PATH_TASK_GONE, message) }
    }

    private fun vibrate() {
        if (state.value is State.Ringing) sound.vibrate()
    }

    private fun release() {
        handler.removeCallbacksAndMessages(null)
        alert.stop()
        sound.release()
    }

    /** Zustand für den Weckbildschirm. */
    sealed interface State {
        data object Idle : State
        /** [sunrise]: Licht auf dem Bildschirm; [lightFrom]: Fortschritt, bei dem es weitergeht (0–1). */
        data class Ringing(
            val fromWatch: Boolean,
            val snoozesLeft: Int,
            val sunrise: Sunrise = Sunrise.OFF,
            val lightFrom: Float = 0f,
            /** Was zum Beenden nötig ist (Schlummern geht immer ohne). */
            val wakeTask: WakeTask = WakeTask.NONE,
        ) : State
        data class Snoozed(val snoozesLeft: Int, val sunrise: Sunrise = Sunrise.OFF, val wakeTask: WakeTask = WakeTask.NONE) : State
    }

    companion object {
        private const val TAG = "PhoneRingService"
        const val ACTION_RING = "com.phasewake.app.ring.RING"
        const val ACTION_STOP = "com.phasewake.app.ring.STOP"
        const val ACTION_STOP_SILENT = "com.phasewake.app.ring.STOP_SILENT"
        const val ACTION_SNOOZE = "com.phasewake.app.ring.SNOOZE"

        /** Eingabe in der Weckaufgabe: Ton vorübergehend leiser. */
        const val ACTION_TYPING = "com.phasewake.app.ring.TYPING"

        /** Der Weckbildschirm zeigt gerade die Aufgabe: der Uhr bestätigen ([DataLayer.PATH_TASK_SHOWN]). */
        const val ACTION_TASK_SHOWN = "com.phasewake.app.ring.TASK_SHOWN"
        const val EXTRA_ALARM_ID = "alarmId"
        /** Zielzeit der Nacht in Millisekunden (nur Zweitwecker und Weckmeldung der Uhr). */
        const val EXTRA_TARGET = "target"
        /** Verbleibende Schlummer-Runden laut Uhr. */
        const val EXTRA_SNOOZES_LEFT = "snoozesLeft"
        /** Bisherige Schlummer-Runden des Handys allein (aus dem Schlummer-Alarm). */
        const val EXTRA_SNOOZES_DONE = "snoozesDone"
        private const val EXTRA_FROM_WATCH = "fromWatch"

        private val state = MutableStateFlow<State>(State.Idle)
        val ringState: StateFlow<State> = state.asStateFlow()

        /** Nacht, die klingelt oder geschlummert ist (Kennung, Zielzeit); im Prozess, damit «stop» der Uhr sie prüfen kann. */
        @Volatile
        private var lastAlarmId: String? = null

        @Volatile
        private var lastTarget: Long? = null

        /**
         * Ob ein «stop» der Uhr ([message]) das Klingeln oder Schlummern hier beendet ([RingRules.stopApplies]): nur
         * für die Nacht, die gerade läuft, und nicht verspätet zugestellt.
         */
        fun stopsRing(message: RingMessage, nowMs: Long): Boolean =
            RingRules.stopApplies(message, nowMs, state.value != State.Idle, lastAlarmId, lastTarget)

        /**
         * Schlummer-Runden einer älteren Uhr (schickt keine Zahl mit): über Neustarts des Dienstes
         * hinweg im Prozess gezählt; nach einer halben Stunde ohne Schlummern gilt ein neuer Weckvorgang.
         */
        private var legacySnoozes = 0
        private var legacySnoozedAt = 0L

        private fun legacyWatchSnoozes(): Int =
            if (System.currentTimeMillis() - legacySnoozedAt > LEGACY_SNOOZE_RESET_MS) 0 else legacySnoozes

        private const val LEGACY_SNOOZE_RESET_MS = 30 * 60_000L

        /**
         * Klingeln für Wecker [alarmId] ([RingResult]): neu, als Ablösung oder Fortsetzung eines laufenden Klingelns,
         * oder abgelehnt, wenn der Dienst nicht starten durfte (aus dem Hintergrund ohne Wecker-Alarm ab Android 12 nicht
         * immer). Nur bei [RingResult.rings] gilt die Nacht als geweckt.
         */
        fun ring(
            context: Context,
            alarmId: String?,
            fromWatch: Boolean = false,
            target: Long? = null,
            snoozesLeft: Int? = null,
            snoozesDone: Int = 0,
        ): RingResult {
            val ringing = state.value is State.Ringing
            val intent = Intent(context, PhoneRingService::class.java)
                .setAction(ACTION_RING)
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_FROM_WATCH, fromWatch)
                .putExtra(EXTRA_SNOOZES_DONE, snoozesDone)
            if (target != null) intent.putExtra(EXTRA_TARGET, target)
            if (snoozesLeft != null) intent.putExtra(EXTRA_SNOOZES_LEFT, snoozesLeft)
            val started = try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (Android 12+) ist eine IllegalStateException.
                Log.w(TAG, "Weckdienst durfte nicht starten", e)
                false
            }
            return RingRules.result(started, ringing)
        }

        fun command(context: Context, action: String) {
            if (state.value != State.Idle) context.startService(Intent(context, PhoneRingService::class.java).setAction(action))
        }
    }
}
