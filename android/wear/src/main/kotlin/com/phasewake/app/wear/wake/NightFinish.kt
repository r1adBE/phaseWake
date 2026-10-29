package com.phasewake.app.wear.wake

import android.util.Log
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.Transient
import com.phasewake.app.core.WakePlan
import com.phasewake.app.core.WakeReason
import com.phasewake.app.shared.AlarmSnapshot
import com.phasewake.app.shared.DataLayer
import com.phasewake.app.wear.schedule.AlarmScheduler
import com.phasewake.app.wear.schedule.DirectBoot
import com.phasewake.app.wear.wake.WakeService.Companion.GOOD_NIGHT_MAX_AHEAD
import com.phasewake.app.wear.wake.WakeService.Companion.TAG
import com.phasewake.app.wear.wake.WakeService.Companion.activePlan
import com.phasewake.app.wear.wake.WakeService.Companion.measuringPlan
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Abschluss einer Nacht: aufräumen, speichern, Einmal-Wecker aus, neu planen, ans Handy senden.

internal fun WakeService.finishNight(notifyPhone: Boolean = true) {
    val n = night ?: return
    WatchSunrise.stop(this)
    night = null
    measuringPlan = null
    activePlan = null
    handler.removeCallbacksAndMessages(null)
    n.sensors?.stop()
    AlarmScheduler.cancelDeadlineAndSnooze(this)
    app.ringState.publish(RingUi.Idle)
    cancelRingScreenAlarm(this)
    Notifications.cancelService(this)
    releaseWakeLock()

    // Probewecker und Powernap: kein Eintrag im Verlauf, nichts «erledigt», kein Einmal-Wecker.
    val transient = Transient.kindOf(n.plan.alarmId) != null
    app.diag.log(DiagKind.STOP, if (n.firstRing == null) "watch ended without ring" else "watch")
    val record = n.firstRing?.takeIf { !transient }?.let { wokeAt ->
        NightRecord(n.plan.alarmId, n.plan.windowStart, n.plan.target, wokeAt, n.reason ?: WakeReason.DEADLINE, n.snoozes, n.epochs.toList())
    }
    val endedAt = Instant.now()
    // Vor dem ersten Entsperren nichts speichern (nicht lesbar). Nach dem Entsperren holt
    // SystemEventReceiver nach: Nacht erledigt, Einmal-Wecker aus, neu planen.
    if (!DirectBoot.isUnlocked(this)) {
        // Auch ein Probewecker oder Powernap, der geweckt hat: Nach dem Entsperren wird sein Plan gelöscht.
        finishLocked(n.plan, rang = record != null || (transient && n.firstRing != null), notifyPhone)
        return
    }
    // Als Erstes und sofort: Die Nacht gilt als erledigt, auch für ein gleichzeitiges Neuplanen
    // (z. B. die Startseite, die nach dem Weckbildschirm wieder erscheint).
    if (record != null) app.scheduler.markDone(n.plan.alarmId, doneUntil(n.plan, n.spec))
    if (notifyPhone) app.appScope.launch { app.sync.message(DataLayer.CAPABILITY_PHONE, DataLayer.PATH_STOP, stopMessage(n.plan)) }

    // Prozessweiter Bereich. Der Dienst bleibt (Vordergrund), bis gespeichert und neu geplant ist:
    // Sonst könnte das System den Prozess vorher beenden, und für morgen stünde kein Wecker.
    // Jeder Schritt für sich abgesichert, damit ein Fehler die folgenden nicht überspringt.
    finishing++
    app.appScope.launch {
        var disabled: AlarmSnapshot? = null
        try {
            // Aus dem Speicher statt aus der Nacht: Nach einem Neustart des Dienstes fehlt n.spec.
            val spec = runStep("Wecker laden") { app.alarms.current().alarms.firstOrNull { it.id == n.plan.alarmId } }
            if (record != null) {
                if (n.spec == null) app.scheduler.markDone(n.plan.alarmId, doneUntil(n.plan, spec))
                runStep("Erledigt speichern") { app.scheduler.saveDone() }
                // Zielzeiten anderer Wecker, die während dieser Nacht fällig wurden, nachholen.
                runStep("Nacht-Zeitraum merken") { app.scheduler.noteNightEnded(n.plan.windowStart, endedAt) }
            }
            if (transient) runStep("Vorübergehenden Plan löschen") { app.transient.clear(n.plan.alarmId) }
            runStep("Einschlaf-Ausnahme löschen") { app.onsetOverride.clear() }
            runStep("Stand löschen") { app.nightProgress.clear(n.plan) }
            if (record != null) runStep("Nacht speichern") { app.nights.add(record) }
            if (!transient && spec?.isOneShot == true && spec.enabled) disabled = runStep("Einmal-Wecker ausschalten") { app.alarms.setEnabled(spec.id, false) }
            runStep("Neu planen") { app.scheduler.reschedule() }
        } finally {
            withContext(Dispatchers.Main) {
                finishing--
                stopIfIdle()
            }
        }
        // Senden darf das Ende des Dienstes überdauern; die Daten liegen schon lokal.
        if (record != null) app.sync.publishNight(record)
        disabled?.let { app.sync.publishAlarms(it) }
    }
}

/** Abschluss vor dem ersten Entsperren: nur Direct-Boot-Speicher, nichts Verschlüsseltes. */
private fun WakeService.finishLocked(plan: WakePlan, rang: Boolean, notifyPhone: Boolean) {
    // Der abgelegte Plan danach wird zum Eintrag: Bleibt die Uhr gesperrt, weckt sie auch am nächsten Morgen.
    DirectBoot.promoteNext(this)?.let { DirectBoot.setDeadlineAt(this, it) }
    if (rang) DirectBoot.markRang(this, plan.alarmId, plan.target)
    if (notifyPhone) app.appScope.launch { app.sync.message(DataLayer.CAPABILITY_PHONE, DataLayer.PATH_STOP, stopMessage(plan)) }
    handler.post { stopIfIdle() }
}

/**
 * Bis wann die Nacht erledigt ist: die eingestellte Weckzeit, auch wenn «Gute Nacht» früher geweckt
 * hat. Sonst begänne danach dieselbe Nacht noch einmal (Fenster vor der eingestellten Zeit).
 */
private fun WakeService.doneUntil(plan: WakePlan, spec: AlarmSpec?): Instant {
    val nominal = spec?.let { AlarmSchedule.nextTarget(it, plan.target.minusMillis(1), ZoneId.systemDefault()) }
        ?: return plan.target
    return if (nominal.isAfter(plan.target) && Duration.between(plan.target, nominal) <= GOOD_NIGHT_MAX_AHEAD) nominal else plan.target
}

/** Führt einen Schritt des Abschlusses aus; ein Fehler wird geloggt und ergibt `null`. */
private suspend fun <T> WakeService.runStep(what: String, block: suspend () -> T): T? = try {
    block()
} catch (e: Exception) {
    if (e is CancellationException) throw e
    Log.e(TAG, "$what fehlgeschlagen", e)
    null
}
