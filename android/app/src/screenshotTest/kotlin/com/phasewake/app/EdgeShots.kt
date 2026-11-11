package com.phasewake.app

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Sunrise
import com.phasewake.app.core.WakeTask
import com.phasewake.app.ring.PhoneRingService
import com.phasewake.app.ring.RingScreen
import com.phasewake.app.ui.editor.EditorScreen
import com.phasewake.app.ui.home.Check
import com.phasewake.app.ui.home.HomeScreen
import com.phasewake.app.ui.home.NightCheckState
import com.phasewake.app.ui.onboarding.OnboardingContent
import com.phasewake.app.ui.onboarding.OnboardingModel
import com.phasewake.app.ui.settings.HealthState
import com.phasewake.app.ui.theme.PhaseWakeTheme
import com.phasewake.app.ui.theme.ThemeMode

/**
 * Randfälle aus dem UI-Audit (11.11.2026): Querformat, kleines Handy (360 × 640), grosse Schrift, lange Sprachen,
 * Arabisch und der Sonnenaufgang mitten im Farbwechsel. Beispieldaten.
 */

internal const val PHONE_LAND = "spec:width=891dp,height=411dp"
internal const val PHONE_SMALL = "spec:width=360dp,height=640dp"
internal const val PHONE_SMALL_TALL = "spec:width=360dp,height=1700dp"

/** Wie [Themed], aber mit der Standard-Sprache [tag] (Zeiten, Ziffern). */
@Composable
internal fun ThemedIn(tag: String, mode: ThemeMode, content: @Composable () -> Unit) {
    java.util.Locale.setDefault(java.util.Locale.forLanguageTag(tag))
    PhaseWakeTheme(mode) { Surface { content() } }
}

private val ringing = PhoneRingService.State.Ringing(fromWatch = true, snoozesLeft = 3)
private val ringingMath = PhoneRingService.State.Ringing(fromWatch = true, snoozesLeft = 3, wakeTask = WakeTask.MATH)

@Composable
private fun Ring(state: PhoneRingService.State, light: Float? = null, tag: String = "de-CH") = ThemedIn(tag, ThemeMode.DARK) {
    RingScreen(state, onStop = {}, onSnooze = {}, light = light, clock = { FIXED_NOW })
}

@PreviewTest @Preview(locale = "de-rCH", name = "Rand Wecken quer", device = PHONE_LAND) @Composable
fun EdgeRingLand() = Ring(ringing)

@PreviewTest @Preview(locale = "de-rCH", name = "Rand Aufgabe quer", device = PHONE_LAND) @Composable
fun EdgeMathLand() = Ring(ringingMath)

@PreviewTest @Preview(locale = "de-rCH", name = "Rand Wecken klein", device = PHONE_SMALL) @Composable
fun EdgeRingSmall() = Ring(ringing)

@PreviewTest @Preview(locale = "de-rCH", name = "Rand Aufgabe klein", device = PHONE_SMALL) @Composable
fun EdgeMathSmall() = Ring(ringingMath)

@PreviewTest @Preview(locale = "de-rCH", name = "Rand Aufgabe klein Schrift 130", device = PHONE_SMALL, fontScale = 1.3f) @Composable
fun EdgeMathSmallF13() = Ring(ringingMath)

@PreviewTest @Preview(locale = "ru", name = "Rand Wecken ru Schrift 2", device = PHONE, fontScale = 2f) @Composable
fun EdgeRingRuF2() = Ring(ringing, tag = "ru")

@PreviewTest @Preview(locale = "fr", name = "Rand Aufgabe fr Schrift 130 quer", device = PHONE_LAND, fontScale = 1.3f) @Composable
fun EdgeMathFrLandF13() = Ring(ringingMath, tag = "fr")

@PreviewTest @Preview(locale = "de-rCH", name = "Rand Sonnenaufgang 62", device = PHONE) @Composable
fun EdgeRingSun062() = Ring(ringing.copy(sunrise = Sunrise.WINDOW), light = 0.62f)

@PreviewTest @Preview(locale = "de-rCH", name = "Rand Sonnenaufgang 55 Aufgabe", device = PHONE) @Composable
fun EdgeMathSun055() = Ring(ringingMath.copy(sunrise = Sunrise.WINDOW), light = 0.55f)

@PreviewTest @Preview(locale = "de-rCH", name = "Rand Sonnenaufgang 68 Schlummern", device = PHONE) @Composable
fun EdgeSnoozedSun068() = Ring(PhoneRingService.State.Snoozed(snoozesLeft = 2, sunrise = Sunrise.WINDOW), light = 0.68f)

@PreviewTest @Preview(locale = "fr", name = "Rand Editor fr klein", device = PHONE_SMALL_TALL) @Composable
fun EdgeEditorFr() = ThemedIn("fr", ThemeMode.LIGHT) {
    EditorScreen(AlarmSpec(id = "work", hour = 7, minute = 0, days = weekdays, label = "Travail"), advice = null, onBack = {}, onSave = {}, onDelete = {})
}

@PreviewTest @Preview(locale = "ar", name = "Rand Start ar", device = PHONE) @Composable
fun EdgeHomeAr() = ThemedIn("ar", ThemeMode.DARK) {
    HomeScreen(
        listOf(AlarmSpec(id = "work", hour = 7, minute = 0, days = weekdays, label = "Arbeit")), nights.last(),
        check = NightCheckState(emptyList()), onFix = {}, contentPadding = androidx.compose.foundation.layout.PaddingValues(), onAdd = {},
        onEdit = {}, onToggle = { _, _ -> }, clock = { FIXED_NOW },
    )
}

private fun onboardingModel(failed: List<Check>) = OnboardingModel(
    check = NightCheckState(failed),
    health = HealthState.CONNECTED,
    canCreateAlarm = true,
    testTarget = null,
    onFix = {},
    onConnectHealth = {},
    onCreateAlarm = {},
    onStartTest = {},
)

@PreviewTest @Preview(locale = "de-rCH", name = "Rand Einrichtung Rechte", device = PHONE) @Composable
fun EdgeOnboardingRights() = ThemedIn("de-CH", ThemeMode.LIGHT) {
    OnboardingContent(2, onPage = {}, onboardingModel(listOf(Check.NOTIFICATIONS)), onDone = {})
}

@PreviewTest @Preview(locale = "de-rCH", name = "Rand Einrichtung Wecker klein", device = PHONE_SMALL) @Composable
fun EdgeOnboardingAlarmSmall() = ThemedIn("de-CH", ThemeMode.DARK) {
    OnboardingContent(3, onPage = {}, onboardingModel(emptyList()), onDone = {})
}
