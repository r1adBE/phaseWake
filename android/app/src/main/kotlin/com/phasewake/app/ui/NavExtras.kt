package com.phasewake.app.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalConfiguration
import com.phasewake.app.PhaseWakeApp
import com.phasewake.app.R
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.TestRuns
import com.phasewake.app.core.Transient
import com.phasewake.app.core.TransientConflict
import com.phasewake.app.core.TransientKind
import com.phasewake.app.holidays.HolidayCountry
import com.phasewake.app.transient.Transients
import com.phasewake.app.ui.home.HomeExtras
import com.phasewake.app.ui.settings.BackupRows
import com.phasewake.app.ui.settings.HolidaysUi
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
 * (`Codec.watchKnowsSkip`); sonst kein Menü, denn die Uhr weckte trotzdem.
 */
internal fun homeExtras(app: PhaseWakeApp, data: AppData, skipAllowed: Boolean = true, now: Instant = Instant.now()): HomeExtras {
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
    )
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
    return SettingsExtras(
        onTest = { startTest(app) },
        holidays = holidays.takeIf { countries.isNotEmpty() },
        backup = { BackupRows() },
        onOnboarding = { actions.nav.navigate(ExtraRoutes.ONBOARDING) },
        diagnostics = data.diagnostics,
        onDiagnostics = { actions.nav.navigate(ExtraRoutes.DIAGNOSTICS) },
        onUnlockDiagnostics = { app.appScope.launch { app.settings.setDiagnostics(true) } },
    )
}
