package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Schlafziel: wie viel Schlaf der Nutzer pro Nacht möchte (nur Handy). */
object SleepGoal {
    /** Standard-Schlafziel in Minuten (7 h 30 min). */
    const val DEFAULT_MINUTES: Int = 450

    /** Kleinstes Schlafziel in Minuten (6 h). */
    const val MIN_MINUTES: Int = 360

    /** Grösstes Schlafziel in Minuten (10 h). */
    const val MAX_MINUTES: Int = 600

    /** Schrittweite der Einstellung in Minuten. */
    const val STEP_MINUTES: Int = 15

    /** So viele Nächte umfasst die Wochenbilanz. */
    const val WEEK_NIGHTS: Int = 7

    /** Auf den Bereich und die Schrittweite gebracht (auf den nächsten Schritt gerundet). */
    fun clamp(minutes: Int): Int {
        val stepped = Math.round(minutes.toDouble() / STEP_MINUTES).toInt() * STEP_MINUTES
        return stepped.coerceIn(MIN_MINUTES, MAX_MINUTES)
    }

    /** Ob [asleepMinutes] (Schlaf ohne Wachphasen) das Ziel [goalMinutes] erreicht; genau gleich zählt als erreicht. */
    fun reached(asleepMinutes: Long, goalMinutes: Int): Boolean = asleepMinutes >= goalMinutes

    /**
     * Wochenbilanz: (erreicht, gezählt) über die jüngsten [WEEK_NIGHTS] Nachtdaten mit Schlafdauer.
     * Mehrere Einträge mit demselben Datum zählen einmal (der längste). Ohne Daten (0, 0).
     */
    fun weekStatus(nights: List<Pair<LocalDate, Long>>, goalMinutes: Int): Pair<Int, Int> {
        val recent = nights.groupBy({ it.first }, { it.second }).mapValues { (_, list) -> list.max() }
            .entries.sortedByDescending { it.key }.take(WEEK_NIGHTS)
        return recent.count { reached(it.value, goalMinutes) } to recent.size
    }

    /** Nächte aus der Gesundheits-App als (Nachtdatum, Minuten) für [weekStatus]. */
    fun nightsOf(sleeps: List<SleepSummary>, zone: ZoneId): List<Pair<LocalDate, Long>> =
        sleeps.map { SleepNotes.nightDate(it.end, zone) to it.asleepMinutes }
}

/**
 * Bettzeit-Erinnerung: Bettzeit = Zielzeit des nächsten Weckers − Schlafziel; erinnert
 * [LEAD_MINUTES] Minuten davor. Beim Einschlaf-Modus gilt die eingestellte (späteste) Weckzeit.
 */
object Bedtime {
    /** Vorlauf der Erinnerung vor der Bettzeit in Minuten. */
    const val LEAD_MINUTES: Int = 30

    /** Wie oft höchstens zur nächsten Nacht weitergesucht wird (Wecker mehrmals am Morgen, verpasste Erinnerungen). */
    private const val MAX_STEPS = 32

    /** Eine geplante Erinnerung für die Nacht vor [target]. */
    data class Reminder(val at: Instant, val bedtime: Instant, val target: Instant)

    /**
     * Bettzeit: [nextAlarmTarget] minus [goalMinutes] als feste Dauer (bei einer Zeitumstellung in der
     * Nacht bleibt die Schlafdauer gleich, die Uhrzeit verschiebt sich).
     */
    fun bedtime(nextAlarmTarget: Instant, goalMinutes: Int): Instant =
        nextAlarmTarget.minus(Duration.ofMinutes(goalMinutes.toLong()))

    /** Zeitpunkt der Erinnerung: [bedtime] minus [leadMinutes]. */
    fun reminderAt(nextAlarmTarget: Instant, goalMinutes: Int, leadMinutes: Int = LEAD_MINUTES): Instant =
        bedtime(nextAlarmTarget, goalMinutes).minus(Duration.ofMinutes(leadMinutes.toLong()))

    /**
     * Nächste Erinnerung, die echt nach [from] liegt: zum frühesten Wecker nach [from]; liegt dessen
     * Erinnerung schon zurück, gilt diese Nacht als verpasst und es geht ab seiner Zielzeit weiter
     * (so erinnert ein zweiter Wecker am selben Morgen nicht nachträglich). `null` ohne Wecker.
     *
     * @param from Jetzt, oder nach einer gezeigten Erinnerung deren Zeitpunkt, falls später.
     */
    fun next(
        specs: Collection<AlarmSpec>,
        from: Instant,
        zone: ZoneId,
        goalMinutes: Int,
        leadMinutes: Int = LEAD_MINUTES,
        /** Feiertage für Wecker mit [AlarmSpec.skipHolidays]; an solchen Tagen keine Erinnerung. */
        holidays: Set<LocalDate> = emptySet(),
    ): Reminder? {
        var cursor = from
        val remaining = specs.toMutableList()
        repeat(MAX_STEPS) {
            val plan = AlarmSchedule.earliestPlan(remaining, cursor, zone, holidays) ?: return null
            val at = reminderAt(plan.target, goalMinutes, leadMinutes)
            if (at.isAfter(cursor)) return Reminder(at, bedtime(plan.target, goalMinutes), plan.target)
            // Ein Einmal-Wecker klingelt nur diese eine Nacht; er kommt danach nicht wieder.
            remaining.removeAll { it.id == plan.alarmId && it.isOneShot }
            cursor = plan.target
        }
        return null
    }
}
