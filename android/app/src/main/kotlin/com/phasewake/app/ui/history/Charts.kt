package com.phasewake.app.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.ChartData
import com.phasewake.app.core.CustomNote
import com.phasewake.app.core.Durations
import com.phasewake.app.core.FeelingSeries
import com.phasewake.app.core.HealthFeeling
import com.phasewake.app.core.Hypnogram
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.NightStages
import com.phasewake.app.core.SleepBars
import com.phasewake.app.core.SleepNotes
import com.phasewake.app.core.SleepSummary
import com.phasewake.app.core.StageSpan
import com.phasewake.app.core.StageStats
import com.phasewake.app.core.WakeMoments
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Trends-Zeilen, die ein Diagramm schon zeigt (dort weggelassen). */
enum class TrendLine { ASLEEP, GOAL, REGULAR, FEELING }

/**
 * Daten der Diagramme im Verlauf je Zeitraum in Nächten ([ChartData.PERIODS]); ganz ohne Daten = Diagramm verborgen,
 * ohne Daten nur im gewählten Zeitraum = Karte mit «Keine Daten».
 *
 * @property hypnogramNight Die Nacht zum Schlafprofil (für Datum und Abgleich).
 * @property hypnogramStages Die Phasen dieser Nacht als Blöcke ([StageStats.forNight]).
 */
data class HistoryCharts(
    val bars: Map<Int, SleepBars> = emptyMap(),
    val durations: Map<Int, Durations> = emptyMap(),
    val wake: Map<Int, WakeMoments> = emptyMap(),
    val hypnogram: Hypnogram? = null,
    val hypnogramNight: NightRecord? = null,
    val hypnogramStages: NightStages? = null,
    val feeling: Map<Int, FeelingSeries> = emptyMap(),
) {
    /** Ob ein Diagramm vom Zeitraum abhängt (dann die Auswahl 7 / 14 / 30 zeigen). */
    val hasPeriods: Boolean get() = bars.isNotEmpty() || durations.isNotEmpty() || wake.isNotEmpty() || feeling.isNotEmpty()

    val isEmpty: Boolean get() = bars.isEmpty() && durations.isEmpty() && wake.isEmpty() && hypnogram == null && feeling.isEmpty()

    /** Was die Trends-Karte nicht mehr wiederholen muss. */
    val covered: Set<TrendLine>
        get() = buildSet {
            // Wie bisher: Schlaf und Ziel der Woche zeigt das Diagramm nur mit Daten in den letzten 7 Nächten.
            if (durations.containsKey(ChartData.SHORT_NIGHTS)) {
                add(TrendLine.ASLEEP)
                add(TrendLine.GOAL)
            }
            if (bars.isNotEmpty()) add(TrendLine.REGULAR)
            if (feeling.isNotEmpty()) add(TrendLine.FEELING)
        }

    companion object {
        /**
         * Rechnet alle Diagramme aus Verlauf, Gesundheitsdaten und Notizen. Schlafprofil: die jüngste
         * der letzten [ChartData.NIGHTS] Uhr-Nächte mit Schlafphasen. Befinden auch zu Nächten aus anderen Apps ([health]).
         */
        fun of(
            nights: List<NightRecord>,
            sleeps: List<SleepSummary>,
            stages: List<StageSpan>,
            notes: Map<LocalDate, List<String>>,
            alarms: List<AlarmSpec>,
            goalMinutes: Int,
            now: Instant,
            zone: ZoneId,
            health: List<HealthFeeling> = emptyList(),
        ): HistoryCharts {
            val today = SleepNotes.nightDate(now, zone)
            val nextTarget = AlarmSchedule.earliestPlan(alarms, now, zone)?.target
            val hypnoNight = if (stages.isEmpty()) null else nights.sortedByDescending { it.wokeAt }.take(ChartData.NIGHTS).firstOrNull { ChartData.hypnogram(stages, it) != null }
            return HistoryCharts(
                bars = periods { ChartData.sleepBars(sleeps, zone, today, nextTarget, goalMinutes, it) },
                durations = periods { ChartData.durations(sleeps, zone, today, goalMinutes, it) },
                wake = periods { ChartData.wakeMoments(nights, zone, it) },
                hypnogram = hypnoNight?.let { ChartData.hypnogram(stages, it) },
                hypnogramNight = hypnoNight,
                hypnogramStages = hypnoNight?.let { StageStats.forNight(stages, it) },
                feeling = periods { ChartData.feelingSeries(nights, notes, zone, today, it, health) },
            )
        }

        /** Je Zeitraum das Diagramm, soweit es in diesem Zeitraum Daten gibt (sonst fehlt der Zeitraum: «Keine Daten»). */
        private fun <T : Any> periods(make: (Int) -> T?): Map<Int, T> =
            ChartData.PERIODS.mapNotNull { nights -> make(nights)?.let { nights to it } }.toMap()
    }
}

