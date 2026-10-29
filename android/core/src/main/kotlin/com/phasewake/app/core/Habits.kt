package com.phasewake.app.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

/**
 * Regelmässigkeit: wie oft die Bettzeit (Einschlafbeginn laut Gesundheits-App) in einer Woche
 * höchstens [TOLERANCE_MINUTES] Minuten vom Median der letzten [MEDIAN_NIGHTS] Nächte abweicht.
 * Ohne Gesundheitsdaten gibt es nichts zu zählen (die Zeile bleibt verborgen).
 */
object Regularity {
    /** Höchste Abweichung vom Median in Minuten, die noch als regelmässig gilt. */
    const val TOLERANCE_MINUTES: Int = 30

    /** Gezählte Nachtdaten (eine Woche). */
    const val WEEK_NIGHTS: Int = 7

    /** Nachtdaten, aus denen der Median der Bettzeit kommt (zwei Wochen). */
    const val MEDIAN_NIGHTS: Int = 14

    /** Bettzeiten als (Nachtdatum, Einschlafbeginn) aus den Nächten der Gesundheits-App. */
    fun bedtimesOf(sleeps: List<SleepSummary>, zone: ZoneId): List<Pair<LocalDate, Instant>> =
        sleeps.map { SleepNotes.nightDate(it.end, zone) to it.start }

    /**
     * (regelmässig, gezählt) für die [WEEK_NIGHTS] Nachtdaten bis [until] (einschliesslich), gemessen
     * am Median der [MEDIAN_NIGHTS] Nachtdaten bis [until]. Je Nachtdatum zählt die früheste Bettzeit.
     * [until] `null`: das jüngste Nachtdatum mit Daten. Ohne Daten (0, 0).
     */
    fun count(
        bedtimes: List<Pair<LocalDate, Instant>>,
        zone: ZoneId,
        toleranceMinutes: Int = TOLERANCE_MINUTES,
        until: LocalDate? = null,
    ): Pair<Int, Int> {
        val byDate = bedtimes.groupBy({ it.first }, { it.second }).mapValues { (_, list) -> list.min() }
        val end = until ?: byDate.keys.maxOrNull() ?: return 0 to 0
        val medianDates = byDate.filterKeys { !it.isAfter(end) && it.isAfter(end.minusDays(MEDIAN_NIGHTS.toLong())) }
        if (medianDates.isEmpty()) return 0 to 0
        val median = SoundEventDetector.median(medianDates.values.map { minutesFromNoon(it, zone) })
        val week = medianDates.filterKeys { it.isAfter(end.minusDays(WEEK_NIGHTS.toLong())) }
        return week.values.count { abs(minutesFromNoon(it, zone) - median) <= toleranceMinutes + 1e-9 } to week.size
    }

    /** Uhrzeit als Minuten ab Mittag, damit 23:30 und 00:30 nahe beieinander liegen. */
    fun minutesFromNoon(at: Instant, zone: ZoneId): Double {
        val t = at.atZone(zone).toLocalTime()
        return Math.floorMod(t.hour * 60 + t.minute - 12 * 60, 24 * 60).toDouble()
    }
}

/** Serien: Nächte in Folge mit erreichtem Schlafziel. Keine Punkte, keine Abzeichen. */
object Streak {
    /** Zeitraum der längsten Serie in Nachtdaten, gleich lang wie der Verlauf ([NightHistory.MAX_NIGHTS]). */
    const val LONGEST_DAYS: Int = 90

    /** Schlafziel erreicht je Nachtdatum (aus der Gesundheits-App; mehrere Nächte am selben Datum → die längste). */
    fun reachedByNight(sleeps: List<SleepSummary>, zone: ZoneId, goalMinutes: Int): Map<LocalDate, Boolean> =
        NoteEffect.asleepByNight(sleeps, zone).mapValues { SleepGoal.reached(it.value, goalMinutes) }

    /**
     * Aktuelle Serie bis zur jüngsten Nacht. [today] = Nachtdatum von «jetzt» ([SleepNotes.nightDate]):
     * Hat diese Nacht noch keine Daten (Abend), beginnt die Zählung bei der Nacht davor. Eine Nacht
     * ohne Daten oder mit verfehltem Ziel beendet die Serie.
     */
    fun current(reached: Map<LocalDate, Boolean>, today: LocalDate): Int {
        var date = if (reached.containsKey(today)) today else today.minusDays(1)
        var count = 0
        while (reached[date] == true) {
            count++
            date = date.minusDays(1)
        }
        return count
    }

    /** Längste Serie innerhalb der [days] Nachtdaten bis [today]. */
    fun longest(reached: Map<LocalDate, Boolean>, today: LocalDate, days: Int = LONGEST_DAYS): Int {
        var best = 0
        var run = 0
        var date = today.minusDays(days - 1L)
        while (!date.isAfter(today)) {
            if (reached[date] == true) {
                run++
                best = maxOf(best, run)
            } else {
                run = 0
            }
            date = date.plusDays(1)
        }
        return best
    }
}

