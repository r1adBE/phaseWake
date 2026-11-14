package com.phasewake.app.wear.wake

import com.phasewake.app.core.DetectorConfig
import com.phasewake.app.core.Snooze
import com.phasewake.app.core.Transient
import com.phasewake.app.core.WakePlan
import com.phasewake.app.core.WakeReason
import java.time.Duration
import java.time.Instant
import java.util.Locale

// Entscheidungen des Weckdienstes ohne Android-Klassen (Fenster → Klingeln → Schlummern → Ende), in Unit-Tests prüfbar.

internal object WakeRules {
    /** Höchstens so lange klingelt es ohne Reaktion am Stück. */
    const val RING_TIMEOUT_MS = 10 * 60_000L

    /** Abstand des Wiederaufnahme-Alarms nach der Zeitgrenze des Klingelns: Der Ablauf im Prozess kommt immer zuerst. */
    val RESUME_MARGIN: Duration = Duration.ofSeconds(30)

    /** «Gute Nacht» nur, wenn die eingestellte Weckzeit höchstens so weit entfernt ist. */
    val GOOD_NIGHT_MAX_AHEAD: Duration = Duration.ofHours(16)

    /** Verbleibende Schlummer-Runden nach [snoozes] Runden; beim Probewecker keine (er prüft genau einen Weckvorgang). */
    fun snoozesLeft(alarmId: String, snoozes: Int): Int = if (Transient.allowsSnooze(alarmId)) Snooze.MAX_COUNT - snoozes else 0

    /** Was die Zeitgrenze einer Klingel-Runde auslöst. */
    enum class Timeout {
        /** Noch Runden: von selbst schlummern (das Handy klingelt als Sicherung weiter). */
        SNOOZE,

        /** Letzte Runde: die Nacht beenden. */
        DISMISS,
    }

    /** Zeitgrenze erreicht, niemand hat reagiert; [snoozesLeft] Runden bleiben. */
    fun timeout(snoozesLeft: Int): Timeout = if (snoozesLeft > 0) Timeout.SNOOZE else Timeout.DISMISS

    /**
     * Wiederaufnahme-Alarm einer Klingel-Runde ab [now]: kurz nach der Zeitgrenze. Stirbt der Prozess mitten im Klingeln,
     * klingelt es damit wieder; Schlummern ersetzt ihn, das Ende der Nacht hebt ihn auf.
     */
    fun resumeAt(now: Instant): Instant = now.plusMillis(RING_TIMEOUT_MS).plus(RESUME_MARGIN)

    /**
     * Grund des Weckens, wenn die Messung die Zielzeit erreicht: «Genau» ([exact]), übergangene kurze Nacht
     * ([earlyBlocked]) oder was die Erkennung zum Schluss meldet ([detected]).
     */
    fun deadlineReason(exact: Boolean, earlyBlocked: Boolean, detected: () -> WakeReason): WakeReason = when {
        exact -> WakeReason.EXACT
        earlyBlocked -> WakeReason.SHORT_NIGHT
        else -> detected()
    }

    /**
     * Ob ein Wecker-Alarm für [plan] die laufende Nacht [current] übernimmt: Läuft keine, dieselbe oder erst eine
     * Messung ([measuring]), ja. Klingelt oder schlummert eine andere Nacht, wartet [plan], bis sie beendet ist.
     */
    fun adopts(current: WakePlan?, measuring: Boolean, plan: WakePlan): Boolean = current == null || current == plan || measuring

    /**
     * Bis wann die Nacht mit Zielzeit [target] erledigt ist: die eingestellte Weckzeit [nominal] derselben Nacht, auch
     * wenn der Einschlaf-Modus früher geweckt hat (höchstens [GOOD_NIGHT_MAX_AHEAD] später). Sonst begänne danach
     * dieselbe Nacht noch einmal.
     */
    fun doneUntil(target: Instant, nominal: Instant?): Instant =
        if (nominal != null && nominal.isAfter(target) && Duration.between(target, nominal) <= GOOD_NIGHT_MAX_AHEAD) nominal else target

    /**
     * Zeile fürs Diagnose-Protokoll: Verzögerung vom geplanten Fensteralarm [scheduledAt] bis zum Start des Dienstes
     * [now], dazu bis zum Empfang [receivedAt] (0 = unbekannt), z. B. «watch start delay 47.0s (alarm 0.3s)» (alle in ms).
     */
    fun startDelayLine(scheduledAt: Long, receivedAt: Long, now: Long): String {
        val alarm = if (receivedAt > 0L) " (alarm ${seconds(receivedAt - scheduledAt)})" else ""
        return "watch start delay ${seconds(now - scheduledAt)}$alarm"
    }

    /**
     * Epochen ab Fensterbeginn [windowStart], die beim Start der Sensoren ([now]) schon vorbei sind: Sie bleiben leer und
     * zählen nicht für die Erkennung (Aufwärmphase, Basispuls), nur für den Verlauf (Audit c3b8b01 E-1). Identisch zu
     * `unmeasured` der Apple Watch (`SmartAlarm+Measuring.swift`): ganze 30-s-Epochen seit Fensterbeginn, nie negativ.
     */
    fun unmeasuredEpochs(windowStart: Instant, now: Instant): Int =
        (Duration.between(windowStart, now).seconds / DetectorConfig.EPOCH_SECONDS).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private fun seconds(millis: Long): String = String.format(Locale.ROOT, "%.1fs", millis / 1000.0)
}
