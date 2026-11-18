package com.phasewake.app.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Gruppe der Liste im Verlauf ([HistoryGroups.of]): ein einzelner Eintrag, eine Woche oder ein Kalenderjahr.
 * Jeder Eintrag steht in genau einer Gruppe.
 */
sealed interface HistoryGroup<out T> {
    /** Eintrag einzeln (die neusten Nächte). */
    data class Single<T>(val entry: T) : HistoryGroup<T>

    /**
     * Woche ab [start] (Wochenbeginn nach der Sprache des Geräts) bis [end]; [entries] neueste zuerst. Reicht die
     * Woche über die Grenze der einzelnen Nächte oder des Jahres, enthält sie nur ihre Einträge dieser Seite.
     */
    data class Week<T>(val start: LocalDate, val entries: List<T>) : HistoryGroup<T> {
        /** Letzter Tag der Woche (eingeschlossen). */
        val end: LocalDate get() = start.plusDays(6)
    }

    /** Kalenderjahr [year] mit seinen Wochen ([weeks], neueste zuerst). */
    data class Year<T>(val year: Int, val weeks: List<Week<T>>) : HistoryGroup<T> {
        /** Alle Einträge des Jahres, neueste zuerst. */
        val entries: List<T> get() = weeks.flatMap { it.entries }
    }
}

/** Kennzahlen einer Woche oder eines Jahres im Verlauf ([HistoryGroups.summary]). */
data class HistoryGroupSummary(
    /** Nächte (ohne Aufzeichnungen und Geräusche ohne Nacht). */
    val nights: Int,
    /** Durchschnittlicher Schlaf je Nachtdatum mit Daten (ganze Minuten, abgerundet); `null` ohne Daten. */
    val asleepMinutes: Long?,
    /** Durchschnittliches Befinden 1–5 der Nächte mit Befinden; `null` ohne. */
    val feeling: Double?,
)

/**
 * Gruppen der Liste im Verlauf: reine Rechnung, gleich in Swift (`HistoryGroups`).
 *
 * Die [RECENT_NIGHTS] neusten Nächte stehen einzeln oben (egal wie alt), samt allem, was in der Liste vor der
 * fünften Nacht steht (Aufzeichnungen, Geräusche ohne Nacht). Alles danach nach dem Tag des Eintrags (Datum des
 * Morgens, wie auf der Karte): bis ein Jahr vor heute ([yearCutoff], eingeschlossen) je Woche, älter je
 * Kalenderjahr und darin je Woche. Eine Woche über eine dieser Grenzen wird geteilt; jede Seite zeigt nur ihre
 * Einträge.
 */
object HistoryGroups {
    /** So viele neuste Nächte stehen immer einzeln oben. */
    const val RECENT_NIGHTS: Int = 5

    /**
     * Gruppen aus [entries] (neueste zuerst); [dayOf] = Tag des Eintrags, [isNight] = zählt als Nacht.
     * Wochen beginnen mit [firstDay].
     */
    fun <T> of(
        entries: List<T>,
        dayOf: (T) -> LocalDate,
        isNight: (T) -> Boolean,
        today: LocalDate,
        firstDay: DayOfWeek,
    ): List<HistoryGroup<T>> {
        var nights = 0
        val recent = entries.takeWhile { entry ->
            (nights < RECENT_NIGHTS).also { if (isNight(entry)) nights++ }
        }
        val rest = entries.drop(recent.size)
        val cutoff = yearCutoff(today)
        val (newer, older) = rest.partition { !dayOf(it).isBefore(cutoff) }
        val years = older.groupBy { dayOf(it).year }.toSortedMap(reverseOrder()).map { (year, list) ->
            HistoryGroup.Year(year, weeks(list, dayOf, firstDay))
        }
        return recent.map { HistoryGroup.Single(it) } + weeks(newer, dayOf, firstDay) + years
    }

    /** Ältester Tag, der noch in die Wochen gehört: heute vor einem Jahr (29. Februar → 28. Februar). */
    fun yearCutoff(today: LocalDate): LocalDate = today.minusYears(1)

    /** Erster Tag der Woche von [day], die mit [firstDay] beginnt. */
    fun weekStart(day: LocalDate, firstDay: DayOfWeek): LocalDate =
        day.with(TemporalAdjusters.previousOrSame(firstDay))

    /** Wochen aus [entries], neueste zuerst; innerhalb einer Woche die Folge von [entries]. */
    private fun <T> weeks(entries: List<T>, dayOf: (T) -> LocalDate, firstDay: DayOfWeek): List<HistoryGroup.Week<T>> =
        entries.groupBy { weekStart(dayOf(it), firstDay) }.toSortedMap(reverseOrder())
            .map { (start, list) -> HistoryGroup.Week(start, list) }

    /**
     * Kennzahlen von [entries]: [nightDate] = Nachtdatum einer Nacht, `null` für alles andere; Schlaf aus [asleep]
     * je Nachtdatum (zwei Nächte am selben Datum zählen einmal), Befinden je Nacht aus [feeling].
     */
    fun <T> summary(
        entries: List<T>,
        nightDate: (T) -> LocalDate?,
        asleep: Map<LocalDate, Long>,
        feeling: (T) -> Int?,
    ): HistoryGroupSummary {
        val nights = entries.filter { nightDate(it) != null }
        val minutes = nights.mapNotNull { nightDate(it) }.distinct().mapNotNull { asleep[it] }
        val feelings = nights.mapNotNull(feeling)
        return HistoryGroupSummary(
            nights = nights.size,
            asleepMinutes = if (minutes.isEmpty()) null else minutes.sum() / minutes.size,
            feeling = if (feelings.isEmpty()) null else feelings.average(),
        )
    }
}
