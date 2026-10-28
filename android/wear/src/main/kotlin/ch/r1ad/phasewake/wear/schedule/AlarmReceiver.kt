package ch.r1ad.phasewake.wear.schedule

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ch.r1ad.phasewake.core.WakePlan
import ch.r1ad.phasewake.wear.wake.WakeService
import java.time.Instant

/** Empfängt die System-Alarme und gibt sie an den [WakeService] weiter. */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val plan = planFrom(intent) ?: return
        when (intent.action) {
            ACTION_WINDOW_START -> WakeService.start(context, plan)
            ACTION_DEADLINE -> WakeService.command(context, WakeService.ACTION_DEADLINE, plan)
            ACTION_SNOOZE_END -> WakeService.command(context, WakeService.ACTION_SNOOZE_END, plan)
        }
    }

    companion object {
        const val ACTION_WINDOW_START = "ch.r1ad.phasewake.action.WINDOW_START"
        const val ACTION_DEADLINE = "ch.r1ad.phasewake.action.DEADLINE"
        const val ACTION_SNOOZE_END = "ch.r1ad.phasewake.action.SNOOZE_END"

        private const val EXTRA_ALARM_ID = "alarmId"
        private const val EXTRA_WINDOW_START = "windowStart"
        private const val EXTRA_TARGET = "target"

        /** PendingIntent für [action]; mit `plan = null` nur zum Abbrechen (gleiche Identität). */
        fun pending(context: Context, action: String, plan: WakePlan?, requestCode: Int): PendingIntent {
            val intent = Intent(context, AlarmReceiver::class.java).setAction(action)
            if (plan != null) putPlan(intent, plan)
            return PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        fun putPlan(intent: Intent, plan: WakePlan): Intent = intent
            .putExtra(EXTRA_ALARM_ID, plan.alarmId)
            .putExtra(EXTRA_WINDOW_START, plan.windowStart.toEpochMilli())
            .putExtra(EXTRA_TARGET, plan.target.toEpochMilli())

        fun planFrom(intent: Intent): WakePlan? {
            val id = intent.getStringExtra(EXTRA_ALARM_ID) ?: return null
            return WakePlan(
                alarmId = id,
                windowStart = Instant.ofEpochMilli(intent.getLongExtra(EXTRA_WINDOW_START, 0)),
                target = Instant.ofEpochMilli(intent.getLongExtra(EXTRA_TARGET, 0)),
            )
        }
    }
}
