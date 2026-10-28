package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh

/** Einschlafklänge, im Code erzeugt (keine Audiodateien). Kennung = Name (Speicher). */
enum class SleepSound { WHITE, PINK, BROWN, RAIN, OCEAN }

/** Timer, Ausblenden und spätestes Ende der Einschlafklänge. */
object SleepSounds {
    /** Timer-Auswahl in Minuten; `null` = ohne Timer. */
    val TIMER_CHOICES: List<Int?> = listOf(15, 30, 60, null)
    const val DEFAULT_TIMER_MINUTES: Int = 30

    const val FADE_IN_MS: Long = 3_000
    const val FADE_OUT_MS: Long = 30_000

    /** Höchster Ausschlag: −12 dBFS. */
    const val PEAK: Float = 0.2511886f

    /**
     * Wann die Klänge enden: nach dem Timer, spätestens bei Beginn des nächsten Weckfensters (nie das
     * Wecken überdecken); läuft das Fenster schon, sofort ([start]). `null` = ohne Ende.
     */
    fun stopAt(start: Instant, timerMinutes: Int?, nextWindowStart: Instant?): Instant? {
        val timer = timerMinutes?.let { start.plus(Duration.ofMinutes(it.toLong())) }
        val window = nextWindowStart?.let { maxOf(it, start) }
        return listOfNotNull(timer, window).minOrNull()
    }

    /**
     * Lautstärkefaktor 0–1: [FADE_IN_MS] Einblenden ab Start, [FADE_OUT_MS] Ausblenden bis zum Ende
     * (kürzer, wenn weniger Zeit bleibt). Weiche Kurve (Kosinus), damit nichts knackt.
     *
     * @param untilEndMs Zeit bis zum Ende; `null` = ohne Ende.
     */
    fun gain(sinceStartMs: Long, untilEndMs: Long?): Float {
        val fadeIn = smooth(sinceStartMs.toDouble() / FADE_IN_MS)
        val fadeOut = untilEndMs?.let { smooth(it.toDouble() / FADE_OUT_MS) } ?: 1.0
        return min(fadeIn, fadeOut).toFloat()
    }

    private fun smooth(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return 0.5 - 0.5 * cos(PI * t)
    }
}

/**
 * Erzeugt einen Klang fortlaufend und nahtlos (Zustand bleibt zwischen den Aufrufen erhalten).
 * Deterministisch mit [seed]. Ausgabe mono, gleichspannungsfrei, höchstens [SleepSounds.PEAK].
 */
