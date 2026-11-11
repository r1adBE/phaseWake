package com.phasewake.app.wear.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Ziffern der Sprache wie bei Zahlen in Texten (`getString` mit %d) und in der Systemuhr: Auf Arabisch erschienen
 * Zeiten sonst mit 0–9, Dauern und die Uhr oben mit ٠–٩ (Audit UI-9).
 */
internal fun localizedDigits(formatter: DateTimeFormatter, locale: Locale = Locale.getDefault()): DateTimeFormatter =
    formatter.withDecimalStyle(DecimalStyle.of(locale))

/**
 * Kurze Uhrzeit nach Sprache mit deren Ziffern ([localizedDigits]) für Texte ausserhalb der Bildschirme der App:
 * Mitteilungen, Kachel und Komplikation (Audit UI-9, Nachtrag).
 */
internal fun shortTime(at: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    localizedDigits(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale), locale).format(at.atZone(zone))
