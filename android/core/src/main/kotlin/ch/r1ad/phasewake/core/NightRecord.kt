package ch.r1ad.phasewake.core

import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Eine aufgezeichnete Weck-Nacht. Bleibt auf dem Gerät; Grundlage für Verlauf und Kalibrierung.
 *
 * @property epochs Alle Epochen des Fensters ab Fensterbeginn, inklusive Aufwärmphase.
 */
data class NightRecord(
    val alarmId: String,
    val windowStart: Instant,
    val target: Instant,
    val wokeAt: Instant,
    val reason: WakeReason,
    val snoozes: Int,
    val epochs: List<Epoch>,
) {
    /** Wie viele Minuten vor der Zielzeit geweckt wurde (0 bei Zielzeit). */
    val minutesBeforeTarget: Long get() = Duration.between(wokeAt, target).toMinutes().coerceAtLeast(0)
}

/** Aufbewahrung und anonymer Export des Verlaufs. */
object NightHistory {
    const val MAX_NIGHTS: Int = 90

    /** Behält die jüngsten [MAX_NIGHTS] Nächte, nach Fensterbeginn sortiert (älteste zuerst). */
    fun trim(nights: List<NightRecord>): List<NightRecord> =
        nights.sortedBy { it.windowStart }.takeLast(MAX_NIGHTS)

    /** Bewegungen je Nacht für [Calibration.calibrate], älteste zuerst. */
    fun motionsForCalibration(nights: List<NightRecord>): List<List<Double?>> =
        trim(nights).map { night -> night.epochs.map { it.motion } }

    /**
     * Anonymer CSV-Export: keine Kennungen, keine Datums- oder Uhrzeitangaben, nur relative Werte.
     * Nächte werden durchnummeriert (1 = älteste). Dezimalpunkt, Komma als Trenner.
     */
    fun exportCsv(nights: List<NightRecord>): String = buildString {
        append("night,window_minutes,reason,woke_minutes_before_target,snoozes,epoch,motion,hr\n")
        trim(nights).forEachIndexed { nightIndex, night ->
            val windowMinutes = Duration.between(night.windowStart, night.target).toMinutes()
            night.epochs.forEachIndexed { epochIndex, epoch ->
                append(nightIndex + 1).append(',')
                append(windowMinutes).append(',')
                append(night.reason.name).append(',')
                append(night.minutesBeforeTarget).append(',')
                append(night.snoozes).append(',')
                append(epochIndex).append(',')
                append(epoch.motion?.let { String.format(Locale.ROOT, "%.6f", it) } ?: "").append(',')
                append(epoch.heartRate?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "").append('\n')
            }
        }
    }
}
