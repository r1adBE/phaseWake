package com.phasewake.app.wear.wake

import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Calibration
import com.phasewake.app.core.DetectorConfig
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.LearnSkip
import com.phasewake.app.core.NightHistory
import com.phasewake.app.core.RecorderSkip
import com.phasewake.app.shared.phaseWakeStore
import com.phasewake.app.wear.record.LearnNotice
import com.phasewake.app.wear.record.RecordControl
import com.phasewake.app.wear.schedule.AlarmScheduler
import com.phasewake.app.wear.schedule.AutoOnset
import com.phasewake.app.wear.schedule.DirectBoot
import com.phasewake.app.wear.system.StartLock
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
 * (alle 15 Minuten) den Modus mit [requestedAlarmId] `null` wieder auf. Die Einschlaf-Erkennung beginnt nur dann von
 * vorn, wenn das vor dem Beginn der Einschlafdauer geschieht (automatisch: vor der Bettzeit). Sonst gilt die Regel des
 * späten Starts ([OnsetRules.lateStart], `docs/PHASENERKENNUNG.md` Abschnitt 6, «Später Beginn»): im Hintergrund ist
 * die Einschlafzeit unbekannt, die Messung endet gleich (nicht gemessen) und die eingestellte Weckzeit gilt; nur bei
 * sichtbarer App misst sie ab jetzt. Nach «Gute Nacht» von Hand ist eine Wiederaufnahme immer spät, denn die
 * Einschlafdauer beginnt mit dem Tippen.
 *
 * Die Messung endet spätestens [OnsetMonitor.MAX_DURATION] nach ihrem ersten Beginn ([GoodNightState]) und
 * 15 Min vor der eingestellten Weckzeit; dann gilt diese, und für die Nacht startet keine automatische Messung mehr.
 * Bei Unruhe verlängert sie sich ([OnsetMonitor]), bis höchstens [OnsetMonitor.MAX_EXTENDED] nach dem Beginn und nie
 * über den Beginn des Weckfensters hinaus. Wird sie nach der Höchstdauer wieder aufgenommen, endet sie wie bisher.
 * Die automatische Messung beginnt nicht unter [AutoOnset.MIN_BATTERY_PERCENT] Akku, ausser
 * die Uhr lädt; dann wartet sie am Ladegerät und endet ohne Untergrenze, wenn der Akku beim Abnehmen noch zu tief ist.
 * Beginnt sie nicht, steht der Grund im Diagnose-Protokoll («… not started: …»), ausser vor dem ersten Entsperren und
 * wenn «Gute Nacht» schon wartet (der Prüf-Alarm nimmt sie alle 15 Minuten wieder auf).
 */
internal fun WakeService.startGoodNight(requestedAlarmId: String?, auto: Boolean = false) {
    val start = when {
        auto -> "auto onset"
        requestedAlarmId == null -> "onset resume"
        else -> "onset"
    }
    // Vor dem Entsperren nicht lesbar (auch das Diagnose-Protokoll nicht). Entscheid: [GoodNightStart.gate].
    val unlocked = DirectBoot.isUnlocked(this)
    when (GoodNightStart.gate(night != null, onset != null, goodNightPending, unlocked)) {
        GoodNightStart.Gate.NIGHT_RUNNING -> {
            app.diag.log(DiagKind.MEASURE_START, "$start not started: night running")
            return
        }
        GoodNightStart.Gate.BUSY -> return
        GoodNightStart.Gate.LOCKED -> return refuseGoodNight()
        GoodNightStart.Gate.TRY -> Unit
    }
    // Ohne Sensorrechte kein Einschlaf-Modus: dann gilt die eingestellte Weckzeit.
    if (!goForeground(Notifications.goodNight(this), measuring = true)) {
        app.diag.log(DiagKind.MEASURE_START, "$start not started: no sensors: ${healthRefusal.orEmpty()}")
        // Lernphase: Diese Nacht zeichnet sie nicht auf (Meldung ans Handy, Mitteilung).
        if (requestedAlarmId != null) app.appScope.launch { LearnNotice.sensorsRefused(app, requestedAlarmId) }
        return refuseGoodNight()
    }
    goodNightPending = true
    goodNightActive = true
    val run = ++goodNightRun
    lifecycleScope.launch {
        val begun = beginGoodNight(requestedAlarmId, auto, start, run) ?: return@launch
        val measure = decideGoodNight(begun) ?: return@launch
        measureGoodNight(measure)
    }
}

