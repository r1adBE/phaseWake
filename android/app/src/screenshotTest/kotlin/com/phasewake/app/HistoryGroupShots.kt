package com.phasewake.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.phasewake.app.core.HealthHistory
import com.phasewake.app.core.HealthSession
import com.phasewake.app.core.NoteEffect
import com.phasewake.app.health.HealthSource
import com.phasewake.app.ui.history.HistoryScreen
import com.phasewake.app.ui.history.HistoryState
import com.phasewake.app.ui.history.weekKey
import com.phasewake.app.ui.history.yearKey
import com.phasewake.app.ui.theme.ThemeMode
import java.time.LocalDate
import java.time.ZoneId

// Verlauf mit vielen Nächten: die fünf neusten einzeln, ältere je Woche, älter als ein Jahr je Kalenderjahr.

private val zone = ZoneId.of("Europe/Zurich")
private val padding = PaddingValues(top = 24.dp, bottom = 96.dp)

/** Höher als [PHONE_TALL]: Unter den fünf einzelnen Nächten sollen alle Wochen- und Jahreszeilen sichtbar sein. */
private const val PHONE_LIST = "spec:width=411dp,height=2400dp"

/** Noch höher für die aufgeklappte Woche und das aufgeklappte Jahr. */
private const val PHONE_LIST_OPEN = "spec:width=411dp,height=4000dp"

/** Heute wie [FIXED_NOW]: Mittwoch 28.10.2026. */
private val today = LocalDate.of(2026, 10, 28)

/**
 * Morgen der Nächte (neueste zuerst): 27.–23.10. einzeln; die Woche ab 19.10. nur mit 22.–20.10., die Woche ab
 * 12.10., nach einer Pause die Woche ab 28.9.; älter als ein Jahr 2025 (zwei Wochen) und 2024.
 */
private val mornings = listOf(
    "2026-10-27", "2026-10-26", "2026-10-25", "2026-10-24", "2026-10-23", "2026-10-22", "2026-10-21", "2026-10-20",
    "2026-10-17", "2026-10-15", "2026-10-14", "2026-10-13", "2026-10-02", "2026-09-30", "2025-10-20", "2025-10-15",
    "2025-10-14", "2024-12-31", "2024-12-30",
).map(LocalDate::parse)

/** Samsung Health ohne Phasen: ins Bett 22:40–23:40, auf 06:10–07:20 (je Nacht etwas anders). */
private val sessions = mornings.mapIndexed { i, morning ->
    val start = morning.minusDays(1).atTime(22, 40).plusMinutes((i * 23L) % 60).atZone(zone).toInstant()
    val end = morning.atTime(6, 10).plusMinutes((i * 37L) % 70).atZone(zone).toInstant()
    HealthSession(start, end, source = HealthSource.SAMSUNG_HEALTH)
}

private val items = HealthHistory.merge(emptyList(), sessions, null, zone)

/** Befinden zu einigen Nächten (Nachtdatum = Abend). */
private val feelings = mornings.withIndex().filter { it.index % 3 != 1 }
    .associate { (i, morning) -> morning.minusDays(1) to (i % 5 + 1) }

@Composable
private fun Grouped(mode: ThemeMode, open: Set<String>) = Themed(mode) {
    HistoryScreen(
        HistoryState(
            emptyList(), loadStages = { emptyList() }, healthConnected = true, items = items,
            healthFeelings = feelings,
            asleep = NoteEffect.asleepByNight(HealthHistory.summaries(sessions, null, zone), zone),
            today = today, openGroups = open,
        ),
        padding,
    )
}

/** Aufgeklappt: die Woche ab 12.10.2026, das Jahr 2025 und darin die Woche ab 13.10.2025. */
private val opened = setOf(
    weekKey(LocalDate.of(2026, 10, 12)), yearKey(2025), weekKey(LocalDate.of(2025, 10, 13), 2025),
)

@PreviewTest @Preview(locale = "de-rCH", name = "Verlauf gruppiert hell", device = PHONE_LIST) @Composable
fun HistoryGroupedLight() = Grouped(ThemeMode.LIGHT, emptySet())

@PreviewTest @Preview(locale = "de-rCH", name = "Verlauf gruppiert dunkel", device = PHONE_LIST) @Composable
fun HistoryGroupedDark() = Grouped(ThemeMode.DARK, emptySet())

@PreviewTest @Preview(locale = "de-rCH", name = "Verlauf aufgeklappt hell", device = PHONE_LIST_OPEN) @Composable
fun HistoryGroupedOpenLight() = Grouped(ThemeMode.LIGHT, opened)

@PreviewTest @Preview(locale = "de-rCH", name = "Verlauf aufgeklappt dunkel", device = PHONE_LIST_OPEN) @Composable
fun HistoryGroupedOpenDark() = Grouped(ThemeMode.DARK, opened)