/** Höhe der Zeichenfläche: gleich auf allen Karten, damit die Karten beim Blättern nicht springen. */
internal val CHART_HEIGHT = 168.dp

/**
 * «Diagramme» oben im Verlauf: Karten zum seitlichen Blättern (die nächste schaut am Rand hervor),
 * darunter Punkte für die Seite. Jede Karte: Titel, eine Zeile Fazit, Diagramm.
 */
@Composable
fun HistoryChartsSection(
    charts: HistoryCharts,
    customNotes: List<CustomNote>,
    opener: (LocalDate) -> (() -> Unit)? = { null },
    modifier: Modifier = Modifier,
) {
    // Ein Zeitraum für alle Diagramme (wie auf Apple), Vorgabe 14 Nächte; gemerkt über Drehen und Zurückkehren.
    var nights by rememberSaveable { mutableIntStateOf(ChartData.NIGHTS) }
    val pages = chartPages(charts, customNotes, opener, nights)
    if (pages.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.charts_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        if (charts.hasPeriods) PeriodSwitch(nights) { nights = it }
        val state = rememberPagerState { pages.size }
        HorizontalPager(
            state = state,
            contentPadding = PaddingValues(end = if (pages.size > 1) 28.dp else 0.dp),
            pageSpacing = 12.dp,
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth(),
        ) { page -> pages[page](Modifier.fillMaxWidth()) }
        if (pages.size > 1) {
            val active = MaterialTheme.colorScheme.primary
            val idle = MaterialTheme.colorScheme.outlineVariant
            // Nur Zierde: Bildschirmleser blättern selbst.
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clearAndSetSemantics { },
            ) {
                repeat(pages.size) { i ->
                    Canvas(Modifier.size(6.dp)) { drawCircle(if (i == state.currentPage) active else idle) }
                }
            }
        }
    }
}

/** Die Karten in der Reihenfolge der Seiten; [opener] führt von einem Nachtdatum zur Detailseite. */
private fun chartPages(
    charts: HistoryCharts,
    customNotes: List<CustomNote>,
    opener: (LocalDate) -> (() -> Unit)?,
    nights: Int,
): List<@Composable (Modifier) -> Unit> =
    buildList<@Composable (Modifier) -> Unit> {
        if (charts.bars.isNotEmpty()) add { m -> SleepBarsCard(charts.bars[nights], opener, m) }
        if (charts.durations.isNotEmpty()) add { m -> DurationsCard(charts.durations[nights], nights, opener, m) }
        if (charts.wake.isNotEmpty()) add { m -> WakeCard(charts.wake[nights], nights, opener, m) }
        val h = charts.hypnogram
        val night = charts.hypnogramNight
        val stats = charts.hypnogramStages
        if (h != null && night != null && stats != null) {
            add { m -> HypnogramCard(h, stats, night, m, opener(SleepNotes.nightDate(night, ZoneId.systemDefault()))) }
        }
        if (charts.feeling.isNotEmpty()) add { m -> FeelingCard(charts.feeling[nights], nights, customNotes, opener, m) }
    }

/** Alle Karten untereinander statt zum Blättern (Bildschirmfotos zeigen so jedes Diagramm). */
@Composable
internal fun HistoryChartsStacked(charts: HistoryCharts, customNotes: List<CustomNote>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        chartPages(charts, customNotes, { null }, ChartData.NIGHTS).forEach { it(Modifier.fillMaxWidth()) }
    }
}

/** Textschlüssel eines Zeitraums. */
private fun periodLabel(nights: Int): Int = when (nights) {
    ChartData.SHORT_NIGHTS -> R.string.chart_nights_7
    ChartData.NIGHTS -> R.string.chart_nights_14
    else -> R.string.chart_nights_30
}

/** Auswahl des Zeitraums über allen Diagrammen: 7 / 14 / 30 Nächte. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodSwitch(selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        ChartData.PERIODS.forEachIndexed { i, value ->
            SegmentedButton(
                selected = selected == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, ChartData.PERIODS.size),
                icon = {},
            ) { Text(stringResource(periodLabel(value)), style = MaterialTheme.typography.labelMedium) }
        }
    }
}

/** Karte ohne Daten im gewählten Zeitraum: «Keine Daten» (vorhandener Text, gross begonnen). */
@Composable
internal fun NoDataText() {
    val locale = Locale.getDefault()
    Text(
        stringResource(R.string.chart_no_data).replaceFirstChar { it.titlecase(locale) },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 24.dp),
    )
}
