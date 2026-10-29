package com.phasewake.app.core

/** Ergebnis des [WakeDetector] für eine Epoche. */
sealed interface Decision {
    /** Noch kein guter Moment: weiter messen. */
    data object Wait : Decision

    /** Jetzt wecken. */
    data class Wake(val reason: WakeReason) : Decision
}

/**
 * Warum geweckt wurde. Wird im Verlauf gespeichert. [EXACT] und [SHORT_NIGHT] kennen ältere Versionen nicht;
 * der [Codec] schreibt sie darum zusätzlich als [DEADLINE] (siehe [Codec.encodeNight]).
 */
enum class WakeReason {
    /** Deutliche Bewegung in mehreren Epochen: Die Person ist schon wach. */
    AWAKE,

    /** Leichte Bewegung, und der gemessene Puls ist über den Basispuls gestiegen. */
    LIGHT_SLEEP,

    /** Zielzeit erreicht, ohne dass vorher ein guter Moment kam. */
    DEADLINE,

    /** Wecker «Genau» ([AlarmSpec.isExact]): zur Zielzeit geweckt, nie früher; die Uhr hat nur für den Verlauf gemessen. */
    EXACT,

    /** Kurze Nacht ([ShortNight.blocksEarly]): frühe Momente wurden übergangen, geweckt zur Zielzeit. */
    SHORT_NIGHT;

    /** Ob zur Zielzeit geweckt wurde, weil kein früher Moment zählte ([DEADLINE], [EXACT], [SHORT_NIGHT]). */
    val atTarget: Boolean get() = this == DEADLINE || this == EXACT || this == SHORT_NIGHT

    /** Ob in dieser Nacht früheres Wecken erlaubt war (nicht «Genau», keine kurze Nacht). */
    val earlyAllowed: Boolean get() = this != EXACT && this != SHORT_NIGHT
}
