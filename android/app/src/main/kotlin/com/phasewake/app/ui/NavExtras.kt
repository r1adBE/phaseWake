package com.phasewake.app.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phasewake.app.PhaseWakeApp
import com.phasewake.app.R
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.Learning
import com.phasewake.app.core.TestRuns
import com.phasewake.app.core.Transient
import com.phasewake.app.core.TransientConflict
import com.phasewake.app.core.TransientKind
import com.phasewake.app.holidays.HolidayCountry
import com.phasewake.app.shared.NightRecording
import com.phasewake.app.shared.RecordSync
import com.phasewake.app.transient.Transients
import com.phasewake.app.ui.home.HomeExtras
import com.phasewake.app.ui.home.HomeLearning
import com.phasewake.app.ui.settings.BackupRows
import com.phasewake.app.ui.settings.HolidaysUi
import com.phasewake.app.ui.settings.LearningSettings
import com.phasewake.app.ui.settings.SettingsExtras
import com.phasewake.app.ui.settings.countryName
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Neues aus Runde 3 für die Bildschirme: Startseite (Powernap, Überspringen, Probewecker) und Einstellungen.

/** Weitere Ziele der Navigation (ohne untere Leiste). */
internal object ExtraRoutes {
    const val DIAGNOSTICS = "diagnostics"
    const val ONBOARDING = "onboarding"
    const val LICENSES = "licenses"

    /** Detailseite einer Nacht; `key` = [com.phasewake.app.core.HistoryItem.key]. */
    const val NIGHT = "night?key={key}"

    /** Ziel zur Detailseite der Nacht [key] (Doppelpunkte und Zeichen der Wecker-Kennung kodiert). */
    fun night(key: String): String = "night?key=" + android.net.Uri.encode(key)
}

/** Startet den Probewecker (im Hintergrund, übersteht das Verlassen des Bildschirms). */
internal fun startTest(app: PhaseWakeApp) = launchTransient(app) { Transients.startTest(app) }

/**
 * Startet einen Probewecker oder Powernap mit [start] im Hintergrund; lehnt der Kern ab (Wecker läuft oder beginnt
 * vorher), erscheint der Grund als kurzer Hinweis.
 */
