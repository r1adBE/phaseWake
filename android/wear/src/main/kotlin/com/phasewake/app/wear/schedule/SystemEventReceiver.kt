package com.phasewake.app.wear.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.phasewake.app.wear.wearApp
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * Plant nach Neustart, App-Update und Zeit- oder Zeitzonenwechsel neu; Alarme gehen dabei verloren bzw. verschieben sich.
 * Vor dem ersten Entsperren (Direct Boot) nur den abgelegten Wecker aus [DirectBoot]; der Speicher der App ist dann nicht lesbar.
 */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        if (!DirectBoot.isUnlocked(context)) {
            DirectBoot.restore(context)
            return
        }
        val pending = goAsync()
        val app = context.wearApp
        app.appScope.launch {
            try {
                // Vor dem Entsperren geweckt: Nacht als erledigt merken und einen Einmal-Wecker ausschalten.
                // Ein Fehler hier darf das Neuplanen nicht verhindern.
                DirectBoot.takeRang(context)?.let { (alarmId, target) ->
                    try {
                        app.scheduler.markDone(alarmId, target)
                        app.scheduler.saveDone()
                        val spec = app.alarms.current().alarms.firstOrNull { it.id == alarmId }
                        if (spec?.isOneShot == true && spec.enabled) app.sync.publishAlarms(app.alarms.setEnabled(spec.id, false))
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Nachtrag nach dem Entsperren fehlgeschlagen", e)
                    }
                }
                app.scheduler.reschedule()
                // Lief «Gute Nacht» vor dem Neustart, gleich wieder aufnehmen (über den Prüf-Alarm, der den Vordergrund erlaubt).
                if (app.goodNight.load() != null) AlarmScheduler.armGoodNightCheck(context, Instant.now().plusSeconds(5))
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SystemEventReceiver"

        /** Systemereignisse wie im Manifest; nur Text, auf jeder Version unbedenklich. */
        @android.annotation.SuppressLint("InlinedApi")
        val ACTIONS = setOf(
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
