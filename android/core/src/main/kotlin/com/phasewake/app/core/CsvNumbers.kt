package com.phasewake.app.core

import kotlin.math.abs
import kotlin.math.floor

/**
 * Zahlen im CSV-Export (Bewegung, Puls), auf Kotlin und Swift Zeichen für Zeichen gleich (Audit F-7): `String.format`
 * (Java) rundet die kürzeste Dezimaldarstellung halb auf, `String(format:)` (Swift, wie C) den genauen Binärwert halb auf
 * gerade – 56.25 wurde so zu 56.3 bzw. 56.2. Hier zählt nur Gleitkomma-Rechnung, die IEEE 754 auf beiden gleich festlegt
 * (Betrag × Zehnerpotenz + 0.5, abrunden), der Rest ist Ganzzahl. Gleich in Swift (`CsvNumbers`).
 */
object CsvNumbers {

    /**
     * [value] mit [decimals] Nachkommastellen (0 bis 9), halbe Werte vom Nullpunkt weg gerundet (am Wert × 10^[decimals]),
     * Dezimalpunkt. Nicht endlich oder zu gross für die Rechnung: leer (wie ein fehlender Wert).
     */
    fun fixed(value: Double, decimals: Int): String {
        require(decimals in 0..MAX_DECIMALS) { "decimals $decimals" }
        if (!value.isFinite()) return ""
        var scale = 1L
        repeat(decimals) { scale *= 10 }
        val scaled = floor(abs(value) * scale + 0.5)
        if (scaled >= LIMIT) return ""
        val units = scaled.toLong()
        val sign = if (value < 0 && units != 0L) "-" else ""
        val whole = units / scale
        if (decimals == 0) return "$sign$whole"
        return "$sign$whole." + (units % scale).toString().padStart(decimals, '0')
    }

    private const val MAX_DECIMALS = 9

    /** Obergrenze für den gerundeten Wert (unter `Long.MAX_VALUE`, als Double exakt vergleichbar). */
    private const val LIMIT = 9.0e18
}
