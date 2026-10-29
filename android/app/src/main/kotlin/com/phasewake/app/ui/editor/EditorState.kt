package com.phasewake.app.ui.editor

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePickerState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.PhoneAlert
import com.phasewake.app.core.Sunrise
import com.phasewake.app.core.SunriseDevice
import com.phasewake.app.core.WakeTask
import java.time.DayOfWeek
import kotlin.math.roundToInt

/**
 * Eingaben des Editors, ausgehend von [base]. Übersteht Drehen und Neuaufbau über [saver]; die
 * Uhrzeit liegt im eigenen Zustand des Uhrzeit-Wählers.
 */
@Stable
internal class EditorState(val base: AlarmSpec) {
    /** Weckfenster in Minuten (Schieber, auf 5 Minuten gerundet). */
    var window by mutableFloatStateOf(base.windowMinutes.toFloat())
    var days by mutableStateOf(base.days)

    /** Einschlaf-Modus: Zielzeit = Einschlafzeit + [sleepMinutes], höchstens die eingestellte Zeit. */
    var sleepMode by mutableStateOf(base.sleepDurationMinutes != null)
    var sleepMinutes by mutableFloatStateOf((base.sleepDurationMinutes ?: DEFAULT_SLEEP_MINUTES).toFloat())
    var phone by mutableStateOf(base.phone)
    var watchSound by mutableStateOf(base.watchSound)
    var sound by mutableStateOf(base.sound)
    var sunrise by mutableStateOf(base.sunrise)
    var sunriseDevice by mutableStateOf(base.sunriseDevice)
    var wakeTask by mutableStateOf(base.wakeTask)
    var label by mutableStateOf(base.label)

    /** An Feiertagen nicht wecken (nur wiederholte Wecker). */
    var skipHolidays by mutableStateOf(base.skipHolidays)

    /** «Mehr» aufgeklappt. */
    var more by mutableStateOf(false)

    /** Der Wecker mit den Eingaben; eingeschaltet, Name ohne Leerraum am Rand. */
    @OptIn(ExperimentalMaterial3Api::class)
    fun result(time: TimePickerState): AlarmSpec = base.copy(
        hour = time.hour,
        minute = time.minute,
        // Ein übersprungener Termin gilt nur für den bisherigen Zeitplan; ändert er sich, nichts mehr überspringen.
        skipUntil = if (time.hour == base.hour && time.minute == base.minute && days == base.days) base.skipUntil else 0L,
        skipHolidays = skipHolidays,
        days = days,
        windowMinutes = window.roundToInt(),
        sleepDurationMinutes = if (sleepMode) sleepMinutes.roundToInt() else null,
        phone = phone,
        watchSound = watchSound,
        sound = sound,
        label = label.trim(),
        sunrise = sunrise,
        sunriseDevice = sunriseDevice,
        wakeTask = wakeTask,
        enabled = true,
    )

    companion object {
        /** Vorschlag der Schlafdauer im Einschlaf-Modus in Minuten (7 h 30 min). */
        private const val DEFAULT_SLEEP_MINUTES = 450

        /** Speichert alle Eingaben als einfache Werte (Zahlen, Text), Aufzählungen über ihren Namen. */
        fun saver(base: AlarmSpec): Saver<EditorState, Any> = listSaver(
            save = {
                listOf(
                    it.window, ArrayList(it.days.map { day -> day.value }), it.sleepMode, it.sleepMinutes,
                    it.phone.name, it.watchSound, it.sound, it.sunrise.name, it.sunriseDevice.name,
                    it.wakeTask.name, it.label, it.more, it.skipHolidays,
                )
            },
            restore = { v ->
                EditorState(base).apply {
                    window = v[0] as Float
                    days = (v[1] as List<*>).map { day -> DayOfWeek.of(day as Int) }.toSet()
                    sleepMode = v[2] as Boolean
                    sleepMinutes = v[3] as Float
                    phone = PhoneAlert.valueOf(v[4] as String)
                    watchSound = v[5] as Boolean
                    sound = v[6] as String
                    sunrise = Sunrise.valueOf(v[7] as String)
                    sunriseDevice = SunriseDevice.valueOf(v[8] as String)
                    wakeTask = WakeTask.valueOf(v[9] as String)
                    label = v[10] as String
                    more = v[11] as Boolean
                    skipHolidays = v[12] as Boolean
                }
            },
        )
    }
}
