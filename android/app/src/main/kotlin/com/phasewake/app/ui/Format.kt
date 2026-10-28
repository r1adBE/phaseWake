package com.phasewake.app.ui

import android.content.Context
import com.phasewake.app.R
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.DayFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
            val is24 = use24Hour ?: return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
            return DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, if (is24) "Hm" else "hm"), locale)
        }

    fun time(t: LocalTime): String = time.format(t)

    fun time(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = time.format(instant.atZone(zone))

    /** «06:30 – 07:00» */
    fun window(alarm: AlarmSpec): String {
        val target = LocalTime.of(alarm.hour, alarm.minute)
        return time(target.minusMinutes(alarm.windowMinutes.toLong())) + " – " + time(target)
    }

    fun days(context: Context, alarm: AlarmSpec): String = DayFormat.format(
        alarm.days, context.resources.configuration.locales[0],
        context.getString(R.string.alarm_once), context.getString(R.string.alarm_every_day),
    )

    /** «7 h 12 min» bzw. «7½ h» für ganze halbe Stunden. */
    fun duration(context: Context, d: Duration): String {
        val minutes = d.toMinutes().coerceAtLeast(0)
        return when {
            minutes < 60 -> context.getString(R.string.duration_minutes, minutes)
            minutes % 60 == 0L -> context.getString(R.string.duration_hours, minutes / 60)
            minutes % 30 == 0L -> context.getString(R.string.duration_hours_half, minutes / 60)
            else -> context.getString(R.string.duration_hours_minutes, minutes / 60, minutes % 60)
        }
    }
}
