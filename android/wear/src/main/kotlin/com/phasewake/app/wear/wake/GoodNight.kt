package com.phasewake.app.wear.wake

import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Calibration
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.NightHistory
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
 * auf Einschlafzeit + Schlafdauer (höchstens die eingestellte Weckzeit). Die Einschlafzeit kommt in
 * [com.phasewake.app.wear.schedule.OnsetStore] (Untergrenze kurzer Nächte).
 *
 * [auto]: automatische Einschlaf-Messung ([com.phasewake.app.wear.schedule.AutoOnset]); auch für Wecker ohne
 * Schlafdauer (dann wird nur die Einschlafzeit gemerkt) und nur, solange das Weckfenster nicht begonnen hat.
 *
 * Startzeit und Wecker liegen in [GoodNightState]. Endet der Prozess, nimmt der Prüf-Alarm
 * (alle 15 Minuten) den Modus mit [requestedAlarmId] `null` wieder auf. Die Einschlaf-Erkennung
 * beginnt dann von vorn; die Zielzeit liegt so höchstens etwas später, nie nach der eingestellten Weckzeit.
 */
internal fun WakeService.startGoodNight(requestedAlarmId: String?, auto: Boolean = false) {
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
        val automatic = resumed?.auto ?: auto
        if (resumed == null) app.goodNight.set(alarmId, since, automatic)
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
        // Automatisch: auch ohne Schlafdauer, aber nicht mehr im Weckfenster und nicht zweimal in derselben Nacht.
        val windowBegun = latest != null && spec != null &&
            !Instant.now().isBefore(AlarmSchedule.planAt(spec, latest).windowStart)
        val alreadyDone = automatic && latest != null && app.onsets.isDone(alarmId, latest)
        if (spec == null || (duration == null && !automatic) || latest == null || giveUpAt == null ||
            Duration.between(since, latest) > GOOD_NIGHT_MAX_AHEAD || !Instant.now().isBefore(giveUpAt) ||
            (automatic && (windowBegun || alreadyDone))
        ) {
            if (stillWanted()) stopGoodNight()
            return@launch
        }
        val threshold = Calibration.calibrate(NightHistory.motionsForCalibration(app.nights.load())).lightThreshold
        if (!stillWanted()) return@launch
        if (automatic) app.diag.log(DiagKind.MEASURE_START, "auto onset start, alarm at $latest")
        onsetNight = alarmId to latest
        onsetAuto = automatic
        // giveUpAt: hartes Zeitlimit nach Uhrzeit, auch wenn der Zeitgeber im Tiefschlaf stehen bleibt.
        onset = OnsetMonitor(this@startGoodNight, threshold, giveUpAt, onGiveUp = { stopGoodNight() }) { asleep ->
            if (automatic) app.diag.log(DiagKind.PHASE, "auto onset asleep $asleep")
            onAsleep(spec, latest, asleep)
        }.also { it.start() }
        goodNightPending = false
        // Spätestens 15 Minuten vor der eingestellten Weckzeit aufgeben: dann gilt sie.
        handler.postDelayed(onsetTimeout, Duration.between(Instant.now(), giveUpAt).toMillis().coerceAtLeast(0))
    }
}

/**
 * Eingeschlafen um [asleep] (Wecker [spec], eingestellte Weckzeit [latest]): Einschlafzeit merken. Mit Schlafdauer gilt
 * Einschlafzeit + Schlafdauer als Ausnahme für diese Nacht. «Gute Nacht» endet, neu planen.
 */
private fun WakeService.onAsleep(spec: AlarmSpec, latest: Instant, asleep: Instant) {
    val duration = spec.sleepDurationMinutes
    val plan = duration?.let { AlarmSchedule.planAt(spec, AlarmSchedule.sleepTarget(asleep, it, latest)) }
    Log.i(TAG, "Eingeschlafen $asleep, Zielzeit ${plan?.target ?: latest}")
    onset?.stop()
    onset = null
    onsetNight = null
    goodNightActive = false
    forgetGoodNight()
    app.appScope.launch {
        app.onsets.set(spec.id, latest, asleep)
        if (plan != null) app.onsetOverride.set(plan)
        app.scheduler.reschedule()
    }
    stopIfIdle()
}

/** Wartet nicht mehr auf das Einschlafen (aufgegeben, abgebrochen): Für diese Nacht startet keine automatische Messung mehr. */
internal fun WakeService.stopGoodNight() {
    onsetNight?.let { (alarmId, latest) ->
        if (onsetAuto) app.diag.log(DiagKind.MEASURE_END, "auto onset give up")
        app.appScope.launch { app.onsets.set(alarmId, latest, null) }
    }
    onsetNight = null
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
