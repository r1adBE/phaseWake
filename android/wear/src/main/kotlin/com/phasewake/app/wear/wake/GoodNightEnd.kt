package com.phasewake.app.wear.wake

import android.util.Log
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.WakePlan
import com.phasewake.app.shared.OnsetShift
import com.phasewake.app.wear.record.RecordService
import com.phasewake.app.wear.schedule.AlarmScheduler
import com.phasewake.app.wear.schedule.DirectBoot
import com.phasewake.app.wear.system.StartLock
import com.phasewake.app.wear.wake.WakeService.Companion.TAG
import com.phasewake.app.wear.wake.WakeService.Companion.goodNightActive
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch

// Ende von «Gute Nacht» (Einschlaf-Modus) des Weckdienstes: eingeschlafen, aufgegeben, abgebrochen oder vom
// Weckfenster abgelöst; ausgelagert aus GoodNight.kt.

/**
 * Zeitgeber der Zeitgrenze ([WakeService.onsetTimeout], solange die Uhr wach ist): Die Messung verlängert sich oder
 * gibt auf ([OnsetMonitor.checkLimit]); läuft sie weiter, gilt ihre neue Grenze.
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
    // Lernphase: Einschlafdauer in der laufenden Aufzeichnung (auch einer von Hand begonnenen) festhalten. Eigene
    // Arbeit: Ein Fehler dabei hält das Festlegen der Zielzeit unten nicht auf.
    if (bedAt != null) {
        app.appScope.launch {
            (RecordService.since.value ?: app.recordings.active()?.start)?.let {
                app.recordings.appendOnset(it, bedAt, asleep)
            }
        }
    }
    app.appScope.launch {
        app.onsets.set(spec.id, latest, asleep, bedAt = bedAt)
        if (plan != null) app.onsetOverride.set(plan)
        // Zuerst die eigenen Alarme auf die neue Zielzeit: Das Senden ans Handy darf sie nicht aufhalten.
        app.scheduler.reschedule()
        // Auch der Zweitwecker am Handy klingelt diese Nacht früher: über die Warteschlange, kommt also auch
        // an, wenn das Handy gerade nicht erreichbar ist. Nach jedem Einschlafen mit Schlafdauer, auch ohne frühere
        // Zielzeit: Ein zweites «Gute Nacht» ersetzt so eine frühere Meldung, statt dass das Handy bei ihr bleibt.
        if (plan != null) {
            app.publishOnsetShift(
                OnsetShift(spec.id, latest.toEpochMilli(), plan.target.toEpochMilli(), System.currentTimeMillis()),
            )
        }
    }
    stopIfIdle()
}

/**
 * Wartet nicht mehr auf das Einschlafen (aufgegeben, abgebrochen): Für diese Nacht startet keine automatische Messung
 * mehr. Ins Diagnose-Protokoll: «give up» (auch «(max duration)»), wenn die Zeitgrenze erreicht ist, sonst «cancelled».
 * [measured]: Die Messung lief und gab ohne Einschlafzeit auf; dann gilt die Untergrenze kurzer Nächte (fail-closed,
 * [com.phasewake.app.wear.schedule.OnsetStore.measuredFor]). Von Hand abgebrochen nicht: Wer «Gute Nacht» aufhebt, will
 * die eingestellte Weckzeit.
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
    // Auch ein Start, der nicht misst, braucht den Start-Wakelock nicht mehr.
    StartLock.release()
    stopIfIdle()
}

/**
 * Das Weckfenster von [plan] beginnt, während die Einschlaf-Messung dieser Nacht noch ohne Ergebnis lief ([running]:
 * Wecker und eingestellte Weckzeit) oder nach einem Prozessende noch gespeichert war: Die Nacht gilt als gemessen ohne
 * Einschlafzeit (fail-closed, [com.phasewake.app.wear.schedule.OnsetStore.measuredFor]). Danach ist «Gute Nacht» vorbei
 * (Stand gelöscht, wie [forgetGoodNight]). Nur nach dem ersten Entsperren aufrufen.
 */
internal suspend fun WakeService.endOnsetAtWindow(plan: WakePlan, running: Pair<String, Instant>?) {
    val saved = app.goodNight.load()
    val night = running ?: saved?.let { s ->
        app.alarms.current().alarms.firstOrNull { it.id == s.alarmId }?.let {
            s.alarmId to AlarmSchedule.nextTarget(it, s.since, ZoneId.systemDefault())
        }
    }
    val latest = night?.second
    if (night != null && latest != null && night.first == plan.alarmId && !latest.isBefore(plan.target) &&
        !app.onsets.isDone(night.first, latest)
    ) {
        app.diag.log(
            DiagKind.MEASURE_END,
            "${if (running != null && onsetAuto) "auto onset" else "onset"} no result, short night unknown",
        )
        app.onsets.set(night.first, latest, null, measured = true)
    }
    if (saved != null) app.goodNight.clear()
}

/** «Gute Nacht» ist vorbei: Stand löschen und keine Prüfung mehr. */
internal fun WakeService.forgetGoodNight() {
    AlarmScheduler.cancelGoodNightCheck(this)
    if (DirectBoot.isUnlocked(this)) app.appScope.launch { app.goodNight.clear() }
}
