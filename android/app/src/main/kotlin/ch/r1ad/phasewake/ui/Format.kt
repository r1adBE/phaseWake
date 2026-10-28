package ch.r1ad.phasewake.ui

import android.content.Context
import ch.r1ad.phasewake.R
import ch.r1ad.phasewake.core.AlarmSpec
import ch.r1ad.phasewake.core.DayFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Einheitliche Darstellung von Zeiten und Weckern in der Handy-App. */
object Format {
    private val time: DateTimeFormatter get() = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    fun time(t: LocalTime): String = time.format(t)

    fun time(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = time.format(instant.atZone(zone))

    /** «06:30 – 07:00» */
    fun window(alarm: AlarmSpec): String {
        val target = LocalTime.of(alarm.hour, alarm.minute)
        return time(target.minusMinutes(alarm.windowMinutes.toLong())) + " – " + time(target)
    }

    fun days(context: Context, alarm: AlarmSpec): String = DayFormat.format(
        alarm.days, Locale.getDefault(),
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
