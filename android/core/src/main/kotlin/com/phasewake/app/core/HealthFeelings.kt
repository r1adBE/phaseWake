package com.phasewake.app.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Befinden einer Nacht aus einer anderen App (z. B. Samsung Health), wie es im Verlauf steht: Nachtdatum,
 * Zeitpunkt zum Einordnen in Wochen (Ende der Sitzung, wie das Wecken bei eigenen Nächten) und Wert 1–5.
 */
data class HealthFeeling(
    /** Nachtdatum ([HistoryItem.date]). */
    val date: LocalDate,
    /** Ende der Sitzung. */
    val at: Instant,
    /** Befinden ([NightRecord.FEELING_MIN] bis [NightRecord.FEELING_MAX]). */
    val feeling: Int,
)

/**
 * Befinden zu Nächten aus anderen Apps: nur bei Phase Wake gespeichert, je Nachtdatum, nie in Health Connect bzw.
 * HealthKit geschrieben. Gesichert wie die Notizen und mit «Verlauf löschen» gelöscht. Eigene Nächte speichern ihr
 * Befinden weiter in [NightRecord.feeling]. Gleich in Swift (`HealthFeelings`).
 *
 * Speicherformat: je Nachtdatum eine Zeile «yyyy-MM-dd|n», nach Datum sortiert.
 */
object HealthFeelings {
    /** Höchstens so viele Nachtdaten bleiben gespeichert (wie der Verlauf). */
    const val MAX_DATES: Int = NightHistory.MAX_NIGHTS

    /** Setzt das Befinden [feeling] für [date]; ein Wert ausserhalb 1–5 ändert nichts. Höchstens [MAX_DATES] bleiben. */
    fun set(feelings: Map<LocalDate, Int>, date: LocalDate, feeling: Int): Map<LocalDate, Int> =
        if (!valid(feeling)) trim(feelings) else trim(feelings + (date to feeling))

    /** Behält die jüngsten [MAX_DATES] Nachtdaten mit gültigem Wert. */
    fun trim(feelings: Map<LocalDate, Int>): Map<LocalDate, Int> =
        feelings.filterValues(::valid).toSortedMap().entries.toList().takeLast(MAX_DATES).associate { it.key to it.value }

    /** Je Nachtdatum eine Zeile «yyyy-MM-dd|n», nach Datum sortiert. */
    fun encode(feelings: Map<LocalDate, Int>): String =
        trim(feelings).entries.joinToString("\n") { (date, feeling) -> "$date|$feeling" }

    /** Liest [encode]; unlesbare Zeilen und Werte ausserhalb 1–5 werden übersprungen. */
    fun decode(text: String?): Map<LocalDate, Int> {
        val result = sortedMapOf<LocalDate, Int>()
        text.orEmpty().lines().forEach { line ->
            val date = runCatching { LocalDate.parse(line.substringBefore('|')) }.getOrNull() ?: return@forEach
            val feeling = line.substringAfter('|', "").trim().toIntOrNull() ?: return@forEach
            if (valid(feeling)) result[date] = feeling
        }
        return trim(result)
    }

    /**
     * Befinden der Nächte aus anderen Apps, die im Verlauf stehen ([HistoryKind.HEALTH] aus [HealthHistory.merge]) und
     * eines haben. Ist die Nacht in der Quelle weg (oder ausgeblendet), zählt ihr Befinden nicht mehr mit.
     */
    fun of(items: List<HistoryItem>, feelings: Map<LocalDate, Int>): List<HealthFeeling> =
        items.mapNotNull { item ->
            val session = item.session?.takeIf { item.kind == HistoryKind.HEALTH } ?: return@mapNotNull null
            feelings[item.date]?.let { HealthFeeling(item.date, session.end, it) }
        }

    /**
     * Befinden je Nachtdatum für Diagramme, Wochenrückblick und Notiz-Wirkung: eigene Nächte wie bisher (die zuletzt
     * geweckte mit Befinden), dazu [health] für Nachtdaten ohne eigenes Befinden.
     */
    fun byDate(nights: List<NightRecord>, health: List<HealthFeeling>, zone: ZoneId): Map<LocalDate, Int> {
        val own = nights.filter { it.feeling != null }
            .groupBy { SleepNotes.nightDate(it, zone) }
            .mapValues { (_, list) -> list.maxBy { it.wokeAt }.feeling!! }
        // Mehrere Einträge am selben Nachtdatum: der erste zählt (wie in Swift).
        return health.filter { it.date !in own }.distinctBy { it.date }.associate { it.date to it.feeling } + own
    }

    private fun valid(feeling: Int): Boolean = feeling in NightRecord.FEELING_MIN..NightRecord.FEELING_MAX
}
