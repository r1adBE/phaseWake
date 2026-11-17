package com.phasewake.app.wear.record

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.phasewake.app.wear.schedule.DirectBoot
import com.phasewake.app.wear.system.StartLock


/**
 * Prüf-Alarm der Aufzeichnung, Start vom Handy (über einen exakten Alarm, siehe [RecordControl.fromPhone]) und Start
 * der Lernphase ([RecordControl.startForLearning]).
 */
class RecordReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Vor dem Entsperren ist die Aufzeichnung nicht lesbar.
        if (!DirectBoot.isUnlocked(context)) return
        if (intent.action == RecordControl.ACTION_CHECK && RecordService.running) return RecordControl.armCheck(context)
        // Start-Wakelock bis zum eigenen des Dienstes; sonst schläft die Uhr nach `onReceive`.
        StartLock.acquire(context)
        when (intent.action) {
            RecordControl.ACTION_CHECK -> RecordControl.resume(context)
            RecordControl.ACTION_PHONE_START -> startFromPhone(context, intent)
            RecordControl.ACTION_LEARN_START -> RecordControl.startService(context, RecordService.ACTION_START_LEARN) {
                it.putExtra(RecordService.EXTRA_UNTIL, intent.getLongExtra(RecordService.EXTRA_UNTIL, 0L))
            }
            else -> StartLock.release()
        }
    }

    private fun startFromPhone(context: Context, intent: Intent) {
        RecordControl.startService(context, RecordService.ACTION_START_PHONE) {
            it.putExtra(RecordService.EXTRA_CONTINUOUS, intent.getBooleanExtra(RecordService.EXTRA_CONTINUOUS, false))
        }
    }
}
