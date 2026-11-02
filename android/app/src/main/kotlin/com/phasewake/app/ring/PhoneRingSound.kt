package com.phasewake.app.ring

import android.app.Service
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Ton und Vibration des [PhoneRingService]: Weckton, der in 30 Sekunden sanft lauter wird, dazu Vibration, beide als
 * Wecker ([AudioAttributes.USAGE_ALARM]). Beim Lösen der Weckaufgabe leiser ([TYPING_VOLUME]), nach [TYPING_IDLE_MS]
 * ohne Eingabe wieder voll.
 */
internal class PhoneRingSound(private val service: Service, private val handler: Handler) {
    private var player: MediaPlayer? = null
    private var volume = 0f
    private var duck = 1f
    private val unduck = Runnable {
        duck = 1f
        applyVolume()
    }

    private val fadeIn = object : Runnable {
        override fun run() {
            volume = (volume + FADE_STEP).coerceAtMost(1f)
            applyVolume()
            if (volume < 1f) handler.postDelayed(this, FADE_INTERVAL_MS)
        }
    }

    /** Eingabe in der Weckaufgabe: vorübergehend leiser. */
    fun duckForTyping() {
        duck = TYPING_VOLUME
        applyVolume()
        handler.removeCallbacks(unduck)
        handler.postDelayed(unduck, TYPING_IDLE_MS)
    }

    /**
     * Gewählter Weckton [chosen] zuerst (eigener aus res/raw oder Systemton); ist er nicht abspielbar (gelöscht, kein
     * Zugriff), der Standard-Weckton; spielt auch der nicht, als letzte Rückfallebene der eigene Ton «Vögel» aus der App.
     */
    fun play(chosen: String) {
        val default = RingtoneManager.getActualDefaultRingtoneUri(service, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val candidates = listOfNotNull(WakeTones.resolve(service, chosen), default, WakeTones.lastResort(service)).distinct()
        for (uri in candidates) {
            // Erst nach erfolgreichem Start übernehmen; scheitert der Start, wird genau dieser Player freigegeben.
            val candidate = MediaPlayer()
            try {
                candidate.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                candidate.setDataSource(service, uri)
                candidate.isLooping = true
                candidate.setVolume(0f, 0f)
                candidate.prepare()
                candidate.start()
                player = candidate
                volume = 0f
                handler.post(fadeIn)
                return
            } catch (e: Exception) {
                candidate.release()
                Log.w(TAG, "Weckton $uri nicht abspielbar", e)
            }
        }
    }

    fun vibrate() {
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 500, 1000), 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator().vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION") // Vor Android 13 nur so als Wecker-Vibration markierbar.
            vibrator().vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    /** Ton und Vibration aus. */
    fun release() {
        handler.removeCallbacks(fadeIn)
        handler.removeCallbacks(unduck)
        duck = 1f
        player?.run {
            runCatching { stop() }
            release()
        }
        player = null
        vibrator().cancel()
    }

    private fun applyVolume() {
        player?.setVolume(volume * duck, volume * duck)
    }

    private fun vibrator(): Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        service.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION") // Vor Android 12 der einzige Weg.
        service.getSystemService(Vibrator::class.java)
    }

    private companion object {
        const val TAG = "PhoneRingService"
        const val TYPING_VOLUME = 0.5f
        const val TYPING_IDLE_MS = 10_000L
        const val FADE_STEP = 0.05f
        const val FADE_INTERVAL_MS = 1500L
    }
}
