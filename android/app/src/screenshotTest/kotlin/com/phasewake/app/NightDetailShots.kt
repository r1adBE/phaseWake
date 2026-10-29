package com.phasewake.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.phasewake.app.core.HealthHistory
import com.phasewake.app.core.HealthSession
import com.phasewake.app.core.SleepNotes
import com.phasewake.app.core.SleepStage
import com.phasewake.app.core.StageSpan
import com.phasewake.app.health.HealthSource
import com.phasewake.app.ui.history.ChartCard
import com.phasewake.app.ui.history.HistoryScreen
import com.phasewake.app.ui.history.NightDetailActions
import com.phasewake.app.ui.history.NightDetailData
import com.phasewake.app.ui.history.NightDetailScreen
import com.phasewake.app.ui.history.StageHypnogram
import com.phasewake.app.ui.history.nightView
import com.phasewake.app.ui.theme.ThemeMode
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

// Detailseite einer Nacht (eigene und aus Samsung Health), die Liste mit Mini-Hypnogrammen und das Hypnogramm mit Blase.

private val zone = ZoneId.of("Europe/Zurich")

/** Phasen ab [from] in Zyklen; [minutes] je Phase, Wach nur zu Beginn und einmal kurz um die Mitte. */
private fun cycles(from: Instant, until: Instant): List<StageSpan> = buildList {
    val cycle = listOf(SleepStage.LIGHT to 25, SleepStage.DEEP to 50, SleepStage.LIGHT to 20, SleepStage.REM to 22)
    add(StageSpan(from, from.plus(Duration.ofMinutes(14)), SleepStage.AWAKE))
    var t = from.plus(Duration.ofMinutes(14))
    var k = 0
    while (t.isBefore(until)) {
        val (stage, base) = cycle[k % cycle.size]
        // Gegen Morgen weniger Tiefschlaf, mehr REM.
        val minutes = when (stage) {
            SleepStage.DEEP -> maxOf(12, base - k * 3)
            SleepStage.REM -> base + k * 2
            else -> base
        }
        val next = minOf(t.plus(Duration.ofMinutes(minutes.toLong())), until)
        add(StageSpan(t, next, if (k == 9) SleepStage.AWAKE else stage))
        t = next
        k++
    }
}

/** Eigene Nacht von heute Morgen ([nights] aus `Screens.kt`) mit Phasen über die ganze Nacht ab 22:40. */
private val ownNight = nights.last()
private val ownStages = cycles(ownNight.windowStart.minus(Duration.ofMinutes(400)), ownNight.wokeAt.plus(Duration.ofMinutes(3)))

/** Samsung-Health-Nacht zwei Nächte davor (ohne eigene Nacht an dem Datum). */
private val samsung = run {
    val end = ownNight.wokeAt.minus(Duration.ofDays(3)).plus(Duration.ofMinutes(40))
    val start = end.minus(Duration.ofMinutes(455))
    HealthSession(start, end, cycles(start, end), HealthSource.SAMSUNG_HEALTH)
}

private val items = HealthHistory.merge(listOf(ownNight), listOf(samsung), null, zone)

private val data = NightDetailData(
    items = items,
    stages = ownStages,
    notes = mapOf(SleepNotes.nightDate(ownNight, zone) to listOf(SleepNotes.CAFFEINE)),
)

@Composable
private fun Detail(mode: ThemeMode, key: String) = Themed(mode) { NightDetailScreen(data, key, NightDetailActions()) }

@PreviewTest @Preview(locale = "de-rCH", name = "Nacht Detail hell", device = PHONE_TALL) @Composable
fun NightDetailLight() = Detail(ThemeMode.LIGHT, items.first { it.night != null }.key)

@PreviewTest @Preview(locale = "de-rCH", name = "Nacht Detail dunkel", device = PHONE_TALL) @Composable
fun NightDetailDark() = Detail(ThemeMode.DARK, items.first { it.night != null }.key)

@PreviewTest @Preview(locale = "de-rCH", name = "Nacht Detail Samsung hell", device = PHONE_TALL) @Composable
fun NightDetailHealthLight() = Detail(ThemeMode.LIGHT, items.first { it.session != null }.key)

@PreviewTest @Preview(locale = "de-rCH", name = "Nacht Detail Samsung dunkel", device = PHONE_TALL) @Composable
fun NightDetailHealthDark() = Detail(ThemeMode.DARK, items.first { it.session != null }.key)

/** Hypnogramm mit gewählter Stelle: senkrechte Linie und Blase «Uhrzeit · Phase · seit …». */
@Composable
private fun Selected(mode: ThemeMode) = Themed(mode) {
    val view = nightView(items.first { it.night != null }, ownStages)
    val model = view.hypno ?: return@Themed
    ChartCard(stringResource(R.string.chart_stages_title), null, Modifier.fillMaxWidth().padding(16.dp)) {
        StageHypnogram(model, height = 240.dp, initialMinute = model.totalMinutes / 2)
    }
}

@PreviewTest @Preview(locale = "de-rCH", name = "Hypnogramm Blase hell", device = PHONE) @Composable
fun HypnogramBubbleLight() = Selected(ThemeMode.LIGHT)

@PreviewTest @Preview(locale = "de-rCH", name = "Hypnogramm Blase dunkel", device = PHONE) @Composable
fun HypnogramBubbleDark() = Selected(ThemeMode.DARK)

@Composable
private fun ListWithStages(mode: ThemeMode) = Themed(mode) {
    HistoryScreen(listOf(ownNight), PaddingValues(top = 24.dp, bottom = 96.dp), loadStages = { ownStages }, onExport = {}, healthConnected = true, items = items)
}

@PreviewTest @Preview(locale = "de-rCH", name = "Verlauf Liste Phasen hell", device = PHONE_TALL) @Composable
fun HistoryListStagesLight() = ListWithStages(ThemeMode.LIGHT)

@PreviewTest @Preview(locale = "de-rCH", name = "Verlauf Liste Phasen dunkel", device = PHONE_TALL) @Composable
fun HistoryListStagesDark() = ListWithStages(ThemeMode.DARK)
