package com.phasewake.app.core

/**
 * Befinden je Weckgrund: Wie erholt fühlte sich der Nutzer nach Nächten, die im leichten Schlaf, wach, zur Zielzeit,
 * mit «Genau» oder als kurze Nacht geweckt haben? Nur Auswertung für die Diagnose (Abschnitt [TITLE]); ändert nichts
 * am Wecken. Ob die Schwellen nach dem Befinden angepasst werden, wird erst ab [REVIEW_NIGHTS] Nächten mit Befinden
 * geprüft. Technische Zeilen auf Englisch wie das Diagnose-Protokoll. Identisch zu `FeelingByReason` (Swift).
 */
object FeelingByReason {
    /** Titel des Abschnitts im Diagnose-Bericht. */
    const val TITLE: String = "FEELING"

    /** Ab so vielen eigenen Nächten mit Befinden lohnt die Prüfung der Schwellen. */
    const val REVIEW_NIGHTS: Int = 20

    /** Erste Zeile von [lines]: was gezählt wird. */
    const val NOTE: String =
        "feeling 1 (tired) to 5 (rested) of own nights with a feeling, by wake reason; does not change waking"

    /** Reihenfolge der Zeilen: früh geweckt zuerst, dann zur Zielzeit. */
    val ORDER: List<WakeReason> = listOf(
        WakeReason.LIGHT_SLEEP, WakeReason.AWAKE, WakeReason.DEADLINE, WakeReason.EXACT, WakeReason.SHORT_NIGHT,
    )

    /** Nächte mit Befinden zu [reason]: Anzahl [nights] und Summe [sum] des Befindens. */
    data class Row(val reason: WakeReason, val nights: Int, val sum: Int) {
        /** Durchschnitt; `null` ohne Nacht. */
        val average: Double? get() = if (nights == 0) null else sum.toDouble() / nights

        /** Durchschnitt mit einer Nachkommastelle, halbe aufwärts, aus ganzen Zahlen (gleich in Swift); sonst «-». */
        fun averageText(): String = averageText(sum, nights)
    }

    /** Je Grund aus [ORDER] eine Zeile, auch ohne Nacht; Nächte ohne Befinden zählen nicht. */
    fun of(nights: List<NightRecord>): List<Row> = ORDER.map { reason ->
        val feelings = nights.filter { it.reason == reason }.mapNotNull { it.feeling }
        Row(reason, feelings.size, feelings.sum())
    }

    /**
     * Zeilen des Abschnitts: [NOTE], je Grund «REASON N nights avg X.Y», zuletzt «all: N nights with feeling avg X.Y»
     * mit dem Stand zu [REVIEW_NIGHTS]. Ohne Nacht mit Befinden leer (kein Abschnitt).
     */
    fun lines(nights: List<NightRecord>): List<String> {
        val rows = of(nights)
        val count = rows.sumOf { it.nights }
        if (count == 0) return emptyList()
        val review = if (count >= REVIEW_NIGHTS) {
            "$REVIEW_NIGHTS reached, review thresholds"
        } else {
            "review thresholds from $REVIEW_NIGHTS (${REVIEW_NIGHTS - count} to go)"
        }
        return listOf(NOTE) +
            rows.map { "${it.reason.name} ${it.nights} nights avg ${it.averageText()}" } +
            "all: $count nights with feeling avg ${averageText(rows.sumOf { it.sum }, count)} · $review"
    }

    /** [sum] / [count] mit einer Nachkommastelle, halbe aufwärts, ohne Gleitkomma; ohne [count] «-». */
    private fun averageText(sum: Int, count: Int): String {
        if (count <= 0) return "-"
        val tenths = (sum * 20 + count) / (2 * count)
        return "${tenths / 10}.${tenths % 10}"
    }
}
