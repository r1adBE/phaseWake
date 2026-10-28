package ch.r1ad.phasewake.core

/**
 * Messwerte einer Epoche (30 s).
 *
 * @property motion Bewegung in m/s² aus [MotionEnergyAccumulator]; `null`, wenn zu wenig Sensordaten kamen.
 * @property heartRate Mittlerer Puls in Schlägen pro Minute aus [HeartRateAccumulator]; `null`, wenn keiner gemessen wurde.
 */
data class Epoch(
    val motion: Double?,
    val heartRate: Double?,
)
