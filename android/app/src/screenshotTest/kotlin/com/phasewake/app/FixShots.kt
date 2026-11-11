package com.phasewake.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Sunrise
import com.phasewake.app.ring.PhoneRingService
import com.phasewake.app.ring.RingScreen
import com.phasewake.app.ring.SunriseScreen
import com.phasewake.app.ui.home.HomeScreen
import com.phasewake.app.ui.home.NightCheckState
import com.phasewake.app.ui.onboarding.OnboardingContent
import com.phasewake.app.ui.onboarding.OnboardingModel
import com.phasewake.app.ui.settings.HealthState
import com.phasewake.app.ui.theme.ThemeMode

/**
 * Randfälle der Korrekturen vom 12.11.2026: Weckfenster einzeilig mit kleinem AM/PM (N-2), Schild hinter der Schrift
 * auf dem Sonnenaufgang (Gestaltung 5), Einrichtung mit Bild je Schritt (Gestaltung 2) und arabische Ziffern im
 * Uhrzeit-Wähler (N-5). Beispieldaten.
 */

@Composable
private fun Home(tag: String, mode: ThemeMode = ThemeMode.DARK, hour: Int = 7, label: String = "Arbeit") = ThemedIn(tag, mode) {
    HomeScreen(
        listOf(AlarmSpec(id = "work", hour = hour, minute = 0, days = weekdays, label = label)), nights.last(),
        check = NightCheckState(emptyList()), onFix = {}, contentPadding = PaddingValues(), onAdd = {},
        onEdit = {}, onToggle = { _, _ -> }, clock = { FIXED_NOW },
    )
}

@PreviewTest @Preview(locale = "sq", name = "Fix Start sq", device = PHONE) @Composable
fun FixHomeSq() = Home("sq", label = "Puna")

@PreviewTest @Preview(locale = "en-rUS", name = "Fix Start en klein Schrift 130", device = PHONE_SMALL, fontScale = 1.3f) @Composable
fun FixHomeEnSmallF13() = Home("en-US", ThemeMode.LIGHT, hour = 11, label = "Work")

@PreviewTest @Preview(locale = "ar", name = "Fix Start ar klein Schrift 130", device = PHONE_SMALL, fontScale = 1.3f) @Composable
fun FixHomeArSmallF13() = Home("ar", label = "عمل")

@Composable
private fun Ring(state: PhoneRingService.State, light: Float, tag: String = "de-CH") = ThemedIn(tag, ThemeMode.DARK) {
    RingScreen(state, onStop = {}, onSnooze = {}, light = light, clock = { FIXED_NOW })
}

private val ringing = PhoneRingService.State.Ringing(fromWatch = true, snoozesLeft = 3, sunrise = Sunrise.WINDOW)

@PreviewTest @Preview(locale = "de-rCH", name = "Fix Sonnenaufgang 30", device = PHONE) @Composable
fun FixRingSun030() = Ring(ringing, 0.30f)

@PreviewTest @Preview(locale = "de-rCH", name = "Fix Sonnenaufgang 50", device = PHONE) @Composable
fun FixRingSun050() = Ring(ringing, 0.50f)

@PreviewTest @Preview(locale = "de-rCH", name = "Fix Sonnenaufgang 64", device = PHONE) @Composable
fun FixRingSun064() = Ring(ringing, 0.64f)

@PreviewTest @Preview(locale = "de-rCH", name = "Fix Sonnenaufgang 90", device = PHONE) @Composable
fun FixRingSun090() = Ring(ringing, 0.90f)

@PreviewTest @Preview(locale = "de-rCH", name = "Fix Licht 62", device = PHONE) @Composable
fun FixSunrise062() = ThemedIn("de-CH", ThemeMode.DARK) {
    SunriseScreen(progress = 0.62f, now = FIXED_NOW, onOff = {})
}

private fun model() = OnboardingModel(
    check = NightCheckState(emptyList()),
    health = HealthState.CONNECTED,
    canCreateAlarm = true,
    testTarget = null,
    onFix = {},
    onConnectHealth = {},
    onCreateAlarm = {},
    onStartTest = {},
)

@PreviewTest @Preview(locale = "de-rCH", name = "Fix Einrichtung Bilder hell", widthDp = 2400, heightDp = 900) @Composable
fun FixOnboardingArtLight() = ThemedIn("de-CH", ThemeMode.LIGHT) {
    androidx.compose.foundation.layout.Row {
        for (page in listOf(0, 1, 2, 4, 5, 6)) {
            Box(Modifier.size(400.dp, 900.dp)) { OnboardingContent(page, onPage = {}, model(), onDone = {}) }
        }
    }
}

@PreviewTest @Preview(locale = "de-rCH", name = "Fix Einrichtung Willkommen dunkel klein", device = PHONE_SMALL) @Composable
fun FixOnboardingWelcomeDarkSmall() = ThemedIn("de-CH", ThemeMode.DARK) {
    OnboardingContent(0, onPage = {}, model(), onDone = {})
}

/** N-5: Uhrzeit-Wähler von Material auf Arabisch neben derselben Zahl als einfacher Text gleicher Grösse. */
@OptIn(ExperimentalMaterial3Api::class)
@PreviewTest @Preview(locale = "ar", name = "Fix Zeitwaehler ar", device = PHONE) @Composable
fun FixTimePickerAr() = ThemedIn("ar", ThemeMode.LIGHT) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("٠٧:٠٠ · 07:00", style = MaterialTheme.typography.displayLarge)
        Text("٠٧ ٠٠", style = MaterialTheme.typography.displayMedium)
        TimePicker(rememberTimePickerState(7, 0, is24Hour = false))
    }
}

/** Gestaltung 3 / N-7: «Daten» und «Über» als Listenzeilen mit Pfeil, mindestens 56 dp hoch. */
@PreviewTest @Preview(locale = "de-rCH", name = "Fix Einstellungen lang", device = "spec:width=411dp,height=3000dp") @Composable
fun FixSettingsTall() = ThemedIn("de-CH", ThemeMode.LIGHT) {
    com.phasewake.app.ui.settings.SettingsScreen(
        ThemeMode.LIGHT, "16.2.2", PaddingValues(), onThemeMode = {}, onExport = {}, onClearHistory = {},
        health = HealthState.CONNECTED, onConnectHealth = {},
        extras = com.phasewake.app.ui.settings.SettingsExtras(onOnboarding = {}, onLicenses = {}, diagnostics = true),
    )
}
