package com.phasewake.app.wear.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.phasewake.app.wear.wearApp
import kotlinx.coroutines.launch

/** Plant nach Neustart, App-Update und Zeit- oder Zeitzonenwechsel neu; Alarme gehen dabei verloren bzw. verschieben sich. */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.wearApp
        app.appScope.launch {
            try {
                app.scheduler.reschedule()
            } finally {
                pending.finish()
            }
        }
    }
}
