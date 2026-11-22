package com.phasewake.app.core

import java.time.DayOfWeek
import java.util.UUID

/** Ein Wert, den [ClampedSpec] auf den gültigen Bereich gesetzt hat. Identisch zu `SpecChange` (Swift). */
data class SpecChange(
    /** Name des Felds wie in [AlarmSpec], z. B. `windowMinutes`. */
    val field: String,
    /** Wert vorher; bei leerer Kennung [EMPTY_ID]. */
    val from: String,
    /** Wert nachher; bei neuer Kennung [NEW_ID] (die Kennung selbst steht nicht in der Diagnose). */
    val to: String,
) {
    /** «feld vorher->nachher», z. B. «windowMinutes 45->30». */
    override fun toString(): String = "$field $from->$to"

    companion object {
        /** Wert vorher einer leeren Kennung. */
        const val EMPTY_ID: String = "empty"

        /** Wert nachher einer leeren Kennung: Sie wird durch eine neue UUID ersetzt. */
        const val NEW_ID: String = "new"
    }
}

/**
 * Wecker aus Werten, die ungültig sein können, ohne Absturz: Jeder Wert ausserhalb der Regeln von [AlarmSpec] wird auf
 * den nächsten gültigen gesetzt und in [changes] gemeldet; gültige Werte bleiben unverändert (dann ist [changes] leer).
 * - `hour` auf 0–23, `minute` auf 0–59;
 * - `windowMinutes`: [AlarmSpec.EXACT_WINDOW] (0, «Genau») bleibt, jeder andere Wert (auch negativ) auf
 *   [AlarmSpec.MIN_WINDOW_MINUTES]–[AlarmSpec.MAX_WINDOW_MINUTES] (Android 60, Apple 30);
 * - `sleepDurationMinutes`: `null` bleibt, sonst auf [AlarmSpec.MIN_SLEEP_MINUTES]–[AlarmSpec.MAX_SLEEP_MINUTES];
 * - leere Kennung: neue UUID.
 * Alle anderen Felder haben keine Regel und werden unverändert übernommen. Gleiche Regeln und Reihenfolge der
 * Änderungen wie `ClampedSpec` (Swift); der Kern schreibt keine Diagnose, das tut die Aufrufstelle mit [diagLine].
 */
class ClampedSpec(
    id: String,
    hour: Int,
    minute: Int,
    days: Set<DayOfWeek> = emptySet(),
    windowMinutes: Int = AlarmSpec.DEFAULT_WINDOW_MINUTES,
    enabled: Boolean = true,
    phone: PhoneAlert = PhoneAlert.SOUND,
    watchSound: Boolean = false,
    sound: String = "",
    sleepDurationMinutes: Int? = null,
    label: String = "",
    sunrise: Sunrise = Sunrise.OFF,
    sunriseDevice: SunriseDevice = SunriseDevice.PHONE,
    wakeTask: WakeTask = WakeTask.NONE,
    skipUntil: Long = 0L,
    skipHolidays: Boolean = false,
) {
    /** Die begrenzten Werte in ihrer Reihenfolge: id, hour, minute, windowMinutes, sleepDurationMinutes. */
    val changes: List<SpecChange>

    /** Der gültige Wecker. */
    val spec: AlarmSpec

    init {
        val found = mutableListOf<SpecChange>()
        fun <T> note(field: String, from: T, to: T): T {
            if (from != to) found += SpecChange(field, from.toString(), to.toString())
            return to
        }
        val validId = if (id.isNotBlank()) id else {
            found += SpecChange("id", SpecChange.EMPTY_ID, SpecChange.NEW_ID)
            UUID.randomUUID().toString()
        }
        val validHour = note("hour", hour, hour.coerceIn(0, MAX_HOUR))
        val validMinute = note("minute", minute, minute.coerceIn(0, MAX_MINUTE))
        val validWindow = note("windowMinutes", windowMinutes, window(windowMinutes))
        val validSleep = note("sleepDurationMinutes", sleepDurationMinutes, sleep(sleepDurationMinutes))
        changes = found
        spec = AlarmSpec(
            id = validId, hour = validHour, minute = validMinute, days = days, windowMinutes = validWindow,
            enabled = enabled, phone = phone, watchSound = watchSound, sound = sound,
            sleepDurationMinutes = validSleep, label = label, sunrise = sunrise, sunriseDevice = sunriseDevice,
            wakeTask = wakeTask, skipUntil = skipUntil, skipHolidays = skipHolidays,
        )
    }

    /**
     * Diagnose-Zeile, wenn etwas begrenzt wurde: [DIAG_PREFIX] und die Änderungen, mit «, » getrennt, z. B.
     * «SPEC clamped hour 24->23, windowMinutes 45->30». `null`, wenn alle Werte gültig waren.
     */
    val diagLine: String? get() = changes.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "$DIAG_PREFIX ")

    companion object {
        /** Anfang der Diagnose-Zeile ([diagLine]); auf allen Geräten gleich. */
        const val DIAG_PREFIX: String = "SPEC clamped"

        private const val MAX_HOUR = 23
        private const val MAX_MINUTE = 59

        /** Gültiges Weckfenster zu [minutes]: «Genau» bleibt, sonst auf 10 bis zum grössten Fenster der Plattform. */
        fun window(minutes: Int): Int =
            if (minutes == AlarmSpec.EXACT_WINDOW) minutes
            else minutes.coerceIn(AlarmSpec.MIN_WINDOW_MINUTES, AlarmSpec.MAX_WINDOW_MINUTES)

        /** Gültige Schlafdauer zu [minutes]: `null` bleibt, sonst auf 60–720 Minuten. */
        fun sleep(minutes: Int?): Int? = minutes?.coerceIn(AlarmSpec.MIN_SLEEP_MINUTES, AlarmSpec.MAX_SLEEP_MINUTES)
    }
}