class NoiseGenerator(
    val sound: SleepSound,
    private val sampleRate: Int = 48_000,
    seed: Long = 1L,
) {
    // xorshift64*: schnell und reproduzierbar.
    private var state: Long = if (seed == 0L) 0x2545F4914F6CDD1DL else seed

    // Rosa Rauschen (Filter nach Paul Kellet).
    private var p0 = 0.0; private var p1 = 0.0; private var p2 = 0.0; private var p3 = 0.0
    private var p4 = 0.0; private var p5 = 0.0; private var p6 = 0.0

    // Braunes Rauschen: undichter Integrator.
    private var brown = 0.0

    // Gleichspannungsfilter (Hochpass ~5 Hz).
    private var dcIn = 0.0
    private var dcOut = 0.0
    private val dcPole = exp(-2.0 * PI * 5.0 / sampleRate)

    // Regen: wenige weiche Tropfen gleichzeitig (gedämpfte Sinusschwingungen).
    private val dropPhase = DoubleArray(MAX_DROPS)
    private val dropStep = DoubleArray(MAX_DROPS)
    private val dropAmp = DoubleArray(MAX_DROPS)
    private val dropDecay = DoubleArray(MAX_DROPS)

    // Meer: langsame Welle, jede Periode 8–12 s, Phase durchgehend.
    private var swellPhase = 0.0
    private var swellPeriod = 10.0

    /** Füllt [out] ab [offset] mit [count] Werten. */
    fun fill(out: FloatArray, offset: Int = 0, count: Int = out.size - offset) {
        for (i in offset until offset + count) out[i] = next()
    }

    fun next(): Float {
        val raw = when (sound) {
            // Pegel grob angeglichen (weisses Rauschen klingt bei gleicher Leistung lauter).
            SleepSound.WHITE -> white() * 0.14
            SleepSound.PINK -> pink() * 0.36
            SleepSound.BROWN -> brown() * 0.55
            SleepSound.RAIN -> pink() * 0.30 + drops()
            SleepSound.OCEAN -> brown() * 0.62 * swell()
        }
        // Gleichspannung entfernen, dann weich begrenzen: nie über PEAK, ohne harte Kanten.
        val y = raw - dcIn + dcPole * dcOut
        dcIn = raw
        dcOut = y
        return (SleepSounds.PEAK * tanh(y / SleepSounds.PEAK)).toFloat()
    }

    /** Gleichverteilt in [−1, 1). */
    private fun white(): Double {
        var x = state
        x = x xor (x ushr 12)
        x = x xor (x shl 25)
        x = x xor (x ushr 27)
        state = x
        val bits = (x * 0x2545F4914F6CDD1DL) ushr 11
        return bits.toDouble() / (1L shl 53).toDouble() * 2.0 - 1.0
    }

    private fun pink(): Double {
        val w = white()
        p0 = 0.99886 * p0 + w * 0.0555179
        p1 = 0.99332 * p1 + w * 0.0750759
        p2 = 0.96900 * p2 + w * 0.1538520
        p3 = 0.86650 * p3 + w * 0.3104856
        p4 = 0.55000 * p4 + w * 0.5329522
        p5 = -0.7616 * p5 - w * 0.0168980
        val out = p0 + p1 + p2 + p3 + p4 + p5 + p6 + w * 0.5362
        p6 = w * 0.115926
        return out * 0.11
    }

    private fun brown(): Double {
        brown = (brown + 0.02 * white()) / 1.02
        return brown * 3.5
    }

    private fun drops(): Double {
        // Im Schnitt [DROPS_PER_SECOND] neue Tropfen; ein freier Platz wird belegt.
        if ((white() + 1.0) / 2.0 < DROPS_PER_SECOND / sampleRate) {
            val slot = dropAmp.indices.minBy { dropAmp[it] }
            val freq = 1_800.0 + (white() + 1.0) * 1_600.0
            dropStep[slot] = 2.0 * PI * freq / sampleRate
            dropPhase[slot] = 0.0
            dropAmp[slot] = 0.02 + (white() + 1.0) * 0.02
            // Abklingzeit 4–12 ms.
            val decayMs = 4.0 + (white() + 1.0) * 4.0
            dropDecay[slot] = exp(-1000.0 / (decayMs * sampleRate))
        }
        var sum = 0.0
        for (k in 0 until MAX_DROPS) {
            if (dropAmp[k] < 1e-5) {
                dropAmp[k] = 0.0
                continue
            }
            // Weicher Einsatz: Sinus beginnt bei 0, kein Sprung.
            sum += dropAmp[k] * sin(dropPhase[k])
            dropPhase[k] += dropStep[k]
            dropAmp[k] *= dropDecay[k]
        }
        return sum
    }

    private fun swell(): Double {
        swellPhase += 1.0 / (swellPeriod * sampleRate)
        if (swellPhase >= 1.0) {
            swellPhase -= 1.0
            swellPeriod = 8.0 + (white() + 1.0) * 2.0
        }
        // 0.3 bis 1: die Brandung wird nie ganz still.
        return 0.3 + 0.7 * (0.5 - 0.5 * cos(2.0 * PI * swellPhase))
    }

    private companion object {
        const val MAX_DROPS = 8
        const val DROPS_PER_SECOND = 7.0
    }
}

