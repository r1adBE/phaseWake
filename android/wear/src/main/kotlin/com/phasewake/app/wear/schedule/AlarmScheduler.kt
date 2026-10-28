package com.phasewake.app.wear.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.WakePlan
import com.phasewake.app.shared.AlarmRepository
import com.phasewake.app.wear.ui.MainActivity
import com.phasewake.app.wear.wake.WakeService
import java.time.Clock
import java.time.Instant

/**
 * Stellt den System-Alarm für den Beginn des nächsten Weckfensters.
 *
 * Es gibt immer höchstens einen geplanten Fensterbeginn: den frühesten aller aktiven Wecker.
 * Läuft das Fenster bereits (Wecker kurz vor der Zielzeit gestellt), startet die Messung sofort.
 */
class AlarmScheduler(
    private val context: Context,
    private val alarms: AlarmRepository,
    private val onsetOverride: OnsetOverride,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /** Plant neu nach dem aktuellen Stand der Wecker. @return der geplante Plan oder `null`. */
    suspend fun reschedule(): WakePlan? {
        val now = clock.instant()
        val plan = onsetOverride.apply(AlarmSchedule.earliestPlan(alarms.current().alarms, now, clock.zone), now)
        cancelWindow()
        if (plan == null) {
            Log.i(TAG, "Kein aktiver Wecker")
            return null
        }
        if (!plan.windowStart.isAfter(now)) {
            WakeService.start(context, plan)
        } else {
            setAlarmClock(plan.windowStart, windowIntent(plan))
            Log.i(TAG, "Fenster geplant: ${plan.windowStart} bis ${plan.target}")
        }
        return plan
    }

    /** Sicherungs-Alarm für die Zielzeit, falls der Messdienst unterwegs beendet wird. */
    fun setDeadline(plan: WakePlan) =
        setAlarmClock(plan.target, AlarmReceiver.pending(context, AlarmReceiver.ACTION_DEADLINE, plan, REQUEST_DEADLINE))

    /** Weckt nach dem Schlummern erneut. */
    fun setSnoozeEnd(plan: WakePlan, at: Instant) =
        setAlarmClock(at, AlarmReceiver.pending(context, AlarmReceiver.ACTION_SNOOZE_END, plan, REQUEST_SNOOZE))

    fun cancelDeadlineAndSnooze() {
        alarmManager.cancel(AlarmReceiver.pending(context, AlarmReceiver.ACTION_DEADLINE, null, REQUEST_DEADLINE))
        alarmManager.cancel(AlarmReceiver.pending(context, AlarmReceiver.ACTION_SNOOZE_END, null, REQUEST_SNOOZE))
    }

    private fun cancelWindow() =
        alarmManager.cancel(AlarmReceiver.pending(context, AlarmReceiver.ACTION_WINDOW_START, null, REQUEST_WINDOW))

    private fun windowIntent(plan: WakePlan) =
        AlarmReceiver.pending(context, AlarmReceiver.ACTION_WINDOW_START, plan, REQUEST_WINDOW)

    /**
     * `setAlarmClock` statt `setExact`: Das System behandelt es als Wecker (zeigt ihn an, weckt aus
     * dem Ruhezustand und erlaubt den Start des Messdienstes aus dem Hintergrund). Als nächster
     * Wecker erscheint der Fensterbeginn, also die früheste mögliche Weckzeit.
     */
    private fun setAlarmClock(at: Instant, operation: PendingIntent) {
        val show = PendingIntent.getActivity(
            context, REQUEST_SHOW, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(at.toEpochMilli(), show), operation)
    }

    private companion object {
        const val TAG = "AlarmScheduler"
        const val REQUEST_WINDOW = 1
        const val REQUEST_DEADLINE = 2
        const val REQUEST_SNOOZE = 3
        const val REQUEST_SHOW = 4
    }
}
