package com.phasewake.app.core

import java.time.Instant

/**
 * Nacht-Check (Audit F-06): Die Uhr hat die Messung vor dem Schlafen für die Nacht mit Zielzeit [target] nicht
 * gestartet, obwohl sie gewünscht war (Einschlaf-Messung oder Lernphase), aus [reason]. Die Uhr meldet es ans Handy;
 * dort ist der Nacht-Check rot, bis die Zielzeit vorbei ist ([appliesAt]). Gleich in Swift (`RecorderSkip`).
 */
data class RecorderSkip(val target: Instant, val reason: Reason) {
    /** Grund, mit seinem Kürzel im gemeldeten Text ([encode]). */
    enum class Reason(val code: String) {
        /** Akku zu schwach (Einschlaf-Messung unter 30 %, Lernphase unter 50 %). */
        BATTERY("battery"),

        /** Keine Erlaubnis für Bewegungsdaten (nur Apple Watch). */
        MOTION("motion"),
    }

    /** Ob die Meldung um [now] noch gilt: Die Nacht ist nicht vorbei. */
    fun appliesAt(now: Instant): Boolean = now.isBefore(target)

    /** «Zielzeit in ms|Grund», siehe [decode]. */
    fun encode(): String = "${target.toEpochMilli()}|${reason.code}"

    companion object {
        /** Liest [encode]; `null` bei leerem oder unlesbarem Text oder unbekanntem Grund (neuere Uhr). */
        fun decode(text: String?): RecorderSkip? {
            val parts = text?.trim()?.split('|') ?: return null
            if (parts.size < 2) return null
            val ms = parts[0].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val reason = Reason.entries.firstOrNull { it.code == parts[1] } ?: return null
            return RecorderSkip(Instant.ofEpochMilli(ms), reason)
        }
    }
}
