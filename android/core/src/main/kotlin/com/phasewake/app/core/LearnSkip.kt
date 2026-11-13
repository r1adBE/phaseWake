package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Meldung der Uhr ans Handy (Lern-Übersicht, Entscheid r1AD 13.11.2026): Die Lernphase zeichnet die Nacht mit Zielzeit
 * [target] nicht auf, aus [reason]; bei [Reason.BATTERY] mit dem Akkustand [battery] in Prozent. Die Galaxy Watch
 * schickt sie über die Warteschlange (bestätigt, wiederholt), die Apple Watch als Benutzerdaten: So kommt sie auch an,
 * wenn das Handy nachts aus ist. Technische Angabe ohne Messwerte, nur am Handy gespeichert ([LearnLog]). Gleich in
 * Swift (`LearnSkip`).
 */
data class LearnSkip(val target: Instant, val reason: Reason, val battery: Int? = null) {
    /** Grund, mit seinem Kürzel im Text ([encode]). Nie ändern, nur ergänzen. */
    enum class Reason(val code: String) {
        /** Akku unter [Learning.MIN_BATTERY] (bzw. unter der Schwelle der Einschlaf-Messung, die die Aufzeichnung startet). */
        BATTERY("battery"),

        /** Keine Sensoren: Messdienst verweigert (Galaxy Watch) bzw. keine Erlaubnis für Bewegungsdaten (Apple Watch). */
        SENSORS("sensors"),

        /** Eine von Hand begonnene Aufzeichnung lief schon (sie zählt dann selbst, sobald sie ankommt). */
        MANUAL("manual"),

        /** Apple Watch: Die App lief am Abend erst, als keine 3 Std. bis zur Zielzeit mehr blieben. */
        LATE("late"),
    }

    /** «Zielzeit in ms|Grund|Akku» (Akku leer, wenn unbekannt), siehe [decode]. */
    fun encode(): String = "${target.toEpochMilli()}|${reason.code}|${battery?.toString().orEmpty()}"

    companion object {
        /**
         * Liest [encode]; `null` bei leerem oder unlesbarem Text, unplausibler Zielzeit ([TimeBounds]) oder unbekanntem
         * Grund (neuere Uhr). Ein Akku ausserhalb 0–100 gilt als unbekannt.
         */
        fun decode(text: String?): LearnSkip? {
            val parts = text?.trim()?.split('|') ?: return null
            if (parts.size < 2) return null
            val ms = TimeBounds.checked(parts[0].toLongOrNull())?.takeIf { it > 0 } ?: return null
            val reason = Reason.entries.firstOrNull { it.code == parts[1] } ?: return null
            val battery = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it in 0..100 }
            return LearnSkip(Instant.ofEpochMilli(ms), reason, battery)
        }
    }
}

/**
 * Was das Handy für die Lern-Übersicht festhält (nur lokal, keine Sicherung, Entscheid r1AD 13.11.2026): die Meldungen
 * der Uhr ([LearnSkip]) und die Nächte mit Wecker (Nachtdatum → Zielzeit), je höchstens [LearnNights.MAX_NIGHTS].
 * Reine Regeln und das Textformat; gespeichert wird am Handy. Gleich in Swift (`LearnLog`).
 */
object LearnLog {
    /** So weit voraus merkt das Handy die geplanten Nächte mit Wecker ([upcomingTargets]). */
    const val PLAN_DAYS: Long = 14

    /**
     * Meldungen nach dem Eintreffen von [skip]: dieselbe Nacht (Nachtdatum der Zielzeit in [zone]) ersetzt die frühere,
     * höchstens [LearnNights.MAX_NIGHTS] Nächte, die jüngsten. Älteste zuerst.
     */
    fun addSkip(skips: List<LearnSkip>, skip: LearnSkip, zone: ZoneId): List<LearnSkip> {
        val date = SleepNotes.nightDate(skip.target, zone)
        return (skips.filter { SleepNotes.nightDate(it.target, zone) != date } + skip)
            .sortedBy { it.target }.takeLast(LearnNights.MAX_NIGHTS)
    }

    /** Meldungen als Text, eine je Zeile ([LearnSkip.encode]). */
    fun encodeSkips(skips: List<LearnSkip>): String = skips.joinToString("\n") { it.encode() }

    /** Liest [encodeSkips]; unlesbare Zeilen fallen weg. Älteste zuerst. */
    fun decodeSkips(text: String?): List<LearnSkip> =
        text.orEmpty().lines().mapNotNull { LearnSkip.decode(it) }.sortedBy { it.target }

    /**
     * Zielzeiten der Wecker [alarms] (nur echte, eingeschaltete) ab [now] bis [PLAN_DAYS] Tage voraus, in [zone] mit den
     * Feiertagen [holidays] ([AlarmSchedule.nextTarget]); ein einmaliger Wecker nur mit seinem nächsten Termin.
     */
    fun upcomingTargets(alarms: List<AlarmSpec>, now: Instant, zone: ZoneId, holidays: Set<LocalDate>): List<Instant> {
        val until = now.plus(Duration.ofDays(PLAN_DAYS))
        val result = mutableListOf<Instant>()
        for (spec in alarms) {
            var from = now
            while (true) {
                val target = AlarmSchedule.nextTarget(spec, from, zone, holidays) ?: break
                if (target.isAfter(until)) break
                result += target
                if (spec.isOneShot) break
                from = target
            }
        }
        return result.sorted()
    }

    /**
     * Nächte mit Wecker nach einem Neuplanen um [now]: Vergangene Einträge (Zielzeit nicht nach [now]) bleiben als
     * Verlauf; die künftigen ersetzt der neue Plan [planned] (ein ausgeschalteter oder übersprungener Wecker fällt so
     * heraus). Je Nachtdatum ([SleepNotes.nightDate] in [zone]) die früheste Zielzeit; höchstens die jüngsten
     * [LearnNights.MAX_NIGHTS] vergangenen Nächte.
     */
    fun mergeAlarmNights(old: Map<LocalDate, Instant>, planned: List<Instant>, now: Instant, zone: ZoneId): Map<LocalDate, Instant> {
        val past = old.filterValues { !it.isAfter(now) }.toSortedMap().entries.toList().takeLast(LearnNights.MAX_NIGHTS)
        val result = sortedMapOf<LocalDate, Instant>()
        for ((date, target) in past) result[date] = target
        for (target in planned.filter { it.isAfter(now) }) {
            val date = SleepNotes.nightDate(target, zone)
            val kept = result[date]
            if (kept == null || (kept.isAfter(now) && target.isBefore(kept))) result[date] = target
        }
        return result
    }

    /** Nächte mit Wecker als Text, je Zeile «yyyy-MM-dd=ms». */
    fun encodeAlarmNights(nights: Map<LocalDate, Instant>): String =
        nights.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value.toEpochMilli()}" }

    /** Liest [encodeAlarmNights]; unlesbare Zeilen fallen weg. */
    fun decodeAlarmNights(text: String?): Map<LocalDate, Instant> {
        val result = sortedMapOf<LocalDate, Instant>()
        for (line in text.orEmpty().lines()) {
            val parts = line.trim().split('=')
            if (parts.size != 2) continue
            val date = runCatching { LocalDate.parse(parts[0]) }.getOrNull() ?: continue
            val ms = TimeBounds.checked(parts[1].toLongOrNull()) ?: continue
            result[date] = Instant.ofEpochMilli(ms)
        }
        return result
    }
}
