package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Einstellung «Abends dunkel ab»: Steht das Design auf «System» oder «Hell», zeigt die App
 * ab der gewählten Uhrzeit ([fromMinutes], Standard 18:00) bis zum Morgen ([UNTIL_MINUTES], 06:00) das dunkle Design;
 * «Dunkel» bleibt dunkel. Gerechnet wird mit der Ortszeit (Wanduhr) in Minuten des Tages, über Mitternacht hinweg; so
 * gilt auch an Tagen mit Sommerzeit-Wechsel «ab 18:00 bis 06:00» nach der Uhr an der Wand. Gleich auf Apple
 * (`EveningDark.swift`), gemeinsame Fälle in shared/testdata/evening-dark.vectors.
 */
object EveningDark {
    /** Standard: an. */
    const val DEFAULT_ENABLED: Boolean = true

    /** Standard-Beginn 18:00 (Minuten des Tages). */
    const val DEFAULT_FROM_MINUTES: Int = 18 * 60

    /**
     * Ende am Morgen: fest 06:00. Nicht die Zielzeit des nächsten Weckers – die fehlt an freien Tagen oder liegt Tage
     * entfernt, und das Ende spränge mit jedem Wecker; der Weckbildschirm ist ohnehin immer dunkel.
     */
    const val UNTIL_MINUTES: Int = 6 * 60

    private const val DAY_MINUTES = 24 * 60

    /** Gespeicherter Beginn zur Verwendung: ausserhalb 00:00–23:59 (oder fehlend) der Standard. */
    fun checkedFrom(raw: Int?): Int = raw?.takeIf { it in 0 until DAY_MINUTES } ?: DEFAULT_FROM_MINUTES

    /**
     * Liegt die Minute des Tages [minuteOfDay] im dunklen Abschnitt von [from] (einschliesslich) bis [until]
     * (ausschliesslich), auch über Mitternacht? Gleiche Grenzen: nie.
     */
    fun covers(minuteOfDay: Int, from: Int, until: Int = UNTIL_MINUTES): Boolean = when {
        from == until -> false
        from < until -> minuteOfDay in from until until
        else -> minuteOfDay >= from || minuteOfDay < until
    }

    /** Ob um [now] (Ortszeit in [zone]) das dunkle Design gilt; aus: nie. */
    fun isDark(now: Instant, zone: ZoneId, enabled: Boolean, fromMinutes: Int): Boolean =
        enabled && covers(minuteOfDay(now, zone), checkedFrom(fromMinutes))

    /**
     * Nächster Wechsel nach [now]: die erste volle Minute, ab der [isDark] anders ist als jetzt; `null`, wenn sich nie
     * etwas ändert (aus, oder Beginn = 06:00). Minutenweise gesucht (höchstens gut einen Tag, nur einmal je Wechsel):
     * So stimmt er auch über Sommerzeit-Wechsel hinweg, in der Lücke wie in der doppelten Stunde, ohne Sonderfälle.
     */
    fun nextChange(now: Instant, zone: ZoneId, enabled: Boolean, fromMinutes: Int): Instant? {
        val from = checkedFrom(fromMinutes)
        if (!enabled || from == UNTIL_MINUTES) return null
        val dark = covers(minuteOfDay(now, zone), from)
        var t = now.truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofMinutes(1))
        repeat(SEARCH_MINUTES) {
            if (covers(minuteOfDay(t, zone), from) != dark) return t
            t = t.plus(Duration.ofMinutes(1))
        }
        return null
    }

    /** Längste Suche: ein Tag und zwei Stunden (mehr als jeder Sommerzeit-Wechsel verschiebt). */
    private const val SEARCH_MINUTES = DAY_MINUTES + 120

    private fun minuteOfDay(at: Instant, zone: ZoneId): Int = at.atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }
}
