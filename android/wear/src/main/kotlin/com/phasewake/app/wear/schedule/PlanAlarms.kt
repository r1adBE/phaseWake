package com.phasewake.app.wear.schedule

import android.content.Context
import android.util.Log
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.WakePlan
import com.phasewake.app.wear.schedule.AlarmScheduler.Companion.REQUEST_DEADLINE
import com.phasewake.app.wear.schedule.AlarmScheduler.Companion.REQUEST_WINDOW
import com.phasewake.app.wear.schedule.AlarmScheduler.Companion.cancel
import com.phasewake.app.wear.schedule.AlarmScheduler.Companion.setAlarmClock
import com.phasewake.app.wear.schedule.AlarmScheduler.Companion.setDeadlineAt
import com.phasewake.app.wear.schedule.AlarmScheduler.Companion.setSnoozeEnd
import kotlinx.coroutines.withTimeoutOrNull

// Stellen der Alarme und Nebenarbeiten beim Neuplanen der Uhr (Audit A-3), ausgelagert aus [AlarmScheduler].

private const val TAG = "AlarmScheduler"

/**
 * Stellt die System-Alarme von [AlarmScheduler.reschedule] und den Eintrag für Direct Boot; ohne Unterbrechung (nicht
 * `suspend`, Audit A-3): Ein neuer Alarm ersetzt den alten mit demselben PendingIntent, aufgehoben wird nur ohne Plan.
 * @return der geplante Plan oder `null`.
 */
internal fun setPlanAlarms(
    context: Context,
    plan: WakePlan?,
    decision: ScheduleRules.Decision,
    pendingRing: DirectBoot.Saved?,
    spec: AlarmSpec?,
    after: WakePlan?,
    specs: List<AlarmSpec>,
): WakePlan? {
    val pendingAt = decision.pendingRingAt
    if (pendingRing != null && pendingAt != null) {
        Log.i(TAG, "Wecken ${pendingRing.plan.alarmId} von vor dem Neustart bleibt: ${pendingRing.at}")
        setSnoozeEnd(context, pendingRing.plan, pendingAt)
    }
    if (plan == null) {
        cancel(context, REQUEST_WINDOW, AlarmReceiver.ACTION_WINDOW_START)
        cancel(context, REQUEST_DEADLINE, AlarmReceiver.ACTION_DEADLINE)
        if (pendingRing == null) DirectBoot.clear(context) else DirectBoot.saveNext(context, null, null)
        Log.i(TAG, "Kein aktiver Wecker")
        return null
    }
    // Zielzeit aus der Entscheidung (Review M-2: dieselbe Regel, die ScheduleRulesTest prüft). Ersetzt den bisherigen
    // Alarm (gleicher PendingIntent), ein vorheriges Aufheben ist nicht nötig.
    setDeadlineAt(context, plan, decision.deadlineAt ?: plan.target)
    // Auch ein schon begonnenes Fenster startet über einen Alarm: Nur so darf der Messdienst
    // aus dem Hintergrund (Empfänger, Data-Layer-Dienst) in den Vordergrund.
    val start = decision.windowAt ?: plan.windowStart
    setAlarmClock(context, start, AlarmReceiver.pending(context, AlarmReceiver.ACTION_WINDOW_START, plan, REQUEST_WINDOW))
    if (pendingRing == null) {
        // Dazu der Plan danach: Endet eine Nacht vor dem ersten Entsperren, gilt dieser (DirectBoot.promoteNext).
        DirectBoot.save(context, plan, spec, after, specs.firstOrNull { it.id == after?.alarmId })
    } else {
        // Der abgelegte Eintrag ist das ausstehende Wecken; der neue Plan folgt danach.
        DirectBoot.saveNext(context, plan, spec)
    }
    Log.i(TAG, "Geplant: Fenster ${plan.windowStart} bis ${plan.target}")
    return plan
}

/** Höchstdauer je Nebenarbeit nach dem Stellen der Alarme ([sideWork], Audit A-3). */
internal const val SIDE_WORK_TIMEOUT_MS = 3_000L

/**
 * Eine Nebenarbeit nach dem Stellen der Alarme ([AlarmScheduler.reschedule], [SystemEventReceiver]): höchstens
 * [timeoutMs], Fehler und Zeitüberschreitung werden unter [tag] geloggt, nie weitergereicht.
 */
internal suspend fun sideWork(tag: String, what: String, timeoutMs: Long = SIDE_WORK_TIMEOUT_MS, block: suspend () -> Unit) {
    try {
        if (withTimeoutOrNull(timeoutMs) { block() } == null) Log.w(tag, "$what: keine Antwort, übergangen")
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w(tag, "$what fehlgeschlagen", e)
    }
}
