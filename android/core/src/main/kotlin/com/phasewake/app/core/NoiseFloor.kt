package com.phasewake.app.core

import java.util.Locale

/**
 * Schwelle relativ zum Rauschen der Uhr. Regel siehe `docs/PHASENERKENNUNG.md`, Abschnitt 3.
 *
 * Manche Uhren messen auch beim Stillliegen deutlich mehr Bewegung als die Startwerte annehmen (Galaxy Watch,
 * Befund 07.11.2026: ruhig rund 0.02 m/s², Leicht-Schwelle 0.002). Dann zählte jede Epoche als leichte Bewegung.
 * Darum gilt je Messung eine Untergrenze aus dem Grundrauschen dieser Nacht; sie hebt die Schwellen nur an.
 *
 * Die Swift-Umsetzung (`ios/PhaseWakeCore`) muss sich identisch verhalten.
 */
object NoiseFloor {
    /** Erst ab so vielen gemessenen Epochen gibt es ein Grundrauschen; vorher gelten die Schwellen unverändert. */
    const val MIN_FLOOR_EPOCHS: Int = 4

    /** Leicht-Schwelle mindestens dieses Vielfache des Grundrauschens. */
    const val LIGHT_FACTOR: Double = 2.5

    /** Wach-Schwelle mindestens dieses Vielfache des Grundrauschens. */
    const val WAKE_FACTOR: Double = 5.0

    /**
     * Obergrenze des Grundrauschens (m/s²): Beginnt eine Messung unruhig, darf mässige Bewegung nicht als ruhig gelten.
     * Rund das Doppelte des stillen Mittels der Galaxy Watch (07.11.2026); so bleibt Leicht ≤ 0.1 und Wach ≤ 0.2.
     */
    const val MAX_FLOOR: Double = 0.04

    /**
     * Grundrauschen: 20. Perzentil der vorhandenen Bewegungen (Rangmethode, aufsteigend sortiert, Position ⌈0.2 · n⌉,
     * gezählt ab 1), höchstens [MAX_FLOOR]. Leere Bewegungen zählen nicht.
     * @param personal eigenes Grundrauschen aus der Lernphase ([DetectorConfig.personalFloor]): gilt (höchstens
     *   [MAX_FLOOR]), solange die Messung noch weniger als [MIN_FLOOR_EPOCHS] Werte hat.
     * @return bei weniger als [MIN_FLOOR_EPOCHS] Werten [personal] bzw. `null`.
     */
    fun floor(motions: List<Double?>, personal: Double? = null): Double? {
        val values = motions.filterNotNull().sorted()
        if (values.size < MIN_FLOOR_EPOCHS) return personal?.let { minOf(it, MAX_FLOOR) }
        // ⌈0.2 · n⌉ ganzzahlig gerechnet, damit Kotlin und Swift exakt dieselbe Position wählen.
        val rank = (values.size + 4) / 5
        return minOf(values[rank - 1], MAX_FLOOR)
    }

    /** Wirksame Leicht-Schwelle: [threshold] (Startwert oder kalibriert), mindestens [LIGHT_FACTOR] × [floor]. */
    fun light(threshold: Double, floor: Double?): Double = if (floor == null) threshold else maxOf(threshold, LIGHT_FACTOR * floor)

    /** Wirksame Leicht-Schwelle zu [config]. */
    fun light(config: DetectorConfig, floor: Double?): Double = light(config.lightThreshold, floor)

    /** Wirksame Wach-Schwelle: die aus [config], mindestens [WAKE_FACTOR] × [floor]. */
    fun wake(config: DetectorConfig, floor: Double?): Double =
        if (floor == null) config.wakeThreshold else maxOf(config.wakeThreshold, WAKE_FACTOR * floor)

    /**
     * Kennzahlen fürs Diagnose-Protokoll am Ende einer Messung: «floor=… light=… wake=…» (4 Nachkommastellen,
     * «-» ohne Grundrauschen), aus allen Bewegungen [motions] der Messung (mit dem eigenen aus [config], solange sie
     * zu wenige hat).
     */
    fun summary(config: DetectorConfig, motions: List<Double?>): String {
        val noise = floor(motions, config.personalFloor)
        return "floor=${format(noise)} light=${format(light(config, noise))} wake=${format(wake(config, noise))}"
    }

    /** Zahl mit 4 Nachkommastellen fürs Diagnose-Protokoll, «-» für `null`. */
    fun format(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.4f", it) } ?: "-"
}
