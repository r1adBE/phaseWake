package com.phasewake.app.sounds

import com.phasewake.app.R
import com.phasewake.app.core.SleepSound

// Hilfen der Einschlafklänge: Aufnahmen in `res/raw` und weiches Überblenden der Lautstärke.

/** Datei der Aufnahme in `res/raw`; `null` = im Code erzeugt. */
internal fun rawRecording(sound: SleepSound): Int? = when (sound) {
    SleepSound.WHITE, SleepSound.PINK, SleepSound.BROWN, SleepSound.FAN, SleepSound.BINAURAL -> null
    SleepSound.RAIN -> R.raw.sound_rain
    SleepSound.OCEAN -> R.raw.sound_ocean
    SleepSound.FOREST -> R.raw.sound_forest
    SleepSound.FIRE -> R.raw.sound_fire
    SleepSound.BROOK -> R.raw.sound_brook
}

/** Je Abtastzeitpunkt (alle Kanäle gleich) weich von der alten zur neuen Lautstärke. */
internal fun rampGain(block: FloatArray, channels: Int, from: Float, to: Float) {
    val frames = block.size / channels
    for (f in 0 until frames) {
        val gain = from + (to - from) * (f + 1) / frames
        for (c in 0 until channels) block[f * channels + c] *= gain
    }
}
