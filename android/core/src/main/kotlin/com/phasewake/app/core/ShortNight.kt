package com.phasewake.app.core

import java.time.Duration
import java.time.Instant

/**
 * Untergrenze für kurze Nächte: Wer deutlich weniger als das Schlafziel geschlafen hat, wird nicht früh geweckt,
 * sondern zur Zielzeit («Pünktlich ist das Produkt, früher ist Bonus»). Regel siehe `docs/PHASENERKENNUNG.md`,
 * Abschnitt 8. Die Swift-Umsetzung (`ios/PhaseWakeCore`) muss sich identisch verhalten.
 */
object ShortNight {
    /** So viele Minuten unter dem Schlafziel gilt eine Nacht noch nicht als kurz. */
    const val MARGIN_MINUTES: Int = 60

    /** Die automatische Einschlaf-Messung beginnt so viele Minuten vor der Bettzeit ([autoOnsetStart]). */
    const val AUTO_LEAD_MINUTES: Int = 60

    /**
     * Ob frühes Wecken gesperrt ist: Einschlafzeit [onset] bekannt und seit dann weniger als Schlafziel minus
     * [MARGIN_MINUTES] vergangen (bei 7½ h also unter 6½ h Schlaf). Ohne bekannte Einschlafzeit nie gesperrt.
     */
    fun blocksEarly(onset: Instant?, now: Instant, sleepGoalMinutes: Int): Boolean {
        if (onset == null) return false
        return Duration.between(onset, now) < Duration.ofMinutes((sleepGoalMinutes - MARGIN_MINUTES).toLong())
    }

    /**
     * Beginn der automatischen Einschlaf-Messung für die Zielzeit [target]: Bettzeit minus [AUTO_LEAD_MINUTES].
     * Bettzeit = [target] minus Schlafdauer ([sleepDurationMinutes], Einschlaf-Modus) bzw. minus Schlafziel
     * ([sleepGoalMinutes]); als feste Dauer gerechnet wie [Bedtime.bedtime].
     */
    fun autoOnsetStart(target: Instant, sleepGoalMinutes: Int, sleepDurationMinutes: Int?): Instant =
        target.minus(Duration.ofMinutes(((sleepDurationMinutes ?: sleepGoalMinutes) + AUTO_LEAD_MINUTES).toLong()))
}
