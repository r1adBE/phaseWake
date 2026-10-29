package com.phasewake.app.wear

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.WakeReason
import androidx.wear.compose.material3.AppScaffold
import com.phasewake.app.wear.ui.EditorContent
import com.phasewake.app.wear.ui.HomeContent
import com.phasewake.app.wear.ui.RingScreen
import com.phasewake.app.wear.ui.theme.PhaseWakeTheme
import com.phasewake.app.wear.wake.RingUi
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * Bildschirmfotos der Uhr-Bildschirme. Beispieldaten, keine echten. Powernap, Diagnose und «Überspringen» stehen in
 * `ExtrasShots.kt`; die gemeinsamen Helfer hier sind darum `internal`.
 */

internal val zone: ZoneId = ZoneId.of("Europe/Zurich")
internal val now: Instant = Instant.parse("2026-10-27T21:15:00Z")
internal val alarms = listOf(
    AlarmSpec(id = "work", hour = 7, minute = 0, days = DayOfWeek.entries.toSet(), label = "Arbeit"),
    AlarmSpec(id = "weekend", hour = 9, minute = 30, days = setOf(DayOfWeek.SATURDAY), enabled = false),
)
private val sleepAlarms = listOf(AlarmSpec(id = "sleep", hour = 7, minute = 0, days = DayOfWeek.entries.toSet(), sleepDurationMinutes = 450))

@Composable
private fun Home(list: List<AlarmSpec>, goodNight: Boolean = false) = Swiss {
    // In der App liegt der AppScaffold um alle Bildschirme (Uhrzeit oben).
    AppScaffold {
        HomeContent(
            alarms = list,
            plan = AlarmSchedule.earliestPlan(list, now, zone),
            zone = zone,
            goodNightActive = goodNight,
            permissionsMissing = false,
            dndBlocks = false,
            onGoodNight = {},
            onRequestPermissions = {},
            onOpenDnd = {},
            onToggle = { _, _ -> },
        )
    }
}

@PreviewTest @Preview(locale = "de-rCH", name = "Uhr Start", device = WATCH, showBackground = true, backgroundColor = 0xFF000000) @Composable
fun WatchHome() = Home(alarms)

@PreviewTest @Preview(locale = "de-rCH", name = "Uhr Gute Nacht", device = WATCH, showBackground = true, backgroundColor = 0xFF000000) @Composable
fun WatchGoodNight() = Home(sleepAlarms)

@PreviewTest @Preview(locale = "de-rCH", name = "Uhr ohne Wecker", device = WATCH, showBackground = true, backgroundColor = 0xFF000000) @Composable
fun WatchEmpty() = Home(emptyList())

@PreviewTest @Preview(locale = "de-rCH", name = "Uhr Wecker bearbeiten", device = WATCH, showBackground = true, backgroundColor = 0xFF000000) @Composable
fun WatchEditor() = Swiss {
    AppScaffold {
        EditorContent(draft = alarms.first(), isNew = false, onPickTime = {}, onChange = {}, onSave = {}, onDelete = {})
    }
}

@PreviewTest @Preview(locale = "de-rCH", name = "Uhr Wecken", device = WATCH, showBackground = true, backgroundColor = 0xFF000000) @Composable
fun WatchRing() = Swiss {
    RingScreen(RingUi.Ringing("", WakeReason.LIGHT_SLEEP, now, snoozesLeft = 3), onDismiss = {}, onSnooze = {})
}

@PreviewTest @Preview(locale = "de-rCH", name = "Uhr Schlummern", device = WATCH, showBackground = true, backgroundColor = 0xFF000000) @Composable
fun WatchSnoozed() = Swiss {
    RingScreen(RingUi.Snoozed("", now.plusSeconds(300), snoozesLeft = 2), onDismiss = {}, onSnooze = {})
}

/** Auf dem Gerät folgt die Standard-Sprache der App-Sprache; im Renderer muss man sie setzen. */
@Composable
internal fun Swiss(content: @Composable () -> Unit) {
    java.util.Locale.setDefault(java.util.Locale.forLanguageTag("de-CH"))
    PhaseWakeTheme(content)
}

internal const val WATCH = "id:wearos_large_round"
