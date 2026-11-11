package com.phasewake.app.wear.wake

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.phasewake.app.core.Calibration
import com.phasewake.app.core.Decision
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.Epoch
import com.phasewake.app.core.NightHistory
import com.phasewake.app.core.NoiseFloor
import com.phasewake.app.core.ShortNight
import com.phasewake.app.core.Sunrise
import com.phasewake.app.core.WakeDetector
import com.phasewake.app.core.WakePlan
import com.phasewake.app.shared.phaseWakeStore
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
import kotlinx.coroutines.flow.first
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
    if (current != null && !WakeRules.adopts(current.plan, current.phase == Phase.MEASURING, plan)) {
        Log.i(TAG, "Wecker ${plan.alarmId} wartet, bis Wecker ${current.plan.alarmId} beendet ist")
        return false
    }
    begin(plan)
    return true
}

private fun WakeService.begin(plan: WakePlan) {
    if (night?.plan == plan) return
    // Das Fenster beginnt: «Gute Nacht» ist vorbei, auch ein nach einem Prozessende nur gespeicherter Stand. Lief die
    // Einschlaf-Messung dieser Nacht noch ohne Ergebnis, gilt die Nacht als gemessen ohne Einschlafzeit (fail-closed,
    // [endOnsetAtWindow] in [loadNight], vor dem Lesen der Einschlafzeit).
    goodNightPending = false
    handler.removeCallbacks(onsetTimeout)
    onset?.stop()
    onset = null
    val lostOnset = onsetNight
    onsetNight = null
    goodNightActive = false
    AlarmScheduler.cancelGoodNightCheck(this)
    night?.let { old ->
        // Eine Messung für einen anderen Plan still verwerfen (kein «stop» ans Handy, nichts erledigt):
        // Ihr Wecker wird danach neu geplant. Ein hängender alter Weckvorgang wird beendet.
        if (old.phase == Phase.MEASURING) abandonMeasuring(old) else finishNight()
    }

    // Vor dem ersten Entsperren sind Wecker, Nächte und Stand nicht lesbar: ohne Messung bis zur
    // Zielzeit warten und dort sicher wecken. Ebenso ohne Sensorrechte (dann nicht als «health»).
    val locked = !DirectBoot.isUnlocked(this)
    val canMeasure = goForeground(Notifications.window(this, formatTime(plan.target), exact = plan.exact), measuring = !locked)
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
    loadNight(current, canMeasure, healthRefusal, lostOnset)
}

/**
 * Lädt Wecker, kalibrierte Schwellen (mit den gelernten Werten) und den Stand vor einem Neustart des Dienstes in [current] und
 * setzt je nach Phase fort (Sensoren starten oder das Klingeln wieder aufnehmen). [refusal]: Grund, warum ohne
 * Sensoren gemessen wird ([WakeService.healthRefusal]), fürs Diagnose-Protokoll. [lostOnset]: Einschlaf-Messung, die bei
 * Fensterbeginn noch ohne Ergebnis lief ([endOnsetAtWindow]).
 */
