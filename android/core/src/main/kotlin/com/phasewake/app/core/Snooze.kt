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
