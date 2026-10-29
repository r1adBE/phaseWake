package com.phasewake.app.wear.wake

import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.phasewake.app.core.Calibration
import com.phasewake.app.core.Decision
import com.phasewake.app.core.Epoch
import com.phasewake.app.core.NightHistory
import com.phasewake.app.core.Sunrise
import com.phasewake.app.core.WakeDetector
import com.phasewake.app.core.WakePlan
import com.phasewake.app.wear.schedule.AlarmScheduler
import com.phasewake.app.wear.schedule.DirectBoot
import com.phasewake.app.wear.wake.WakeService.Companion.NIGHT_HARD_LIMIT
import com.phasewake.app.wear.wake.WakeService.Companion.TAG
import com.phasewake.app.wear.wake.WakeService.Companion.WAKE_LOCK_MARGIN_MS
import com.phasewake.app.wear.wake.WakeService.Companion.activePlan
import com.phasewake.app.wear.wake.WakeService.Companion.goodNightActive
import com.phasewake.app.wear.wake.WakeService.Companion.measuringPlan
import com.phasewake.app.wear.wake.WakeService.Night
import com.phasewake.app.wear.wake.WakeService.Phase
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.launch

// Messen im Weckfenster: Nacht übernehmen, Sensoren, Epochen, Zielzeit erreicht.

/**
 * Macht [plan] zur laufenden Nacht. Klingelt oder schlummert schon eine andere, bleibt diese: [plan]
 * wird nach ihrem Ende nachgeholt ([AlarmScheduler.noteNightEnded]). Eine Messung für einen anderen
 * Plan weicht dagegen (der Alarm, der jetzt kommt, ist der frühere oder der gültige).
 * @return `true`, wenn [plan] jetzt die laufende Nacht ist.
 */
internal fun WakeService.adopt(plan: WakePlan): Boolean {
    val current = night
    if (current != null && current.plan != plan && current.phase != Phase.MEASURING) {
        Log.i(TAG, "Wecker ${plan.alarmId} wartet, bis Wecker ${current.plan.alarmId} beendet ist")
        return false
    }
    begin(plan)
    return true
}

private fun WakeService.begin(plan: WakePlan) {
    if (night?.plan == plan) return
    // Das Fenster beginnt: «Gute Nacht» ist vorbei, auch ein nach einem Prozessende nur gespeicherter Stand.
    goodNightPending = false
    handler.removeCallbacks(onsetTimeout)
    onset?.stop()
    onset = null
    goodNightActive = false
    forgetGoodNight()
    night?.let { old ->
        // Eine Messung für einen anderen Plan still verwerfen (kein «stop» ans Handy, nichts erledigt):
        // Ihr Wecker wird danach neu geplant. Ein hängender alter Weckvorgang wird beendet.
        if (old.phase == Phase.MEASURING) abandonMeasuring(old) else finishNight()
    }

    // Vor dem ersten Entsperren sind Wecker, Nächte und Stand nicht lesbar: ohne Messung bis zur
    // Zielzeit warten und dort sicher wecken. Ebenso ohne Sensorrechte (dann nicht als «health»).
    val locked = !DirectBoot.isUnlocked(this)
    val canMeasure = goForeground(Notifications.window(this, formatTime(plan.target)), measuring = !locked)
    holdWakeLock(Duration.between(Instant.now(), plan.target).toMillis().coerceAtLeast(0) + WAKE_LOCK_MARGIN_MS)

    val current = Night(plan, spec = null, detector = WakeDetector(), locked = locked)
    if (locked) {
        DirectBoot.load(this)?.takeIf { it.plan.alarmId == plan.alarmId }?.let {
            current.lockedSound = it.watchSound
            current.lockedLabel = it.label
        }
    }
    night = current
    measuringPlan = plan
    activePlan = plan
    // Nach einem Neustart zur Zielzeit oder beim Schlummer-Ende liegt sie schon zurück: keine Sicherung mehr.
    if (plan.target.isAfter(Instant.now())) AlarmScheduler.setDeadline(this, plan)
    handler.postDelayed(deadline, Duration.between(Instant.now(), plan.target).toMillis().coerceAtLeast(0))
    // Hartes Zeitlimit: Klingeln und Schlummern sind lange vorher zu Ende.
    handler.postDelayed(hardLimit, Duration.between(Instant.now(), plan.target.plus(NIGHT_HARD_LIMIT)).toMillis().coerceAtLeast(0))
    if (locked) return
    loadNight(current, canMeasure)
}

