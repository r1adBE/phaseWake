package com.phasewake.app.ui.history

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.ChartData
import com.phasewake.app.core.Durations
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.theme.PhaseWakeTheme
import java.time.Duration

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DurationsCard(week: Durations?, twoWeeks: Durations, modifier: Modifier) {
    val context = LocalContext.current
    val brand = PhaseWakeTheme.brand
    val scheme = MaterialTheme.colorScheme
    var nights by rememberSaveable { mutableIntStateOf(ChartData.SHORT_NIGHTS) }
    val d = if (nights == ChartData.SHORT_NIGHTS && week != null) week else twoWeeks
    val measurer = rememberTextMeasurer()
    val label = labelStyle()
    val description = remember(d) { durationsDescription(context, d) }
    ChartCard(stringResource(R.string.chart_durations_title), durationsTakeaway(context, d), modifier) {
        // 7 oder 14 Nächte; ohne Daten in den letzten 7 nur die 14.
        if (week != null) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                listOf(ChartData.SHORT_NIGHTS to R.string.chart_nights_7, ChartData.NIGHTS to R.string.chart_nights_14).forEachIndexed { i, (value, text) ->
                    SegmentedButton(
                        selected = nights == value,
                        onClick = { nights = value },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        icon = {},
                    ) { Text(stringResource(text), style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(CHART_HEIGHT).fadeIn().semantics { contentDescription = description }) {
            val axisHeight = 16.dp.toPx()
            // Platz für das ✓ über der höchsten erreichten Säule.
            val check = 5.dp.toPx()
            val top = check * 2.4f
            val plot = size.height - axisHeight - top
            // Etwas Luft über der höchsten Säule bzw. dem Ziel.
            val scale = d.scaleMinutes * 1.1f
            fun y(minutes: Long) = top + plot * (1f - minutes / scale)
            val n = d.bars.size
            val gap = (if (n > 7) 4 else 8).dp.toPx()
            val w = (size.width - gap * (n - 1)) / n
            d.bars.forEachIndexed { i, bar ->
                val x = i * (w + gap)
                val minutes = bar.asleepMinutes
                if (minutes == null) {
                    drawRoundRect(brand.chartTrack, Offset(x, top + plot - 3.dp.toPx()), Size(w, 3.dp.toPx()), CornerRadius(1.5.dp.toPx()))
                } else {
                    // Ziel erreicht: gefüllt und ✓ darüber; verfehlt: nur Umriss (nicht allein über die Farbe).
                    val yTop = y(minutes)
                    val corner = CornerRadius(minOf(w / 2, 6.dp.toPx()))
                    if (bar.reached) {
                        drawRoundRect(brand.chartActive, Offset(x, yTop), Size(w, top + plot - yTop), corner)
                        mark(LegendMark.CHECK, brand.chartActive, Offset(x + w / 2, yTop - check), check * 0.8f)
                    } else {
                        val stroke = 1.5.dp.toPx()
                        // Umriss in onSurfaceVariant: ≥ 3:1 zur Karte (chartQuiet wäre zu blass).
                        drawRoundRect(
                            scheme.onSurfaceVariant,
                            Offset(x + stroke / 2, yTop + stroke / 2),
                            Size(w - stroke, top + plot - yTop - stroke),
                            corner,
                            style = Stroke(stroke),
                        )
                    }
                }
            }
            val goalY = y(d.goalMinutes.toLong())
            dashed(scheme.primary, Offset(0f, goalY), Offset(size.width, goalY))
            // Datum der ersten und der letzten Nacht.
            val first = measurer.measure(morning(d.bars.first().date, "dM"), label)
            drawText(first, topLeft = Offset(0f, size.height - first.size.height))
            val last = measurer.measure(morning(d.bars.last().date, "dM"), label)
            drawText(last, topLeft = Offset(size.width - last.size.width, size.height - last.size.height))
        }
        Legend(listOf(LegendItem(stringResource(R.string.chart_goal, Format.duration(context, Duration.ofMinutes(d.goalMinutes.toLong()))), LegendMark.DASH, scheme.primary)))
    }
}
