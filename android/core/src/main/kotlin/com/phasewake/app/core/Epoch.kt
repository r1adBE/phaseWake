package com.phasewake.app.core

/** Messwerte einer Epoche (30 s). */
data class Epoch(
    /** Bewegung in m/s² aus [MotionEnergyAccumulator]; `null`, wenn zu wenig Sensordaten kamen. */
    val motion: Double?,
    /** Mittlerer Puls in Schlägen pro Minute aus [HeartRateAccumulator]; `null`, wenn keiner gemessen wurde. */
    val heartRate: Double?,
)
