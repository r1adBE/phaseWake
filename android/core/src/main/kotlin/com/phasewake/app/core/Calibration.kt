package com.phasewake.app.core

/**
 * Passt die Schwellen an den Nutzer an. Regel siehe `docs/PHASENERKENNUNG.md`, Abschnitt 4.
 */
object Calibration {
    /** Erst ab so vielen gemessenen Nächten wird angepasst; vorher gelten die Startwerte. */
    const val MIN_NIGHTS: Int = 3

    /** Höchstens so viele jüngste Nächte fliessen ein, damit sich die Schwellen an Veränderungen anpassen. */
    const val MAX_NIGHTS: Int = 14

    /** Mindestanzahl entscheidbarer Epochen über alle Nächte; darunter wäre das 80. Perzentil zu zufällig. */
    const val MIN_VALUES: Int = 30

    /** Verhältnis Wach- zu Leicht-Schwelle, aus den Startwerten (0.100 / 0.002). */
    const val WAKE_TO_LIGHT_RATIO: Double = 50.0

    /**
     * @param nights Bewegung aller Epochen je aufgezeichneter Nacht, älteste zuerst.
     *   Die Aufwärm-Epochen werden hier entfernt.
     * @param defaults Startwerte; von ihnen werden alle übrigen Parameter übernommen.
     * @return [defaults] mit angepassten Schwellen, oder [defaults] selbst bei zu wenig Daten.
     */
    fun calibrate(nights: List<List<Double?>>, defaults: DetectorConfig = DetectorConfig()): DetectorConfig {
        // Nur Nächte mit mindestens einem entscheidbaren Wert zählen (Uhr nicht getragen = keine Nacht).
        val measured = nights.map { it.drop(defaults.warmupEpochs).filterNotNull() }.filter { it.isNotEmpty() }
        if (measured.size < MIN_NIGHTS) return defaults

        val values = measured
            .takeLast(MAX_NIGHTS)
            .flatten()
            .sorted()
        if (values.size < MIN_VALUES) return defaults

        // ⌈0.8 · n⌉ ganzzahlig gerechnet, damit Kotlin und Swift exakt dieselbe Position wählen.
        val rank = (4 * values.size + 4) / 5
        val percentile80 = values[rank - 1]

        val light = percentile80.coerceIn(
            DetectorConfig.DEFAULT_LIGHT_THRESHOLD * 0.5,
            DetectorConfig.DEFAULT_LIGHT_THRESHOLD * 2.0,
        )
        return defaults.copy(lightThreshold = light, wakeThreshold = light * WAKE_TO_LIGHT_RATIO)
    }
}
