package com.phasewake.app.ring

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.phasewake.app.core.Snooze
import com.phasewake.app.phoneApp
import com.phasewake.app.shared.DataLayer
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
    private var snoozes = 0

    private val fadeIn = object : Runnable {
        override fun run() {
            volume = (volume + FADE_STEP).coerceAtMost(1f)
            player?.setVolume(volume, volume)
            if (volume < 1f) handler.postDelayed(this, FADE_INTERVAL_MS)
        }
    }

    private val timeout = Runnable { stop(notifyWatch = false) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> start(fromWatch = intent.getBooleanExtra(EXTRA_FROM_WATCH, false))
            ACTION_STOP -> stop(notifyWatch = true)
            ACTION_STOP_SILENT -> stop(notifyWatch = false)
            ACTION_SNOOZE -> snooze()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        release()
        state.value = State.Idle
        super.onDestroy()
    }

    private fun start(fromWatch: Boolean) {
        ServiceCompat.startForeground(
            this, RingNotifications.ID_RING, RingNotifications.ringing(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0,
        )
        if (state.value is State.Ringing) return
        state.value = State.Ringing(fromWatch = fromWatch, snoozesLeft = Snooze.MAX_COUNT - snoozes)
        if (fromWatch) phoneApp.appScope.launch { phoneApp.backup.watchIsRinging() }
        startActivity(Intent(this, PhoneRingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        playSound()
        vibrate()
        handler.postDelayed(timeout, TIMEOUT_MS)
    }

    private fun snooze() {
        val ringing = state.value as? State.Ringing ?: return
        if (!Snooze.canSnooze(snoozes)) return
        snoozes++
        release()
        if (ringing.fromWatch) {
            // Die Uhr führt die Nacht: sie schlummert und meldet sich in 5 Minuten wieder.
            phoneApp.appScope.launch { phoneApp.sync.message(DataLayer.CAPABILITY_WATCH, DataLayer.PATH_SNOOZE) }
            state.value = State.Idle
            stopSelf()
        } else {
            state.value = State.Snoozed(snoozesLeft = Snooze.MAX_COUNT - snoozes)
            handler.postDelayed({ state.value = State.Idle; start(fromWatch = false) }, Snooze.MINUTES * 60_000L)
        }
    }

    private fun stop(notifyWatch: Boolean) {
        val wasFromWatch = (state.value as? State.Ringing)?.fromWatch == true
        release()
        state.value = State.Idle
        if (notifyWatch && wasFromWatch) {
            phoneApp.appScope.launch { phoneApp.sync.message(DataLayer.CAPABILITY_WATCH, DataLayer.PATH_STOP) }
        }
        stopSelf()
    }

    private fun playSound() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
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
        } catch (e: Exception) {
            Log.w(TAG, "Weckton nicht abspielbar, nur Vibration", e)
        }
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION") // Vor Android 12 der einzige Weg.
            getSystemService(Vibrator::class.java)
        }
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
        player?.run {
            runCatching { stop() }
            release()
        }
        player = null
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION") // Vor Android 12 der einzige Weg.
            getSystemService(Vibrator::class.java)
        }
        vibrator.cancel()
    }

    /** Zustand für den Weckbildschirm. */
    sealed interface State {
        data object Idle : State
        data class Ringing(val fromWatch: Boolean, val snoozesLeft: Int) : State
        data class Snoozed(val snoozesLeft: Int) : State
    }

    companion object {
        private const val TAG = "PhoneRingService"
        const val ACTION_RING = "com.phasewake.app.ring.RING"
        const val ACTION_STOP = "com.phasewake.app.ring.STOP"
        const val ACTION_STOP_SILENT = "com.phasewake.app.ring.STOP_SILENT"
        const val ACTION_SNOOZE = "com.phasewake.app.ring.SNOOZE"
        const val EXTRA_ALARM_ID = "alarmId"
        private const val EXTRA_FROM_WATCH = "fromWatch"
        private const val FADE_STEP = 0.05f
        private const val FADE_INTERVAL_MS = 1500L
        private const val TIMEOUT_MS = 10 * 60_000L

        private val state = MutableStateFlow<State>(State.Idle)
        val ringState: StateFlow<State> = state.asStateFlow()

        fun ring(context: Context, alarmId: String?, fromWatch: Boolean = false) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PhoneRingService::class.java)
                    .setAction(ACTION_RING)
                    .putExtra(EXTRA_ALARM_ID, alarmId)
                    .putExtra(EXTRA_FROM_WATCH, fromWatch),
            )
        }

        fun command(context: Context, action: String) {
            if (state.value != State.Idle) context.startService(Intent(context, PhoneRingService::class.java).setAction(action))
        }
    }
}
