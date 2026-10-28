package ch.r1ad.phasewake.core

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
        val sorted = days.sorted()
        val parts = mutableListOf<String>()
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size && sorted[j + 1].value == sorted[j].value + 1) j++
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
