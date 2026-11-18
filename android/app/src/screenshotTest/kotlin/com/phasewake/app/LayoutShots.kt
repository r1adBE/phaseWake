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
import com.phasewake.app.ui.home.HomeActions
import com.phasewake.app.ui.home.HomeScreen
import com.phasewake.app.ui.home.HomeState
import com.phasewake.app.ui.home.NightCheckState
import com.phasewake.app.ui.onboarding.OnboardingContent
import com.phasewake.app.ui.onboarding.OnboardingModel
import com.phasewake.app.ui.settings.HealthState
import com.phasewake.app.ui.theme.ThemeMode

/**
 * Layout bei Sprache, Schriftgrösse und Bildschirmgrösse: Weckfenster einzeilig mit kleinem AM/PM, Schild
 * hinter der Schrift auf dem Sonnenaufgang, Einrichtung mit Bild je Schritt und arabische Ziffern im
 * Uhrzeit-Wähler. Beispieldaten.
 */

@Composable
private fun Home(tag: String, mode: ThemeMode = ThemeMode.DARK, hour: Int = 7, label: String = "Arbeit") = ThemedIn(
    tag, mode,
) {
    HomeScreen(
        HomeState(
            listOf(AlarmSpec(id = "work", hour = hour, minute = 0, days = weekdays, label = label)), nights.last(),
            check = NightCheckState(emptyList()),
        ),
        HomeActions(onFix = {}, onAdd = {}, onEdit = {}, onToggle = { _, _ -> }), contentPadding = PaddingValues(),
        clock = { FIXED_NOW },
    )
}

@PreviewTest @Preview(locale = "sq", name = "Layout Start sq", device = PHONE) @Composable
fun LayoutHomeSq() = Home("sq", label = "Puna")

@PreviewTest @Composable
@Preview(locale = "en-rUS", name = "Layout Start en klein Schrift 130", device = PHONE_SMALL, fontScale = 1.3f)
fun LayoutHomeEnSmallF13() = Home("en-US", ThemeMode.LIGHT, hour = 11, label = "Work")

@PreviewTest @Composable
@Preview(locale = "ar", name = "Layout Start ar klein Schrift 130", device = PHONE_SMALL, fontScale = 1.3f)
fun LayoutHomeArSmallF13() = Home("ar", label = "عمل")

@Composable
private fun Ring(state: PhoneRingService.State, light: Float, tag: String = "de-CH") = ThemedIn(tag, ThemeMode.DARK) {
    RingScreen(
        state, onStop = {}, onSnooze = {}, light = light, clock = { FIXED_NOW }, random = kotlin.random.Random(7),
    )
}

private val ringing = PhoneRingService.State.Ringing(fromWatch = true, snoozesLeft = 3, sunrise = Sunrise.WINDOW)

@PreviewTest @Preview(locale = "de-rCH", name = "Layout Sonnenaufgang 30", device = PHONE) @Composable
fun LayoutRingSun030() = Ring(ringing, 0.30f)

@PreviewTest @Preview(locale = "de-rCH", name = "Layout Sonnenaufgang 50", device = PHONE) @Composable
fun LayoutRingSun050() = Ring(ringing, 0.50f)

@PreviewTest @Preview(locale = "de-rCH", name = "Layout Sonnenaufgang 64", device = PHONE) @Composable
fun LayoutRingSun064() = Ring(ringing, 0.64f)

@PreviewTest @Preview(locale = "de-rCH", name = "Layout Sonnenaufgang 90", device = PHONE) @Composable
fun LayoutRingSun090() = Ring(ringing, 0.90f)

@PreviewTest @Preview(locale = "de-rCH", name = "Layout Licht 62", device = PHONE) @Composable
fun LayoutSunrise062() = ThemedIn("de-CH", ThemeMode.DARK) {
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

@PreviewTest @Composable
@Preview(locale = "de-rCH", name = "Layout Einrichtung Bilder hell", widthDp = 2400, heightDp = 900)
fun LayoutOnboardingArtLight() = ThemedIn("de-CH", ThemeMode.LIGHT) {
    androidx.compose.foundation.layout.Row {
        for (page in listOf(0, 1, 2, 4, 5, 6)) {
            Box(Modifier.size(400.dp, 900.dp)) { OnboardingContent(page, onPage = {}, model(), onDone = {}) }
        }
    }
}

@PreviewTest @Composable
@Preview(locale = "de-rCH", name = "Layout Einrichtung Willkommen dunkel klein", device = PHONE_SMALL)
fun LayoutOnboardingWelcomeDarkSmall() = ThemedIn("de-CH", ThemeMode.DARK) {
    OnboardingContent(0, onPage = {}, model(), onDone = {})
}

/** Uhrzeit-Wähler von Material auf Arabisch neben derselben Zahl als einfacher Text gleicher Grösse. */
@OptIn(ExperimentalMaterial3Api::class)
@PreviewTest @Preview(locale = "ar", name = "Layout Zeitwaehler ar", device = PHONE) @Composable
fun LayoutTimePickerAr() = ThemedIn("ar", ThemeMode.LIGHT) {
    Column(
        Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("٠٧:٠٠ · 07:00", style = MaterialTheme.typography.displayLarge)
        Text("٠٧ ٠٠", style = MaterialTheme.typography.displayMedium)
        TimePicker(rememberTimePickerState(7, 0, is24Hour = false))
    }
}

/** «Daten» und «Über» als Listenzeilen mit Pfeil, mindestens 56 dp hoch. */
@PreviewTest @Composable
@Preview(locale = "de-rCH", name = "Layout Einstellungen lang", device = "spec:width=411dp,height=3000dp")
fun LayoutSettingsTall() = ThemedIn("de-CH", ThemeMode.LIGHT) {
    com.phasewake.app.ui.settings.SettingsScreen(
        com.phasewake.app.ui.settings.SettingsState(ThemeMode.LIGHT, "16.2.2", HealthState.CONNECTED),
        com.phasewake.app.ui.settings.SettingsActions(
            onThemeMode = {}, onExport = {}, onClearHistory = {}, onConnectHealth = {},
        ),
        PaddingValues(),
        extras = com.phasewake.app.ui.settings.SettingsExtras(onOnboarding = {}, onLicenses = {}, diagnostics = true),
    )
}

/** «Abends dunkel» gilt gerade (hier bei «Hell»): Hinweis «Gilt jetzt – hell ab …» unter dem Schalter. */
@PreviewTest @Composable
@Preview(locale = "de-rCH", name = "Layout Abends dunkel gilt", device = "spec:width=411dp,height=320dp")
fun LayoutEveningDarkNow() {
    java.util.Locale.setDefault(java.util.Locale.forLanguageTag("de-CH"))
    com.phasewake.app.ui.theme.PhaseWakeTheme(ThemeMode.LIGHT, eveningDark = true) {
        androidx.compose.material3.Surface {
            Column(Modifier.padding(16.dp)) {
                com.phasewake.app.ui.settings.EveningDarkRows(
                    com.phasewake.app.ui.theme.EveningDarkSetting(), onEnabled = {}, onFrom = {}, onUntil = {},
                )
            }
        }
    }
}
