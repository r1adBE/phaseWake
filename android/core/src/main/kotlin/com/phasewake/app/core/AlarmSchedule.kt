package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Zeitplan einer Weck-Nacht: wann das Fenster beginnt und wann spätestens geweckt wird. */
data class WakePlan(
    /** Kennung des Weckers ([AlarmSpec.id]), zu dem die Nacht gehört. */
    val alarmId: String,
    /** Beginn des Weckfensters: ab hier misst die Uhr und darf wecken. */
    val windowStart: Instant,
    /** Zielzeit: spätester Weckzeitpunkt, an dem in jedem Fall geklingelt wird. */
    val target: Instant,
    /**
     * «Genau» ([AlarmSpec.isExact]): ab [windowStart] wird nur für den Verlauf gemessen; geweckt wird erst zur
     * Zielzeit, nie früher (Grund [WakeReason.EXACT]).
     */
    val exact: Boolean = false,
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
     * Bei wiederholten Weckern fallen Termine bis [AlarmSpec.skipUntil] (Wandzeit, siehe [wallMillis]) aus und
     * – mit [AlarmSpec.skipHolidays] – solche, deren Datum (in [zone]) in [holidays] liegt.
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
            if (target.isAfter(now) && !dropped(spec, wallMillis(target, zone), date, holidays)) return target
        }
        return null
    }

    /** Ob bei diesem Wecker überhaupt Termine ausfallen können (nur wiederholte Wecker). */
    private fun mayDrop(spec: AlarmSpec, holidays: Set<LocalDate>): Boolean =
        !spec.isOneShot && (spec.skipUntil > 0L || (spec.skipHolidays && holidays.isNotEmpty()))

    /** Ob der Termin mit Wandzeit [wall] ([wallMillis]) am Datum [date] ausfällt: übersprungen oder Feiertag. */
    private fun dropped(spec: AlarmSpec, wall: Long, date: LocalDate, holidays: Set<LocalDate>): Boolean {
        if (spec.isOneShot) return false
        if (wall <= spec.skipUntil) return true
        return spec.skipHolidays && date in holidays
    }

    /**
     * Wandzeit von [at] in [zone] in Millisekunden: Datum und Uhrzeit vor Ort, gezählt, als wäre es UTC
     * (2026-10-29 07:00 in Zürich und in New York ergibt dieselbe Zahl). So speichert [AlarmSpec.skipUntil] den
     * übersprungenen Termin: Auf Reisen fällt derselbe Kalendertag aus, nicht derselbe absolute Zeitpunkt.
     * Bruchteile einer Millisekunde werden abgeschnitten (abgerundet, wie `Instant.toEpochMilli`); Swift rechnet
     * gleich (`rounded(.down)`), damit beide Kerne bitgleiche Werte liefern.
     */
    fun wallMillis(at: Instant, zone: ZoneId): Long =
        at.atZone(zone).toLocalDateTime().toInstant(ZoneOffset.UTC).toEpochMilli()

    /** Plan für die nächste Nacht des Weckers, oder `null`, wenn er aus ist. Ausfälle wie bei [nextTarget]. */
    fun nextPlan(spec: AlarmSpec, now: Instant, zone: ZoneId, holidays: Set<LocalDate> = emptySet()): WakePlan? {
        val target = nextTarget(spec, now, zone, holidays) ?: return null
        return planAt(spec, target)
    }

    /**
     * Plan des Weckers [spec] für die Zielzeit [target]: Messbeginn [AlarmSpec.measureWindowMinutes] davor; bei «Genau»
     * mit [WakePlan.exact].
     */
    fun planAt(spec: AlarmSpec, target: Instant): WakePlan =
        WakePlan(spec.id, windowStart(target, spec.measureWindowMinutes), target, spec.isExact)

    /**
     * Der früheste Plan über alle Wecker, oder `null`, wenn keiner aktiv ist. Bei gleicher Zielzeit gewinnt die
     * kleinere Kennung. [doneUntil]: pro Wecker erledigt bzw. ausgesetzt bis (früh geweckt, Zweitwecker hat
     * geklingelt); dieser Wecker sucht erst ab dort. Ein zweiter Wecker am selben Morgen bleibt davon unberührt.
     */
    fun earliestPlan(
        specs: Collection<AlarmSpec>,
        now: Instant,
        zone: ZoneId,
        holidays: Set<LocalDate> = emptySet(),
        doneUntil: Map<String, Instant> = emptyMap(),
    ): WakePlan? =
        specs.mapNotNull { nextPlan(it, searchFrom(now, doneUntil[it.id]), zone, holidays) }
            .minWithOrNull(compareBy({ it.target }, { it.alarmId }))

    /** Ab wann gesucht wird: [now], oder [doneUntil], wenn das später ist (eine erledigte Nacht zählt nicht). */
    private fun searchFrom(now: Instant, doneUntil: Instant?): Instant =
        if (doneUntil != null && doneUntil.isAfter(now)) doneUntil else now

    /**
     * «Nächstes Mal überspringen»: [AlarmSpec.skipUntil] = Wandzeit ([wallMillis]) der nächsten Zielzeit nach allen
     * Regeln (auch nach Feiertagen), gesucht ab dem späteren von [now] und [doneUntil]. So trifft es nach frühem
     * Wecken (Nacht erledigt, Zielzeit noch vorn) den nächsten Termin, nicht den schon erledigten heutigen.
     * Einmal-Wecker, ausgeschaltete Wecker oder ohne Termin: unverändert.
     */
    fun skipNext(
        spec: AlarmSpec,
        now: Instant,
        zone: ZoneId,
        holidays: Set<LocalDate> = emptySet(),
        doneUntil: Instant? = null,
    ): AlarmSpec {
        if (spec.isOneShot) return spec
        val next = nextTarget(spec, searchFrom(now, doneUntil), zone, holidays) ?: return spec
        return spec.copy(skipUntil = wallMillis(next, zone))
    }

    /**
     * Datum (in [zone]) des Termins, den [skipNext] mit denselben Werten überspringen würde; für den Menüpunkt
     * «Do 30.10. überspringen». `null` bei Einmal-Weckern, ausgeschalteten Weckern oder ohne Termin.
     */
    fun nextSkipDate(
        spec: AlarmSpec,
        now: Instant,
        zone: ZoneId,
        holidays: Set<LocalDate> = emptySet(),
        doneUntil: Instant? = null,
    ): LocalDate? {
        if (spec.isOneShot) return null
        return nextTarget(spec, searchFrom(now, doneUntil), zone, holidays)?.atZone(zone)?.toLocalDate()
    }

    /**
     * [AlarmSpec.skipUntil] nach dem Bearbeiten von [old] zu [edited]: bleibt, wenn Stunde, Minute und Tage gleich
     * sind; sonst 0 (geänderte Zeit oder Tage heben das Überspringen auf, docs/ENTSCHEIDE.md). Schaltet der Nutzer
     * im selben Bearbeiten «Nächstes Mal überspringen» bewusst ein, ruft der Editor danach [skipNext] auf den neuen
     * Stand auf.
     */
    fun keepSkip(old: AlarmSpec, edited: AlarmSpec): Long =
        if (old.hour == edited.hour && old.minute == edited.minute && old.days == edited.days) old.skipUntil else 0L

    /**
     * [AlarmSpec.skipUntil] nach einem Editor mit Schalter «Nächstes Mal überspringen» (Uhr). [requested] ist die
     * ausdrückliche Wahl im Editor, nicht aus Werten erraten:
     * - `null` = Schalter nicht angefasst → [keepSkip] (geänderte Zeit oder Tage heben das Überspringen auf).
     * - `true` = bewusst eingeschaltet → [skipNext] auf den neuen Stand [edited] (nächster Termin des neuen Zeitplans,
     *   ab [doneUntil]); Einmal-Wecker oder ohne Termin → 0.
     * - `false` = ausgeschaltet → 0.
     * `edited.skipUntil` wird nicht gelesen.
     */
    fun editedSkip(
        old: AlarmSpec,
        edited: AlarmSpec,
        requested: Boolean?,
        now: Instant,
        zone: ZoneId,
        holidays: Set<LocalDate> = emptySet(),
        doneUntil: Instant? = null,
    ): Long = when (requested) {
        null -> keepSkip(old, edited)
        true -> skipNext(edited.copy(skipUntil = 0L), now, zone, holidays, doneUntil).skipUntil
        false -> 0L
    }

    /** Hebt das Überspringen auf. */
    fun undoSkip(spec: AlarmSpec): AlarmSpec = spec.copy(skipUntil = 0L)

    /**
     * Datum des übersprungenen Termins, solange er noch bevorsteht ([AlarmSpec.skipUntil] nach der Wandzeit von [now]
     * in [zone]); sonst `null`. Das Datum hängt nicht von der Zone ab (Wandzeit).
     */
    fun skippedDate(spec: AlarmSpec, now: Instant, zone: ZoneId): LocalDate? {
        if (spec.isOneShot || spec.skipUntil <= wallMillis(now, zone)) return null
        return Instant.ofEpochMilli(spec.skipUntil).atOffset(ZoneOffset.UTC).toLocalDate()
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
     * Hinweis «Do 30.10.: Feiertag – nicht geweckt» der Startseite: der früheste [skippedHoliday] über alle Wecker
     * (je ab dem späteren von [now] und [doneUntil]), aber nur, wenn er nicht nach dem Datum der Zielzeit des
     * nächsten Plans ([earliestPlan]) liegt – ein Feiertag hinter dem nächsten Wecken ist noch kein Hinweis.
     * Ohne Plan zählt jeder. Sonst `null`.
     */
    fun holidayNote(
        specs: Collection<AlarmSpec>,
        now: Instant,
        zone: ZoneId,
        holidays: Set<LocalDate>,
        doneUntil: Map<String, Instant> = emptyMap(),
    ): LocalDate? {
        val day = specs.mapNotNull { skippedHoliday(it, searchFrom(now, doneUntil[it.id]), zone, holidays) }.minOrNull() ?: return null
        val plan = earliestPlan(specs, now, zone, holidays, doneUntil) ?: return day
        return day.takeIf { !it.isAfter(plan.target.atZone(zone).toLocalDate()) }
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
