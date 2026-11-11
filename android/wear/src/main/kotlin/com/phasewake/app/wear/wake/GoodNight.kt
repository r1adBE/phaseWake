package com.phasewake.app.wear.wake

import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Calibration
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.NightHistory
import com.phasewake.app.core.RecorderSkip
import com.phasewake.app.core.WakePlan
import com.phasewake.app.shared.OnsetShift
import com.phasewake.app.shared.phaseWakeStore
import com.phasewake.app.wear.record.RecordControl
import com.phasewake.app.wear.record.RecordService
import com.phasewake.app.wear.schedule.AlarmScheduler
import com.phasewake.app.wear.schedule.AutoOnset
import com.phasewake.app.wear.schedule.DirectBoot
import com.phasewake.app.wear.wake.WakeService.Companion.ONSET_GIVE_UP
import com.phasewake.app.wear.wake.WakeService.Companion.TAG
import com.phasewake.app.wear.wake.WakeService.Companion.goodNightActive
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
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
 * Bei Unruhe verlängert sie sich ([OnsetMonitor]), bis höchstens [OnsetMonitor.MAX_EXTENDED] nach dem Beginn und nie
 * über den Beginn des Weckfensters hinaus. Wird sie nach der Höchstdauer wieder aufgenommen, endet sie wie bisher.
 * Die automatische Messung beginnt nicht unter [AutoOnset.MIN_BATTERY_PERCENT] Akku (Akku-Befund 03.11.2026).
 * Beginnt sie nicht, steht der Grund im Diagnose-Protokoll («… not started: …»), ausser vor dem ersten Entsperren und
 * wenn «Gute Nacht» schon wartet (der Prüf-Alarm nimmt sie alle 15 Minuten wieder auf).
 */
internal fun WakeService.startGoodNight(requestedAlarmId: String?, auto: Boolean = false) {
    val start = when {
        auto -> "auto onset"
        requestedAlarmId == null -> "onset resume"
        else -> "onset"
    }
    // Vor dem Entsperren nicht lesbar (auch das Diagnose-Protokoll nicht).
    val unlocked = DirectBoot.isUnlocked(this)
    if (night != null && unlocked) app.diag.log(DiagKind.MEASURE_START, "$start not started: night running")
    if (night != null || onset != null || goodNightPending) return
    // Ohne Sensorrechte kein Einschlaf-Modus: dann gilt die eingestellte Weckzeit.
    if (!unlocked || !goForeground(Notifications.goodNight(this), measuring = true)) {
        if (unlocked) app.diag.log(DiagKind.MEASURE_START, "$start not started: no sensors: ${healthRefusal.orEmpty()}")
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
            if (stillWanted()) {
                app.diag.log(DiagKind.MEASURE_START, "$start not started: nothing saved")
                stopGoodNight()
            }
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
        val label = if (automatic) "auto onset" else "onset"
        val notStarted = OnsetRules.notStartedReason(spec != null, duration != null, automatic, since, latest, windowBegun, alreadyDone)
        if (notStarted != null || spec == null || latest == null || latestGiveUp == null) {
            if (stillWanted()) {
                app.diag.log(DiagKind.MEASURE_START, "$label not started: ${notStarted ?: "no alarm"}")
                stopGoodNight()
            }
            return@launch
        }
        // Zeitgrenze schon vorbei (z. B. nach dem Wiederaufnehmen): Ende protokollieren, Nacht erledigt, Weckzeit gilt.
        // Nach dem Wiederaufnehmen hatte sie gemessen: ohne Ergebnis gilt die Untergrenze kurzer Nächte (fail-closed).
        OnsetRules.giveUpReason(Instant.now(), latestGiveUp, maxEnd)?.let { reason ->
            if (stillWanted()) endBeforeMeasuring(alarmId, latest, "$label $reason", DiagKind.MEASURE_END, measured = resumed != null)
            return@launch
        }
        // Wenig Akku: automatisch nicht messen (die Messung ist eine Zugabe, der Wecker hat Vorrang).
        val battery = getSystemService(android.os.BatteryManager::class.java)
            ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        if (automatic && AutoOnset.skipForBattery(battery)) {
            if (stillWanted()) {
                endBeforeMeasuring(alarmId, latest, "auto onset skipped battery $battery", DiagKind.BATTERY)
                // Nacht-Check am Handy rot bis zur Weckzeit (Audit F-06, wie die Apple Watch).
                app.appScope.launch { app.scheduler.noteRecorderSkip(RecorderSkip(latest, RecorderSkip.Reason.BATTERY)) }
            }
            return@launch
        }
        // Gelernte Werte der Lernphase: eigenes Grundrauschen, solange die Messung noch keines hat.
        val learning = app.learning.current()
        val config = learning.applyTo(Calibration.calibrate(NightHistory.motionsForCalibration(app.nights.load())))
        // Beginn der Einschlafdauer für die Lernphase (Schlafenszeit-Empfehlung am Handy).
        val bedAt = OnsetRules.bedAt(since, automatic, latest, spec.sleepDurationMinutes ?: app.sleepGoal.minutes.first())
        if (!stillWanted()) return@launch
        app.diag.log(DiagKind.MEASURE_START, "$label start, alarm at $latest${if (resumed != null) ", resumed" else ""}")
        onsetNight = alarmId to latest
        onsetAuto = automatic
        // Hartes Zeitlimit nach Uhrzeit (auch im Tiefschlaf, über den Alarm der Messung): 15 Min vor der eingestellten
        // Weckzeit, spätestens MAX_DURATION nach dem Beginn.
        // Verlängerung bei spätem Einschlafen: höchstens 4 Std. ab dem Beginn, nie über den Beginn des Weckfensters.
        val extendCap = plan?.let { minOf(since.plus(OnsetMonitor.MAX_EXTENDED), it.windowStart) }
        val monitor = OnsetMonitor(
            this@startGoodNight, config.lightThreshold, latestGiveUp, maxEnd, label, app.diag::log,
            onGiveUp = {
                // Auch ohne Einschlafzeit: Das Grundrauschen dieser Messung gilt im Weckfenster (Vollaudit K-1).
                saveOnsetFloor(alarmId, latest, onset?.floor)
                stopGoodNight(measured = true)
            },
            extendCap = extendCap, personalFloor = config.personalFloor,
        ) { found ->
            saveOnsetFloor(alarmId, latest, onset?.floor)
            // Automatisch nie vor der Bettzeit eingeschlafen (Vollaudit K-4); nach «Gute Nacht» unverändert.
            val asleep = OnsetRules.onsetAt(found, bedAt)
            app.diag.log(DiagKind.PHASE, "$label asleep $asleep${if (asleep != found) " (still since $found)" else ""}")
            onAsleep(spec, latest, asleep, bedAt)
        }
        onset = monitor
        monitor.start()
        // Misst doch (z. B. «Gute Nacht» von Hand nach dem Laden): Meldung dieser Nacht aufheben.
        app.appScope.launch { app.scheduler.clearRecorderSkip(latest) }
        goodNightPending = false
        // Lernphase: Die Uhr zeichnet ab jetzt die Nacht auf (nicht beim Wiederaufnehmen: dort läuft sie schon oder wurde
        // von Hand gestoppt).
        if (learning.active && resumed == null) RecordControl.startForLearning(this@startGoodNight, latest)
        // Zusätzlich, solange die Uhr wach ist: Dann gilt die eingestellte Weckzeit.
        handler.postDelayed(onsetTimeout, Duration.between(Instant.now(), monitor.giveUpAt).toMillis().coerceAtLeast(0))
    }
}

