package com.phasewake.app.core

import java.time.Duration
import java.time.Instant

/** Schlummern: 5 Minuten, höchstens 3-mal pro Weckvorgang. */
object Snooze {
    /** Dauer einer Schlummer-Runde in Minuten. */
    const val MINUTES: Int = 5

    /** Höchstzahl Schlummer-Runden je Weckvorgang; danach nur noch Stoppen. */
    const val MAX_COUNT: Int = 3

    /** Zeitpunkt des nächsten Klingelns nach Schlummern ab [now]. */
    fun nextRing(now: Instant): Instant = now.plus(Duration.ofMinutes(MINUTES.toLong()))
}

/**
 * Harte Grenze einer laufenden Nacht (Messen, Klingeln, Schlummern), gleich auf Galaxy Watch und Apple Watch (wie
 * `NightLimit` in Swift): Spätestens [AFTER_TARGET] nach der Zielzeit endet sie, auch wenn sie noch klingelt oder
 * schlummert (Sicherheitsnetz gegen eine hängende Nacht; reguläres Klingeln und Schlummern ist lange vorher vorbei).
 */
object NightLimit {
    /** Abstand nach der Zielzeit in Minuten. */
    const val MINUTES: Int = 120

    /** [MINUTES] als Dauer. */
    val AFTER_TARGET: Duration = Duration.ofMinutes(MINUTES.toLong())
}
