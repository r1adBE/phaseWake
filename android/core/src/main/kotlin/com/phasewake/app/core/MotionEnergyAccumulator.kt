package com.phasewake.app.core

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Verdichtet rohe Beschleunigungswerte zur Bewegung einer Epoche (Aktigraphie).
 *
 * Regel siehe `docs/PHASENERKENNUNG.md`, Abschnitt 1. Kurz: Takt auf höchstens ~28 Hz begrenzen,
 * Abstand aufeinanderfolgender Messvektoren bilden, Sensorrauschen abziehen, über die Epoche mitteln.
 *
 * Nicht threadsicher; der Weck-Dienst ruft [add] und [closeEpoch] aus demselben Thread auf.
 */
class MotionEnergyAccumulator(
    private val minIntervalNanos: Long = DEFAULT_MIN_INTERVAL_NANOS,
    private val maxGapNanos: Long = DEFAULT_MAX_GAP_NANOS,
    private val noiseFloor: Double = DEFAULT_NOISE_FLOOR,
    private val minDiffs: Int = DEFAULT_MIN_DIFFS,
) {
    private var hasLast = false
    private var lastTimestamp = 0L
    private var lastX = 0.0
    private var lastY = 0.0
    private var lastZ = 0.0

    private var sum = 0.0
    private var count = 0

    /**
     * Nimmt einen Beschleunigungswert auf.
     *
     * @param timestampNanos Monotoner Zeitstempel in Nanosekunden.
     * @param x Beschleunigung in m/s².
     * @param y Beschleunigung in m/s².
     * @param z Beschleunigung in m/s².
     */
    fun add(timestampNanos: Long, x: Double, y: Double, z: Double) {
        if (hasLast) {
            val dt = timestampNanos - lastTimestamp
            // Zu dicht (oder rückwärts): verwerfen, damit beide Plattformen im selben Takt messen.
            if (dt < minIntervalNanos) return
            // Nach einer Sensorlücke ist der Abstand zum alten Wert keine Bewegung.
            if (dt <= maxGapNanos) {
                val dx = x - lastX
                val dy = y - lastY
                val dz = z - lastZ
                sum += max(0.0, sqrt(dx * dx + dy * dy + dz * dz) - noiseFloor)
                count++
            }
        }
        hasLast = true
        lastTimestamp = timestampNanos
        lastX = x
        lastY = y
        lastZ = z
    }

    /**
     * Schliesst die laufende Epoche ab und beginnt eine neue.
     *
     * Der letzte Messwert bleibt Bezugspunkt für die nächste Epoche.
     *
     * @return Bewegung in m/s² oder `null`, wenn weniger als [minDiffs] Differenzen vorlagen.
     */
    fun closeEpoch(): Double? {
        val motion = if (count >= minDiffs) sum / count else null
        sum = 0.0
        count = 0
        return motion
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_NANOS: Long = 35_000_000L
        const val DEFAULT_MAX_GAP_NANOS: Long = 1_000_000_000L
        const val DEFAULT_NOISE_FLOOR: Double = 0.05
        const val DEFAULT_MIN_DIFFS: Int = 300
    }
}
