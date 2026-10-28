package com.phasewake.app.core

/**
 * Vorschlag aus dem Verlauf: Kam in den letzten Nächten eines Weckers meist kein leichter Schlaf
 * ins Fenster (Wecken erst zur Zielzeit), lohnt sich ein grösseres Fenster.
 */
object WindowAdvice {
    const val NIGHTS = 7
    const val MIN_NIGHTS = 3
    const val STEP_MINUTES = 10

    /** Ergebnis: [deadlineNights] von [nights] Nächten endeten zur Zielzeit; vorgeschlagen [windowMinutes]. */
    data class Suggestion(val windowMinutes: Int, val deadlineNights: Int, val nights: Int)

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
