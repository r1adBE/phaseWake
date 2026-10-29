package com.phasewake.app.ui.history

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.ChartData
import com.phasewake.app.core.WakeKind
import com.phasewake.app.core.WakeMoment
import com.phasewake.app.core.WakeMoments
import com.phasewake.app.ui.theme.PhaseWakeTheme
import java.time.LocalDate

// --- 3. Weckmoment im Fenster ---

fun wakeTakeaway(context: Context, w: WakeMoments): String = context.getString(R.string.history_average, w.averageBefore)

/** «12 Min vor der Zielzeit · Unruhig – wohl leichter Schlaf» für einen Weckmoment ([wakeTimingText]). */
private fun wakeTiming(context: Context, p: WakeMoment): String = wakeTimingText(context, p.minutesBefore, p.minutesAfter, p.reason)

private fun wakeDescription(context: Context, w: WakeMoments): String = buildList {
    add(context.getString(R.string.chart_wake_title))
    add(wakeTakeaway(context, w))
    w.points.forEach { p -> add(morning(p.date) + ": " + wakeTiming(context, p)) }
}.joinToString(". ")

/**
 * Form und Farbe je Weckart. Die Form allein unterscheidet schon (WCAG 1.4.1, auch bei Farbenblindheit):
 * Kreis = leichter Schlaf, Dreieck = wach, Quadrat = Zielzeit, Kreuz = zu spät.
 */
private fun wakeStyle(kind: WakeKind, active: Color, muted: Color, error: Color): Pair<LegendMark, Color> = when (kind) {
    WakeKind.LIGHT -> LegendMark.DOT to active
    WakeKind.AWAKE -> LegendMark.TRIANGLE to muted
    WakeKind.TARGET -> LegendMark.SQUARE to muted
    WakeKind.LATE -> LegendMark.CROSS to error
}

/**
 * Weckmoment je Uhr-Nacht über den gewählten Zeitraum ([nights]; `null`: «Keine Daten»): Minuten vor der Zielzeit als Marke
 * (Form je Weckart), ab 14 Nächten der Wochendurchschnitt als Linie. Antippen oder Ziehen zeigt die Nacht in der Blase,
 * Antippen der Blase öffnet sie ([opener]).
 */
@Composable
internal fun WakeCard(chart: WakeMoments?, nights: Int, opener: (LocalDate) -> (() -> Unit)?, modifier: Modifier) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val label = labelStyle()
    val active = PhaseWakeTheme.brand.chartActive
    val styles = WakeKind.entries.associateWith { wakeStyle(it, active, scheme.onSurfaceVariant, scheme.error) }
    ChartCard(stringResource(R.string.chart_wake_title), null, modifier) {
        val w = chart
        if (w == null) {
            NoDataText()
            return@ChartCard
        }
        Text(wakeTakeaway(context, w), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 10.dp))
        val description = remember(w) { wakeDescription(context, w) }
        val average = remember(w) { ChartData.weekAverage(w.points.map { it.minutesBefore.toDouble() }) }
        val labels = remember(w, label) { listOf(0, w.scaleMinutes / 2, w.scaleMinutes).map { measurer.measure(res.getString(R.string.duration_minutes, it.toLong()), label) } }
        val left = labels.maxOf { it.size.width } + with(LocalDensity.current) { 6.dp.toPx() }
        val n = w.points.size
        val pick = ChartPick(
            indexAt = { p, size -> slotAt(p.x, size.width, left, n) },
            anchor = { i, width -> left + (width - left) / n * (i + 0.5f) },
            text = { morning(w.points[it].date) + " · " + wakeTiming(context, w.points[it]) },
            open = { opener(w.points[it].date) },
        )
        TouchChart(w, description, pick) { selected ->
            val r = 5.dp.toPx()
            val axisHeight = 16.dp.toPx()
            val top = r + 2.dp.toPx()
            val plot = size.height - axisHeight - top - r
            fun y(minutes: Double) = top + plot * (1f - (minutes / w.scaleMinutes).toFloat())
            val slot = (size.width - left) / n
            fun x(i: Int) = left + slot * (i + 0.5f)
            selected?.let { highlight(scheme.primary, left + slot * it, slot, size.height - axisHeight) }
            listOf(0, w.scaleMinutes / 2, w.scaleMinutes).forEachIndexed { i, m ->
                val gy = y(m.toDouble())
                drawLine(if (m == 0) scheme.outline else scheme.outlineVariant, Offset(left, gy), Offset(size.width, gy), strokeWidth = 1.dp.toPx())
                drawText(labels[i], topLeft = Offset(0f, gy - labels[i].size.height / 2f))
            }
            if (nights > ChartData.SHORT_NIGHTS) averageLine(average, scheme.onSurface.copy(alpha = 0.75f), ::x, ::y)
            w.points.forEachIndexed { i, p ->
                val (shape, color) = styles.getValue(p.kind)
                mark(shape, color, Offset(x(i), y(p.minutesBefore.toDouble())), r)
            }
            val first = measurer.measure(morning(w.points.first().date, "dM"), label)
            drawText(first, topLeft = Offset(left, size.height - first.size.height))
            if (n > 1) {
                val last = measurer.measure(morning(w.points.last().date, "dM"), label)
                drawText(last, topLeft = Offset(size.width - last.size.width, size.height - last.size.height))
            }
        }
        WakeLegend(w, styles, withAverage = nights > ChartData.SHORT_NIGHTS)
    }
}

/** Legende der vorkommenden Weckarten (Form und Farbe), dazu die Durchschnittslinie. */
@Composable
private fun WakeLegend(w: WakeMoments, styles: Map<WakeKind, Pair<LegendMark, Color>>, withAverage: Boolean) {
    val present = w.points.map { it.kind }.toSet()
    val kinds = listOf(
        WakeKind.LIGHT to R.string.reason_light,
        WakeKind.AWAKE to R.string.reason_awake,
        WakeKind.TARGET to R.string.chart_wake_target,
        WakeKind.LATE to R.string.chart_wake_late,
    ).filter { it.first in present }.map { (kind, text) ->
        val (shape, color) = styles.getValue(kind)
        LegendItem(stringResource(text), shape, color)
    }
    val average = LegendItem(stringResource(R.string.chart_week_average), LegendMark.LINE, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
    Legend(if (withAverage) kinds + average else kinds)
}
