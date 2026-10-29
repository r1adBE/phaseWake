package com.phasewake.app.wear.wake

import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.Calibration
import com.phasewake.app.core.NightHistory
import com.phasewake.app.core.WakePlan
import com.phasewake.app.wear.schedule.AlarmScheduler
import com.phasewake.app.wear.schedule.DirectBoot
import com.phasewake.app.wear.wake.WakeService.Companion.GOOD_NIGHT_MAX_AHEAD
import com.phasewake.app.wear.wake.WakeService.Companion.ONSET_GIVE_UP
import com.phasewake.app.wear.wake.WakeService.Companion.TAG
import com.phasewake.app.wear.wake.WakeService.Companion.goodNightActive
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch

// «Gute Nacht» (Einschlaf-Modus) des Weckdienstes: auf das Einschlafen warten, dann die Zielzeit festlegen.

/**
 * «Gute Nacht»: misst stromsparend, bis das Einschlafen erkannt ist, und legt dann die Zielzeit
 * auf Einschlafzeit + Schlafdauer (höchstens die eingestellte Weckzeit).
 *
 * Startzeit und Wecker liegen in [GoodNightState]. Endet der Prozess, nimmt der Prüf-Alarm
 * (alle 15 Minuten) den Modus mit [requestedAlarmId] `null` wieder auf. Die Einschlaf-Erkennung
 * beginnt dann von vorn; die Zielzeit liegt so höchstens etwas später, nie nach der eingestellten Weckzeit.
 */
internal fun WakeService.startGoodNight(requestedAlarmId: String?) {
    if (night != null || onset != null || goodNightPending) return
    // Vor dem Entsperren nicht lesbar. Ohne Sensorrechte kein Einschlaf-Modus: dann gilt die eingestellte Weckzeit.
    if (!DirectBoot.isUnlocked(this) || !goForeground(Notifications.goodNight(this), measuring = true)) {
        forgetGoodNight()
        stopIfIdle()
        return
    }
    goodNightPending = true
    goodNightActive = true
    val run = ++goodNightRun
    fun stillWanted() = goodNightPending && run == goodNightRun
    lifecycleScope.launch {
        val resumed = if (requestedAlarmId == null) app.goodNight.load() else null
        val alarmId = requestedAlarmId ?: resumed?.alarmId
        if (alarmId == null) {
            // Nichts wieder aufzunehmen.
            if (stillWanted()) stopGoodNight()
            return@launch
        }
        val since = resumed?.since ?: Instant.now()
        if (resumed == null) app.goodNight.set(alarmId, since)
        // Inzwischen abgebrochen (oder ein Fenster hat begonnen): den eben geschriebenen Stand wieder löschen.
        if (!stillWanted()) {
            // Ein neuerer Start schreibt seinen eigenen Stand; dann nichts löschen.
            if (resumed == null && run == goodNightRun) app.goodNight.clear()
            return@launch
        }
        AlarmScheduler.armGoodNightCheck(this@startGoodNight)
        val spec = app.alarms.current().alarms.firstOrNull { it.id == alarmId }
        val duration = spec?.sleepDurationMinutes
        // Weckzeit der Nacht, in der «Gute Nacht» begann (auch beim Wiederaufnehmen).
        val latest = spec?.let { AlarmSchedule.nextTarget(it, since, ZoneId.systemDefault()) }
        val giveUpAt = latest?.minus(ONSET_GIVE_UP)
        // Nur für die kommende Nacht: liegt die Weckzeit weiter weg, zeigt die Startseite einen Hinweis.
        if (spec == null || duration == null || latest == null || giveUpAt == null ||
            Duration.between(since, latest) > GOOD_NIGHT_MAX_AHEAD || !Instant.now().isBefore(giveUpAt)
        ) {
            if (stillWanted()) stopGoodNight()
            return@launch
        }
        val threshold = Calibration.calibrate(NightHistory.motionsForCalibration(app.nights.load())).lightThreshold
        if (!stillWanted()) return@launch
        // giveUpAt: hartes Zeitlimit nach Uhrzeit, auch wenn der Zeitgeber im Tiefschlaf stehen bleibt.
        onset = OnsetMonitor(this@startGoodNight, threshold, giveUpAt, onGiveUp = { stopGoodNight() }) { asleep ->
            onAsleep(alarmId, AlarmSchedule.sleepTarget(asleep, duration, latest), spec.windowMinutes, asleep)
        }.also { it.start() }
        goodNightPending = false
        // Spätestens 15 Minuten vor der eingestellten Weckzeit aufgeben: dann gilt sie.
        handler.postDelayed(onsetTimeout, Duration.between(Instant.now(), giveUpAt).toMillis().coerceAtLeast(0))
    }
}

/** Eingeschlafen um [asleep]: [target] gilt als Ausnahme für diese Nacht; «Gute Nacht» endet, neu planen. */
private fun WakeService.onAsleep(alarmId: String, target: Instant, windowMinutes: Int, asleep: Instant) {
    val plan = WakePlan(alarmId, AlarmSchedule.windowStart(target, windowMinutes), target)
    Log.i(TAG, "Eingeschlafen $asleep, neue Zielzeit $target")
    onset?.stop()
    onset = null
    goodNightActive = false
    forgetGoodNight()
    app.appScope.launch {
        app.onsetOverride.set(plan)
        app.scheduler.reschedule()
    }
    stopIfIdle()
}

internal fun WakeService.stopGoodNight() {
    goodNightPending = false
    handler.removeCallbacks(onsetTimeout)
    onset?.stop()
    onset = null
    goodNightActive = false
    forgetGoodNight()
    stopIfIdle()
}

/** «Gute Nacht» ist vorbei: Stand löschen und keine Prüfung mehr. */
internal fun WakeService.forgetGoodNight() {
    AlarmScheduler.cancelGoodNightCheck(this)
    if (DirectBoot.isUnlocked(this)) app.appScope.launch { app.goodNight.clear() }
}