/** Ob der Lauf [run] von «Gute Nacht» noch gewünscht ist: nicht abgebrochen und kein neuerer Start. */
private fun WakeService.stillWanted(run: Int) = goodNightPending && run == goodNightRun

/**
 * Ein begonnener Lauf [run] von «Gute Nacht»: Wecker [alarmId] ab [since], wieder aufgenommen aus [resumed] oder neu,
 * [automatic] für die automatische Einschlaf-Messung.
 */
private class GoodNightBegun(
    val run: Int,
    val alarmId: String,
    val resumed: GoodNightState.Saved?,
    val since: Instant,
    val automatic: Boolean,
) {
    /** Name im Diagnose-Protokoll. */
    val label = if (automatic) "auto onset" else "onset"
}

/** Die Einschlaf-Messung von [begun] beginnt: Wecker [spec], Weckfenster ab [windowStart], Grenzen aus [limits]. */
private class GoodNightMeasure(
    val begun: GoodNightBegun,
    val spec: AlarmSpec,
    val windowStart: Instant?,
    val limits: GoodNightStart.Decision.Measure,
)

/**
 * Stand von «Gute Nacht» laden oder neu speichern ([GoodNightState]) und den Prüf-Alarm stellen. `null`, wenn nichts
 * wieder aufzunehmen ist oder der Lauf [run] inzwischen abgebrochen wurde ([start]: Name fürs Diagnose-Protokoll).
 */
private suspend fun WakeService.beginGoodNight(
    requestedAlarmId: String?,
    auto: Boolean,
    start: String,
    run: Int,
): GoodNightBegun? {
    val resumed = if (requestedAlarmId == null) app.goodNight.load() else null
    val alarmId = requestedAlarmId ?: resumed?.alarmId
    if (alarmId == null) {
        // Nichts wieder aufzunehmen.
        if (stillWanted(run)) {
            app.diag.log(DiagKind.MEASURE_START, "$start not started: nothing saved")
            stopGoodNight()
        }
        return null
    }
    val since = resumed?.since ?: Instant.now()
    val automatic = resumed?.auto ?: auto
    if (resumed == null) app.goodNight.set(alarmId, since, automatic)
    // Inzwischen abgebrochen (oder ein Fenster hat begonnen): den eben geschriebenen Stand wieder löschen.
    if (!stillWanted(run)) {
        // Ein neuerer Start schreibt seinen eigenen Stand; dann nichts löschen.
        if (resumed == null && run == goodNightRun) app.goodNight.clear()
        return null
    }
    AlarmScheduler.armGoodNightCheck(this)
    return GoodNightBegun(run, alarmId, resumed, since, automatic)
}

/**
 * Ob für [b] gemessen wird ([GoodNightStart.decide]); sonst endet «Gute Nacht» hier (Grund im Diagnose-Protokoll)
 * und das Ergebnis ist `null`.
 */
private suspend fun WakeService.decideGoodNight(b: GoodNightBegun): GoodNightMeasure? {
    val spec = app.alarms.current().alarms.firstOrNull { it.id == b.alarmId }
    // Weckzeit der Nacht, in der «Gute Nacht» begann (auch beim Wiederaufnehmen).
    val nightTarget = spec?.let { AlarmSchedule.nextTarget(it, b.since, ZoneId.systemDefault()) }
    // Nur für die kommende Nacht: liegt die Weckzeit weiter weg, zeigt die Startseite einen Hinweis.
    // Automatisch: auch ohne Schlafdauer, aber nicht mehr im Weckfenster und nicht zweimal in derselben Nacht.
    val plan = spec?.let { s -> nightTarget?.let { AlarmSchedule.planAt(s, it) } }
    val alreadyDone = b.automatic && nightTarget != null && app.onsets.isDone(b.alarmId, nightTarget)
    // Wenig Akku: automatisch nicht messen (die Messung ist eine Zugabe, der Wecker hat Vorrang). Lädt die Uhr,
    // doch: Sie wartet am Ladegerät (die Zeit dort zählt nicht und verschiebt die Grenze).
    val batteryManager = getSystemService(android.os.BatteryManager::class.java)
    val battery = batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        ?.takeIf { it in 0..100 }
    val charging = batteryManager?.isCharging == true
    val request = GoodNightStart.Request(
        spec != null, spec?.sleepDurationMinutes != null, b.automatic, b.since, nightTarget, plan?.windowStart,
        alreadyDone,
    )
    val measure = when (val decision = GoodNightStart.decide(request, Instant.now(), battery, charging)) {
        is GoodNightStart.Decision.Measure -> decision
        is GoodNightStart.Decision.NotStarted -> {
            if (stillWanted(b.run)) {
                app.diag.log(DiagKind.MEASURE_START, "${b.label} not started: ${decision.reason}")
                stopGoodNight()
            }
            return null
        }
        // Zeitgrenze schon vorbei (z. B. nach dem Wiederaufnehmen): Ende protokollieren, Nacht erledigt, Weckzeit
        // gilt. Nach dem Wiederaufnehmen hatte sie gemessen: ohne Ergebnis gilt die Untergrenze kurzer Nächte
        // (fail-closed).
        is GoodNightStart.Decision.GiveUp -> {
            if (stillWanted(b.run)) {
                val detail = "${b.label} ${decision.reason}"
                val measured = b.resumed != null
                endBeforeMeasuring(b.alarmId, decision.latest, detail, DiagKind.MEASURE_END, measured = measured)
            }
            return null
        }
        is GoodNightStart.Decision.SkipBattery -> {
            if (stillWanted(b.run)) skipForBattery(b.alarmId, decision.latest, battery)
            return null
        }
    }
    // Ab hier misst sie: Wecker und Weckzeit sind da ([GoodNightStart.decide]).
    if (spec == null) return null
    return GoodNightMeasure(b, spec, plan?.windowStart, measure)
}

