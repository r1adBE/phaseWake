package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Zeitplan einer Weck-Nacht: wann das Fenster beginnt und wann spätestens geweckt wird. */
data class WakePlan(
    val alarmId: String,
    val windowStart: Instant,
    val target: Instant,
)

/**
 * Berechnet, wann ein Wecker das nächste Mal fällig ist.
 *
 * Sommer- und Winterzeit: Fällt die Zielzeit in die übersprungene Stunde, gilt die erste gültige
 * Zeit danach (Verhalten von [ZonedDateTime.of]); in der doppelten Stunde gilt die frühere.
 */
object AlarmSchedule {

    /** Wie weit voraus gesucht wird; eine Woche plus ein Tag deckt jeden Wochentag ab. */
    private const val SEARCH_DAYS = 8L

    /**
     * Nächste Zielzeit des Weckers, die echt nach [now] liegt, oder `null`, wenn er aus ist.
     *
     * Läuft das Fenster schon (now zwischen Fensterbeginn und Zielzeit), ist das die heutige Zielzeit.
     */
    fun nextTarget(spec: AlarmSpec, now: Instant, zone: ZoneId): Instant? {
        if (!spec.enabled) return null
        val time = LocalTime.of(spec.hour, spec.minute)
        val today = now.atZone(zone).toLocalDate()
        for (offset in 0..SEARCH_DAYS) {
            val date = today.plusDays(offset)
            if (!spec.isOneShot && date.dayOfWeek !in spec.days) continue
            val target = ZonedDateTime.of(date, time, zone).toInstant()
            if (target.isAfter(now)) return target
        }
        return null
    }

    /** Plan für die nächste Nacht des Weckers, oder `null`, wenn er aus ist. */
    fun nextPlan(spec: AlarmSpec, now: Instant, zone: ZoneId): WakePlan? {
        val target = nextTarget(spec, now, zone) ?: return null
        return WakePlan(spec.id, windowStart(target, spec.windowMinutes), target)
    }

    /** Der früheste Plan über alle Wecker, oder `null`, wenn keiner aktiv ist. */
    fun earliestPlan(specs: Collection<AlarmSpec>, now: Instant, zone: ZoneId): WakePlan? =
        specs.mapNotNull { nextPlan(it, now, zone) }.minWithOrNull(compareBy({ it.target }, { it.alarmId }))

    /**
     * Zielzeit bei Einschlaf-Modus: Einschlafzeit plus Schlafdauer, aber nie später als die
     * eingestellte Weckzeit [latest].
     */
    fun sleepTarget(onset: Instant, sleepDurationMinutes: Int, latest: Instant): Instant {
        val wanted = onset.plus(Duration.ofMinutes(sleepDurationMinutes.toLong()))
        return if (wanted.isBefore(latest)) wanted else latest
    }

    fun windowStart(target: Instant, windowMinutes: Int): Instant =
        target.minus(Duration.ofMinutes(windowMinutes.toLong()))
}
