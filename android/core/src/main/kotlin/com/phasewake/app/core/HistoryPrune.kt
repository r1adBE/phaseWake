package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * «Älter als 90 Tage löschen»: wie «Verlauf löschen», nur bis [cutoff] (jetzt − [DAYS] Tage). Was bis dahin endete,
 * ist weg; Nächte aus der Gesundheits-App lassen sich nicht löschen und werden wie dort über den Zeitpunkt «Verlauf
 * löschen» ausgeblendet ([HealthHistory.visible]). Gleich in Swift.
 */
object HistoryPrune {
    /** Älter als so viele Tage wird gelöscht. */
    const val DAYS: Long = 90

    /** Grenze: jetzt − [DAYS] Tage. */
    fun cutoff(now: Instant): Instant = now.minus(Duration.ofDays(DAYS))

    /**
     * Neuer Zeitpunkt «Verlauf löschen» für die Nächte aus der Gesundheits-App: [cutoff], ausser ein früheres «Verlauf
     * löschen» liegt später (dann bleibt es, sonst kämen dort gelöschte Nächte zurück).
     */
    fun clearedAt(current: Instant?, cutoff: Instant): Instant =
        if (current != null && current.isAfter(cutoff)) current else cutoff

    /** Eigene Nächte, die bleiben: geweckt echt nach [cutoff] (wie beim Löschen über die Uhr). */
    fun nights(nights: List<NightRecord>, cutoff: Instant): List<NightRecord> =
        nights.filter { it.wokeAt.isAfter(cutoff) }

    /**
     * Ob eine Aufzeichnung von [start] bis [end] bleibt: endet echt nach [cutoff], läuft noch ([end] `null`) oder
     * gehört zur noch offenen Lernphase ab [learningSince] (Beginn nicht vor [learningSince], wie [Learning.counted]
     * zählt) – sonst fehlten ihre Nächte der Lernphase. [learningSince] `null`: keine offene Lernphase.
     */
    fun keepsRecording(start: Instant, end: Instant?, cutoff: Instant, learningSince: Instant?): Boolean =
        end == null || end.isAfter(cutoff) || (learningSince != null && !start.isBefore(learningSince))

    /**
     * Notizen, Befinden oder Haken, die bleiben: ab dem Nachtdatum von [cutoff] ([SleepNotes.nightDate]). Im Zweifel
     * bleibt ein Nachtdatum (eine Nacht, die am Morgen von [cutoff] endete, behält ihre Notizen bis zum nächsten Mal).
     */
    fun <T> dates(byDate: Map<LocalDate, T>, cutoff: Instant, zone: ZoneId): Map<LocalDate, T> {
        val first = SleepNotes.nightDate(cutoff, zone)
        return byDate.filterKeys { !it.isBefore(first) }
    }
}
