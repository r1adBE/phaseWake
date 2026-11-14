package com.phasewake.app.wear.schedule

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.Transient
import com.phasewake.app.shared.AlarmSnapshot
import com.phasewake.app.shared.AlarmStamps
import com.phasewake.app.wear.sync.PhoneCatchUp
import com.phasewake.app.wear.wearApp
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
        var changed: AlarmSnapshot? = null
        // Gesamtfrist für den Empfänger (Audit HG-6): Stehen die Alarme, ist er fertig (finish); die Nebenarbeiten laufen
        // danach weiter. Hängt schon das Planen (Nebenarbeiten in reschedule, je 3 s), endet er spätestens nach
        // [RECEIVER_BUDGET_MS], weit vor der Frist des Systems (10 s) – ein ANR beendete den Prozess samt Weckdienst.
        app.appScope.launchWithin(RECEIVER_BUDGET_MS, pending::finish, first = {
            // Vor dem Entsperren geweckt: Nacht als erledigt merken und einen Einmal-Wecker ausschalten (nur lokal).
            // Ein Fehler hier darf das Neuplanen nicht verhindern.
            // Probewecker und Powernap zählen nie als Nacht; ihr Plan ist mit dem Wecken vorbei (sonst klingelte
            // er nach dem Entsperren noch einmal).
            try {
                val rang = DirectBoot.takeRang(context)
                rang?.takeIf { Transient.kindOf(it.first) != null }?.let { (alarmId, _) -> app.transient.clear(alarmId) }
                rang?.takeIf { Transient.kindOf(it.first) == null }?.let { (alarmId, target) ->
                    app.scheduler.markDone(alarmId, target)
                    app.scheduler.saveDone()
                    // Nur die Fassung, die geklingelt hat (Fix G-1/S-1); früher geweckt: Ausnahme dieser Nacht.
                    val shifted = app.onsetOverride.get()?.let { it.alarmId == alarmId && it.target == target } == true
                    changed = app.alarms.disableAfterRing(alarmId) { AlarmStamps.rang(it, null, target, ZoneId.systemDefault(), shifted) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Nachtrag nach dem Entsperren fehlgeschlagen", e)
            }
            // Zuerst die Alarme (Audit A-3): Nichts, was die Wear-Dienste braucht, steht davor.
            app.scheduler.reschedule()
            // Lief «Gute Nacht» vor dem Neustart, gleich wieder aufnehmen (über den Prüf-Alarm, der den Vordergrund erlaubt).
            if (app.goodNight.load() != null) AlarmScheduler.armGoodNightCheck(context, Instant.now().plusSeconds(5))
        }) {
            // Danach die Nebenarbeiten, jede abgesichert und mit Frist: Protokoll, ausgeschalteten Einmal-Wecker ans
            // Handy, Stand des Handys anfragen und ein verpasstes «Verlauf löschen» anwenden (PhoneCatchUp, eigene Frist).
            sideWork(TAG, "Protokoll") { app.diag.log(DiagKind.BOOT, "watch ${intent.action?.substringAfterLast('.')}") }
            changed?.let { snapshot -> sideWork(TAG, "Wecker ans Handy") { app.sync.publishAlarms(snapshot) } }
            sideWork(TAG, "Abgleich mit dem Handy", CATCH_UP_TIMEOUT_MS) { PhoneCatchUp.run(app) }
        }
    }

    private companion object {
        const val TAG = "SystemEventReceiver"

        /** Höchstdauer, bis der Empfänger fertig meldet (Audit HG-6); die Frist des Systems ist 10 s. */
        const val RECEIVER_BUDGET_MS = 8_000L

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

/**
 * Startet [first] (das Wichtige, z. B. Alarme stellen) und danach [then] (Nebenarbeiten) in diesem Bereich. [finish] (das
 * Ende eines `goAsync`) kommt genau einmal: sobald [first] fertig ist oder scheitert, spätestens aber nach [budgetMs] –
 * [then] läuft dann unabhängig davon weiter (Audit HG-6).
 */
internal fun CoroutineScope.launchWithin(budgetMs: Long, finish: () -> Unit, first: suspend () -> Unit, then: suspend () -> Unit): Job {
    val released = AtomicBoolean(false)
    val release = { if (released.compareAndSet(false, true)) finish() }
    val deadline = launch {
        delay(budgetMs)
        release()
    }
    return launch {
        try {
            first()
        } finally {
            release()
            deadline.cancel()
        }
        then()
    }
}