/**
 * Wochenrückblick über die Nachtdaten [from] bis [to] (Sonntag bis Samstag; am Sonntag ist die
 * Samstagnacht die letzte abgeschlossene).
 */
data class WeeklySummary(
    /** Erstes Nachtdatum der Woche (Sonntag). */
    val from: LocalDate,
    /** Letztes Nachtdatum der Woche (Samstag). */
    val to: LocalDate,
    /** Durchschnittlicher Schlaf in Minuten; `null` ohne Gesundheitsdaten. */
    val asleepAverage: Long?,
    /** Nächte mit erreichtem Schlafziel (von [nightsWithSleep]). */
    val goalReached: Int,
    /** Nächte mit Schlafdaten der Gesundheits-App in dieser Woche. */
    val nightsWithSleep: Int,
    /** Regelmässige Abende (von [regularCounted]). */
    val regular: Int,
    /** Abende mit Bettzeit (Nenner zu [regular]). */
    val regularCounted: Int,
    /** Durchschnittliches Befinden; `null` ohne Angaben. */
    val feelingAverage: Double?,
    /** Notiz mit der deutlichsten Wirkung (über die letzten 90 Nächte), falls eine. */
    val strongestNote: NoteEffect?,
) {
    /** Gesundheitsdaten in dieser Woche. */
    val hasSleep: Boolean get() = nightsWithSleep > 0

    /** Etwas zu berichten: Schlaf oder Befinden. */
    val hasData: Boolean get() = hasSleep || feelingAverage != null

    companion object {
        /** Mitteilung am Sonntag um 18:00. */
        val NOTIFY_DAY: DayOfWeek = DayOfWeek.SUNDAY

        /** Stunde der Mitteilung (Ortszeit). */
        const val NOTIFY_HOUR: Int = 18

        /** Letztes Nachtdatum der zuletzt abgeschlossenen Woche: der Samstag vor [today] (Kalenderdatum). */
        fun weekEnd(today: LocalDate): LocalDate = today.with(TemporalAdjusters.previous(DayOfWeek.SATURDAY))

        /** Die Karte im Verlauf erscheint am Sonntag und am Montag. */
        fun cardVisible(today: LocalDate): Boolean = today.dayOfWeek == DayOfWeek.SUNDAY || today.dayOfWeek == DayOfWeek.MONDAY

        /** Nächster Zeitpunkt der Mitteilung echt nach [after]: Sonntag 18:00 Ortszeit. */
        fun nextNotification(after: Instant, zone: ZoneId): Instant {
            var date = after.atZone(zone).toLocalDate().with(TemporalAdjusters.nextOrSame(NOTIFY_DAY))
            while (true) {
                val at = date.atTime(NOTIFY_HOUR, 0).atZone(zone).toInstant()
                if (at.isAfter(after)) return at
                date = date.plusWeeks(1)
            }
        }

        /**
         * Rückblick für die Woche, die am Samstag [weekEnd] endet. Schlaf aus der
         * Gesundheits-App, Befinden aus den Uhr-Nächten; Wochen ohne beides ergeben [hasData] `false`.
         */
        fun of(
            nights: List<NightRecord>,
            sleeps: List<SleepSummary>,
            notes: Map<LocalDate, List<String>>,
            zone: ZoneId,
            goalMinutes: Int,
            weekEnd: LocalDate,
        ): WeeklySummary {
            val from = weekEnd.minusDays(Regularity.WEEK_NIGHTS - 1L)
            fun inWeek(date: LocalDate) = !date.isBefore(from) && !date.isAfter(weekEnd)
            val asleep = NoteEffect.asleepByNight(sleeps, zone).filterKeys(::inWeek)
            val (regular, counted) = Regularity.count(Regularity.bedtimesOf(sleeps, zone), zone, until = weekEnd)
            // Je Nachtdatum das Befinden der zuletzt geweckten Nacht (wie bei den Notizen).
            val feelings = nights.filter { it.feeling != null }
                .groupBy { SleepNotes.nightDate(it, zone) }
                .filterKeys(::inWeek)
                .map { (_, list) -> list.maxBy { it.wokeAt }.feeling!! }
            val effects = NoteEffect.compute(nights, notes, zone, NoteEffect.asleepByNight(sleeps, zone))
            return WeeklySummary(
                from = from,
                to = weekEnd,
                asleepAverage = asleep.values.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size },
                goalReached = asleep.values.count { SleepGoal.reached(it, goalMinutes) },
                nightsWithSleep = asleep.size,
                regular = regular,
                regularCounted = counted,
                feelingAverage = feelings.takeIf { it.isNotEmpty() }?.average(),
                strongestNote = effects.firstOrNull { it.isNotable },
            )
        }
    }
}

/** Ein Punkt der Abendroutine. Vorgeschlagene Punkte haben einen leeren [text] (Anzeige übersetzt). */
data class RoutineItem(val id: String, val text: String = "")

