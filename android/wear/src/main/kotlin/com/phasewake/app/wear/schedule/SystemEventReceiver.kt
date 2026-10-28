package com.phasewake.app.wear.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.phasewake.app.wear.wearApp
import kotlinx.coroutines.launch

/** Plant nach Neustart, App-Update und Zeit- oder Zeitzonenwechsel neu; Alarme gehen dabei verloren bzw. verschieben sich. */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
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

    private companion object {
        /** Systemereignisse wie im Manifest. */
        val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)
    }
}
