package com.phasewake.app.ui

import android.content.Context
import android.content.res.Resources
import com.phasewake.app.R
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.DayFormat
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.format.FormatStyle
import java.util.Locale

/** Einheitliche Darstellung von Zeiten und Weckern in der Handy-App. */
object Format {
    /**
     * 12- oder 24-Stunden-Anzeige nach der Systemeinstellung (wie der Zeitwähler), nicht nur nach
     * der Sprache. `null`, solange unbekannt: dann das Format der Sprache.
     */
    @Volatile
    var use24Hour: Boolean? = null

    /** Liest die 12/24-h-Einstellung des Systems; beim Start und bei jedem Öffnen aufrufen. */
    fun update(context: Context) {
        use24Hour = android.text.format.DateFormat.is24HourFormat(context)
    }

    private val time: DateTimeFormatter
        get() {
            val locale = Locale.getDefault()
            val is24 = use24Hour ?:
                return digits(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale), locale)
            return digits(
                DateTimeFormatter.ofPattern(
                    android.text.format.DateFormat.getBestDateTimePattern(locale, if (is24) "Hm" else "hm"), locale,
                ),
                locale,
            )
        }

    /**
     * Ziffern der Sprache wie bei Zahlen in Texten (`String.format`, `getString`): Auf Arabisch erschienen Zeiten
     * sonst mit 0–9, Dauern mit ٠–٩.
     */
    fun digits(formatter: DateTimeFormatter, locale: Locale = Locale.getDefault()): DateTimeFormatter =
        formatter.withDecimalStyle(DecimalStyle.of(locale))

    /** Ganze Zahl mit den Ziffern der Sprache (wie [digits]). */
    fun number(value: Int): String = String.format(Locale.getDefault(), "%d", value)

    /**
     * Eine Nachkommastelle mit dem Dezimalzeichen der Sprache der Texte: «1,5» auf Deutsch – auch in der Schweiz, wo
     * die Region sonst den Punkt vorgibt –, «1.5» auf Englisch. Ziffern und übrige Zeichen nach Sprache und Region;
     * schreibt die Sprache allein andere Ziffern (Arabisch), bleibt das Zeichen der Region. [signed]: mit Vorzeichen
     * und echtem Minus («+0,5», «−0,8»). Sprache aus [resources], nicht aus `Locale.getDefault()`, damit sie zum Text
     * daneben passt.
     */
    fun decimal(resources: Resources, value: Double, signed: Boolean = false): String =
        decimal(resources.configuration.locales[0], value, signed)

    /** [decimal] für [locale]. */
    fun decimal(locale: Locale, value: Double, signed: Boolean = false): String {
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val language = DecimalFormatSymbols.getInstance(Locale.forLanguageTag(locale.language))
        if (language.zeroDigit == symbols.zeroDigit) symbols.decimalSeparator = language.decimalSeparator
        val format = DecimalFormat("0.0", symbols).apply { roundingMode = RoundingMode.HALF_UP }
        if (signed) {
            format.positivePrefix = "+"
            format.negativePrefix = "−"
        }
        return format.format(value)
    }

    fun time(t: LocalTime): String = time.format(t)

    fun time(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = time.format(instant.atZone(zone))

    /**
     * Uhrzeit wie [time] und die Lage des Tageszeit-Zusatzes darin (AM/PM, auf Arabisch «ص»/«م»), damit ihn die
     * Startseite klein setzen kann; `null`, wenn die Zeit keinen hat (24 h).
     */
    fun timeWithPeriod(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): Pair<String, IntRange?> {
        val at = instant.atZone(zone)
        val text = time.format(at)
        val period = DateTimeFormatter.ofPattern("a", Locale.getDefault()).format(at)
        val index = if (period.isBlank()) -1 else text.indexOf(period)
        return text to if (index < 0) null else index until index + period.length
    }

    /** Kurzes Datum mit Wochentag nach Sprache und Region, z. B. «Do., 30.10.». */
    fun shortDay(date: LocalDate, locale: Locale): String =
        digits(
            DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdM"), locale),
            locale,
        ).format(date)

    /** «06:30 – 07:00»; bei «Genau» ([AlarmSpec.isExact]) «Genau um 07:00». */
    fun window(context: Context, alarm: AlarmSpec): String {
        val target = LocalTime.of(alarm.hour, alarm.minute)
        if (alarm.isExact) return context.getString(R.string.window_exact_line, time(target))
        return time(target.minusMinutes(alarm.windowMinutes.toLong())) + " – " + time(target)
    }

    fun days(context: Context, alarm: AlarmSpec): String = DayFormat.format(
        alarm.days, context.resources.configuration.locales[0],
        context.getString(R.string.alarm_once), context.getString(R.string.alarm_every_day),
    )

    /**
     * «7 h 12 min» bzw. «7½ h» für ganze halbe Stunden. Zwischen Stunden und Minuten darf es umbrechen (Kachel);
     * [keepTogether] für Fliesstext («· in 23 Std. 20 Min»): ganz ohne Umbruch, die Zeile bricht davor um.
     */
    fun duration(context: Context, d: Duration, keepTogether: Boolean = false): String {
        val minutes = d.toMinutes().coerceAtLeast(0)
        val text = when {
            minutes < 60 -> context.getString(R.string.duration_minutes, minutes)
            minutes % 60 == 0L -> context.getString(R.string.duration_hours, minutes / 60)
            minutes % 30 == 0L -> context.getString(R.string.duration_hours_half, minutes / 60)
            else -> context.getString(R.string.duration_hours_minutes, minutes / 60, minutes % 60)
        }
        return if (keepTogether) unbreakable(text) else text
    }

    /** [text] ohne Stelle zum Umbrechen: Leerzeichen werden zu geschützten (U+00A0). */
    fun unbreakable(text: String): String = text.replace(' ', '\u00A0')
}
