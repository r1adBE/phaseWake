package com.phasewake.app.wear.ui

import android.content.Context
import android.text.format.DateFormat
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
 * 12/24-Stunden-Einstellung der Uhr (Audit N-10): Zeiten folgen ihr wie die Systemuhr oben, nicht nur der Sprache.
 * Die Bildschirme rufen [update] beim Öffnen; danach liest [use24Hour] die Einstellung bei jedem Aufruf neu.
 */
object WearClock {
    @Volatile
    private var app: Context? = null

    /** Merkt sich den App-Kontext, um die Einstellung zu lesen; beim Start und bei jedem Öffnen aufrufen. */
    fun update(context: Context) {
        app = context.applicationContext ?: context
    }

    /** `true`/`false` nach der Einstellung; `null`, solange kein Kontext bekannt ist (dann das Format der Sprache). */
    val use24Hour: Boolean?
        get() = app?.let { runCatching { DateFormat.is24HourFormat(it) }.getOrNull() }
}

/**
 * Kurze Uhrzeit nach [is24] (12/24 h) mit den Ziffern der Sprache; [is24] = `null`: das Format der Sprache.
 */
internal fun timeFormatter(locale: Locale = Locale.getDefault(), is24: Boolean? = WearClock.use24Hour): DateTimeFormatter {
    val base = if (is24 == null) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    } else {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, if (is24) "Hm" else "hm"), locale)
    }
    return localizedDigits(base, locale)
}

/**
 * Kurze Uhrzeit nach Sprache mit deren Ziffern ([localizedDigits]) und der 12/24-h-Einstellung ([WearClock]) für
 * Texte ausserhalb der Bildschirme der App: Mitteilungen, Kachel und Komplikation (Audit UI-9, Nachtrag; N-10).
 */
internal fun shortTime(
    at: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    is24: Boolean? = WearClock.use24Hour,
): String = timeFormatter(locale, is24).format(at.atZone(zone))