/**
 * Grundrauschen [floor] der Einschlaf-Messung für die Nacht von [alarmId] mit Weckzeit [latest] merken ([OnsetFloorStore]);
 * zu Beginn des Weckfensters hebt es die Schwellen höchstens auf das 1.5-Fache an (Vollaudit K-1). Eigene Arbeit: Ein
 * Fehler dabei hält das Einschlafen nicht auf.
 */
private fun WakeService.saveOnsetFloor(alarmId: String, latest: Instant, floor: Double?) {
    app.appScope.launch {
        try {
            OnsetFloorStore(phaseWakeStore).set(alarmId, latest, floor)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Grundrauschen der Einschlaf-Messung nicht gespeichert", e)
        }
    }
}

/**
 * Zeitgeber der Zeitgrenze ([WakeService.onsetTimeout], solange die Uhr wach ist): Die Messung verlängert sich oder gibt
 * auf ([OnsetMonitor.checkLimit]); läuft sie weiter, gilt ihre neue Grenze.
 */
internal fun WakeService.onOnsetTimeout() {
    val monitor = onset ?: return stopGoodNight()
    monitor.checkLimit()
    if (onset !== monitor) return
    val remaining = Duration.between(Instant.now(), monitor.giveUpAt).toMillis()
    // Grenze vorbei und nicht verlängert (sollte nicht vorkommen): wie bisher aufgeben, nie ein Zeitgeber ohne Pause.
    if (remaining <= 0) return stopGoodNight(measured = true)
    handler.removeCallbacks(onsetTimeout)
    handler.postDelayed(onsetTimeout, remaining)
}

/**
 * Eingeschlafen um [asleep] (Wecker [spec], eingestellte Weckzeit [latest]): Einschlafzeit merken, mit dem Beginn der
 * Einschlafdauer [bedAt] auch in einer laufenden Aufzeichnung (Lernphase). Mit Schlafdauer gilt
 * Einschlafzeit + Schlafdauer als Ausnahme für diese Nacht, auch für den Zweitwecker am Handy ([OnsetShift]; ohne
 * frühere Zielzeit hebt die Meldung dort eine frühere auf).
 * «Gute Nacht» endet, neu planen. Intern statt privat, damit WakeServiceTest das Einschlafen ohne Sensoren auslöst.
 */