/**
 * Startet die Einschlaf-Messung von [m]. Erst nach dem Beginn der Einschlafdauer gestartet (oder wieder aufgenommen;
 * wie die Apple Watch): Bei sichtbarer App ist die Person wach, gemessen wird ab jetzt. Im Hintergrund ist die
 * Einschlafzeit unbekannt: nicht gemessen, ohne Untergrenze. Die Lernphase zeichnet trotzdem auf.
 */
private suspend fun WakeService.measureGoodNight(m: GoodNightMeasure) {
    val b = m.begun
    val latest = m.limits.latest
    // Gelernte Werte der Lernphase: eigenes Grundrauschen, solange die Messung noch keines hat.
    val learning = app.learning.current()
    val config = learning.applyTo(Calibration.calibrate(NightHistory.motionsForCalibration(app.nights.load())))
    // Beginn der Einschlafdauer für die Lernphase (Schlafenszeit-Empfehlung am Handy).
    val sleepMinutes = m.spec.sleepDurationMinutes ?: app.sleepGoal.minutes.first()
    if (!stillWanted(b.run)) return
    val now = Instant.now()
    val plannedBed = OnsetRules.plannedBedAt(b.since, b.automatic, latest, sleepMinutes)
    val late = OnsetRules.lateStart(now, plannedBed, foreground = appVisible())
    if (late == OnsetRules.LateStart.BACKGROUND) {
        if (learning.active && b.resumed == null) RecordControl.startForLearning(this, latest)
        val detail = "${b.label} start after bedtime $plannedBed: onset unknown, not measured"
        endBeforeMeasuring(b.alarmId, latest, detail, DiagKind.MEASURE_END)
        return
    }
    val bedAt = if (late == OnsetRules.LateStart.FOREGROUND) {
        now
    } else {
        OnsetRules.bedAt(b.since, b.automatic, latest, sleepMinutes)
    }
    if (late == OnsetRules.LateStart.FOREGROUND) {
        app.diag.log(DiagKind.MEASURE_START, "${b.label} after bedtime $plannedBed, app open: from now")
    }
    val resumed = if (b.resumed != null) ", resumed" else ""
    app.diag.log(DiagKind.MEASURE_START, "${b.label} start, alarm at $latest$resumed")
    onsetNight = b.alarmId to latest
    onsetAuto = b.automatic
    val monitor = onsetMonitor(m, config, bedAt)
    onset = monitor
    monitor.start()
    nightBatteryBegin(this, b.alarmId, latest)
    // Die Messung läuft (Stichproben mit eigenem Wakelock): Start-Wakelock des Prüf-Alarms lösen.
    StartLock.release()
    // Misst doch (z. B. «Gute Nacht» von Hand nach dem Laden): Meldung dieser Nacht aufheben.
    app.appScope.launch { app.scheduler.clearRecorderSkip(latest) }
    goodNightPending = false
    // Lernphase: Die Uhr zeichnet ab jetzt die Nacht auf (nicht beim Wiederaufnehmen: dort läuft sie schon oder
    // wurde von Hand gestoppt).
    if (learning.active && b.resumed == null) RecordControl.startForLearning(this, latest)
    // Zusätzlich, solange die Uhr wach ist: Dann gilt die eingestellte Weckzeit.
    handler.postDelayed(onsetTimeout, Duration.between(Instant.now(), monitor.giveUpAt).toMillis().coerceAtLeast(0))
}

