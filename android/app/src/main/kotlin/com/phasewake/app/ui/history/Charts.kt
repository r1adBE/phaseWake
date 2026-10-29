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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.phasewake.app.core.SleepBars
import com.phasewake.app.core.SleepNotes
import com.phasewake.app.core.SleepSummary
import com.phasewake.app.core.StageSpan
import com.phasewake.app.core.WakeMoments
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Trends-Zeilen, die ein Diagramm schon zeigt (dort weggelassen). */
enum class TrendLine { ASLEEP, GOAL, REGULAR, FEELING }

/**
 * Daten der Diagramme im Verlauf; jedes `null` = Diagramm verborgen.
 *
 * @property hypnogramNight Die Nacht zum Schlafprofil (für Datum und Abgleich).
 */
data class HistoryCharts(
    val bars: SleepBars? = null,
    val week: Durations? = null,
    val twoWeeks: Durations? = null,
    val wake: WakeMoments? = null,
    val hypnogram: Hypnogram? = null,
    val hypnogramNight: NightRecord? = null,
    val feeling: FeelingSeries? = null,
) {
    val isEmpty: Boolean get() = bars == null && twoWeeks == null && wake == null && hypnogram == null && feeling == null

    /** Was die Trends-Karte nicht mehr wiederholen muss. */
    val covered: Set<TrendLine>
        get() = buildSet {
            if (week != null) {
                add(TrendLine.ASLEEP)
                add(TrendLine.GOAL)
            }
            if (bars != null) add(TrendLine.REGULAR)
            if (feeling != null) add(TrendLine.FEELING)
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
                bars = ChartData.sleepBars(sleeps, zone, today, nextTarget, goalMinutes),
                week = ChartData.durations(sleeps, zone, today, goalMinutes, ChartData.SHORT_NIGHTS),
                twoWeeks = ChartData.durations(sleeps, zone, today, goalMinutes, ChartData.NIGHTS),
                wake = ChartData.wakeMoments(nights, zone),
                hypnogram = hypnoNight?.let { ChartData.hypnogram(stages, it) },
                hypnogramNight = hypnoNight,
                feeling = ChartData.feelingSeries(nights, notes, zone, today, health = health),
            )
        }
    }
}

/** Höhe der Zeichenfläche: gleich auf allen Karten, damit die Karten beim Blättern nicht springen. */
internal val CHART_HEIGHT = 168.dp

/**
 * «Diagramme» oben im Verlauf: Karten zum seitlichen Blättern (die nächste schaut am Rand hervor),
 * darunter Punkte für die Seite. Jede Karte: Titel, eine Zeile Fazit, Diagramm.
 */
@Composable
fun HistoryChartsSection(charts: HistoryCharts, customNotes: List<CustomNote>, modifier: Modifier = Modifier) {
    val pages = chartPages(charts, customNotes)
    if (pages.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.charts_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
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

/** Die Karten in der Reihenfolge der Seiten. */
private fun chartPages(charts: HistoryCharts, customNotes: List<CustomNote>): List<@Composable (Modifier) -> Unit> =
    buildList<@Composable (Modifier) -> Unit> {
        charts.bars?.let { add { m -> SleepBarsCard(it, m) } }
        charts.twoWeeks?.let { add { m -> DurationsCard(charts.week, it, m) } }
        charts.wake?.let { add { m -> WakeCard(it, m) } }
        charts.hypnogram?.let { h -> charts.hypnogramNight?.let { night -> add { m -> HypnogramCard(h, night, m) } } }
        charts.feeling?.let { add { m -> FeelingCard(it, customNotes, m) } }
    }

/** Alle Karten untereinander statt zum Blättern (Bildschirmfotos zeigen so jedes Diagramm). */
@Composable
internal fun HistoryChartsStacked(charts: HistoryCharts, customNotes: List<CustomNote>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        chartPages(charts, customNotes).forEach { it(Modifier.fillMaxWidth()) }
    }
}
