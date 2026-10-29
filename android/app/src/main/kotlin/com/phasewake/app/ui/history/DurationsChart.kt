package com.phasewake.app.ui.history

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.ChartData
import com.phasewake.app.core.DurationBar
import com.phasewake.app.core.Durations
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.theme.PhaseWakeTheme
import java.time.Duration
import java.time.LocalDate

// --- 2. Schlafdauer pro Nacht ---

fun durationsTakeaway(context: Context, d: Durations): String =
    context.getString(R.string.trends_asleep, Format.duration(context, Duration.ofMinutes(d.averageMinutes))) + " · " +
        context.resources.getQuantityString(R.plurals.goal_week, d.counted, d.reached, d.counted)

private fun durationsDescription(context: Context, d: Durations): String = buildList {
    add(context.getString(R.string.chart_durations_title))
    add(durationsTakeaway(context, d))
    add(context.getString(R.string.chart_goal, Format.duration(context, Duration.ofMinutes(d.goalMinutes.toLong()))))
    d.bars.forEach { bar ->
        add(morning(bar.date) + ": " + (bar.asleepMinutes?.let { Format.duration(context, Duration.ofMinutes(it)) } ?: context.getString(R.string.chart_no_data)))
    }
}.joinToString(". ")

/** «28. Okt. · 7 Std. 20 Min» bzw. «28. Okt. · keine Daten». */
private fun durationLine(context: Context, bar: DurationBar): String =
    morning(bar.date) + " · " + (bar.asleepMinutes?.let { Format.duration(context, Duration.ofMinutes(it)) } ?: context.getString(R.string.chart_no_data))

/**
 * Schlafdauer je Nacht über den gewählten Zeitraum ([nights]; `null`: «Keine Daten»): Säulen gefüllt mit ✓ (Ziel erreicht) oder
 * als Umriss, Ziellinie gestrichelt, ab 14 Nächten der Wochendurchschnitt als Linie. Antippen oder Ziehen zeigt die Nacht
 * in der Blase; Antippen der Blase öffnet sie ([opener]).
 */
@Composable
internal fun DurationsCard(chart: Durations?, nights: Int, opener: (LocalDate) -> (() -> Unit)?, modifier: Modifier) {
    val context = LocalContext.current
    val brand = PhaseWakeTheme.brand
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val label = labelStyle()
    ChartCard(stringResource(R.string.chart_durations_title), null, modifier) {
        val d = chart
        if (d == null) {
            NoDataText()
            return@ChartCard
        }
        Text(durationsTakeaway(context, d), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 10.dp))
        val withAverage = nights > ChartData.SHORT_NIGHTS
        val average = remember(d) { ChartData.weekAverage(d.bars.map { it.asleepMinutes?.toDouble() }) }
        val description = remember(d) { durationsDescription(context, d) }
        val pick = ChartPick(
            indexAt = { p, size -> slotAt(p.x, size.width, 0f, d.bars.size) },
            anchor = { i, width -> width / d.bars.size * (i + 0.5f) },
            text = { durationLine(context, d.bars[it]) },
            open = { opener(d.bars[it].date) },
        )
        TouchChart(d, description, pick) { selected ->
            val axisHeight = 16.dp.toPx()
            // Platz für das ✓ über der höchsten erreichten Säule.
            val check = 5.dp.toPx()
            val top = check * 2.4f
            val plot = size.height - axisHeight - top
            // Etwas Luft über der höchsten Säule bzw. dem Ziel.
            val scale = d.scaleMinutes * 1.1f
            fun y(minutes: Double) = top + plot * (1f - (minutes / scale).toFloat())
            val n = d.bars.size
            val gap = (if (n > ChartData.SHORT_NIGHTS) (if (n > ChartData.NIGHTS) 2 else 4) else 8).dp.toPx()
            val w = (size.width - gap * (n - 1)) / n
            selected?.let { highlight(scheme.primary, it * (w + gap) - gap / 2, w + gap, top + plot) }
            val ink = BarInk(brand.chartActive, brand.chartTrack, scheme.onSurfaceVariant)
            d.bars.forEachIndexed { i, bar -> durationBar(bar, Offset(i * (w + gap), y(bar.asleepMinutes?.toDouble() ?: 0.0)), w, top + plot, check, ink) }
            if (withAverage) averageLine(average, scheme.onSurface.copy(alpha = 0.75f), { i -> i * (w + gap) + w / 2 }, ::y)
            val goalY = y(d.goalMinutes.toDouble())
            dashed(scheme.primary, Offset(0f, goalY), Offset(size.width, goalY))
            // Datum der ersten und der letzten Nacht.
            val first = measurer.measure(morning(d.bars.first().date, "dM"), label)
            drawText(first, topLeft = Offset(0f, size.height - first.size.height))
            val last = measurer.measure(morning(d.bars.last().date, "dM"), label)
            drawText(last, topLeft = Offset(size.width - last.size.width, size.height - last.size.height))
        }
        Legend(
            listOfNotNull(
                LegendItem(stringResource(R.string.chart_goal, Format.duration(context, Duration.ofMinutes(d.goalMinutes.toLong()))), LegendMark.DASH, scheme.primary),
                LegendItem(stringResource(R.string.chart_goal_met), LegendMark.NONE, brand.chartActive),
                if (withAverage) LegendItem(stringResource(R.string.chart_week_average), LegendMark.LINE, scheme.onSurface.copy(alpha = 0.75f)) else null,
            ),
        )
    }
}

/** Farben der Säulen: erreicht (gefüllt), ohne Daten (Strich), verfehlt (Umriss). */
private data class BarInk(val active: Color, val track: Color, val outline: Color)

/**
 * Eine Säule: ohne Daten ein flacher Strich; Ziel erreicht gefüllt mit ✓ darüber, verfehlt nur Umriss (nicht allein
 * über die Farbe). [topLeft] ist die obere linke Ecke, [bottom] die Grundlinie.
 */
private fun DrawScope.durationBar(bar: DurationBar, topLeft: Offset, w: Float, bottom: Float, check: Float, ink: BarInk) {
    val (active, track, outline) = ink
    val x = topLeft.x
    if (bar.asleepMinutes == null) {
        drawRoundRect(track, Offset(x, bottom - 3.dp.toPx()), Size(w, 3.dp.toPx()), CornerRadius(1.5.dp.toPx()))
        return
    }
    val yTop = topLeft.y
    val corner = CornerRadius(minOf(w / 2, 6.dp.toPx()))
    if (bar.reached) {
        drawRoundRect(active, Offset(x, yTop), Size(w, bottom - yTop), corner)
        mark(LegendMark.CHECK, active, Offset(x + w / 2, yTop - check), check * 0.8f)
    } else {
        val stroke = 1.5.dp.toPx()
        // Umriss in onSurfaceVariant: ≥ 3:1 zur Karte (chartQuiet wäre zu blass).
        drawRoundRect(outline, Offset(x + stroke / 2, yTop + stroke / 2), Size(w - stroke, bottom - yTop - stroke), corner, style = Stroke(stroke))
    }
}
