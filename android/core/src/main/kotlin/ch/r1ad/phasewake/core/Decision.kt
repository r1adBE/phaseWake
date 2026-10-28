package ch.r1ad.phasewake.core

/** Ergebnis des [WakeDetector] für eine Epoche. */
sealed interface Decision {
    /** Noch kein guter Moment: weiter messen. */
    data object Wait : Decision

    /** Jetzt wecken. */
    data class Wake(val reason: WakeReason) : Decision
}

/** Warum geweckt wurde. Wird im Verlauf gespeichert. */
enum class WakeReason {
    /** Deutliche Bewegung in mehreren Epochen: Die Person ist schon wach. */
    AWAKE,

    /** Leichte Bewegung, und der Puls ist (falls gemessen) gestiegen. */
    LIGHT_SLEEP,

    /** Zielzeit erreicht, ohne dass vorher ein guter Moment kam. */
    DEADLINE,
}
