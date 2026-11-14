package com.phasewake.app.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** Zahlen im CSV-Export gleich auf beiden Plattformen (Audit F-7); Gegenstück: `CsvNumbersTests` (Swift). */
class CsvNumbersTest {

    @Test
    fun halfRoundsAwayFromZeroOnScaledValue() {
        // Exakt halbe Werte: Swift (`String(format:)`) gab 56.2, Java 56.3 – jetzt beide 56.3.
        assertEquals("56.3", CsvNumbers.fixed(56.25, 1))
        assertEquals("72.8", CsvNumbers.fixed(72.75, 1))
        assertEquals("52.3", CsvNumbers.fixed(52.34, 1))
        assertEquals("60.0", CsvNumbers.fixed(60.0, 1))
        // Java rundete hier die Dezimaldarstellung (0.000247), C den Binärwert; die Regel am skalierten Wert ergibt 0.000246.
        assertEquals("0.000246", CsvNumbers.fixed(0.0002465, 6))
        assertEquals("0.002100", CsvNumbers.fixed(0.0021, 6))
        assertEquals("0.000000", CsvNumbers.fixed(0.0, 6))
    }

    @Test
    fun edgeCases() {
        assertEquals("-1.5", CsvNumbers.fixed(-1.5, 1))
        assertEquals("-2", CsvNumbers.fixed(-1.5, 0))
        assertEquals("0", CsvNumbers.fixed(-0.4, 0))
        assertEquals("", CsvNumbers.fixed(Double.NaN, 1))
        assertEquals("", CsvNumbers.fixed(Double.POSITIVE_INFINITY, 1))
        assertEquals("", CsvNumbers.fixed(1e300, 1))
    }

    @Test
    fun exportUsesTheSameRule() {
        val at = java.time.Instant.parse("2026-10-01T04:30:00Z")
        val night = NightRecord("a", at, at.plusSeconds(1800), at.plusSeconds(1080), WakeReason.LIGHT_SLEEP, 0, listOf(Epoch(0.0002465, 56.25)))
        val line = NightHistory.exportCsv(listOf(night)).trimEnd().lines()[1]
        assertEquals("0.000246,56.3,", line.split(',').takeLast(3).joinToString(","))
    }
}
