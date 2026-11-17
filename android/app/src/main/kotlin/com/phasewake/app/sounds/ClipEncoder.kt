package com.phasewake.app.sounds

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteOrder

/**
 * Schreibt einen Ausschnitt (16 kHz, mono, 16 Bit) als AAC in eine M4A-Datei: mit [BIT_RATE] etwa
 * 25 KB für 10 Sekunden. Ohne Bibliothek, über MediaCodec und MediaMuxer des Systems.
 */
object ClipEncoder {
    private const val TAG = "ClipEncoder"
    private const val BIT_RATE = 20_000
    private const val TIMEOUT_US = 10_000L

    /** Schutz gegen einen hängenden Codec: so viele Runden ohne Fortschritt, dann Abbruch. */
    private const val MAX_IDLE_ROUNDS = 500

    /** @return ob die Datei vollständig geschrieben wurde; sonst ist sie gelöscht. */
    fun writeM4a(pcm: ShortArray, length: Int, sampleRate: Int, file: File): Boolean {
        val count = length.coerceIn(0, pcm.size)
        if (count == 0) return false
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var run: Run? = null
        var ok = false
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            run = Run(codec, muxer, pcm, count, sampleRate)
            ok = run.encode()
        } catch (e: Exception) {
            Log.w(TAG, "Ausschnitt nicht speicherbar", e)
            ok = false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (run?.muxing == true) runCatching { muxer?.stop() }.onFailure { ok = false }
            runCatching { muxer?.release() }
            if (!ok) file.delete()
        }
        return ok
    }

    /** Ergebnis eines Auslese-Schritts am Ausgang des Codecs. */
    private enum class Drain { NONE, PROGRESS, END }

    /** Ein Durchlauf: [count] Werte aus [pcm] in den Codec, fertige Blöcke in den Muxer. */
    private class Run(
        private val codec: MediaCodec,
        private val muxer: MediaMuxer,
        private val pcm: ShortArray,
        private val count: Int,
        private val sampleRate: Int,
    ) {
        /** Muxer gestartet (muss dann gestoppt werden). */
        var muxing = false
            private set
        private val info = MediaCodec.BufferInfo()
        private var track = -1
        private var offset = 0
        private var inputDone = false

        /** @return ob das Ende erreicht und die Spur geschrieben wurde. */
        fun encode(): Boolean {
            var idle = 0
            while (idle < MAX_IDLE_ROUNDS) {
                val fed = !inputDone && feed()
                val drained = drain()
                if (drained == Drain.END) return muxing
                idle = if (fed || drained == Drain.PROGRESS) 0 else idle + 1
            }
            return false
        }

        /** Füllt einen freien Eingabepuffer; am Ende mit Ende-Markierung. @return ob ein Puffer frei war. */
        private fun feed(): Boolean {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) return false
            val buffer = codec.getInputBuffer(index) ?: throw IllegalStateException("kein Eingabepuffer")
            buffer.clear()
            val samples = minOf(count - offset, buffer.remaining() / 2)
            val presentationUs = offset * 1_000_000L / sampleRate
            if (samples <= 0) {
                codec.queueInputBuffer(index, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputDone = true
            } else {
                buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, offset, samples)
                codec.queueInputBuffer(index, 0, samples * 2, presentationUs, 0)
                offset += samples
            }
            return true
        }

        /** Holt einen Ausgabepuffer ab und schreibt ihn in den Muxer. */
        private fun drain(): Drain {
            val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            return when {
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                    Drain.PROGRESS
                }
                out >= 0 -> {
                    val data = codec.getOutputBuffer(out)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (data != null && muxing && !config && info.size > 0) {
                        data.position(info.offset)
                        data.limit(info.offset + info.size)
                        muxer.writeSampleData(track, data, info)
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) Drain.END else Drain.PROGRESS
                }
                else -> Drain.NONE
            }
        }
    }
}
