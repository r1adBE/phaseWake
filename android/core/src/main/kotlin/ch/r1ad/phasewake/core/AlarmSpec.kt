package ch.r1ad.phasewake.core

import java.time.DayOfWeek

/**
 * Ein Wecker, wie ihn der Nutzer einstellt. Gilt auf Handy und Uhr gleich.
 *
 * @property id Feste Kennung (UUID als Text), über Handy und Uhr hinweg gleich.
 * @property hour Zielzeit, Stunde 0–23. Bei [sleepDurationMinutes] ist das die späteste Weckzeit.
 * @property minute Zielzeit, Minute 0–59.
 * @property days Wochentage, an denen der Wecker gilt; leer = einmalig.
 * @property windowMinutes Weckfenster vor der Zielzeit, 10–30 Minuten.
 * @property enabled Ob der Wecker aktiv ist.
 * @property phoneBackup Ob das Handy als Zweitwecker mitklingelt.
 * @property sleepDurationMinutes Optional: Zielzeit = Einschlafzeit + Dauer, höchstens [hour]:[minute].
 * @property label Freier Name, z. B. «Arbeit».
 */
data class AlarmSpec(
    val id: String,
    val hour: Int,
    val minute: Int,
    val days: Set<DayOfWeek> = emptySet(),
    val windowMinutes: Int = DEFAULT_WINDOW_MINUTES,
    val enabled: Boolean = true,
    val phoneBackup: Boolean = true,
    val sleepDurationMinutes: Int? = null,
    val label: String = "",
) {
    init {
        require(id.isNotBlank()) { "id darf nicht leer sein" }
        require(hour in 0..23) { "hour muss zwischen 0 und 23 liegen" }
        require(minute in 0..59) { "minute muss zwischen 0 und 59 liegen" }
        require(windowMinutes in MIN_WINDOW_MINUTES..MAX_WINDOW_MINUTES) {
            "windowMinutes muss zwischen $MIN_WINDOW_MINUTES und $MAX_WINDOW_MINUTES liegen"
        }
        require(sleepDurationMinutes == null || sleepDurationMinutes in MIN_SLEEP_MINUTES..MAX_SLEEP_MINUTES) {
            "sleepDurationMinutes muss zwischen $MIN_SLEEP_MINUTES und $MAX_SLEEP_MINUTES liegen"
        }
    }

    /** Einmaliger Wecker: wird nach dem Klingeln ausgeschaltet. */
    val isOneShot: Boolean get() = days.isEmpty()

    companion object {
        const val DEFAULT_WINDOW_MINUTES: Int = 30
        const val MIN_WINDOW_MINUTES: Int = 10

        /** Grenze von watchOS (Smart-Alarm-Sitzung höchstens 30 Min), auf beiden Plattformen gleich. */
        const val MAX_WINDOW_MINUTES: Int = 30

        const val MIN_SLEEP_MINUTES: Int = 60
        const val MAX_SLEEP_MINUTES: Int = 12 * 60
    }
}
