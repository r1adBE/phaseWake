package com.phasewake.app.core

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
 * @property phone Wie das Handy als Zweitwecker weckt (aus, nur Vibration, Ton und Vibration).
 * @property watchSound Ob die Uhr zusätzlich zur Vibration einen Ton spielt.
 * @property sound Weckton des Handys als Uri-Text; leer = Standard-Weckton des Systems.
 * @property sleepDurationMinutes Optional: Zielzeit = Einschlafzeit + Dauer, höchstens [hour]:[minute].
 * @property label Freier Name, z. B. «Arbeit».
 * @property sunrise Sonnenaufgang auf dem Bildschirm: aus, beim Wecken oder ab Beginn des Weckfensters.
 * @property sunriseDevice Wo der Sonnenaufgang leuchtet: Handy (Standard), Uhr oder beide. Auf Apple nur Handy.
 * @property wakeTask Was zum Beenden nötig ist: Halten (Standard) oder eine Rechenaufgabe am Handy.
 */
data class AlarmSpec(
    val id: String,
    val hour: Int,
    val minute: Int,
    val days: Set<DayOfWeek> = emptySet(),
    val windowMinutes: Int = DEFAULT_WINDOW_MINUTES,
    val enabled: Boolean = true,
    val phone: PhoneAlert = PhoneAlert.SOUND,
    val watchSound: Boolean = false,
    val sound: String = "",
    val sleepDurationMinutes: Int? = null,
    val label: String = "",
    val sunrise: Sunrise = Sunrise.OFF,
    val sunriseDevice: SunriseDevice = SunriseDevice.PHONE,
    val wakeTask: WakeTask = WakeTask.NONE,
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

    /** Ob das Handy als Zweitwecker mitweckt. */
    val phoneBackup: Boolean get() = phone != PhoneAlert.OFF

    /** Einmaliger Wecker: wird nach dem Klingeln ausgeschaltet. */
    val isOneShot: Boolean get() = days.isEmpty()

    /** Sonnenaufgang auf dem Handy-Bildschirm; [Sunrise.OFF], wenn er nur auf der Uhr leuchtet. */
    val phoneSunrise: Sunrise get() = if (sunriseDevice.onPhone) sunrise else Sunrise.OFF

    /** Sonnenaufgang auf dem Uhr-Bildschirm; [Sunrise.OFF], wenn er nur auf dem Handy leuchtet. */
    val watchSunrise: Sunrise get() = if (sunriseDevice.onWatch) sunrise else Sunrise.OFF

    companion object {
        const val DEFAULT_WINDOW_MINUTES: Int = 30
        const val MIN_WINDOW_MINUTES: Int = 10

        /** Grenze von watchOS (Smart-Alarm-Sitzung höchstens 30 Min), auf beiden Plattformen gleich. */
        const val MAX_WINDOW_MINUTES: Int = 30

        const val MIN_SLEEP_MINUTES: Int = 60
        const val MAX_SLEEP_MINUTES: Int = 12 * 60
    }
}

/** Weckart des Handy-Zweitweckers. Wecker gelten als Wecker: auch bei «Nicht stören» und lautlosem Handy. */
enum class PhoneAlert {
    /** Handy weckt nicht mit. */
    OFF,

    /** Nur Vibration, kein Ton. */
    VIBRATE,

    /** Ton (sanft lauter werdend) und Vibration. */
    SOUND,
}

/**
 * Weckaufgabe: was zum Beenden des Weckers nötig ist. Schlummern geht immer ohne Aufgabe.
 * Auf dem iPhone nicht wählbar (der Stopp-Knopf des Systems lässt sich nicht ersetzen); dort gilt [NONE].
 */
enum class WakeTask {
    /** Halten zum Stoppen. */
    NONE,

    /** [MathChallenge.STEPS] Additionen am Handy lösen. */
    MATH,
}

/**
 * Sonnenaufgang: Der Bildschirm (Handy, Uhr oder beide, siehe [SunriseDevice]) leuchtet erst schwach dunkelorange und wird bis
 * zu vollem, warmem Licht bei voller Helligkeit. Unabhängig von [PhoneAlert].
 */
enum class Sunrise {
    /** Kein Licht. */
    OFF,

    /** Beginnt im Weckmoment und ist nach [RING_SECONDS] Sekunden voll hell. */
    RING,

    /** Beginnt mit dem Weckfenster und ist zur Zielzeit voll hell; weckt die Uhr früher, in [CATCH_UP_SECONDS] Sekunden. */
    WINDOW;

    companion object {
        const val RING_SECONDS: Int = 120
        const val CATCH_UP_SECONDS: Int = 60

        /** Fortschritt 0–1 eines Lichts von [start] bis [end] zum Zeitpunkt [now]. */
        fun progress(start: java.time.Instant, end: java.time.Instant, now: java.time.Instant): Float {
            val total = java.time.Duration.between(start, end).toMillis()
            if (total <= 0) return 1f
            return (java.time.Duration.between(start, now).toMillis().toFloat() / total).coerceIn(0f, 1f)
        }

        /** Bildschirmhelligkeit 0.01–1 zum Fortschritt: am Anfang sehr dunkel, dann schneller heller. */
        fun brightness(progress: Float): Float {
            val p = progress.coerceIn(0f, 1f)
            return 0.01f + 0.99f * p * p
        }
    }
}

/**
 * Wo der Sonnenaufgang leuchtet. Gleiche Farbkurve und Helligkeit auf beiden Geräten.
 * Auf Apple nicht wählbar (die Apple Watch hält ihren Bildschirm in der Weck-Sitzung nicht
 * zuverlässig an); dort gilt immer das Handy, der Wert bleibt aber im Format erhalten.
 */
enum class SunriseDevice {
    /** Nur auf dem Handy (Standard, wie vor der Gerätewahl). */
    PHONE,

    /** Nur auf der Uhr. */
    WATCH,

    /** Auf Handy und Uhr. */
    BOTH;

    val onPhone: Boolean get() = this != WATCH
    val onWatch: Boolean get() = this != PHONE
}
