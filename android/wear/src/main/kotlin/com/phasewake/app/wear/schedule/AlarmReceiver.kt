package com.phasewake.app.wear.schedule

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.phasewake.app.core.WakePlan
import com.phasewake.app.wear.record.LearnNotice
import com.phasewake.app.wear.system.StartLock
import com.phasewake.app.wear.wake.WakeService
import com.phasewake.app.wear.wearApp
import java.time.Instant
import kotlinx.coroutines.launch

/** Empfängt die System-Alarme und gibt sie an den [WakeService] weiter (die Warnung der Lernphase an [LearnNotice]). */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_GOOD_NIGHT_CHECK) {
            // Vor dem Entsperren ist «Gute Nacht» nicht lesbar; nach dem Entsperren plant der Neustart-Empfänger die Prüfung neu.
            if (!DirectBoot.isUnlocked(context)) return
            // Misst «Gute Nacht» noch, nur die nächste Prüfung stellen; sonst den Dienst neu starten (er liest den Stand).
            if (WakeService.goodNightActive) {
                AlarmScheduler.armGoodNightCheck(context)
            } else {
                StartLock.acquire(context)
                WakeService.resumeGoodNight(context)
            }
            return
        }
        if (intent.action == LearnNotice.ACTION_WARN) {
            // Warnung der Lernphase am Abend (Akku); vor dem Entsperren nicht lesbar.
            if (!DirectBoot.isUnlocked(context)) return
            val async = goAsync()
            val app = context.wearApp
            app.appScope.launch {
                try {
                    LearnNotice.onWarn(context)
                } finally {
                    async.finish()
                }
            }
            return
        }
        if (intent.action == AutoOnset.ACTION) {
            // Vor dem Entsperren nicht lesbar: dann keine Messung, der Wecker weckt wie bisher.
            if (!DirectBoot.isUnlocked(context)) return
            AutoOnset.alarmIdFrom(intent)?.let {
                StartLock.acquire(context)
                WakeService.autoOnset(context, it)
            }
            return
        }
        val plan = planFrom(intent) ?: return
        val action = when (intent.action) {
            ACTION_WINDOW_START -> WakeService.ACTION_START
            ACTION_DEADLINE -> WakeService.ACTION_DEADLINE
            ACTION_SNOOZE_END -> WakeService.ACTION_SNOOZE_END
            else -> return
        }
        // Bis der Dienst seinen eigenen Wakelock hält; sonst schläft die Uhr nach `onReceive` (Befund 13.11.2026).
        StartLock.acquire(context)
        if (action == WakeService.ACTION_START) {
            // Geplante Zeit und Empfang für die Verzögerung bis zum Start im Diagnose-Protokoll.
            WakeService.start(context, plan, intent.getLongExtra(EXTRA_AT, 0L), System.currentTimeMillis())
        } else {
            WakeService.command(context, action, plan)
        }
    }

    companion object {
        const val ACTION_WINDOW_START = "com.phasewake.app.action.WINDOW_START"
        const val ACTION_DEADLINE = "com.phasewake.app.action.DEADLINE"
        const val ACTION_SNOOZE_END = "com.phasewake.app.action.SNOOZE_END"
        const val ACTION_GOOD_NIGHT_CHECK = "com.phasewake.app.action.GOOD_NIGHT_CHECK"

        private const val EXTRA_ALARM_ID = "alarmId"
        private const val EXTRA_WINDOW_START = "windowStart"
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_EXACT = "exact"

        /** Geplante Zeit des Alarms (ms), für die Verzögerung bis zum Start des Dienstes. */
        private const val EXTRA_AT = "at"

        /**
         * PendingIntent für [action]; mit `plan = null` nur zum Abbrechen (gleiche Identität). [at]: geplante Zeit des
         * Alarms, nur fürs Diagnose-Protokoll (Extras zählen nicht zur Identität).
         */
        fun pending(context: Context, action: String, plan: WakePlan?, requestCode: Int, at: Instant? = null): PendingIntent {
            val intent = Intent(context, AlarmReceiver::class.java).setAction(action)
            if (plan != null) putPlan(intent, plan)
            if (at != null) intent.putExtra(EXTRA_AT, at.toEpochMilli())
            return PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        fun putPlan(intent: Intent, plan: WakePlan): Intent = intent
            .putExtra(EXTRA_ALARM_ID, plan.alarmId)
            .putExtra(EXTRA_WINDOW_START, plan.windowStart.toEpochMilli())
            .putExtra(EXTRA_TARGET, plan.target.toEpochMilli())
            .putExtra(EXTRA_EXACT, plan.exact)

        fun planFrom(intent: Intent): WakePlan? {
            val id = intent.getStringExtra(EXTRA_ALARM_ID) ?: return null
            return WakePlan(
                alarmId = id,
                windowStart = Instant.ofEpochMilli(intent.getLongExtra(EXTRA_WINDOW_START, 0)),
                target = Instant.ofEpochMilli(intent.getLongExtra(EXTRA_TARGET, 0)),
                exact = intent.getBooleanExtra(EXTRA_EXACT, false),
            )
        }
    }
}
