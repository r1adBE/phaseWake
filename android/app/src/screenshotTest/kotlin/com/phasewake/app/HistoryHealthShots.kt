package com.phasewake.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.phasewake.app.core.HealthHistory
import com.phasewake.app.core.HealthSession
import com.phasewake.app.core.SleepStage
import com.phasewake.app.core.StageSpan
import com.phasewake.app.health.HealthSource
import com.phasewake.app.ui.history.HistoryScreen
import com.phasewake.app.ui.theme.ThemeMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Verlauf mit Nächten aus Health Connect (ohne Phase-Wake-Wecker) zwischen den eigenen Nächten und die leeren Zustände.

private val zone = ZoneId.of("Europe/Zurich")
private val padding = PaddingValues(top = 24.dp, bottom = 96.dp)

private fun at(date: LocalDate, hour: Int, minute: Int): Instant = date.atTime(hour, minute).atZone(zone).toInstant()

/** Eigene Nächte von heute und vorgestern; die Nacht dazwischen kommt aus Samsung Health. */
private val ownNights = listOf(nights.first(), nights.last())

/** Samsung Health, 26./27.10.: Zyklen aus Leicht, Tief, REM, einmal kurz wach. */
private val samsung = run {
    val start = at(LocalDate.of(2026, 10, 26), 23, 5)
    val end = at(LocalDate.of(2026, 10, 27), 6, 55)
    val cycle = listOf(SleepStage.AWAKE to 15, SleepStage.LIGHT to 40, SleepStage.DEEP to 50, SleepStage.LIGHT to 25, SleepStage.REM to 25)
    val spans = buildList {
        var t = start
        var k = 0
        while (t.isBefore(end)) {
            val (stage, minutes) = cycle[k % cycle.size]
            val next = minOf(t.plus(Duration.ofMinutes(minutes.toLong())), end)
            add(StageSpan(t, next, if (stage == SleepStage.AWAKE && k != 0 && k != 10) SleepStage.LIGHT else stage))
            t = next
            k++
        }
    }
    HealthSession(start, end, spans, HealthSource.SAMSUNG_HEALTH)
}

/** Unbekannte App ohne Phasen, Nacht der Zeitumstellung (24./25.10.). */
private val unknown = HealthSession(at(LocalDate.of(2026, 10, 24), 23, 40), at(LocalDate.of(2026, 10, 25), 8, 10), source = "com.example.sleep")

/** Nickerchen am Nachmittag (kurz und ganz zwischen 09:00 und 19:00): zählt nicht. */
private val nap = HealthSession(at(LocalDate.of(2026, 10, 25), 14, 0), at(LocalDate.of(2026, 10, 25), 14, 40), source = HealthSource.SAMSUNG_HEALTH)

private val mixed = HealthHistory.merge(ownNights, listOf(samsung, unknown, nap), null, zone)

@Composable
private fun Mixed(mode: ThemeMode) = Themed(mode) {
    // Samsung-Nacht mit Befinden, die unbekannte App noch ohne (Auswahl sichtbar).
    HistoryScreen(
        ownNights, padding, loadStages = { emptyList() }, onExport = {}, healthConnected = true, items = mixed,
        healthFeelings = mapOf(LocalDate.of(2026, 10, 26) to 4),
    )
}

@PreviewTest @Preview(locale = "de-rCH", name = "Verlauf gemischt hell", device = PHONE_TALL) @Composable
fun HistoryMixedLight() = Mixed(ThemeMode.LIGHT)

@PreviewTest @Preview(locale = "de-rCH", name = "Verlauf gemischt dunkel", device = PHONE_TALL) @Composable
fun HistoryMixedDark() = Mixed(ThemeMode.DARK)

@PreviewTest @Preview(locale = "de-rCH", name = "Verlauf leer ohne Freigabe hell", device = PHONE) @Composable
fun HistoryEmptyConnectLight() = Themed(ThemeMode.LIGHT) {
    HistoryScreen(emptyList(), padding, loadStages = null, onExport = {}, healthConnected = false, canConnectHealth = true)
}

@PreviewTest @Preview(locale = "de-rCH", name = "Verlauf leer ohne Daten dunkel", device = PHONE) @Composable
fun HistoryEmptyNoDataDark() = Themed(ThemeMode.DARK) {
    HistoryScreen(emptyList(), padding, loadStages = null, onExport = {}, healthConnected = true)
}
