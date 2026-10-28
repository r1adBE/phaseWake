package com.phasewake.app.core

import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Kurzschreibweise der Wochentage eines Weckers, z. B. «Mo–Fr» oder «Mo, Mi, Fr». */
object DayFormat {

    /**
     * @param once Text für einen einmaligen Wecker (keine Tage).
     * @param daily Text für alle sieben Tage.
     */
    fun format(days: Set<DayOfWeek>, locale: Locale, once: String, daily: String): String {
        if (days.isEmpty()) return once
        if (days.size == 7) return daily
        // Reihenfolge ab dem Wochenbeginn der Sprache/Region. Gehört der letzte und der erste Tag der
        // Woche dazu (z. B. Sa, So, Mo), beginnt sie nach einer Lücke, damit die Spanne zusammenbleibt.
        val first = java.time.temporal.WeekFields.of(locale).firstDayOfWeek
        val week = (0L until 7L).map { first.plus(it) }
        val start = if (week.first() in days && week.last() in days) week.indexOfLast { it !in days } + 1 else 0
        val sorted = (0 until 7).map { week[(start + it) % 7] }.filter { it in days }
        val parts = mutableListOf<String>()
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size && sorted[j + 1] == sorted[j].plus(1)) j++
            // Ab drei aufeinanderfolgenden Tagen als Spanne, sonst einzeln.
            if (j - i >= 2) {
                parts += name(sorted[i], locale) + "–" + name(sorted[j], locale)
            } else {
                for (k in i..j) parts += name(sorted[k], locale)
            }
            i = j + 1
        }
        return parts.joinToString(", ")
    }

    private fun name(day: DayOfWeek, locale: Locale): String =
        day.getDisplayName(TextStyle.SHORT, locale).trimEnd('.')
}
