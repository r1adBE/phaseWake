package com.phasewake.app.core

/**
 * Parameter der Phasenerkennung.
 *
 * Die Standardwerte sind die Startwerte aus `docs/PHASENERKENNUNG.md`.
 * Nach drei aufgezeichneten Nächten liefert [Calibration] angepasste Schwellen.
 */
data class DetectorConfig(
    /** Bewegung (m/s²), ab der eine Epoche als leichte Bewegung zählt. */
    val lightThreshold: Double = DEFAULT_LIGHT_THRESHOLD,
    /** Bewegung (m/s²), ab der eine Epoche als Wachsein zählt. */
    val wakeThreshold: Double = DEFAULT_WAKE_THRESHOLD,
    /** Nötiger Pulsanstieg gegenüber dem Basispuls, als Anteil (0.05 = 5 %). */
    val heartRateRise: Double = DEFAULT_HEART_RATE_RISE,
    /** Epochen zu Fensterbeginn, die nur beobachtet werden. */
    val warmupEpochs: Int = DEFAULT_WARMUP_EPOCHS,
    /** Anzahl der letzten Epochen, die die Wach-Regel betrachtet. */
    val awakeWindow: Int = 3,
    /** Nötige Treffer im [awakeWindow]. */
    val awakeHits: Int = 2,
    /** Anzahl der letzten Epochen, die die Leichtschlaf-Regel betrachtet. */
    val lightWindow: Int = 4,
    /** Nötige Treffer im [lightWindow]. */
    val lightHits: Int = 2,
) {
    init {
        require(lightThreshold > 0.0) { "lightThreshold muss positiv sein" }
        require(wakeThreshold > lightThreshold) { "wakeThreshold muss über lightThreshold liegen" }
        require(heartRateRise >= 0.0) { "heartRateRise darf nicht negativ sein" }
        require(warmupEpochs >= 0) { "warmupEpochs darf nicht negativ sein" }
        require(awakeHits in 1..awakeWindow) { "awakeHits muss zwischen 1 und awakeWindow liegen" }
        require(lightHits in 1..lightWindow) { "lightHits muss zwischen 1 und lightWindow liegen" }
    }

    /** Grösstes Fenster beider Regeln; so viele Epochen merkt sich der [WakeDetector]. */
    internal val maxWindow: Int get() = maxOf(awakeWindow, lightWindow)

    companion object {
        /** Länge einer Epoche in Sekunden. */
        const val EPOCH_SECONDS: Int = 30

        /** Startwert der Leicht-Schwelle in m/s² (`docs/PHASENERKENNUNG.md`). */
        const val DEFAULT_LIGHT_THRESHOLD: Double = 0.002

        /** Startwert der Wach-Schwelle in m/s². */
        const val DEFAULT_WAKE_THRESHOLD: Double = 0.100

        /** Startwert des nötigen Pulsanstiegs (5 %). */
        const val DEFAULT_HEART_RATE_RISE: Double = 0.05

        /** Aufwärm-Epochen zu Fensterbeginn (2 × 30 s): Bewegung beim Einschalten der Messung zählt nicht. */
        const val DEFAULT_WARMUP_EPOCHS: Int = 2
    }
}
