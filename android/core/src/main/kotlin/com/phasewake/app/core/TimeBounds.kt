package com.phasewake.app.core

/**
 * Plausible Zeitpunkte beim Lesen gespeicherter oder empfangener Daten (ms seit 1970): von 1970-01-01 bis 2100-01-01 UTC.
 * Ausserhalb ist der Wert kaputt oder fremd (z. B. eine manipulierte Sicherung mit 9223372036854775807); er wird beim
 * Lesen verworfen, damit spätere Rechnungen mit Millisekunden nicht überlaufen (Swift bricht dabei ab). Untergrenze 1970
 * statt 2000: Tests und Testdaten beider Plattformen nutzen kleine Zeiten; die laufen nie über.
 * Identisch zu `TimeBounds` (Swift).
 */
object TimeBounds {
    /** 1970-01-01T00:00:00Z. */
    const val MIN_MS: Long = 0L

    /** 2100-01-01T00:00:00Z. */
    const val MAX_MS: Long = 4_102_444_800_000L

    /** Ob [ms] ein plausibler Zeitpunkt ist ([MIN_MS] bis [MAX_MS]). */
    fun contains(ms: Long): Boolean = ms in MIN_MS..MAX_MS

    /** [ms], falls plausibel ([contains]); sonst `null`. */
    fun checked(ms: Long?): Long? = ms?.takeIf(::contains)
}
