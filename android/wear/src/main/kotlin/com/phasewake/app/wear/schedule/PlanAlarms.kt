package com.phasewake.app.wear.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.phasewake.app.core.AlarmSpec
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.phasewake.app.core.WakePlan
import com.phasewake.app.wear.complication.NextAlarmComplicationService
import com.phasewake.app.wear.schedule.AlarmScheduler.Companion.REQUEST_DEADLINE
import com.phasewake.app.wear.schedule.AlarmScheduler.Companion.REQUEST_WINDOW
import com.phasewake.app.wear.schedule.AlarmScheduler.Companion.cancel
import com.phasewake.app.wear.schedule.AlarmScheduler.Companion.setDeadlineAt
import com.phasewake.app.wear.schedule.AlarmScheduler.Companion.setSnoozeEnd
import com.phasewake.app.wear.tile.NextAlarmTileService
import com.phasewake.app.wear.ui.MainActivity
import java.time.Instant
import kotlinx.coroutines.withTimeoutOrNull

// Stellen der Alarme und Nebenarbeiten beim Neuplanen der Uhr, ausgelagert aus [AlarmScheduler].

private const val TAG = "AlarmScheduler"

/**
 * Stellt die System-Alarme von [AlarmScheduler.reschedule] und den Eintrag für Direct Boot; ohne Unterbrechung (nicht
 * `suspend`): Ein neuer Alarm ersetzt den alten mit demselben PendingIntent, aufgehoben wird nur ohne Plan.
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
    // Zielzeit aus der Entscheidung (dieselbe Regel, die ScheduleRulesTest prüft). Ersetzt den bisherigen
    // Alarm (gleicher PendingIntent), ein vorheriges Aufheben ist nicht nötig.
    setDeadlineAt(context, plan, decision.deadlineAt ?: plan.target)
    // Auch ein schon begonnenes Fenster startet über einen Alarm: Nur so darf der Messdienst
    // aus dem Hintergrund (Empfänger, Data-Layer-Dienst) in den Vordergrund.
    val start = decision.windowAt ?: plan.windowStart
    AlarmScheduler.setAlarmClock(
        context,
        start,
        AlarmReceiver.pending(context, AlarmReceiver.ACTION_WINDOW_START, plan, REQUEST_WINDOW, at = start),
    )
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

/** Höchstdauer je Nebenarbeit nach dem Stellen der Alarme ([sideWork]). */
internal const val SIDE_WORK_TIMEOUT_MS = 3_000L

/**
 * Eine Nebenarbeit nach dem Stellen der Alarme ([AlarmScheduler.reschedule], [SystemEventReceiver]): höchstens
 * [timeoutMs], Fehler und Zeitüberschreitung werden unter [tag] geloggt, nie weitergereicht.
 */
internal suspend fun sideWork(
    tag: String,
    what: String,
    timeoutMs: Long = SIDE_WORK_TIMEOUT_MS,
    block: suspend () -> Unit,
) {
    try {
        if (withTimeoutOrNull(timeoutMs) { block() } == null) Log.w(tag, "$what: keine Antwort, übergangen")
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w(tag, "$what fehlgeschlagen", e)
    }
}

private const val REQUEST_SHOW = 4

/**
 * Kachel und Komplikation sofort aktualisieren: Das System fragt sie nicht von sich aus ab (Komplikation
 * `UPDATE_PERIOD_SECONDS = 0`), nur nach dem Neuplanen und zur Zielzeit ([AlarmReceiver]).
 */
internal fun AlarmScheduler.Companion.refreshSurfaces(context: Context) {
    runCatching { TileService.getUpdater(context).requestUpdate(NextAlarmTileService::class.java) }
    runCatching {
        ComplicationDataSourceUpdateRequester
            .create(context, ComponentName(context, NextAlarmComplicationService::class.java))
            .requestUpdateAll()
    }
}

/**
 * `setAlarmClock` statt `setExact`: Das System behandelt es als Wecker (zeigt ihn an, weckt aus
 * dem Ruhezustand und erlaubt den Start des Messdienstes aus dem Hintergrund). Ohne Recht für exakte Alarme
 * (entziehbar auf Android 12/12L) oder bei einer [SecurityException] ungenau statt gar nicht, wie der
 * Zweitwecker am Handy: Sonst bräche [AlarmScheduler.reschedule] ab, und Fenster und Zielzeit wären beide weg.
 */
internal fun AlarmScheduler.Companion.setAlarmClock(context: Context, at: Instant, operation: PendingIntent) {
    val manager = context.getSystemService(AlarmManager::class.java)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
        val show = PendingIntent.getActivity(
            context, REQUEST_SHOW, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(at.toEpochMilli(), show), operation)
            return
        } catch (e: SecurityException) {
            Log.w(TAG, "Exakter Alarm nicht erlaubt", e)
        }
    }
    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), operation)
}
