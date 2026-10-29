package com.phasewake.app.sounds

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Aufnahme als PCM (16 Bit, Kanäle verschachtelt), bereit für eine Schleife ohne Lücke: Der erste
 * Durchgang beginnt bei [start], jeder weitere bei [loopStart]; [end] ist exklusiv. Alle Angaben sind
 * Indizes in [pcm] (Abtastwerte, nicht Abtastzeitpunkte).
 */
class LoopedRecording private constructor(
    val pcm: ShortArray,
    val sampleRate: Int,
    val channels: Int,
    val start: Int,
    val loopStart: Int,
    val end: Int,
) {
    companion object {
        /** Stille am Rand (Vorlauf des AAC-Decoders) bis zu diesem Betrag wird abgeschnitten. */
        private const val SILENCE = 4

        /** Übergang am Schleifenpunkt: 20 ms gleich laut übergeblendet, falls der Decoder Ränder anders liefert. */
        private const val SEAM_MS = 20

        private const val TIMEOUT_US = 10_000L

        /**
         * Dekodiert [resId] (`res/raw`, unkomprimiert abgelegt wie .m4a) einmal vollständig. [cancelled]
         * wird zwischendurch gefragt; dann endet es mit [InterruptedException].
         */
        fun decode(context: Context, resId: Int, cancelled: () -> Boolean): LoopedRecording {
            val extractor = MediaExtractor()
            try {
                // Den Deskriptor darf man direkt nach setDataSource schliessen.
                context.resources.openRawResourceFd(resId).use { fd -> extractor.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
                val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                extractor.selectTrack(track)
                val input = extractor.getTrackFormat(track)
                var sampleRate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val durationUs = if (input.containsKey(MediaFormat.KEY_DURATION)) input.getLong(MediaFormat.KEY_DURATION) else 60_000_000L
                var pcm = ShortArray(((durationUs * sampleRate / 1_000_000L + sampleRate) * channels).toInt())
                var size = 0

                val codec = MediaCodec.createDecoderByType(requireNotNull(input.getString(MediaFormat.KEY_MIME)))
                try {
                    codec.configure(input, null, null, 0)
                    codec.start()
                    val info = MediaCodec.BufferInfo()
                    var inputDone = false
                    while (true) {
                        if (cancelled()) throw InterruptedException()
                        if (!inputDone) {
                            val index = codec.dequeueInputBuffer(TIMEOUT_US)
                            if (index >= 0) {
                                val buffer = requireNotNull(codec.getInputBuffer(index))
                                val read = extractor.readSampleData(buffer, 0)
                                if (read < 0) {
                                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(index, 0, read, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                        val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val output = codec.outputFormat
                            sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                check(output.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT) { "Nur 16-Bit-PCM" }
                            }
                        } else if (index >= 0) {
                            val buffer = requireNotNull(codec.getOutputBuffer(index))
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val count = shorts.remaining()
                            if (size + count > pcm.size) pcm = pcm.copyOf(maxOf(pcm.size * 3 / 2, size + count))
                            shorts.get(pcm, size, count)
                            size += count
                            codec.releaseOutputBuffer(index, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                } finally {
                    runCatching { codec.stop() }
                    codec.release()
                }
                check(channels in 1..2) { "Nur Mono oder Stereo" }
                return prepare(pcm, size, sampleRate, channels)
            } finally {
                extractor.release()
            }
        }

        /**
         * Schneidet stille Ränder ab und blendet das Ende gleich laut (Kosinus/Sinus) in den Anfang über:
         * Der letzte Abschnitt vor [end] klingt dann wie der Beginn, und die Schleife springt danach
         * nahtlos zu [loopStart].
         */
        private fun prepare(pcm: ShortArray, size: Int, sampleRate: Int, channels: Int): LoopedRecording {
            var first = 0
            while (first < size && frameSilent(pcm, first, channels)) first += channels
            var last = size - channels
            while (last > first && frameSilent(pcm, last, channels)) last -= channels
            val end = last + channels
            val frames = (end - first) / channels
            check(frames > sampleRate) { "Aufnahme zu kurz" }
            val seam = minOf(sampleRate * SEAM_MS / 1000, frames / 4)
            val tail = end - seam * channels
            for (f in 0 until seam) {
                val t = (f + 0.5) / seam
                val fadeOut = cos(t * PI / 2)
                val fadeIn = sin(t * PI / 2)
                for (c in 0 until channels) {
                    val i = tail + f * channels + c
                    val mixed = pcm[i] * fadeOut + pcm[first + f * channels + c] * fadeIn
                    pcm[i] = mixed.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }
            return LoopedRecording(pcm, sampleRate, channels, start = first, loopStart = first + seam * channels, end = end)
        }

        private fun frameSilent(pcm: ShortArray, index: Int, channels: Int): Boolean =
            (0 until channels).all { abs(pcm[index + it].toInt()) <= SILENCE }
    }
}
