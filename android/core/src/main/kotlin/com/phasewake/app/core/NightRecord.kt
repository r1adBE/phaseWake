package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Eine aufgezeichnete Weck-Nacht. Bleibt auf dem Gerät; Grundlage für Verlauf und Kalibrierung.
 *
 * @property epochs Alle Epochen des Fensters ab Fensterbeginn, inklusive Aufwärmphase.
 * @property feeling Wie erholt sich der Nutzer am Morgen fühlte, 1 (müde) bis 5 (erholt); `null` = nicht angegeben.
 */
data class NightRecord(
    val alarmId: String,
    val windowStart: Instant,
    val target: Instant,
    val wokeAt: Instant,
    val reason: WakeReason,
    val snoozes: Int,
    val epochs: List<Epoch>,
    val feeling: Int? = null,
) {
    init {
        require(feeling == null || feeling in FEELING_MIN..FEELING_MAX) { "feeling muss zwischen $FEELING_MIN und $FEELING_MAX liegen" }
    }

    /** Wie viele Minuten vor der Zielzeit geweckt wurde (0 bei Zielzeit und danach). */
    val minutesBeforeTarget: Long get() = Duration.between(wokeAt, target).toMinutes().coerceAtLeast(0)

    /** Wie viele Minuten nach der Zielzeit geweckt wurde, z. B. nach Schlummern (0 bei Zielzeit und davor). */
    val minutesAfterTarget: Long get() = Duration.between(target, wokeAt).toMinutes().coerceAtLeast(0)

    companion object {
        const val FEELING_MIN: Int = 1
        const val FEELING_MAX: Int = 5
    }
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
     * Letzte Spalte `notes`: Schlafnotizen der Nacht mit «;» getrennt, eigene nur als «custom»
     * (am Ende angefügt, damit ältere Leser weiter funktionieren).
     *
     * @param notes Schlafnotizen je Nachtdatum (siehe [SleepNotes.nightDate]).
     */
    fun exportCsv(
        nights: List<NightRecord>,
        notes: Map<java.time.LocalDate, List<String>> = emptyMap(),
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): String = buildString {
        append("night,window_minutes,reason,woke_minutes_before_target,snoozes,feeling,epoch,motion,hr,notes\n")
        trim(nights).forEachIndexed { nightIndex, night ->
            val windowMinutes = Duration.between(night.windowStart, night.target).toMinutes()
            val noteText = notes[SleepNotes.nightDate(night, zone)].orEmpty().joinToString(";") { SleepNotes.exportId(it) }
            night.epochs.forEachIndexed { epochIndex, epoch ->
                append(nightIndex + 1).append(',')
                append(windowMinutes).append(',')
                append(night.reason.name).append(',')
                append(night.minutesBeforeTarget).append(',')
                append(night.snoozes).append(',')
                append(night.feeling?.toString() ?: "").append(',')
                append(epochIndex).append(',')
                append(epoch.motion?.let { String.format(Locale.ROOT, "%.6f", it) } ?: "").append(',')
                append(epoch.heartRate?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "").append(',')
                append(noteText).append('\n')
            }
        }
    }
}