/**
 * Lädt Wecker, kalibrierte Schwellen und den Stand vor einem Neustart des Dienstes in [current] und
 * setzt je nach Phase fort (Sensoren starten oder das Klingeln wieder aufnehmen).
 */
private fun WakeService.loadNight(current: Night, canMeasure: Boolean) {
    val plan = current.plan
    lifecycleScope.launch {
        // Wecker und kalibrierte Schwellen laden. Auch wenn schon geweckt wird (Neustart zur
        // Zielzeit), braucht der Weckvorgang den Wecker: Name, Uhr-Ton, einmalig.
        val spec = app.alarms.current().alarms.firstOrNull { it.id == plan.alarmId }
        val config = Calibration.calibrate(NightHistory.motionsForCalibration(app.nights.load()))
        // Stand vor einem Neustart des Dienstes: erstes Klingeln, Grund und Schlummer-Runden.
        val saved = app.nightProgress.load(plan)
        if (night !== current) return@launch
        current.spec = spec
        if (saved != null) {
            saved.firstRing?.let { first -> if (current.firstRing?.isAfter(first) != false) current.firstRing = first }
            saved.reason?.let { current.reason = it }
            current.snoozes = maxOf(current.snoozes, saved.snoozes)
        }
        when (current.phase) {
            Phase.MEASURING -> {
                current.detector = WakeDetector(config)
                if (canMeasure) startSensors(current)
                // Sonnenaufgang ab Fenster auf der Uhr; unabhängig von der Messung.
                if (spec?.watchSunrise == Sunrise.WINDOW && plan.target.isAfter(Instant.now())) {
                    WatchSunrise.start(this@loadNight, WatchSunrise.Active(plan.alarmId, plan.windowStart, plan.target), formatTime(plan.target))
                }
            }
            Phase.RINGING -> {
                checkTaskOnPhone(current)
                publishRinging(current)
                if (saved != null) goForeground(ringingNotification(current), measuring = false)
                // Nach einem Neustart ging «klingeln» mit 0 Runden ans Handy: korrigierte Restzahl nachschicken.
                if (saved != null && saved.snoozes > 0) sendRing(current)
                if (spec?.watchSound == true) escalation.start(withSound = true)
            }
            Phase.SNOOZED -> Unit
        }
    }
}

private fun WakeService.startSensors(n: Night) {
    val elapsedNow = SystemClock.elapsedRealtimeNanos()
    val sinceStart = Duration.between(n.plan.windowStart, Instant.now()).toNanos()
    n.sensors = SensorSession(this, elapsedNow - sinceStart, SystemClock::elapsedRealtimeNanos) { epochs ->
        onEpochs(n, epochs)
    }.also { it.start() }
}

/** Messung für einen anderen Plan aufgeben, ohne Aufzeichnung und ohne Neuplanen (das folgt mit dem neuen Plan). */
private fun WakeService.abandonMeasuring(n: Night) {
    Log.i(TAG, "Messung für ${n.plan.alarmId} verworfen, anderer Plan beginnt")
    WatchSunrise.stop(this)
    night = null
    measuringPlan = null
    activePlan = null
    handler.removeCallbacksAndMessages(null)
    n.sensors?.stop()
}

/** Am Wecker wurde etwas geändert (aus, gelöscht, verschoben): Messung ohne Aufzeichnung beenden. */
internal fun WakeService.cancelMeasuring() {
    val n = night ?: return
    if (n.phase != Phase.MEASURING) return
    Log.i(TAG, "Messung abgebrochen: Wecker geändert")
    WatchSunrise.stop(this)
    night = null
    measuringPlan = null
    activePlan = null
    handler.removeCallbacksAndMessages(null)
    n.sensors?.stop()
    AlarmScheduler.cancelDeadlineAndSnooze(this)
    NotificationManagerCompat.from(this).cancel(Notifications.ID_SERVICE)
    releaseWakeLock()
    app.appScope.launch { app.scheduler.reschedule() }
    stopIfIdle()
}

internal fun WakeService.onEpochs(n: Night, epochs: List<Epoch>) {
    if (night !== n || n.phase != Phase.MEASURING) return
    for (epoch in epochs) {
        n.epochs += epoch
        val decision = n.detector.onEpoch(epoch)
        if (decision is Decision.Wake) {
            n.reason = decision.reason
            ring()
            return
        }
    }
}

internal fun WakeService.onDeadline() {
    val n = night ?: return
    if (n.phase == Phase.MEASURING) {
        n.reason = n.detector.finish().reason
        ring()
    }
}