private fun launchTransient(app: PhaseWakeApp, start: suspend () -> TransientConflict?) {
    app.appScope.launch {
        val refused = start() ?: return@launch
        val text = when (refused) {
            TransientConflict.ALARM_RUNNING -> R.string.transient_alarm_running
            TransientConflict.ALARM_SOON -> R.string.transient_alarm_soon
        }
        withContext(Dispatchers.Main) { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
    }
}

/**
 * Probewecker, Powernap, Überspringen und Feiertage für die Startseite. [skipAllowed]: Die Uhr kennt «Überspringen»
 * (`Codec.watchKnowsSkip`); sonst kein Menü, denn die Uhr weckte trotzdem. [record]: Stand von «Nacht aufzeichnen» der
 * Uhr; `null` (Uhr kennt es nicht): keine Karte.
 */
internal fun homeExtras(
    app: PhaseWakeApp,
    data: AppData,
    skipAllowed: Boolean = true,
    now: Instant = Instant.now(),
    record: RecordSync.RecordStatus? = null,
): HomeExtras {
    val transient = data.transient?.takeIf { Transient.isActive(it, now) }
    return HomeExtras(
        holidays = data.holidays,
        doneUntil = data.doneUntil,
        nap = transient?.takeIf { it.kind == TransientKind.NAP },
        napMinutes = data.napMinutes,
        onNap = { minutes -> launchTransient(app) { Transients.startNap(app, minutes) } },
        onStopNap = { id -> app.appScope.launch { Transients.cancel(app, id) } },
        onSkip = { alarm: AlarmSpec ->
            app.afterAlarmsChanged {
                // Ab der erledigten Nacht: Nach frühem Wecken trifft es den nächsten Termin, nicht den heutigen.
                val done = app.backup.doneUntil()[alarm.id]
                app.alarms.update(alarm.id) { AlarmSchedule.skipNext(it, Instant.now(), ZoneId.systemDefault(), data.holidays, done) }
            }
        }.takeIf { skipAllowed },
        onUndoSkip = { alarm -> app.afterAlarmsChanged { app.alarms.update(alarm.id, AlarmSchedule::undoSkip) } },
        onTest = { startTest(app) },
        testAt = data.testRun?.takeIf { transient?.kind == TransientKind.TEST && !TestRuns.isFinished(it, now) }?.target,
        autoOnset = data.autoOnset,
        sleepGoal = data.sleepGoal,
        record = record,
        onRecord = { start -> sendRecord(app, start) },
        learning = homeLearning(app, data, watchRecords = record != null),
        learned = data.learning?.values,
    )
}

/**
 * Karte der Lernphase: solange sie läuft und die Uhr aufzeichnen kann ([watchRecords]) «Nacht N von 7» aus den gezählten
 * Aufzeichnungen; danach «Angepasst an dich», bis bestätigt. Sonst `null`.
 */
private fun homeLearning(app: PhaseWakeApp, data: AppData, watchRecords: Boolean): HomeLearning? {
    val state = data.learning ?: return null
    val values = state.values
    val since = state.since
    return when {
        values != null -> HomeLearning(null, values) { app.appScope.launch { app.learning.setSeen() } }.takeIf { !state.seen }
        since != null && watchRecords -> HomeLearning(Learning.progress(countedNights(data, since)), null)
        else -> null
    }
}

/** Gezählte Nächte der Lernphase ab [since] aus den Aufzeichnungen. */
internal fun countedNights(data: AppData, since: Instant): Int =
    Learning.counted(data.recordings.orEmpty().map { it.toLearnNight() }, since, ZoneId.systemDefault()).size

/**
 * Start ([start]) oder Stopp von «Nacht aufzeichnen» an die Uhr. Hinweis, wenn sie nicht erreichbar ist oder ihr Akku
 * unter [NightRecording.MIN_BATTERY] liegt (dann startet sie nicht). Den neuen Stand meldet die Uhr selbst.
 */
private fun sendRecord(app: PhaseWakeApp, start: Boolean) {
    app.appScope.launch {
        val battery = if (start) app.sync.watchBattery() else null
        val text = when {
            battery != null && battery < NightRecording.MIN_BATTERY -> R.string.record_end_battery
            !app.recordSync.command(start) -> R.string.check_watch_problem
            else -> null
        }
        app.diag.log(DiagKind.SYNC, "record ${if (start) "start" else "stop"} ${if (text == null) "sent" else "not sent"}")
        if (text != null) withContext(Dispatchers.Main) { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
    }
}

/** Feiertage, Sichern, Probewecker, Einrichtung und Diagnose für die Einstellungen. */
@Composable
internal fun settingsExtras(app: PhaseWakeApp, data: AppData, actions: NavActions): SettingsExtras {
    val locale = LocalConfiguration.current.locales[0]
    val countries by produceState(emptyList<HolidayCountry>()) { value = app.holidays.countries() }
    // Nach jeder Wahl ändern sich die Feiertage: dann die Wahl neu lesen.
    val choice by produceState("" to "", data.holidays) { value = app.holidays.choice() }
    val today = LocalDate.now()
    val holidays = HolidaysUi(
        countries = countries.map { it.code to countryName(it.code, locale) },
        country = choice.first,
        regions = countries.firstOrNull { it.code == choice.first }?.regions?.toList().orEmpty(),
        region = choice.second,
        next = data.holidays.filter { !it.isBefore(today) }.minOrNull(),
        onChoose = { country, region ->
            app.appScope.launch {
                app.holidays.choose(country, region)
                app.backup.reschedule()
            }
        },
    )
    // Lernphase: nur mit einer Uhr, die aufzeichnen kann, oder wenn schon gelernt.
    val record by app.recordStatus.collectAsStateWithLifecycle()
    val learningState = data.learning
    val since = learningState?.since
    val learning = when {
        learningState == null || since == null -> null
        learningState.values != null || record != null -> LearningSettings(
            night = Learning.progress(countedNights(data, since)).takeIf { learningState.values == null },
            values = learningState.values,
            onRestart = { app.appScope.launch { app.restartLearning() } },
        )
        else -> null
    }
    return SettingsExtras(
        learning = learning,
        onTest = { startTest(app) },
        holidays = holidays.takeIf { countries.isNotEmpty() },
        backup = { BackupRows() },
        onOnboarding = { actions.nav.navigate(ExtraRoutes.ONBOARDING) },
        onLicenses = { actions.nav.navigate(ExtraRoutes.LICENSES) },
        diagnostics = data.diagnostics,
        onDiagnostics = { actions.nav.navigate(ExtraRoutes.DIAGNOSTICS) },
        onUnlockDiagnostics = { app.appScope.launch { app.settings.setDiagnostics(true) } },
    )
}
