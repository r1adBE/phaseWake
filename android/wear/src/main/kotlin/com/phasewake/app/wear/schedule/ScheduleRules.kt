package com.phasewake.app.wear.schedule

import com.phasewake.app.core.WakePlan
import java.time.Duration
import java.time.Instant

// Entscheidungen von [AlarmScheduler.reschedule] ohne Android-Klassen, in Unit-Tests prüfbar.

internal object ScheduleRules {
    /** Ein Alarm «jetzt» liegt so weit vorn, damit das System ihn sicher noch zustellt. */
    val IMMEDIATE: Duration = Duration.ofSeconds(1)

    /**
     * Was [AlarmScheduler.reschedule] stellt: [cancelMeasuring] = eine laufende Messung gilt einem Plan, der nicht mehr
     * gilt; [keepRunning] = eine Nacht klingelt oder schlummert, nichts anfassen; [pendingRingAt] = abgelegtes Wecken von
     * vor einem Neustart bleibt (Schlummer-Ende-Alarm); [windowAt]/[deadlineAt] = Fensterbeginn und Zielzeit des Plans.
     */
    data class Decision(
        val cancelMeasuring: Boolean,
        val keepRunning: Boolean,
        val pendingRingAt: Instant?,
        val windowAt: Instant?,
        val deadlineAt: Instant?,
    )

    /**
     * [plan]: der Plan, der jetzt gilt; [measuring]/[active]: Plan der laufenden Messung bzw. Nacht
     * ([com.phasewake.app.wear.wake.WakeService]); [pending]: abgelegtes, noch ausstehendes Wecken ([pendingRing]).
     */
    fun decide(plan: WakePlan?, measuring: WakePlan?, active: WakePlan?, pending: DirectBoot.Saved?, now: Instant): Decision {
        val cancel = measuring != null && measuring != plan
        // Klingelt oder schlummert gerade eine Nacht: ihre Alarme nicht anfassen, keinen anderen Wecker starten.
        if (active != null && measuring == null) return Decision(cancel, keepRunning = true, null, null, null)
        val kept = pending?.takeIf { it.plan != plan }?.let { maxOf(it.at, now.plus(IMMEDIATE)) }
        if (plan == null) return Decision(cancel, keepRunning = false, kept, null, null)
        // Auch ein schon begonnenes Fenster startet über einen Alarm (nur so darf der Messdienst in den Vordergrund).
        return Decision(cancel, keepRunning = false, kept, maxOf(plan.windowStart, now.plus(IMMEDIATE)), plan.target)
    }

    /**
     * Ob ein abgelegtes Wecken [saved] noch aussteht: geschlummert (Ende nach der Zielzeit) oder Zielzeit vorbei, noch
     * nachzuholen ([catchesUp]), die Nacht nicht erledigt ([doneUntil]) und der Wecker noch da ([exists]).
     */
    fun pendingRing(saved: DirectBoot.Saved, now: Instant, catchesUp: Boolean, doneUntil: Instant?, exists: Boolean): Boolean {
        val late = saved.at.isAfter(saved.plan.target) || !saved.plan.target.isAfter(now)
        if (!late || !catchesUp) return false
        if (doneUntil != null && !doneUntil.isBefore(saved.plan.target)) return false
        return exists
    }
}
