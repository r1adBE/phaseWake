package com.phasewake.app.wear.schedule

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.Transient
import com.phasewake.app.shared.AlarmSnapshot
import com.phasewake.app.wear.sync.PhoneCatchUp
import com.phasewake.app.wear.wearApp
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * Plant nach Neustart, App-Update, Zeit- oder Zeitzonenwechsel und erteiltem Recht für exakte Alarme neu; Alarme gehen
 * dabei verloren, verschieben sich oder standen nur ungenau.
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
                // Vor dem Entsperren geweckt: Nacht als erledigt merken und einen Einmal-Wecker ausschalten (nur lokal).
                // Ein Fehler hier darf das Neuplanen nicht verhindern.
                // Probewecker und Powernap zählen nie als Nacht; ihr Plan ist mit dem Wecken vorbei (sonst klingelte
                // er nach dem Entsperren noch einmal).
                var changed: AlarmSnapshot? = null
                try {
                    val rang = DirectBoot.takeRang(context)
                    rang?.takeIf { Transient.kindOf(it.first) != null }?.let { (alarmId, _) -> app.transient.clear(alarmId) }
                    rang?.takeIf { Transient.kindOf(it.first) == null }?.let { (alarmId, target) ->
                        app.scheduler.markDone(alarmId, target)
                        app.scheduler.saveDone()
                        val spec = app.alarms.current().alarms.firstOrNull { it.id == alarmId }
                        if (spec?.isOneShot == true && spec.enabled) changed = app.alarms.setEnabled(spec.id, false)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Nachtrag nach dem Entsperren fehlgeschlagen", e)
                }
                // Zuerst die Alarme (Audit A-3): Nichts, was die Wear-Dienste braucht, steht davor.
                app.scheduler.reschedule()
                // Lief «Gute Nacht» vor dem Neustart, gleich wieder aufnehmen (über den Prüf-Alarm, der den Vordergrund erlaubt).
                if (app.goodNight.load() != null) AlarmScheduler.armGoodNightCheck(context, Instant.now().plusSeconds(5))
                // Danach die Nebenarbeiten, jede abgesichert und mit Frist: Protokoll, ausgeschalteten Einmal-Wecker ans
                // Handy, Stand des Handys anfragen und ein verpasstes «Verlauf löschen» anwenden (PhoneCatchUp, eigene Frist).
                sideWork(TAG, "Protokoll") { app.diag.log(DiagKind.BOOT, "watch ${intent.action?.substringAfterLast('.')}") }
                changed?.let { snapshot -> sideWork(TAG, "Wecker ans Handy") { app.sync.publishAlarms(snapshot) } }
                sideWork(TAG, "Abgleich mit dem Handy", CATCH_UP_TIMEOUT_MS) { PhoneCatchUp.run(app) }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SystemEventReceiver"

        /** Höchstdauer des Abgleichs mit dem Handy; PhoneCatchUp begrenzt sich selbst auf 5 s. */
        const val CATCH_UP_TIMEOUT_MS = 6_000L

        /** Systemereignisse wie im Manifest; nur Text, auf jeder Version unbedenklich. */
        @android.annotation.SuppressLint("InlinedApi")
        val ACTIONS = setOf(
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
