package com.phasewake.app.wear

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.phasewake.app.core.Sunrise
import com.phasewake.app.core.WakeReason
import com.phasewake.app.wear.ui.RingScreen
import com.phasewake.app.wear.ui.theme.PhaseWakeTheme
import com.phasewake.app.wear.wake.RingUi

/**
 * Randfälle aus dem UI-Audit (11.11.2026): grosse Systemschrift auf kleiner und grosser runder Uhr, Hinweis der
 * Weckaufgabe in langen Sprachen, Arabisch und der Sonnenaufgang im Farbwechsel. Beispieldaten.
 */

@Composable
private fun RingIn(tag: String, ui: RingUi, light: Float? = null) {
    java.util.Locale.setDefault(java.util.Locale.forLanguageTag(tag))
    PhaseWakeTheme { RingScreen(ui, onDismiss = {}, onSnooze = {}, light = light) }
}

private val ringing = RingUi.Ringing("", WakeReason.LIGHT_SLEEP, now, snoozesLeft = 3)
private val task = RingUi.Ringing("", WakeReason.SHORT_NIGHT, now, snoozesLeft = 3, taskOnPhone = true)

@PreviewTest @Preview(locale = "de-rCH", name = "Rand Uhr Wecken klein Schrift 130", device = WATCH_SMALL, fontScale = 1.3f, showBackground = true, backgroundColor = 0xFF000000) @Composable
fun EdgeWatchRingSmallF13() = RingIn("de-CH", ringing)

@PreviewTest @Preview(locale = "de-rCH", name = "Rand Uhr Wecken gross Schrift 130", device = WATCH, fontScale = 1.3f, showBackground = true, backgroundColor = 0xFF000000) @Composable
fun EdgeWatchRingLargeF13() = RingIn("de-CH", ringing)

@PreviewTest @Preview(locale = "fr", name = "Rand Uhr Aufgabe klein fr", device = WATCH_SMALL, showBackground = true, backgroundColor = 0xFF000000) @Composable
fun EdgeWatchTaskSmallFr() = RingIn("fr", task)

@PreviewTest @Preview(locale = "ru", name = "Rand Uhr Aufgabe klein ru", device = WATCH_SMALL, showBackground = true, backgroundColor = 0xFF000000) @Composable
fun EdgeWatchTaskSmallRu() = RingIn("ru", task)

@PreviewTest @Preview(locale = "de-rCH", name = "Rand Uhr Aufgabe klein Schrift 130", device = WATCH_SMALL, fontScale = 1.3f, showBackground = true, backgroundColor = 0xFF000000) @Composable
fun EdgeWatchTaskSmallF13() = RingIn("de-CH", task)

@PreviewTest @Preview(locale = "ar", name = "Rand Uhr Wecken klein ar", device = WATCH_SMALL, showBackground = true, backgroundColor = 0xFF000000) @Composable
fun EdgeWatchRingSmallAr() = RingIn("ar", ringing)

@PreviewTest @Preview(locale = "de-rCH", name = "Rand Uhr Sonnenaufgang 62", device = WATCH_SMALL, showBackground = true, backgroundColor = 0xFF000000) @Composable
fun EdgeWatchRingSun062() = RingIn("de-CH", ringing.copy(sunrise = Sunrise.WINDOW), light = 0.62f)

@PreviewTest @Preview(locale = "ru", name = "Rand Uhr Schlummern klein ru Schrift 130", device = WATCH_SMALL, fontScale = 1.3f, showBackground = true, backgroundColor = 0xFF000000) @Composable
fun EdgeWatchSnoozedRuF13() = RingIn("ru", RingUi.Snoozed("", now.plusSeconds(300), snoozesLeft = 2))
