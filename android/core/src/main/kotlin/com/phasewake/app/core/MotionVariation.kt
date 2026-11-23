package com.phasewake.app.core

/**
 * «Auf und Ab» (Variante B, `docs/PHASENERKENNUNG.md` Abschnitt 3): Eine Epoche zählt in Regel S5 und in der
 * Leicht-Regel nur als Bewegung, wenn sie vom bisherigen Verlauf abweicht. Ein Plateau (eine neue Armlage, die gleich
 * bleibt) ist keine Bewegung (OFFEN 11g, Audit 23.11.2026).
 *
 * Reine Logik; identisch zu `MotionVariation` (Swift).
 */
object MotionVariation {
    /** Höchstens so viele vorherige Epochen mit Bewegung bilden den Median, gegen den [varies] vergleicht. */
    const val VARIATION_WINDOW: Int = 10

    /** Nötige Abweichung als Vielfaches des Grundrauschens *F*. */
    const val VARIATION_FACTOR: Double = 1.0

    /**
     * Weicht [motion] um mindestens [VARIATION_FACTOR] × [floor] ab, vom Median der letzten bis zu [VARIATION_WINDOW]
     * Bewegungen in [previous] (leere übergangen; bei gerader Anzahl Mittel der beiden mittleren) oder von der
     * Vorepoche (letzter Eintrag von [previous], wenn er eine Bewegung hat)? Ohne vorherige Bewegung `true`.
     * @param previous Bewegungen der Epochen vor dieser in der laufenden Messung, älteste zuerst; `null` = leer.
     */
    fun varies(previous: List<Double?>, motion: Double, floor: Double): Boolean {
        val values = previous.filterNotNull().takeLast(VARIATION_WINDOW)
        if (values.isEmpty()) return true
        val needed = VARIATION_FACTOR * floor
        val last = previous.last()
        return kotlin.math.abs(motion - median(values)) >= needed ||
            (last != null && kotlin.math.abs(motion - last) >= needed)
    }

    /** Median von [values] (nicht leer); bei gerader Anzahl das Mittel der beiden mittleren Werte. */
    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
