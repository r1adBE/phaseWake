package com.phasewake.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalConfiguration
import com.phasewake.app.PhaseWakeApp
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.TestRuns
import com.phasewake.app.core.Transient
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
import kotlinx.coroutines.launch

// Neues aus Runde 3 für die Bildschirme: Startseite (Powernap, Überspringen, Probewecker) und Einstellungen.

/** Weitere Ziele der Navigation (ohne untere Leiste). */
internal object ExtraRoutes {
    const val DIAGNOSTICS = "diagnostics"
    const val ONBOARDING = "onboarding"
}

/** Startet den Probewecker (im Hintergrund, übersteht das Verlassen des Bildschirms). */
internal fun startTest(app: PhaseWakeApp) {
    app.appScope.launch { Transients.startTest(app) }
}

/** Probewecker, Powernap, Überspringen und Feiertage für die Startseite. */
internal fun homeExtras(app: PhaseWakeApp, data: AppData, now: Instant = Instant.now()): HomeExtras {
    val transient = data.transient?.takeIf { Transient.isActive(it, now) }
    return HomeExtras(
        holidays = data.holidays,
        nap = transient?.takeIf { it.kind == TransientKind.NAP },
        napMinutes = data.napMinutes,
        onNap = { minutes -> app.appScope.launch { Transients.startNap(app, minutes) } },
        onStopNap = { id -> app.appScope.launch { Transients.cancel(app, id) } },
        onSkip = { alarm ->
            app.afterAlarmsChanged { app.alarms.update(alarm.id) { AlarmSchedule.skipNext(it, Instant.now(), ZoneId.systemDefault(), data.holidays) } }
        },
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
