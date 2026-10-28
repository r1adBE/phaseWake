package com.phasewake.app.core

import java.time.Duration
import java.time.Instant

/** Schlummern: 5 Minuten, höchstens 3-mal pro Weckvorgang. */
object Snooze {
    const val MINUTES: Int = 5
    const val MAX_COUNT: Int = 3

    /** Ob nach [count] bisherigen Schlummer-Runden noch einmal geschlummert werden darf. */
    fun canSnooze(count: Int): Boolean = count < MAX_COUNT

    /** Zeitpunkt des nächsten Klingelns nach Schlummern ab [now]. */
    fun nextRing(now: Instant): Instant = now.plus(Duration.ofMinutes(MINUTES.toLong()))
}
