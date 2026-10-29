package com.phasewake.app.core

/**
 * Vorschlag aus dem Verlauf: Kam in den letzten Nächten eines Weckers meist kein leichter Schlaf
 * ins Fenster (Wecken erst zur Zielzeit), lohnt sich ein grösseres Fenster.
 */
object WindowAdvice {
    /** Betrachtete jüngste Nächte des Weckers. */
    const val NIGHTS = 7

    /** Mindestanzahl Nächte für einen Vorschlag. */
    const val MIN_NIGHTS = 3

    /** Um so viele Minuten wird das Fenster vergrössert (höchstens bis [AlarmSpec.MAX_WINDOW_MINUTES]). */
    const val STEP_MINUTES = 10

    /** Ergebnis: [deadlineNights] von [nights] Nächten endeten zur Zielzeit; vorgeschlagen [windowMinutes]. */
    data class Suggestion(val windowMinutes: Int, val deadlineNights: Int, val nights: Int)

    /**
     * Vorschlag für [spec], wenn von seinen letzten [NIGHTS] Nächten (mindestens [MIN_NIGHTS]) mehr als
     * die Hälfte erst zur Zielzeit endete; `null`, wenn das Fenster schon am grössten ist oder zu wenig Nächte da sind.
     */
    fun suggest(spec: AlarmSpec, history: List<NightRecord>): Suggestion? {
        if (spec.windowMinutes >= AlarmSpec.MAX_WINDOW_MINUTES) return null
        val recent = history.filter { it.alarmId == spec.id }.sortedBy { it.windowStart }.takeLast(NIGHTS)
        if (recent.size < MIN_NIGHTS) return null
        val deadline = recent.count { it.reason == WakeReason.DEADLINE }
        // Mehr als die Hälfte der Nächte ohne passenden Moment.
        if (deadline * 2 <= recent.size) return null
        val wider = (spec.windowMinutes + STEP_MINUTES).coerceAtMost(AlarmSpec.MAX_WINDOW_MINUTES)
        return Suggestion(wider, deadline, recent.size)
    }
}
