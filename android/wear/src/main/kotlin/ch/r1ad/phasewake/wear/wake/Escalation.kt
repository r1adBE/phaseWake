package ch.r1ad.phasewake.wear.wake

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Weckvibration in drei Stufen: sanft, deutlich, anhaltend – je eine Minute, die letzte bleibt.
 * Läuft als Wecker-Vibration und damit auch im Bettzeit- oder Nicht-stören-Modus.
 */
class Escalation(context: Context) {

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

    fun start() {
        stop()
        stage = 0
        play()
        handler.postDelayed(next, STAGE_MILLIS)
    }

    fun stop() {
        handler.removeCallbacks(next)
        vibrator.cancel()
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
    }
}
