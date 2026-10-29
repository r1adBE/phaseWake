package com.phasewake.app.ui.history

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.SleepBars
import com.phasewake.app.ui.theme.PhaseWakeTheme

// --- 1. Schlaf-Streifen ---

fun sleepBarsTakeaway(context: Context, bars: SleepBars): String? = bars.regularCounted.takeIf { it > 0 }?.let {
    context.resources.getQuantityString(R.plurals.habit_regular, it, bars.regular, it)
}

private fun sleepBarsDescription(context: Context, bars: SleepBars): String = buildList {
    add(context.getString(R.string.chart_bars_title))
    sleepBarsTakeaway(context, bars)?.let(::add)
    bars.bedtimeMinute?.let { add(context.getString(R.string.chart_bedtime, clock(it))) }
    bars.bars.forEach { bar ->
        val value = if (bar.hasData) clock(bar.startMinute!!) + " – " + clock(bar.endMinute!!) else context.getString(R.string.chart_no_data)
        add(morning(bar.date) + ": " + value)
    }
}.joinToString(". ")

@Composable
internal fun SleepBarsCard(bars: SleepBars, modifier: Modifier) {
    val context = LocalContext.current
    val brand = PhaseWakeTheme.brand
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val label = labelStyle()
    val description = remember(bars) { sleepBarsDescription(context, bars) }
    ChartCard(stringResource(R.string.chart_bars_title), sleepBarsTakeaway(context, bars), modifier) {
        Canvas(Modifier.fillMaxWidth().height(CHART_HEIGHT).fadeIn().semantics { contentDescription = description }) {
            val axisHeight = 16.dp.toPx()
            val span = (bars.axisEnd - bars.axisStart).toFloat()
            fun x(minute: Int) = size.width * (minute - bars.axisStart) / span
            val rows = bars.bars.size
            val rowHeight = (size.height - axisHeight) / rows
            val barHeight = (rowHeight * 0.62f).coerceAtMost(10.dp.toPx())
            // Raster alle 3 Stunden (bei langer Achse alle 4), mit Uhrzeit darunter.
            val step = if (span > 15 * 60) 240 else 180
            var tick = (bars.axisStart + step - 1) / step * step
            while (tick <= bars.axisEnd) {
                val tx = x(tick)
                drawLine(scheme.outlineVariant, Offset(tx, 0f), Offset(tx, size.height - axisHeight), strokeWidth = 1.dp.toPx())
                val text = measurer.measure(clock(tick), label)
                val left = (tx - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
                drawText(text, topLeft = Offset(left, size.height - text.size.height))
                tick += step
            }
            bars.bars.forEachIndexed { i, bar ->
                val cy = rowHeight * (i + 0.5f)
                if (bar.hasData) {
                    val x0 = x(bar.startMinute!!).coerceAtLeast(0f)
                    val x1 = x(bar.endMinute!!).coerceAtMost(size.width)
                    drawRoundRect(brand.chartActive, Offset(x0, cy - barHeight / 2), Size((x1 - x0).coerceAtLeast(barHeight), barHeight), CornerRadius(barHeight / 2))
                } else {
                    drawLine(brand.chartTrack, Offset(0f, cy), Offset(size.width, cy), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                }
            }
            bars.bedtimeMinute?.let { dashed(scheme.primary, Offset(x(it), 0f), Offset(x(it), size.height - axisHeight)) }
        }
        bars.bedtimeMinute?.let {
            Legend(listOf(LegendItem(stringResource(R.string.chart_bedtime, clock(it)), LegendMark.DASH, scheme.primary)))
        }
    }
}
