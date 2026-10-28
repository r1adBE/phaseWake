package com.phasewake.app.wear.wake

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Weckvibration in drei Stufen: sanft, deutlich, anhaltend – je eine Minute, die letzte bleibt.
 * Optional dazu der Weckton der Uhr, der in 30 Sekunden von leise auf voll ansteigt.
 * Läuft als Wecker (nicht als Mitteilung) und damit auch im Bettzeit- oder Nicht-stören-Modus.
 */
class Escalation(private val context: Context) {

    /** Eine Stufe: Muster aus Ein/Aus-Zeiten (ms) und Stärken (0–255), wiederholt. */
    private class Stage(val timings: LongArray, val amplitudes: IntArray)

    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION") // Vor Android 12 der einzige Weg.
            context.getSystemService(Vibrator::class.java)
        }
    private val handler = Handler(Looper.getMainLooper())
    private var stage = 0
    private var player: MediaPlayer? = null
    private var volume = 0f

    private val fadeIn = object : Runnable {
        override fun run() {
            volume = (volume + FADE_STEP).coerceAtMost(1f)
            player?.setVolume(volume, volume)
            if (volume < 1f) handler.postDelayed(this, FADE_INTERVAL_MS)
        }
    }

    private val stages = listOf(
        Stage(longArrayOf(180, 1820), intArrayOf(90, 0)),
        Stage(longArrayOf(320, 680), intArrayOf(170, 0)),
        Stage(longArrayOf(600, 250, 600, 900), intArrayOf(255, 0, 255, 0)),
    )

    private val next = object : Runnable {
        override fun run() {
            if (stage < stages.lastIndex) {
                stage++
                play()
                handler.postDelayed(this, STAGE_MILLIS)
            }
        }
    }

    fun start(withSound: Boolean) {
        stop()
        stage = 0
        play()
        handler.postDelayed(next, STAGE_MILLIS)
        if (withSound) playSound()
    }

    fun stop() {
        handler.removeCallbacks(next)
        handler.removeCallbacks(fadeIn)
        vibrator.cancel()
        player?.run {
            runCatching { stop() }
            release()
        }
        player = null
    }

    private fun playSound() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: return
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(context, uri)
                isLooping = true
                setVolume(0f, 0f)
                prepare()
                start()
            }
            volume = 0f
            handler.post(fadeIn)
        } catch (e: Exception) {
            Log.w("Escalation", "Weckton der Uhr nicht abspielbar, nur Vibration", e)
        }
    }

    private fun play() {
        val s = stages[stage]
        val effect = VibrationEffect.createWaveform(s.timings, s.amplitudes, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION") // Vor Android 13 nur so als Wecker-Vibration markierbar.
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    private companion object {
        const val STAGE_MILLIS = 60_000L
        const val FADE_STEP = 0.05f
        const val FADE_INTERVAL_MS = 1500L
    }
}
