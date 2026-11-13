package com.phasewake.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.LearnNights
import com.phasewake.app.core.LearnNights.Reason
import com.phasewake.app.shared.RecordSync
import com.phasewake.app.ui.home.HomeExtras
import com.phasewake.app.ui.home.HomeLearning
import com.phasewake.app.ui.home.HomeScreen
import com.phasewake.app.ui.settings.LearningGroup
import com.phasewake.app.ui.settings.LearningSettings
import com.phasewake.app.ui.theme.ThemeMode
import java.time.Duration
import java.time.LocalDate

/**
 * Bildschirmfotos ohne doppelte Texte (r1AD 13.11.2026): Startseite während der Lernphase mit nur einer Karte (Lernphase
 * samt «Nacht aufzeichnen»), nach der Lernphase die eigene Karte «Nacht-Aufzeichnung» mit kurzem Hinweis, und die Gruppe
 * der Lernphase in den Einstellungen (Titel, Schalter «Aktiv», «Details» und «Neu lernen» direkt untereinander).
 */

private val learnPadding = PaddingValues(top = 24.dp, bottom = 96.dp)

private val work = AlarmSpec(id = "work", hour = 7, minute = 0, days = weekdays, label = "Arbeit")

/** Letzte Nacht: keine Aufzeichnung angekommen. */
private val missed = LearnNights.Night(LocalDate.of(2026, 10, 27), Reason.NO_RECORDING)

private fun learning() = HomeLearning(4, null, autoHint = false, miss = missed, onDetails = {})

@Composable
private fun LearnHome(mode: ThemeMode, extras: HomeExtras) = Themed(mode) {
    HomeScreen(
        listOf(work), lastNight = null, check = null, onFix = {}, contentPadding = learnPadding,
        onAdd = {}, onEdit = {}, onToggle = { _, _ -> }, clock = { FIXED_NOW }, extras = extras,
    )
}

/** Lernphase läuft, die Uhr kann aufzeichnen, keine Aufzeichnung läuft. */
private val during = HomeExtras(learning = learning(), record = RecordSync.RecordStatus(null))

/** Lernphase läuft, eine Aufzeichnung seit einer Stunde. */
private val duringRecording = HomeExtras(learning = learning(), record = RecordSync.RecordStatus(FIXED_NOW.minus(Duration.ofHours(1))))

/** Nach der Lernphase (Karte «Angepasst an dich» schon bestätigt): eigene Karte «Nacht-Aufzeichnung». */
private val after = HomeExtras(record = RecordSync.RecordStatus(null))

@PreviewTest @Preview(locale = "de-rCH", name = "Start Lernphase hell", device = PHONE) @Composable
fun LearnHomeLight() = LearnHome(ThemeMode.LIGHT, during)

@PreviewTest @Preview(locale = "de-rCH", name = "Start Lernphase dunkel", device = PHONE) @Composable
fun LearnHomeDark() = LearnHome(ThemeMode.DARK, during)

@PreviewTest @Preview(locale = "de-rCH", name = "Start Lernphase Aufzeichnung läuft", device = PHONE) @Composable
fun LearnHomeRecording() = LearnHome(ThemeMode.LIGHT, duringRecording)

@PreviewTest @Preview(locale = "de-rCH", name = "Start nach der Lernphase", device = PHONE) @Composable
fun RecordHomeAfter() = LearnHome(ThemeMode.LIGHT, after)

/** Gruppe der Lernphase in den Einstellungen: Nacht 4 von 7, Schalter an. */
@Composable
private fun LearnSettings(mode: ThemeMode) = Themed(mode) {
    Box(Modifier.padding(16.dp)) {
        LearningGroup(LearningSettings(4, null, onRestart = {}, consent = true, onDetails = {}))
    }
}

@PreviewTest @Preview(locale = "de-rCH", name = "Einstellungen Lernphase hell", device = PHONE) @Composable
fun LearnSettingsLight() = LearnSettings(ThemeMode.LIGHT)

@PreviewTest @Preview(locale = "de-rCH", name = "Einstellungen Lernphase dunkel", device = PHONE) @Composable
fun LearnSettingsDark() = LearnSettings(ThemeMode.DARK)
