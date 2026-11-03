package com.phasewake.app.wear.wake

import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Calibration
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.NightHistory
import com.phasewake.app.shared.OnsetShift
import com.phasewake.app.wear.schedule.AlarmScheduler
import com.phasewake.app.wear.schedule.AutoOnset
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
 *
 * Die Messung endet spätestens [OnsetMonitor.MAX_DURATION] nach ihrem Beginn (auch nach dem Wiederaufnehmen) und
 * 15 Min vor der eingestellten Weckzeit; dann gilt diese, und für die Nacht startet keine automatische Messung mehr.
 * Die automatische Messung beginnt nicht unter [AutoOnset.MIN_BATTERY_PERCENT] Akku (Akku-Befund 03.11.2026).
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
        val latestGiveUp = latest?.minus(ONSET_GIVE_UP)
        val maxEnd = since.plus(OnsetMonitor.MAX_DURATION)
        // Nur für die kommende Nacht: liegt die Weckzeit weiter weg, zeigt die Startseite einen Hinweis.
        // Automatisch: auch ohne Schlafdauer, aber nicht mehr im Weckfenster und nicht zweimal in derselben Nacht.
        val plan = spec?.let { s -> latest?.let { AlarmSchedule.planAt(s, it) } }
        val windowBegun = plan != null && !Instant.now().isBefore(plan.windowStart)
        val alreadyDone = automatic && latest != null && app.onsets.isDone(alarmId, latest)
        if (spec == null || (duration == null && !automatic) || latest == null || latestGiveUp == null ||
            Duration.between(since, latest) > GOOD_NIGHT_MAX_AHEAD || (automatic && (windowBegun || alreadyDone))
        ) {
            if (stillWanted()) stopGoodNight()
            return@launch
        }
        val label = if (automatic) "auto onset" else "onset"
        // Zeitgrenze schon vorbei (z. B. nach dem Wiederaufnehmen): Ende protokollieren, Nacht erledigt, Weckzeit gilt.
        OnsetRules.giveUpReason(Instant.now(), latestGiveUp, maxEnd)?.let { reason ->
            if (stillWanted()) endBeforeMeasuring(alarmId, latest, "$label $reason", DiagKind.MEASURE_END)
            return@launch
        }
        // Wenig Akku: automatisch nicht messen (die Messung ist eine Zugabe, der Wecker hat Vorrang).
        val battery = getSystemService(android.os.BatteryManager::class.java)
            ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        if (automatic && AutoOnset.skipForBattery(battery)) {
            if (stillWanted()) endBeforeMeasuring(alarmId, latest, "auto onset skipped battery $battery", DiagKind.BATTERY)
            return@launch
        }
        val threshold = Calibration.calibrate(NightHistory.motionsForCalibration(app.nights.load())).lightThreshold
        if (!stillWanted()) return@launch
        app.diag.log(DiagKind.MEASURE_START, "$label start, alarm at $latest${if (resumed != null) ", resumed" else ""}")
        onsetNight = alarmId to latest
        onsetAuto = automatic
        // Hartes Zeitlimit nach Uhrzeit (auch im Tiefschlaf, über den Alarm der Messung): 15 Min vor der eingestellten
        // Weckzeit, spätestens MAX_DURATION nach dem Beginn.
        val monitor = OnsetMonitor(
            this@startGoodNight, threshold, latestGiveUp, maxEnd, label, app.diag::log, onGiveUp = { stopGoodNight() },
        ) { asleep ->
            app.diag.log(DiagKind.PHASE, "$label asleep $asleep")
            onAsleep(spec, latest, asleep)
        }
        onset = monitor
        monitor.start()
        goodNightPending = false
        // Zusätzlich, solange die Uhr wach ist: Dann gilt die eingestellte Weckzeit.
        handler.postDelayed(onsetTimeout, Duration.between(Instant.now(), monitor.giveUpAt).toMillis().coerceAtLeast(0))
    }
}

/**
 * Eingeschlafen um [asleep] (Wecker [spec], eingestellte Weckzeit [latest]): Einschlafzeit merken. Mit Schlafdauer gilt
 * Einschlafzeit + Schlafdauer als Ausnahme für diese Nacht, auch für den Zweitwecker am Handy ([OnsetShift]; ohne
 * frühere Zielzeit hebt die Meldung dort eine frühere auf).
 * «Gute Nacht» endet, neu planen. Intern statt privat, damit WakeServiceTest das Einschlafen ohne Sensoren auslöst.
 */
internal fun WakeService.onAsleep(spec: AlarmSpec, latest: Instant, asleep: Instant) {
    val duration = spec.sleepDurationMinutes
    val plan = duration?.let { AlarmSchedule.planAt(spec, AlarmSchedule.sleepTarget(asleep, it, latest)) }
    // Ohne Uhrzeiten: Schlafdaten gehören nicht ins Systemprotokoll (logcat), nur ins eigene Diagnose-Protokoll.
    Log.i(TAG, "Eingeschlafen erkannt${if (plan != null) ", Zielzeit festgelegt" else ""}")
    onset?.stop("asleep")
    onset = null
    onsetNight = null
    goodNightActive = false
    forgetGoodNight()
    app.appScope.launch {
        app.onsets.set(spec.id, latest, asleep)
        if (plan != null) app.onsetOverride.set(plan)
        // Auch der Zweitwecker am Handy klingelt diese Nacht früher (Review K-07): als Paket, kommt also auch an,
        // wenn das Handy gerade nicht erreichbar ist. Nach jedem Einschlafen mit Schlafdauer, auch ohne frühere Zielzeit
        // (Review H-1): Ein zweites «Gute Nacht» ersetzt so eine frühere Meldung, statt dass das Handy bei ihr bleibt.
        if (plan != null) {
            app.publishOnsetShift(OnsetShift(spec.id, latest.toEpochMilli(), plan.target.toEpochMilli(), System.currentTimeMillis()))
        }
        app.scheduler.reschedule()
    }
    stopIfIdle()
}

/**
 * Wartet nicht mehr auf das Einschlafen (aufgegeben, abgebrochen): Für diese Nacht startet keine automatische Messung mehr.
 * Ins Diagnose-Protokoll: «give up» (auch «(max duration)»), wenn die Zeitgrenze erreicht ist, sonst «cancelled».
 */
internal fun WakeService.stopGoodNight() {
    onsetNight?.let { (alarmId, latest) ->
        app.appScope.launch { app.onsets.set(alarmId, latest, null) }
    }
    onsetNight = null
    goodNightPending = false
    handler.removeCallbacks(onsetTimeout)
    onset?.let { it.stop(it.giveUpReason(Instant.now()) ?: "cancelled") }
    onset = null
    goodNightActive = false
    forgetGoodNight()
    stopIfIdle()
}

/**
 * Endet vor dem Messen ([detail] ins Diagnose-Protokoll als [kind]): Nacht für [alarmId] mit Weckzeit [latest] erledigt,
 * damit die automatische Messung nicht gleich wieder startet; die eingestellte Weckzeit gilt.
 */
private fun WakeService.endBeforeMeasuring(alarmId: String, latest: Instant, detail: String, kind: DiagKind) {
    app.diag.log(kind, detail)
    app.appScope.launch { app.onsets.set(alarmId, latest, null) }
    stopGoodNight()
}

/** «Gute Nacht» ist vorbei: Stand löschen und keine Prüfung mehr. */
internal fun WakeService.forgetGoodNight() {
    AlarmScheduler.cancelGoodNightCheck(this)
    if (DirectBoot.isUnlocked(this)) app.appScope.launch { app.goodNight.clear() }
}