/**
 * Die Einschlaf-Messung für [m] mit den Werten [config] und dem Beginn der Einschlafdauer [bedAt]. Hartes Zeitlimit
 * nach Uhrzeit (auch im Tiefschlaf, über den Alarm der Messung): 15 Min vor der eingestellten Weckzeit, spätestens
 * MAX_DURATION nach dem Beginn. Verlängerung bei spätem Einschlafen: höchstens 4 Std. ab dem Beginn, nie über den
 * Beginn des Weckfensters.
 */
private fun WakeService.onsetMonitor(m: GoodNightMeasure, config: DetectorConfig, bedAt: Instant): OnsetMonitor {
    val b = m.begun
    val latest = m.limits.latest
    val extendCap = GoodNightStart.extendCap(b.since, m.windowStart)
    return OnsetMonitor(
        this, config.lightThreshold, m.limits.latestGiveUp, m.limits.maxEnd, b.label, app.diag::log,
        onGiveUp = {
            // Auch ohne Einschlafzeit: Das Grundrauschen dieser Messung gilt im Weckfenster.
            saveOnsetFloor(b.alarmId, latest, onset?.floor)
            stopGoodNight(measured = true)
        },
        extendCap = extendCap, personalFloor = config.personalFloor,
        // Wegen des Ladens trotz wenig Akku begonnen, abgenommen noch zu tief: nicht gemessen, Nacht-Check wie beim
        // Auslassen.
        onLowBattery = {
            app.diag.log(DiagKind.BATTERY, "${b.label} battery low after charging, not measured")
            app.appScope.launch {
                app.scheduler.noteRecorderSkip(RecorderSkip(latest, RecorderSkip.Reason.BATTERY))
            }
            stopGoodNight()
        }.takeIf { b.automatic },
    ) { found ->
        saveOnsetFloor(b.alarmId, latest, onset?.floor)
        // Automatisch nie vor der Bettzeit eingeschlafen; nach «Gute Nacht» unverändert.
        val asleep = OnsetRules.onsetAt(found, bedAt)
        app.diag.log(DiagKind.PHASE, "${b.label} asleep $asleep${if (asleep != found) " (still since $found)" else ""}")
        onAsleep(m.spec, latest, asleep, bedAt)
    }
}

/** «Gute Nacht» startet nicht (vor dem Entsperren oder ohne Sensorrechte): die eingestellte Weckzeit gilt. */
private fun WakeService.refuseGoodNight() {
    forgetGoodNight()
    stopIfIdle()
}

/**
 * Automatisch mit zu wenig Akku ([GoodNightStart.Decision.SkipBattery]): Nacht für [alarmId] mit Weckzeit [latest]
 * erledigt, Nacht-Check am Handy rot bis zur Weckzeit (wie die Apple Watch), Meldung der Lernphase.
 */
private fun WakeService.skipForBattery(alarmId: String, latest: Instant, battery: Int?) {
    endBeforeMeasuring(alarmId, latest, "auto onset skipped battery $battery", DiagKind.BATTERY)
    app.appScope.launch { app.scheduler.noteRecorderSkip(RecorderSkip(latest, RecorderSkip.Reason.BATTERY)) }
    // Lernphase: Ihre Aufzeichnung startet mit der Einschlaf-Messung, also diese Nacht auch nicht.
    app.appScope.launch {
        if (app.learning.current().active) {
            LearnNotice.skipped(app, LearnSkip(latest, LearnSkip.Reason.BATTERY, battery))
        }
    }
}

/**
 * Grundrauschen [floor] der Einschlaf-Messung für die Nacht von [alarmId] mit Weckzeit [latest] merken
 * ([OnsetFloorStore]); zu Beginn des Weckfensters hebt es die Schwellen höchstens auf das 1.5-Fache an. Eigene Arbeit:
 * Ein Fehler dabei hält das Einschlafen nicht auf.
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
 * Endet vor dem Messen ([detail] ins Diagnose-Protokoll als [kind]): Nacht für [alarmId] mit Weckzeit [latest]
 * erledigt, damit die automatische Messung nicht gleich wieder startet; die eingestellte Weckzeit gilt.
 */
private fun WakeService.endBeforeMeasuring(
    alarmId: String,
    latest: Instant,
    detail: String,
    kind: DiagKind,
    measured: Boolean = false,
) {
    app.diag.log(kind, detail)
    app.appScope.launch { app.onsets.set(alarmId, latest, null, measured) }
    stopGoodNight()
}
