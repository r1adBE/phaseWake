package com.phasewake.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.phasewake.app.core.LearnNights
import com.phasewake.app.core.LearnNights.Reason
import com.phasewake.app.ui.home.HomeLearning
import com.phasewake.app.ui.home.LearnNightsScreen
import com.phasewake.app.ui.home.LearningCard
import com.phasewake.app.ui.theme.ThemeMode
import java.time.LocalDate

/** Bildschirmfotos der Lern-Übersicht (r1AD 13.11.2026) und der Karte der Lernphase mit «Letzte Nacht zählte nicht». */

private fun day(d: Int): LocalDate = LocalDate.of(2026, 10, d)

/** Beispielnächte, jüngste zuerst: gezählt, Akku, vermutlich nicht getragen, kein Wecker, zu kurz, gestoppt, nichts angekommen. */
private val sampleNights = listOf(
    LearnNights.Night(day(27), Reason.BATTERY, battery = 34),
    LearnNights.Night(day(26), Reason.COUNTED, minutes = 452, measuredEpochs = 182, pulsePercent = 21),
    LearnNights.Night(day(25), Reason.NOT_WORN, minutes = 410, measuredEpochs = 164, pulsePercent = 0),
    LearnNights.Night(day(24), Reason.NO_ALARM),
    LearnNights.Night(day(23), Reason.TOO_SHORT, minutes = 135, measuredEpochs = 54, pulsePercent = 19),
    LearnNights.Night(day(22), Reason.ENDED_STOPPED, minutes = 95, measuredEpochs = 38, pulsePercent = 22),
    LearnNights.Night(day(21), Reason.COUNTED, minutes = 470, measuredEpochs = 188, pulsePercent = 20),
    LearnNights.Night(day(20), Reason.NO_RECORDING),
)

@Composable
private fun Overview(mode: ThemeMode) = Themed(mode) { LearnNightsScreen(sampleNights, onBack = {}) }

@PreviewTest @Preview(locale = "de-rCH", name = "Lern-Übersicht hell", device = PHONE) @Composable
fun LearnNightsLight() = Overview(ThemeMode.LIGHT)

@PreviewTest @Preview(locale = "de-rCH", name = "Lern-Übersicht dunkel", device = PHONE) @Composable
fun LearnNightsDark() = Overview(ThemeMode.DARK)

/** Lange Wörter (Finnisch): Zeilen brechen um, nichts wird abgeschnitten. */
@PreviewTest @Preview(locale = "fi", name = "Lern-Übersicht Finnisch", device = PHONE) @Composable
fun LearnNightsFinnish() = Overview(ThemeMode.DARK)

/** Karte der Lernphase auf der Startseite: Nacht 3 von 7, letzte Nacht wegen Akku nicht gezählt, «Details». */
@Composable
private fun Card(mode: ThemeMode) = Themed(mode) {
    Box(Modifier.padding(vertical = 24.dp)) {
        LearningCard(HomeLearning(3, null, miss = sampleNights.first(), onDetails = {}))
    }
}

@PreviewTest @Preview(locale = "de-rCH", name = "Karte Lernphase hell", device = PHONE) @Composable
fun LearnCardMissLight() = Card(ThemeMode.LIGHT)

@PreviewTest @Preview(locale = "de-rCH", name = "Karte Lernphase dunkel", device = PHONE) @Composable
fun LearnCardMissDark() = Card(ThemeMode.DARK)
