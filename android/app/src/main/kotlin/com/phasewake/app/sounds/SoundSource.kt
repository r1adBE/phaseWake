package com.phasewake.app.sounds

import com.phasewake.app.core.NoiseGenerator
import com.phasewake.app.core.SleepSound
import com.phasewake.app.core.SleepSounds

/** 16-Bit-Abtastwerte auf ±1 (Float-Ausgabe des AudioTrack). */
private const val PCM_SCALE = 1f / 32_768f

/** Was der Audio-Thread spielt: [fill] liefert verschachtelte Werte ([channels] je Abtastzeitpunkt). */
internal interface SoundSource {
    val sound: SleepSound
    val sampleRate: Int
    val channels: Int
    fun fill(out: FloatArray)
}

internal class GeneratedSound(private val generator: NoiseGenerator, override val sampleRate: Int) : SoundSource {
    override val sound: SleepSound get() = generator.sound
    override val channels: Int = generator.channels
    override fun fill(out: FloatArray) = generator.fill(out)
}

/** Aufnahme ohne Lücke: nach dem Ende weiter ab [LoopedRecording.loopStart]. */
internal class LoopedSound(override val sound: SleepSound, private val recording: LoopedRecording) : SoundSource {
    override val sampleRate: Int get() = recording.sampleRate
    override val channels: Int get() = recording.channels
    private var position = recording.start

    /** Aufnahmen sind leiser als die erzeugten Klänge: Verstärkung aus dem Kern, begrenzt. */
    private val gain = sound.gain

    override fun fill(out: FloatArray) {
        val pcm = recording.pcm
        var i = 0
        while (i < out.size) {
            if (position >= recording.end) position = recording.loopStart
            val n = minOf(out.size - i, recording.end - position)
            for (k in 0 until n) out[i + k] = SleepSounds.amplify(pcm[position + k] * PCM_SCALE, gain)
            i += n
            position += n
        }
    }
}