private fun WakeService.loadNight(current: Night, canMeasure: Boolean, refusal: String?, lostOnset: Pair<String, Instant>?) {
    val plan = current.plan
    lifecycleScope.launch {
        endOnsetAtWindow(plan, lostOnset)
        // Wecker und kalibrierte Schwellen laden. Auch wenn schon geweckt wird (Neustart zur
        // Zielzeit), braucht der Weckvorgang den Wecker: Name, Uhr-Ton, einmalig.
        val spec = app.specFor(plan.alarmId)
        // Dazu die gelernten Werte der Lernphase (eigenes Grundrauschen, Ruhepuls), falls es sie gibt.
        // Dazu das Grundrauschen der Einschlaf-Messung dieser Nacht als Vorwissen (Vollaudit K-1), falls es eines gibt.
        val config = app.learning.current().applyTo(Calibration.calibrate(NightHistory.motionsForCalibration(app.nights.load())))
            .withOnsetFloor(onsetFloorFor(plan))
        // Stand vor einem Neustart des Dienstes: erstes Klingeln, Grund und Schlummer-Runden.
        val saved = app.nightProgress.load(plan)
        // Untergrenze kurzer Nächte: Einschlafzeit (automatische Messung oder «Gute Nacht») und Ziel; ohne Einschlafzeit
        // nach einer Messung ohne Ergebnis fail-closed.
        val asleepAt = app.onsets.onsetFor(plan)
        val onsetMeasured = asleepAt == null && app.onsets.measuredFor(plan)
        val goal = spec?.sleepDurationMinutes ?: app.sleepGoal.minutes.first()
        if (night !== current) return@launch
        current.spec = spec
        current.asleepAt = asleepAt
        current.onsetMeasured = onsetMeasured
        current.goalMinutes = goal
        if (saved != null) {
            saved.firstRing?.let { first -> if (current.firstRing?.isAfter(first) != false) current.firstRing = first }
            saved.reason?.let { current.reason = it }
            current.snoozes = maxOf(current.snoozes, saved.snoozes)
        }
        when (current.phase) {
            Phase.MEASURING -> {
                val mode = when {
                    current.exact -> ", exact"
                    asleepAt != null -> ", asleep $asleepAt"
                    onsetMeasured -> ", onset unknown"
                    else -> ""
                }
                current.detector = WakeDetector(config)
                // Sensoren vor der Zeile starten: Sie nennt die Quelle des Pulses («hrsrc=sensor|measure|none»).
                if (canMeasure) startSensors(current)
                val detail = when {
                    canMeasure -> "window ${plan.windowStart}$mode hrsrc=${current.sensors?.heartRateSource ?: SensorSession.SOURCE_NONE}"
                    refusal != null -> "no sensors: $refusal"
                    else -> "no sensors, wake at target"
                }
                app.diag.log(DiagKind.MEASURE_START, detail)
                // Sonnenaufgang ab Fenster auf der Uhr; unabhängig von der Messung.
                if (spec?.watchSunrise == Sunrise.WINDOW && plan.target.isAfter(Instant.now())) {
                    WatchSunrise.start(this@loadNight, WatchSunrise.Active(plan.alarmId, plan.windowStart, plan.target), formatTime(plan.target))
                }
            }
            Phase.RINGING -> {
                checkTaskOnPhone(current)
                publishRinging(current)
                if (saved != null) postRinging(current, "restart")
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
    logMeasureEnd(n, ", replaced")
    WatchSunrise.stop(this)
    night = null
    measuringPlan = null
    activePlan = null
    handler.removeCallbacksAndMessages(null)
    n.sensors?.stop()
}

/**
 * Am Wecker wurde etwas geändert (aus, gelöscht, verschoben): Messung ohne Aufzeichnung beenden. Den Zielzeit-Alarm
 * nicht aufheben: [AlarmScheduler.reschedule] hat ihn schon für den neuen Plan gestellt (oder ohne Plan aufgehoben),
 * bevor dieser Auftrag ankommt; ein Schlummer-Alarm besteht beim Messen nicht (Review 04.11. b, P2).
 */
internal fun WakeService.cancelMeasuring() {
    val n = night ?: return
    if (n.phase != Phase.MEASURING) return
    Log.i(TAG, "Messung abgebrochen: Wecker geändert")
    logMeasureEnd(n, ", cancelled")
    WatchSunrise.stop(this)
    night = null
    measuringPlan = null
    activePlan = null
    handler.removeCallbacksAndMessages(null)
    n.sensors?.stop()
    Notifications.cancelService(this)
    releaseWakeLock()
    app.appScope.launch { app.scheduler.reschedule() }
    stopIfIdle()
}

/** Grundrauschen der Einschlaf-Messung für [plan] ([OnsetFloorStore]); unlesbar oder keines: `null` (wie bisher). */
private suspend fun WakeService.onsetFloorFor(plan: WakePlan): Double? =
    try {
        OnsetFloorStore(phaseWakeStore).floorFor(plan)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w(TAG, "Grundrauschen der Einschlaf-Messung nicht lesbar", e)
        null
    }

internal fun WakeService.onEpochs(n: Night, epochs: List<Epoch>) {
    if (night !== n || n.phase != Phase.MEASURING) return
    for (epoch in epochs) {
        n.epochs += epoch
        // «Genau»: nur für den Verlauf messen, geweckt wird zur Zielzeit.
        if (n.exact) continue
        val decision = n.detector.onEpoch(epoch)
        if (decision is Decision.Wake) {
            // Kurze Nacht: Moment übergehen (nicht nachholen), weiter erkennen; in den letzten
            // [ShortNight.LATE_MINUTES] vor der Zielzeit weckt ein neuer Moment (Entscheid r1AD 10.11.2026).
            if (ShortNight.blocksEarly(n.asleepAt, Instant.now(), n.goalMinutes, n.plan.target, n.onsetMeasured)) {
                n.detector.rearm()
                val why = if (n.asleepAt == null) "short night unknown" else "short night"
                if (!n.earlyBlocked) app.diag.log(DiagKind.PHASE, "${decision.reason.name} ignored, $why")
                n.earlyBlocked = true
                continue
            }
            n.reason = decision.reason
            app.diag.log(DiagKind.PHASE, decision.reason.name)
            ring()
            return
        }
    }
}

/**
 * Ende der Messung im Diagnose-Protokoll, einmal je Nacht (nur während [Phase.MEASURING], nicht vor dem ersten Entsperren):
 * Epochen, davon mit Puls (`hr=`), und Kennzahlen der Schwellen ([NoiseFloor.summary]: Grundrauschen, wirksame Leicht-
 * und Wach-Schwelle am Ende), ohne einzelne Messwerte. [note] ergänzt den Grund (z. B. abgebrochen).
 */
internal fun WakeService.logMeasureEnd(n: Night, note: String = "") {
    if (n.phase != Phase.MEASURING || n.locked) return
    val summary = NoiseFloor.summary(n.detector.config, n.epochs.map { it.motion })
    app.diag.log(DiagKind.MEASURE_END, "watch ${n.epochs.size} epochs hr=${n.epochs.count { it.heartRate != null }}, $summary$note")
}

internal fun WakeService.onDeadline() {
    val n = night ?: return
    if (n.phase == Phase.MEASURING) {
        n.reason = WakeRules.deadlineReason(n.exact, n.earlyBlocked) { n.detector.finish().reason }
        ring()
    }
}
