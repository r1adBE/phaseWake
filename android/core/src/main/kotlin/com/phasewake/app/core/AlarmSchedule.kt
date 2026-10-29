package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Zeitplan einer Weck-Nacht: wann das Fenster beginnt und wann spätestens geweckt wird. */
data class WakePlan(
    /** Kennung des Weckers ([AlarmSpec.id]), zu dem die Nacht gehört. */
    val alarmId: String,
    /** Beginn des Weckfensters: ab hier misst die Uhr und darf wecken. */
    val windowStart: Instant,
    /** Zielzeit: spätester Weckzeitpunkt, an dem in jedem Fall geklingelt wird. */
    val target: Instant,
)

/**
 * Berechnet, wann ein Wecker das nächste Mal fällig ist.
 *
 * Sommer- und Winterzeit: Fällt die Zielzeit in die übersprungene Stunde, wird sie um die Länge der
 * Lücke verschoben (02:30 → 03:30, Verhalten von [ZonedDateTime.of]); in der doppelten Stunde gilt
 * die frühere.
 */
object AlarmSchedule {

    /** Wie weit voraus gesucht wird; eine Woche plus ein Tag deckt jeden Wochentag ab. */
    private const val SEARCH_DAYS = 8L

    /**
     * Grenze der Suche, wenn Termine ausfallen (Überspringen, Feiertage): gut ein Jahr. Auch eine
     * unsinnige Feiertagsliste (jeder Tag) endet so mit `null` statt in einer endlosen Suche.
     */
    const val MAX_SEARCH_DAYS: Long = 370L

    /**
     * Nächste Zielzeit des Weckers, die echt nach [now] liegt, oder `null`, wenn er aus ist.
     *
     * Läuft das Fenster schon (now zwischen Fensterbeginn und Zielzeit), ist das die heutige Zielzeit.
     * Bei wiederholten Weckern fallen Termine bis [AlarmSpec.skipUntil] aus und – mit
     * [AlarmSpec.skipHolidays] – solche, deren Datum (in [zone]) in [holidays] liegt.
     */
    fun nextTarget(spec: AlarmSpec, now: Instant, zone: ZoneId, holidays: Set<LocalDate> = emptySet()): Instant? {
        if (!spec.enabled) return null
        val time = LocalTime.of(spec.hour, spec.minute)
        val today = now.atZone(zone).toLocalDate()
        val limit = if (mayDrop(spec, holidays)) MAX_SEARCH_DAYS else SEARCH_DAYS
        for (offset in 0..limit) {
            val date = today.plusDays(offset)
            if (!spec.isOneShot && date.dayOfWeek !in spec.days) continue
            val target = ZonedDateTime.of(date, time, zone).toInstant()
            if (target.isAfter(now) && !dropped(spec, target, date, holidays)) return target
        }
        return null
    }

    /** Ob bei diesem Wecker überhaupt Termine ausfallen können (nur wiederholte Wecker). */
    private fun mayDrop(spec: AlarmSpec, holidays: Set<LocalDate>): Boolean =
        !spec.isOneShot && (spec.skipUntil > 0L || (spec.skipHolidays && holidays.isNotEmpty()))

    /** Ob der Termin [target] am Datum [date] ausfällt: übersprungen oder Feiertag. */
    private fun dropped(spec: AlarmSpec, target: Instant, date: LocalDate, holidays: Set<LocalDate>): Boolean {
        if (spec.isOneShot) return false
        if (target.toEpochMilli() <= spec.skipUntil) return true
        return spec.skipHolidays && date in holidays
    }

    /** Plan für die nächste Nacht des Weckers, oder `null`, wenn er aus ist. Ausfälle wie bei [nextTarget]. */
    fun nextPlan(spec: AlarmSpec, now: Instant, zone: ZoneId, holidays: Set<LocalDate> = emptySet()): WakePlan? {
        val target = nextTarget(spec, now, zone, holidays) ?: return null
        return WakePlan(spec.id, windowStart(target, spec.windowMinutes), target)
    }

    /** Der früheste Plan über alle Wecker, oder `null`, wenn keiner aktiv ist. */
    fun earliestPlan(specs: Collection<AlarmSpec>, now: Instant, zone: ZoneId, holidays: Set<LocalDate> = emptySet()): WakePlan? =
        specs.mapNotNull { nextPlan(it, now, zone, holidays) }.minWithOrNull(compareBy({ it.target }, { it.alarmId }))

    /**
     * «Nächstes Mal überspringen»: [AlarmSpec.skipUntil] auf die nächste Zielzeit nach allen Regeln (auch nach
     * Feiertagen). Einmal-Wecker, ausgeschaltete Wecker oder ohne Termin: unverändert.
     */
    fun skipNext(spec: AlarmSpec, now: Instant, zone: ZoneId, holidays: Set<LocalDate> = emptySet()): AlarmSpec {
        if (spec.isOneShot) return spec
        val next = nextTarget(spec, now, zone, holidays) ?: return spec
        return spec.copy(skipUntil = next.toEpochMilli())
    }

    /** Hebt das Überspringen auf. */
    fun undoSkip(spec: AlarmSpec): AlarmSpec = spec.copy(skipUntil = 0L)

    /** Datum des übersprungenen Termins, solange er noch bevorsteht ([AlarmSpec.skipUntil] nach [now]); sonst `null`. */
    fun skippedDate(spec: AlarmSpec, now: Instant, zone: ZoneId): LocalDate? {
        if (spec.isOneShot || spec.skipUntil <= now.toEpochMilli()) return null
        return Instant.ofEpochMilli(spec.skipUntil).atZone(zone).toLocalDate()
    }

    /**
     * Feiertag, an dem der Wecker als Nächstes geweckt hätte: das Datum des nächsten Termins ohne die
     * Feiertagsregel, wenn es in [holidays] liegt und [AlarmSpec.skipHolidays] gilt. Sonst `null`.
     */
    fun skippedHoliday(spec: AlarmSpec, now: Instant, zone: ZoneId, holidays: Set<LocalDate>): LocalDate? {
        if (!spec.skipHolidays || spec.isOneShot || holidays.isEmpty()) return null
        val date = nextTarget(spec.copy(skipHolidays = false), now, zone)?.atZone(zone)?.toLocalDate() ?: return null
        return date.takeIf { it in holidays }
    }

    /**
     * Zielzeit bei Einschlaf-Modus: Einschlafzeit plus Schlafdauer, aber nie später als die
     * eingestellte Weckzeit [latest].
     */
    fun sleepTarget(onset: Instant, sleepDurationMinutes: Int, latest: Instant): Instant {
        val wanted = onset.plus(Duration.ofMinutes(sleepDurationMinutes.toLong()))
        return if (wanted.isBefore(latest)) wanted else latest
    }

    /**
     * Beginn des Weckfensters: [target] minus [windowMinutes] Minuten. Als feste Dauer gerechnet, damit
     * eine Zeitumstellung im Fenster dessen Länge nicht verändert.
     */
    fun windowStart(target: Instant, windowMinutes: Int): Instant =
        target.minus(Duration.ofMinutes(windowMinutes.toLong()))
}
