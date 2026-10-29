package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.util.Locale

/** Eine aufgezeichnete Weck-Nacht. Bleibt auf dem Gerät; Grundlage für Verlauf und Kalibrierung. */
data class NightRecord(
    /** Kennung des Weckers ([AlarmSpec.id]); bleibt, auch wenn der Wecker gelöscht wird. */
    val alarmId: String,
    /** Beginn des Weckfensters. */
    val windowStart: Instant,
    /** Zielzeit (spätester Weckzeitpunkt) dieser Nacht. */
    val target: Instant,
    /** Zeitpunkt des ersten Weckens (vor dem Schlummern). */
    val wokeAt: Instant,
    /** Warum in diesem Moment geweckt wurde. */
    val reason: WakeReason,
    /** Wie oft geschlummert wurde, 0 bis [Snooze.MAX_COUNT]. */
    val snoozes: Int,
    /** Alle Epochen des Fensters ab Fensterbeginn, inklusive Aufwärmphase. */
    val epochs: List<Epoch>,
    /** Wie erholt sich der Nutzer am Morgen fühlte, 1 (müde) bis 5 (erholt); `null` = nicht angegeben. */
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
        /** Schlechtestes Befinden (müde). */
        const val FEELING_MIN: Int = 1

        /** Bestes Befinden (erholt). */
        const val FEELING_MAX: Int = 5
    }
}

/** Aufbewahrung und anonymer Export des Verlaufs. */
object NightHistory {
    /** Höchstens so viele Nächte bleiben gespeichert (rund drei Monate); ältere fallen bei [trim] weg. */
    const val MAX_NIGHTS: Int = 90

    /** Behält die jüngsten [MAX_NIGHTS] Nächte, nach Fensterbeginn sortiert (älteste zuerst). */
    fun trim(nights: List<NightRecord>): List<NightRecord> =
        nights.sortedBy { it.windowStart }.takeLast(MAX_NIGHTS)

    /**
     * Anteil der Nächte in Prozent (gerundet), in denen im leichten Schlaf geweckt wurde. Es zählen nur
     * Nächte, in denen früher wecken erlaubt war: «Genau» ([WakeReason.EXACT]) und kurze Nächte
     * ([WakeReason.SHORT_NIGHT]) fehlen auch im Nenner. `null`, wenn keine solche Nacht da ist.
     */
    fun lightShare(nights: List<NightRecord>): Int? {
        val counted = nights.filter { it.reason != WakeReason.EXACT && it.reason != WakeReason.SHORT_NIGHT }
        if (counted.isEmpty()) return null
        return Math.round(100.0 * counted.count { it.reason == WakeReason.LIGHT_SLEEP } / counted.size).toInt()
    }

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