/**
 * Abendroutine: eine eigene Checkliste (höchstens [MAX_ITEMS] Punkte) für den Abend, sichtbar wie die
 * Notizen ab 17:00 bis zum Weckfenster. Abgehakt wird je Nachtdatum; am nächsten Abend ist alles frei.
 *
 * Speicherformat: Punkte je Zeile «id|Text»; abgehakte Punkte je Nachtdatum «yyyy-MM-dd|id,id».
 */
object Routine {
    /** Vorschlag «Handy weg» (feste Kennung im Speicher). */
    const val PHONE = "PHONE"

    /** Vorschlag «Licht dimmen» (feste Kennung im Speicher). */
    const val LIGHT = "LIGHT"

    /** Vorschlag «Wasser bereit» (feste Kennung im Speicher). */
    const val WATER = "WATER"

    /** Die drei Vorschläge für den Anfang. */
    val DEFAULTS: List<RoutineItem> = listOf(RoutineItem(PHONE), RoutineItem(LIGHT), RoutineItem(WATER))

    /** Höchstzahl der Punkte der Abendroutine. */
    const val MAX_ITEMS: Int = 8

    /** Höchstlänge eines eigenen Punkts in Zeichen. */
    const val MAX_TEXT_LENGTH: Int = 40

    /** Vorsatz der Kennung eigener Punkte («custom:<uuid>»). */
    const val CUSTOM_PREFIX: String = "custom:"

    /** Durchschnitt erst ab so vielen Abenden. */
    const val MIN_EVENINGS: Int = 3

    /** Der Durchschnitt betrachtet so viele Nachtdaten bis heute. */
    const val AVERAGE_NIGHTS: Int = 30

    /** Ob [id] einer der drei Vorschläge ist (Text kommt dann aus der Übersetzung). */
    fun isBuiltIn(id: String): Boolean = id == PHONE || id == LIGHT || id == WATER

    /** Neue Kennung für einen eigenen Punkt aus einer UUID (als Text). */
    fun customId(uuid: String): String = CUSTOM_PREFIX + uuid

    /** Bereinigt einen Text: ohne Zeilenumbrüche und «|» (Trennzeichen im Speicher), getrimmt, höchstens [MAX_TEXT_LENGTH] Zeichen. */
    fun cleanText(text: String): String = text.replace('\n', ' ').replace('\r', ' ').replace('|', ' ').trim().take(MAX_TEXT_LENGTH).trim()

    /** Punkte je Zeile «id|Text», höchstens [MAX_ITEMS]; Vorschläge mit leerem Text. */
    fun encodeItems(items: List<RoutineItem>): String =
        items.take(MAX_ITEMS).joinToString("\n") { "${it.id}|${cleanText(it.text)}" }

    /** Liest [encodeItems]; nie gespeichert (`null`): die Vorschläge. Eigene Punkte ohne Text fallen weg. */
    fun decodeItems(text: String?): List<RoutineItem> {
        if (text == null) return DEFAULTS
        return text.lines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val id = line.substringBefore('|').trim()
            val label = cleanText(line.substringAfter('|', ""))
            when {
                isBuiltIn(id) -> RoutineItem(id)
                id.startsWith(CUSTOM_PREFIX) && id.length > CUSTOM_PREFIX.length && label.isNotEmpty() -> RoutineItem(id, label)
                else -> null
            }
        }.distinctBy { it.id }.take(MAX_ITEMS)
    }

    /** Hakt einen Punkt für ein Nachtdatum ab oder wieder an (gleiches Format und gleiche Grenze wie die Notizen). */
    fun toggle(ticks: Map<LocalDate, List<String>>, date: LocalDate, id: String): Map<LocalDate, List<String>> =
        SleepNotes.toggle(ticks, date, id)

    /** Abgehakte Punkte im Format der Schlafnotizen ([SleepNotes.encode]). */
    fun encodeTicks(ticks: Map<LocalDate, List<String>>): String = SleepNotes.encode(ticks)

    /** Liest [encodeTicks]; unlesbare Zeilen werden übersprungen, `null` ergibt eine leere Zuordnung. */
    fun decodeTicks(text: String?): Map<LocalDate, List<String>> = SleepNotes.decode(text)

    /**
     * Durchschnittlich erledigte Punkte je Abend über die [AVERAGE_NIGHTS] Nachtdaten bis [today]; es
     * zählen nur Abende mit mindestens einem Haken und nur Punkte, die es noch gibt. `null` unter
     * [MIN_EVENINGS] Abenden oder ohne Punkte.
     */
    fun average(ticks: Map<LocalDate, List<String>>, items: List<RoutineItem>, today: LocalDate): Double? {
        if (items.isEmpty()) return null
        val ids = items.map { it.id }.toSet()
        val from = today.minusDays(AVERAGE_NIGHTS - 1L)
        val counts = ticks.filterKeys { !it.isBefore(from) && !it.isAfter(today) }
            .values.map { list -> list.count { it in ids } }.filter { it > 0 }
        return counts.takeIf { it.size >= MIN_EVENINGS }?.average()
    }
}
