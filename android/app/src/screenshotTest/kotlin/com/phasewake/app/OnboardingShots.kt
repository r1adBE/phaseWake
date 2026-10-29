package com.phasewake.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.phasewake.app.ui.home.Check
import com.phasewake.app.ui.home.NightCheckState
import com.phasewake.app.ui.onboarding.OnboardingContent
import com.phasewake.app.ui.onboarding.OnboardingModel
import com.phasewake.app.ui.settings.HealthState
import com.phasewake.app.ui.theme.ThemeMode

/** Bildschirmfotos der Ersteinrichtung: Willkommen, Uhr, Rechte, erster Wecker, Probewecker, Fertig. Beispieldaten. */

/** Beispiel mit dem Nacht-Check [check]; Health Connect wäre verfügbar, kein Probewecker läuft. */
private fun model(check: NightCheckState?) = OnboardingModel(
    check = check,
    health = HealthState.AVAILABLE,
    canCreateAlarm = true,
    testTarget = null,
    onFix = {},
    onConnectHealth = {},
    onCreateAlarm = {},
    onStartTest = {},
)

/** Seite [page] (0 = Willkommen … 5 = Fertig) der Einrichtung; Vorgabe: Mitteilungen fehlen noch, Vollbild ist erlaubt. */
@Composable
private fun Onboarding(mode: ThemeMode, page: Int, check: NightCheckState? = NightCheckState(listOf(Check.NOTIFICATIONS))) = Themed(mode) {
    OnboardingContent(page, onPage = {}, model(check), onDone = {})
}

@PreviewTest @Preview(locale = "de-rCH", name = "Einrichtung Willkommen hell", device = PHONE) @Composable
fun OnboardingWelcomeLight() = Onboarding(ThemeMode.LIGHT, 0)

@PreviewTest @Preview(locale = "de-rCH", name = "Einrichtung Willkommen dunkel", device = PHONE) @Composable
fun OnboardingWelcomeDark() = Onboarding(ThemeMode.DARK, 0)

@PreviewTest @Preview(locale = "de-rCH", name = "Einrichtung Uhr", device = PHONE) @Composable
fun OnboardingWatch() = Onboarding(ThemeMode.LIGHT, 1, NightCheckState(listOf(Check.WATCH_APP)))

@PreviewTest @Preview(locale = "de-rCH", name = "Einrichtung Rechte hell", device = PHONE) @Composable
fun OnboardingRightsLight() = Onboarding(ThemeMode.LIGHT, 2)

@PreviewTest @Preview(locale = "de-rCH", name = "Einrichtung Rechte dunkel", device = PHONE) @Composable
fun OnboardingRightsDark() = Onboarding(ThemeMode.DARK, 2)

@PreviewTest @Preview(locale = "de-rCH", name = "Einrichtung erster Wecker", device = PHONE) @Composable
fun OnboardingAlarm() = Onboarding(ThemeMode.LIGHT, 3)

@PreviewTest @Preview(locale = "de-rCH", name = "Einrichtung Probewecker", device = PHONE) @Composable
fun OnboardingTest() = Onboarding(ThemeMode.DARK, 4)

@PreviewTest @Preview(locale = "de-rCH", name = "Einrichtung fertig", device = PHONE) @Composable
fun OnboardingDone() = Onboarding(ThemeMode.LIGHT, 5)