internal fun WakeService.onAsleep(spec: AlarmSpec, latest: Instant, asleep: Instant, bedAt: Instant? = null) {
    val duration = spec.sleepDurationMinutes
    val plan = duration?.let { AlarmSchedule.planAt(spec, AlarmSchedule.sleepTarget(asleep, it, latest)) }
    // Ohne Uhrzeiten: Schlafdaten gehören nicht ins Systemprotokoll (logcat), nur ins eigene Diagnose-Protokoll.
    Log.i(TAG, "Eingeschlafen erkannt${if (plan != null) ", Zielzeit festgelegt" else ""}")
    onset?.stop("asleep")
    onset = null
    onsetNight = null
    goodNightActive = false
    forgetGoodNight()
    // Lernphase: Einschlafdauer in der laufenden Aufzeichnung (auch einer von Hand begonnenen) festhalten. Eigene Arbeit:
    // Ein Fehler dabei hält das Festlegen der Zielzeit unten nicht auf.
    if (bedAt != null) {
        app.appScope.launch { (RecordService.since.value ?: app.recordings.active()?.start)?.let { app.recordings.appendOnset(it, bedAt, asleep) } }
    }
    app.appScope.launch {
        app.onsets.set(spec.id, latest, asleep)
        if (plan != null) app.onsetOverride.set(plan)
        // Zuerst die eigenen Alarme auf die neue Zielzeit (Audit AN-6): Das Senden ans Handy darf sie nicht aufhalten.
        app.scheduler.reschedule()
        // Auch der Zweitwecker am Handy klingelt diese Nacht früher (Review K-07): über die Warteschlange, kommt also auch
        // an, wenn das Handy gerade nicht erreichbar ist. Nach jedem Einschlafen mit Schlafdauer, auch ohne frühere
        // Zielzeit (Review H-1): Ein zweites «Gute Nacht» ersetzt so eine frühere Meldung, statt dass das Handy bei ihr bleibt.
        if (plan != null) {
            app.publishOnsetShift(OnsetShift(spec.id, latest.toEpochMilli(), plan.target.toEpochMilli(), System.currentTimeMillis()))
        }
    }
    stopIfIdle()
}

/**
 * Wartet nicht mehr auf das Einschlafen (aufgegeben, abgebrochen): Für diese Nacht startet keine automatische Messung mehr.
 * Ins Diagnose-Protokoll: «give up» (auch «(max duration)»), wenn die Zeitgrenze erreicht ist, sonst «cancelled».
 * [measured]: Die Messung lief und gab ohne Einschlafzeit auf; dann gilt die Untergrenze kurzer Nächte (fail-closed,
 * [com.phasewake.app.wear.schedule.OnsetStore.measuredFor]). Von Hand abgebrochen nicht: Wer «Gute Nacht» aufhebt, will die eingestellte Weckzeit.
 */
internal fun WakeService.stopGoodNight(measured: Boolean = false) {
    onsetNight?.let { (alarmId, latest) ->
        app.appScope.launch { app.onsets.set(alarmId, latest, null, measured) }
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
private fun WakeService.endBeforeMeasuring(alarmId: String, latest: Instant, detail: String, kind: DiagKind, measured: Boolean = false) {
    app.diag.log(kind, detail)
    app.appScope.launch { app.onsets.set(alarmId, latest, null, measured) }
    stopGoodNight()
}

/**
 * Das Weckfenster von [plan] beginnt, während die Einschlaf-Messung dieser Nacht noch ohne Ergebnis lief ([running]:
 * Wecker und eingestellte Weckzeit) oder nach einem Prozessende noch gespeichert war: Die Nacht gilt als gemessen ohne
 * Einschlafzeit (fail-closed, [com.phasewake.app.wear.schedule.OnsetStore.measuredFor]). Danach ist «Gute Nacht» vorbei (Stand gelöscht, wie
 * [forgetGoodNight]). Nur nach dem ersten Entsperren aufrufen.
 */
internal suspend fun WakeService.endOnsetAtWindow(plan: WakePlan, running: Pair<String, Instant>?) {
    val saved = app.goodNight.load()
    val night = running ?: saved?.let { s ->
        app.alarms.current().alarms.firstOrNull { it.id == s.alarmId }?.let { s.alarmId to AlarmSchedule.nextTarget(it, s.since, ZoneId.systemDefault()) }
    }
    val latest = night?.second
    if (night != null && latest != null && night.first == plan.alarmId && !latest.isBefore(plan.target) && !app.onsets.isDone(night.first, latest)) {
        app.diag.log(DiagKind.MEASURE_END, "${if (running != null && onsetAuto) "auto onset" else "onset"} no result, short night unknown")
        app.onsets.set(night.first, latest, null, measured = true)
    }
    if (saved != null) app.goodNight.clear()
}

/** «Gute Nacht» ist vorbei: Stand löschen und keine Prüfung mehr. */
internal fun WakeService.forgetGoodNight() {
    AlarmScheduler.cancelGoodNightCheck(this)
    if (DirectBoot.isUnlocked(this)) app.appScope.launch { app.goodNight.clear() }
}
