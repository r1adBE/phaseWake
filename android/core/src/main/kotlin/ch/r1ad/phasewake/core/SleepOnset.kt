package ch.r1ad.phasewake.core

/**
 * Erkennt den Einschlafzeitpunkt für den Einschlaf-Modus.
 * Regel siehe `docs/PHASENERKENNUNG.md`, Abschnitt 6.
 */
object SleepOnset {
    /** 30 Epochen = 15 Minuten durchgehende Ruhe. */
    const val STILL_EPOCHS: Int = 30

    /**
     * @param motions Bewegung je Epoche seit «Gute Nacht», in zeitlicher Reihenfolge.
     * @param threshold Leicht-Schwelle; ruhig heisst Bewegung vorhanden und darunter.
     * @return Index der ersten Epoche der ersten ruhigen Strecke, oder `null`, solange es keine gibt.
     */
    fun find(motions: List<Double?>, threshold: Double = DetectorConfig.DEFAULT_LIGHT_THRESHOLD): Int? {
        var runStart = 0
        var runLength = 0
        for ((index, motion) in motions.withIndex()) {
            if (motion != null && motion < threshold) {
                if (runLength == 0) runStart = index
                runLength++
                if (runLength == STILL_EPOCHS) return runStart
            } else {
                runLength = 0
            }
        }
        return null
    }
}
