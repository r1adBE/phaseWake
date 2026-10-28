package com.phasewake.app.core

/**
 * Mittelt die Pulswerte einer Epoche. Regel siehe `docs/PHASENERKENNUNG.md`, Abschnitt 2.
 *
 * Nicht threadsicher; Aufrufe aus demselben Thread wie [MotionEnergyAccumulator].
 */
class HeartRateAccumulator {
    private var sum = 0.0
    private var count = 0

    /** Nimmt einen Pulswert auf. Unplausible Werte (≤ 0 oder > 250) werden ignoriert. */
    fun add(bpm: Double) {
        if (!bpm.isFinite() || bpm <= 0.0 || bpm > MAX_PLAUSIBLE_BPM) return
        sum += bpm
        count++
    }

    /** Schliesst die Epoche ab. @return Mittelwert oder `null` ohne gültigen Wert. */
    fun closeEpoch(): Double? {
        val mean = if (count > 0) sum / count else null
        sum = 0.0
        count = 0
        return mean
    }

    private companion object {
        const val MAX_PLAUSIBLE_BPM = 250.0
    }
}
