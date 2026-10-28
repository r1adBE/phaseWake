package com.phasewake.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Epoch
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.PhoneAlert
import com.phasewake.app.core.SleepStage
import com.phasewake.app.core.StageSpan
import com.phasewake.app.core.WakeReason
import com.phasewake.app.core.WindowAdvice
import com.phasewake.app.ring.PhoneRingService
import com.phasewake.app.ring.RingScreen
import com.phasewake.app.ui.editor.EditorScreen
import com.phasewake.app.ui.history.HistoryScreen
import com.phasewake.app.ui.home.HomeScreen
import com.phasewake.app.ui.settings.HealthState
import com.phasewake.app.ui.settings.SettingsScreen
import com.phasewake.app.ui.theme.PhaseWakeTheme
import com.phasewake.app.ui.theme.ThemeMode
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant

/** Bildschirmfotos aller Handy-Bildschirme, hell und dunkel. Beispieldaten, keine echten. */

private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

private val alarms = listOf(
    AlarmSpec(id = "work", hour = 7, minute = 0, days = weekdays, label = "Arbeit"),
    AlarmSpec(id = "weekend", hour = 9, minute = 30, days = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), enabled = false, phone = PhoneAlert.VIBRATE, sleepDurationMinutes = 450),
)

private fun night(daysAgo: Long, wokeMinute: Long, reason: WakeReason): NightRecord {
    val start = Instant.parse("2026-10-27T05:30:00Z").minus(Duration.ofDays(daysAgo))
    val epochs = List((wokeMinute * 2).toInt()) { i ->
        val m = when {
            i % 9 == 4 -> 0.0015
            i >= wokeMinute * 2 - 3 -> 0.004 + (i % 3) * 0.002
            else -> 0.0002
        }
        Epoch(m, 52.0 + (i % 5))
    }
    return NightRecord("work", start, start.plus(Duration.ofMinutes(30)), start.plus(Duration.ofMinutes(wokeMinute)), reason, if (daysAgo == 2L) 1 else 0, epochs)
}

private val nights = listOf(night(2, 30, WakeReason.DEADLINE), night(1, 22, WakeReason.AWAKE), night(0, 11, WakeReason.LIGHT_SLEEP))

private val stages = nights.last().let { n ->
    listOf(
        StageSpan(n.windowStart, n.windowStart.plusSeconds(360), SleepStage.DEEP),
        StageSpan(n.windowStart.plusSeconds(360), n.windowStart.plusSeconds(900), SleepStage.REM),
        StageSpan(n.windowStart.plusSeconds(900), n.target, SleepStage.LIGHT),
    )
}

private val padding = PaddingValues(top = 24.dp, bottom = 96.dp)

@Composable
private fun Themed(mode: ThemeMode, content: @Composable () -> Unit) {
    // Auf dem Gerät folgt die Standard-Sprache der App-Sprache; im Renderer muss man sie setzen.
    java.util.Locale.setDefault(java.util.Locale.forLanguageTag("de-CH"))
    PhaseWakeTheme(mode) { Surface { content() } }
}

@Composable
private fun Home(mode: ThemeMode, list: List<AlarmSpec>) = Themed(mode) {
    HomeScreen(list, nights.last(), needsPermission = false, dndBlocks = false, onOpenDnd = {}, contentPadding = padding, onRequestPermission = {}, onAdd = {}, onEdit = {}, onToggle = { _, _ -> })
}

@PreviewTest @Preview(locale = "de-rCH", name = "Startseite hell", device = PHONE) @Composable
fun HomeLight() = Home(ThemeMode.LIGHT, alarms)

@PreviewTest @Preview(locale = "de-rCH", name = "Startseite dunkel", device = PHONE) @Composable
fun HomeDark() = Home(ThemeMode.DARK, alarms)

@PreviewTest @Preview(locale = "de-rCH", name = "Startseite leer hell", device = PHONE) @Composable
fun HomeEmptyLight() = Home(ThemeMode.LIGHT, emptyList())

@PreviewTest @Preview(locale = "de-rCH", name = "Startseite leer dunkel", device = PHONE) @Composable
fun HomeEmptyDark() = Home(ThemeMode.DARK, emptyList())

@PreviewTest @Preview(locale = "de-rCH", name = "Editor hell", device = PHONE_TALL) @Composable
fun EditorLight() = Themed(ThemeMode.LIGHT) { EditorScreen(alarms.first(), advice = WindowAdvice.Suggestion(30, 4, 6), onBack = {}, onSave = {}, onDelete = {}) }

@PreviewTest @Preview(locale = "de-rCH", name = "Editor dunkel", device = PHONE_TALL) @Composable
fun EditorDark() = Themed(ThemeMode.DARK) { EditorScreen(alarms.first(), advice = WindowAdvice.Suggestion(30, 4, 6), onBack = {}, onSave = {}, onDelete = {}) }

@PreviewTest @Preview(locale = "de-rCH", name = "Verlauf hell", device = PHONE) @Composable
fun HistoryLight() = Themed(ThemeMode.LIGHT) { HistoryScreen(nights, padding, loadStages = { stages }, onExport = {}) }

@PreviewTest @Preview(locale = "de-rCH", name = "Verlauf dunkel", device = PHONE) @Composable
fun HistoryDark() = Themed(ThemeMode.DARK) { HistoryScreen(nights, padding, loadStages = { stages }, onExport = {}) }

@PreviewTest @Preview(locale = "de-rCH", name = "Einstellungen hell", device = PHONE) @Composable
fun SettingsLight() = Themed(ThemeMode.LIGHT) {
    SettingsScreen(ThemeMode.LIGHT, "1.0", padding, onThemeMode = {}, onExport = {}, onClearHistory = {}, health = HealthState.CONNECTED, onConnectHealth = {})
}

@PreviewTest @Preview(locale = "de-rCH", name = "Einstellungen dunkel", device = PHONE) @Composable
fun SettingsDark() = Themed(ThemeMode.DARK) {
    SettingsScreen(ThemeMode.DARK, "1.0", padding, onThemeMode = {}, onExport = {}, onClearHistory = {}, health = HealthState.AVAILABLE, onConnectHealth = {})
}

@PreviewTest @Preview(locale = "de-rCH", name = "Wecken Handy", device = PHONE) @Composable
fun RingPhone() = Themed(ThemeMode.DARK) {
    RingScreen(PhoneRingService.State.Ringing(fromWatch = true, snoozesLeft = 3), onStop = {}, onSnooze = {})
}

private const val PHONE = "spec:width=411dp,height=891dp"
private const val PHONE_TALL = "spec:width=411dp,height=1600dp"
