package ch.r1ad.phasewake.core

/**
 * Parameter der Phasenerkennung.
 *
 * Die Standardwerte sind die Startwerte aus `docs/PHASENERKENNUNG.md`.
 * Nach drei aufgezeichneten Nächten liefert [Calibration] angepasste Schwellen.
 *
 * @property lightThreshold Bewegung (m/s²), ab der eine Epoche als leichte Bewegung zählt.
 * @property wakeThreshold Bewegung (m/s²), ab der eine Epoche als Wachsein zählt.
 * @property heartRateRise Nötiger Pulsanstieg gegenüber dem Basispuls, als Anteil (0.05 = 5 %).
 * @property warmupEpochs Epochen zu Fensterbeginn, die nur beobachtet werden.
 * @property awakeWindow Anzahl der letzten Epochen, die die Wach-Regel betrachtet.
 * @property awakeHits Nötige Treffer im [awakeWindow].
 * @property lightWindow Anzahl der letzten Epochen, die die Leichtschlaf-Regel betrachtet.
 * @property lightHits Nötige Treffer im [lightWindow].
 */
data class DetectorConfig(
    val lightThreshold: Double = DEFAULT_LIGHT_THRESHOLD,
    val wakeThreshold: Double = DEFAULT_WAKE_THRESHOLD,
    val heartRateRise: Double = DEFAULT_HEART_RATE_RISE,
    val warmupEpochs: Int = DEFAULT_WARMUP_EPOCHS,
    val awakeWindow: Int = 3,
    val awakeHits: Int = 2,
    val lightWindow: Int = 4,
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

        const val DEFAULT_LIGHT_THRESHOLD: Double = 0.002
        const val DEFAULT_WAKE_THRESHOLD: Double = 0.100
        const val DEFAULT_HEART_RATE_RISE: Double = 0.05
        const val DEFAULT_WARMUP_EPOCHS: Int = 2
    }
}
