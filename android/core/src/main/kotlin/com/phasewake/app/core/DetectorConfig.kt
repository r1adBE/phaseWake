package com.phasewake.app.core

/**
 * Parameter der Phasenerkennung.
 *
 * Die Standardwerte sind die Startwerte aus `docs/PHASENERKENNUNG.md`.
 * Nach drei aufgezeichneten Nächten liefert [Calibration] angepasste Schwellen; nach der Lernphase ([Learning]) kommen
 * eigenes Grundrauschen ([personalFloor]) und eigener Ruhepuls ([restingHeartRate]) dazu.
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
    /**
     * Eigenes Grundrauschen aus der Lernphase (m/s², [Learning]); gilt, solange die Messung selbst noch keines hat
     * ([NoiseFloor.floor]). `null` = keines gelernt.
     */
    val personalFloor: Double? = null,
    /**
     * Eigener Ruhepuls aus der Lernphase ([Learning]). Ohne Basispuls des Fensters gilt [RESTING_FALLBACK_FACTOR] × Ruhepuls
     * als Basispuls; sonst begrenzt er den Basispuls auf [restingHeartRate] bis [RESTING_CLAMP_FACTOR] × [restingHeartRate]
     * ([WakeDetector]). `null` = keiner.
     */
    val restingHeartRate: Double? = null,
    /**
     * Grundrauschen der Einschlaf-Messung dieser Nacht (m/s²); gilt wie [personalFloor] und hat Vorrang davor
     * ([NoiseFloor.windowFloor]). `null` = keines.
     */
    val onsetFloor: Double? = null,
) {
    init {
        require(lightThreshold > 0.0) { "lightThreshold muss positiv sein" }
        require(wakeThreshold > lightThreshold) { "wakeThreshold muss über lightThreshold liegen" }
        require(heartRateRise >= 0.0) { "heartRateRise darf nicht negativ sein" }
        require(warmupEpochs >= 0) { "warmupEpochs darf nicht negativ sein" }
        require(awakeHits in 1..awakeWindow) { "awakeHits muss zwischen 1 und awakeWindow liegen" }
        require(lightHits in 1..lightWindow) { "lightHits muss zwischen 1 und lightWindow liegen" }
        require(personalFloor == null || personalFloor >= 0.0) { "personalFloor darf nicht negativ sein" }
        require(restingHeartRate == null || restingHeartRate > 0.0) { "restingHeartRate muss positiv sein" }
        require(onsetFloor == null || onsetFloor >= 0.0) { "onsetFloor darf nicht negativ sein" }
    }

    /** Grösstes Fenster beider Regeln; so viele Epochen merkt sich der [WakeDetector]. */
    internal val maxWindow: Int get() = maxOf(awakeWindow, lightWindow)

    /**
     * Kopie mit den gelernten Werten der Lernphase; ungültige Werte (siehe [Learning.validFloor] und
     * [Learning.validRestingHeartRate], z. B. aus einer kaputten Nachricht) gelten als keiner, statt beim Start des
     * Fensters an [require] zu scheitern. Identisch zu `DetectorConfig.withLearned` (Swift).
     */
    fun withLearned(floor: Double?, restingHeartRate: Double?): DetectorConfig =
        copy(personalFloor = Learning.validFloor(floor), restingHeartRate = Learning.validRestingHeartRate(restingHeartRate))

    /** Kopie mit dem Grundrauschen der Einschlaf-Messung dieser Nacht ([onsetFloor]); ungültig gilt als keines. */
    fun withOnsetFloor(floor: Double?): DetectorConfig = copy(onsetFloor = Learning.validFloor(floor))

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

        /** Der Basispuls ist der Median der Pulse aus so vielen ersten Epochen des Fensters (mindestens der Aufwärmphase). */
        const val BASELINE_EPOCHS: Int = 6

        /** … sobald es mindestens so viele Pulse darunter gibt; vorher gilt der Ersatz aus dem Ruhepuls oder keiner. */
        const val MIN_BASELINE_HEART_RATES: Int = 2

        /**
         * Ersatz-Basispuls ohne Puls zu Fensterbeginn: gelernter Ruhepuls (10. Perzentil der Nacht) mal diesem Faktor. Der
         * Ruhepuls selbst liegt unter dem morgendlichen Puls; als Basispuls wäre der Anstieg fast immer erfüllt.
         */
        const val RESTING_FALLBACK_FACTOR: Double = 1.10

        /** Mit gelerntem Ruhepuls liegt der Basispuls des Fensters zwischen Ruhepuls und Ruhepuls mal diesem Faktor. */
        const val RESTING_CLAMP_FACTOR: Double = 1.3
    }
}
