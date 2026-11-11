package com.phasewake.app.wear.ui

import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.util.Locale

/**
 * Ziffern der Sprache wie bei Zahlen in Texten (`getString` mit %d) und in der Systemuhr: Auf Arabisch erschienen
 * Zeiten sonst mit 0–9, Dauern und die Uhr oben mit ٠–٩ (Audit UI-9).
 */
internal fun localizedDigits(formatter: DateTimeFormatter, locale: Locale = Locale.getDefault()): DateTimeFormatter =
    formatter.withDecimalStyle(DecimalStyle.of(locale))
