package com.phasewake.app.sounds

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions
import com.phasewake.app.core.SoundEvents
import com.phasewake.app.core.SoundType

/**
 * Ordnet einen Ausschnitt ein (Schnarchen, Sprechen, anderes) mit YAMNet über MediaPipe Audio, ganz
 * auf dem Handy. Das Modell `yamnet.tflite` liegt in den Assets (Apache-2.0, siehe README); fehlt es
 * oder lässt es sich nicht laden, ist jeder Ausschnitt [SoundType.OTHER] – aufgenommen wird trotzdem.
 *
 * Nicht threadsicher: nur aus einem Thread verwenden (der Arbeits-Thread der Aufnahme).
 */
class SoundClassifier(private val context: Context) : AutoCloseable {
    private var classifier: AudioClassifier? = null
    private var failed = false

    private fun classifier(): AudioClassifier? {
        if (classifier != null || failed) return classifier
        classifier = try {
            // Ohne Modell gar nicht erst versuchen (die Bibliothek meldet das sonst nur als Fehler).
            context.assets.open(MODEL).close()
            val options = AudioClassifier.AudioClassifierOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
                .setRunningMode(RunningMode.AUDIO_CLIPS)
                // Ohne Begrenzung: alle Bezeichnungen je Abschnitt, damit Schnarchen auch dann zählt,
                // wenn andere (Atmen, Stille) höher liegen. Entschieden wird in [SoundEvents.classify].
                .build()
            AudioClassifier.createFromOptions(context, options)
        } catch (e: Throwable) {
            // Auch UnsatisfiedLinkError (Bibliothek für diesen Prozessor fehlt): nie die Aufnahme stoppen.
            Log.i(TAG, "Kein Klassifikator, Geräusche gelten als «anderes»", e)
            failed = true
            null
        }
        return classifier
    }

    /**
     * Art des Ausschnitts [pcm] (16 kHz, mono) bis [length]: Werte je Bezeichnung über alle Abschnitte
     * gemittelt ([SoundEvents.averageScores]), dann [SoundEvents.classify] – wie auf dem iPhone.
     */
    fun classify(pcm: ShortArray, length: Int): SoundType {
        val model = classifier() ?: return SoundType.OTHER
        return try {
            val count = length.coerceIn(0, pcm.size)
            if (count == 0) return SoundType.OTHER
            val samples = FloatArray(count) { pcm[it] / 32768f }
            val format = AudioData.AudioDataFormat.builder()
                .setNumOfChannels(1)
                .setSampleRate(SoundEvents.SAMPLE_RATE.toFloat())
                .build()
            val data = AudioData.create(format, count)
            data.load(samples)
            val result = model.classify(data)
            val frames = result.classificationResults().map { window ->
                val scores = HashMap<String, Double>()
                for (head in window.classifications()) {
                    for (category in head.categories()) {
                        val name = category.categoryName() ?: continue
                        scores[name] = maxOf(scores[name] ?: 0.0, category.score().toDouble())
                    }
                }
                scores
            }
            SoundEvents.classify(SoundEvents.averageScores(frames))
        } catch (e: Exception) {
            Log.w(TAG, "Einordnen fehlgeschlagen", e)
            SoundType.OTHER
        }
    }

    override fun close() {
        runCatching { classifier?.close() }
        classifier = null
    }

    companion object {
        private const val TAG = "SoundClassifier"
        const val MODEL = "yamnet.tflite"
    }
}
