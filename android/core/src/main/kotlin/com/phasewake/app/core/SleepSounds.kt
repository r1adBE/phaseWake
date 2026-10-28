package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Einschlafklänge in der Reihenfolge der Anzeige. Kennung = Name (Speicher; ältere Werte bleiben gültig).
 *
 * @property recorded `true` = Aufnahme (CC0), nahtlos in Schleife gespielt; Datei «sound_<name>» (klein
 *   geschrieben, Android `res/raw`, Apple im App-Bundle). `false` = im Code erzeugt ([NoiseGenerator]).
 */
enum class SleepSound(val recorded: Boolean) {
    WHITE(false), PINK(false), BROWN(false), FAN(false), BINAURAL(false),
    RAIN(true), OCEAN(true), FOREST(true), FIRE(true), BROOK(true);
}

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
 * Deterministisch mit [seed]. Ausgabe mit [channels] Kanälen (verschachtelt: links, rechts, links …),
 * gleichspannungsfrei, höchstens [SleepSounds.PEAK]. Nur für erzeugte Klänge ([SleepSound.recorded] = `false`).
 */
class NoiseGenerator(
    val sound: SleepSound,
    private val sampleRate: Int = 48_000,
    seed: Long = 1L,
) {
    init {
        require(!sound.recorded) { "$sound ist eine Aufnahme" }
    }

    /** Kanäle: 2 für die Einschlaf-Wellen (je Ohr ein anderer Ton), sonst 1. */
    val channels: Int = if (sound == SleepSound.BINAURAL) 2 else 1

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

    // Ventilator: braunes Rauschen durch einen Tiefpass (~300 Hz), dazu leises Brummen (50 und 100 Hz).
    private var fanLow = 0.0
    private val fanPole = exp(-2.0 * PI * FAN_CUTOFF_HZ / sampleRate)
    private var humPhase = 0.0

    // Einschlaf-Wellen: je Ohr ein Sinus (links 200 Hz, rechts 202 Hz), dazu sehr leises rosa Rauschen.
    private var leftPhase = 0.0
    private var rightPhase = 0.0
    private var rightPending = false
    private var right = 0f

    /** Füllt [out] ab [offset] mit [count] Werten. */
    fun fill(out: FloatArray, offset: Int = 0, count: Int = out.size - offset) {
        for (i in offset until offset + count) out[i] = next()
    }

    fun next(): Float {
        if (sound == SleepSound.BINAURAL) return nextBinaural()
        val raw = when (sound) {
            // Pegel grob angeglichen (weisses Rauschen klingt bei gleicher Leistung lauter).
            SleepSound.WHITE -> white() * 0.14
            SleepSound.PINK -> pink() * 0.36
            SleepSound.BROWN -> brown() * 0.55
            SleepSound.FAN -> fan()
            // Einschlaf-Wellen: oben (zwei Kanäle). Aufnahmen werden nicht erzeugt (siehe init).
            SleepSound.BINAURAL, SleepSound.RAIN, SleepSound.OCEAN, SleepSound.FOREST, SleepSound.FIRE, SleepSound.BROOK -> 0.0
        }
        // Gleichspannung entfernen, dann weich begrenzen: nie über PEAK, ohne harte Kanten.
        val y = raw - dcIn + dcPole * dcOut
        dcIn = raw
        dcOut = y
        return limit(y)
    }

    /** Weich begrenzen: nie über PEAK, ohne harte Kanten. */
    private fun limit(y: Double): Float = (SleepSounds.PEAK * tanh(y / SleepSounds.PEAK)).toFloat()

    /**
     * Abwechselnd links und rechts. Sinus je ≈ −30 dBFS RMS, Rauschen ≈ −40 dBFS; ohne Gleichanteil
     * (darum kein Filter, der sonst beide Kanäle vermischen würde).
     */
    private fun nextBinaural(): Float {
        if (rightPending) {
            rightPending = false
            return right
        }
        val bed = pink() * 0.052
        leftPhase += BINAURAL_LEFT_HZ / sampleRate
        if (leftPhase >= 1.0) leftPhase -= 1.0
        rightPhase += BINAURAL_RIGHT_HZ / sampleRate
        if (rightPhase >= 1.0) rightPhase -= 1.0
        right = limit(BINAURAL_AMP * sin(2.0 * PI * rightPhase) + bed)
        rightPending = true
        return limit(BINAURAL_AMP * sin(2.0 * PI * leftPhase) + bed)
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

    /** Gleichmässig und sanft: gedämpftes tiefes Rauschen, das Brummen knapp hörbar (Phase durchgehend). */
    private fun fan(): Double {
        fanLow = fanPole * fanLow + (1.0 - fanPole) * brown()
        humPhase += 50.0 / sampleRate
        if (humPhase >= 1.0) humPhase -= 1.0
        val hum = 0.010 * sin(2.0 * PI * humPhase) + 0.005 * sin(4.0 * PI * humPhase)
        return fanLow * 0.6 + hum
    }

    private companion object {
        const val FAN_CUTOFF_HZ = 300.0
        const val BINAURAL_LEFT_HZ = 200.0
        const val BINAURAL_RIGHT_HZ = 202.0

        /** Spitze des Sinus: RMS = 0,0447 / √2 ≈ 0,032 (−30 dBFS). */
        const val BINAURAL_AMP = 0.0447
    }
}

