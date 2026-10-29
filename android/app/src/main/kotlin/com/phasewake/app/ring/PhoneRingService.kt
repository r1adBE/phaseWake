package com.phasewake.app.ring

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.PhoneAlert
import com.phasewake.app.core.Snooze
import com.phasewake.app.core.Sunrise
import com.phasewake.app.core.TestStep
import com.phasewake.app.core.Transient
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
    private var player: MediaPlayer? = null
    private var volume = 0f

    /** Beim Lösen der Weckaufgabe leiser ([TYPING_VOLUME]), nach [TYPING_IDLE_MS] ohne Eingabe wieder voll. */
    private var duck = 1f
    private val unduck = Runnable {
        duck = 1f
        applyVolume()
    }
    private var lastAlarmId: String? = null
    private var lastTarget: Long? = null

    private val fadeIn = object : Runnable {
        override fun run() {
            volume = (volume + FADE_STEP).coerceAtMost(1f)
            applyVolume()
            if (volume < 1f) handler.postDelayed(this, FADE_INTERVAL_MS)
        }
    }

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
            ACTION_TYPING -> if (state.value is State.Ringing) {
                duck = TYPING_VOLUME
                applyVolume()
                handler.removeCallbacks(unduck)
                handler.postDelayed(unduck, TYPING_IDLE_MS)
            }
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
     */
    private fun start(alarmId: String?, fromWatch: Boolean, target: Long?, watchSnoozesLeft: Int?, snoozesDone: Int) {
        phoneApp.backup.cancelSnooze()
        // Einschlafklänge überdecken nie das Wecken (enden sonst schon zu Beginn des Weckfensters).
        com.phasewake.app.sounds.SleepSoundService.stop(this)
        alert.post(newRound = state.value !is State.Ringing)
        val ringing = state.value as? State.Ringing
        if (ringing != null) {
            // Zweite Weckmeldung derselben Nacht (z. B. nach einem Neustart des Weckdienstes der Uhr):
            // Restzahl der Uhr übernehmen, sonst schlummerten Handy und Uhr unterschiedlich oft.
            if (fromWatch && watchSnoozesLeft != null && RingMessage(alarmId, target).sameNight(lastAlarmId, lastTarget)) {
                state.value = ringing.copy(fromWatch = true, snoozesLeft = watchSnoozesLeft.coerceIn(0, Snooze.MAX_COUNT))
                if (lastAlarmId == null) lastAlarmId = alarmId
                if (lastTarget == null) lastTarget = target
            }
            return
        }
        val afterSnooze = state.value is State.Snoozed
        if (afterSnooze) state.value = State.Idle
        // Probewecker: ohne Schlummern (er prüft genau einen Weckvorgang).
        val left = when {
            !Transient.allowsSnooze(alarmId) -> 0
            fromWatch -> watchSnoozesLeft ?: (Snooze.MAX_COUNT - legacyWatchSnoozes())
            else -> Snooze.MAX_COUNT - snoozesDone
        }
        state.value = State.Ringing(fromWatch = fromWatch, snoozesLeft = left.coerceIn(0, Snooze.MAX_COUNT))
        alert.show(if (fromWatch) "with watch" else "backup")
        handler.postDelayed(timeout, TIMEOUT_MS)
        // Nach dem Schlummern am Handy kommt keine Zielzeit mit: die der ersten Runde behalten.
        lastTarget = target ?: lastTarget.takeIf { afterSnooze && lastAlarmId == alarmId }
        lastAlarmId = alarmId
        phoneApp.appScope.launch(Dispatchers.Main) {
            // Vor dem ersten Entsperren nach einem Neustart sind die Wecker nicht lesbar: Standard-Weckton.
            val unlocked = BackupAlarm.isUnlocked(this@PhoneRingService)
            val spec = if (unlocked) phoneApp.specFor(alarmId) else null
            phoneApp.diag.log(DiagKind.RING, if (fromWatch) "phone with watch" else "phone backup")
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
                    playSound(spec?.sound.orEmpty())
                    vibrate()
                }
            }
        }
    }

    private fun snooze() {
        val ringing = state.value as? State.Ringing ?: return
        if (ringing.snoozesLeft <= 0) return
        val done = Snooze.MAX_COUNT - ringing.snoozesLeft + 1
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
     * Niemand hat reagiert. Führt die Uhr die Nacht und hat sie noch Runden, schlummert sie von selbst
     * (ohne «stop» ans Handy): dann wie beim Schlummern auf der Uhr eine eigene Sicherung kurz nach dem
     * Schlummer-Ende, falls die Uhr danach nicht wieder klingelt. Ein neues «klingeln» hebt sie auf.
     */
    private fun onTimeout() {
        val ringing = state.value as? State.Ringing
        if (ringing != null && ringing.fromWatch && ringing.snoozesLeft > 0) {
            val alarmId = lastAlarmId
            val done = Snooze.MAX_COUNT - ringing.snoozesLeft + 1
            val safetyAt = Snooze.nextRing(java.time.Instant.now()).plusMillis(RingMessage.SNOOZE_SAFETY_MS)
            phoneApp.appScope.launch { phoneApp.backup.snoozeSafety(alarmId, safetyAt, done) }
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

    private fun playSound(chosen: String) {
        if (state.value !is State.Ringing) return
        val default = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        // Gewählter Weckton zuerst (eigener aus res/raw oder Systemton); ist er nicht abspielbar (gelöscht,
        // kein Zugriff), der Standard-Weckton.
        val candidates = listOfNotNull(WakeTones.resolve(this, chosen), default).distinct()
        for (uri in candidates) {
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    setDataSource(this@PhoneRingService, uri)
                    isLooping = true
                    setVolume(0f, 0f)
                    prepare()
                    start()
                }
                volume = 0f
                handler.post(fadeIn)
                return
            } catch (e: Exception) {
                player?.release()
                player = null
                Log.w(TAG, "Weckton $uri nicht abspielbar", e)
            }
        }
    }

    private fun applyVolume() {
        player?.setVolume(volume * duck, volume * duck)
    }

    private fun vibrator(): Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION") // Vor Android 12 der einzige Weg.
        getSystemService(Vibrator::class.java)
    }

    private fun vibrate() {
        if (state.value !is State.Ringing) return
        val vibrator = vibrator()
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 500, 1000), 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION") // Vor Android 13 nur so als Wecker-Vibration markierbar.
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    private fun release() {
        handler.removeCallbacksAndMessages(null)
        alert.stop()
        duck = 1f
        player?.run {
            runCatching { stop() }
            release()
        }
        player = null
        vibrator().cancel()
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
        private const val TYPING_VOLUME = 0.5f
        private const val TYPING_IDLE_MS = 10_000L
        const val EXTRA_ALARM_ID = "alarmId"
        /** Zielzeit der Nacht in Millisekunden (nur Zweitwecker und Weckmeldung der Uhr). */
        const val EXTRA_TARGET = "target"
        /** Verbleibende Schlummer-Runden laut Uhr. */
        const val EXTRA_SNOOZES_LEFT = "snoozesLeft"
        /** Bisherige Schlummer-Runden des Handys allein (aus dem Schlummer-Alarm). */
        const val EXTRA_SNOOZES_DONE = "snoozesDone"
        private const val EXTRA_FROM_WATCH = "fromWatch"
        private const val FADE_STEP = 0.05f
        private const val FADE_INTERVAL_MS = 1500L
        private const val TIMEOUT_MS = 10 * 60_000L

        private val state = MutableStateFlow<State>(State.Idle)
        val ringState: StateFlow<State> = state.asStateFlow()

        /**
         * Schlummer-Runden einer älteren Uhr (schickt keine Zahl mit): über Neustarts des Dienstes
         * hinweg im Prozess gezählt; nach einer halben Stunde ohne Schlummern gilt ein neuer Weckvorgang.
         */
        private var legacySnoozes = 0
        private var legacySnoozedAt = 0L

        private fun legacyWatchSnoozes(): Int =
            if (System.currentTimeMillis() - legacySnoozedAt > LEGACY_SNOOZE_RESET_MS) 0 else legacySnoozes

        private const val LEGACY_SNOOZE_RESET_MS = 30 * 60_000L

        /** @return ob der Dienst starten durfte (aus dem Hintergrund ohne Wecker-Alarm ab Android 12 nicht immer). */
        fun ring(
            context: Context,
            alarmId: String?,
            fromWatch: Boolean = false,
            target: Long? = null,
            snoozesLeft: Int? = null,
            snoozesDone: Int = 0,
        ): Boolean {
            val intent = Intent(context, PhoneRingService::class.java)
                .setAction(ACTION_RING)
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_FROM_WATCH, fromWatch)
                .putExtra(EXTRA_SNOOZES_DONE, snoozesDone)
            if (target != null) intent.putExtra(EXTRA_TARGET, target)
            if (snoozesLeft != null) intent.putExtra(EXTRA_SNOOZES_LEFT, snoozesLeft)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (Android 12+) ist eine IllegalStateException.
                Log.w(TAG, "Weckdienst durfte nicht starten", e)
                false
            }
        }

        fun command(context: Context, action: String) {
            if (state.value != State.Idle) context.startService(Intent(context, PhoneRingService::class.java).setAction(action))
        }
    }
}
