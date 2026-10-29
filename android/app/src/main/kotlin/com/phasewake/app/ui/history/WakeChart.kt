package com.phasewake.app.ui.history

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.WakeKind
import com.phasewake.app.core.WakeMoments
import com.phasewake.app.ui.theme.PhaseWakeTheme

// --- 3. Weckmoment im Fenster ---

fun wakeTakeaway(context: Context, w: WakeMoments): String = context.getString(R.string.history_average, w.averageBefore)

private fun wakeDescription(context: Context, w: WakeMoments): String = buildList {
    add(context.getString(R.string.chart_wake_title))
    add(wakeTakeaway(context, w))
    w.points.forEach { p ->
        val reason = context.getString(
            when (p.kind) {
                WakeKind.LIGHT -> R.string.reason_light
                WakeKind.AWAKE -> R.string.reason_awake
                WakeKind.TARGET, WakeKind.LATE -> R.string.reason_deadline
            },
        )
        val timing = when {
            p.minutesBefore > 0 -> context.getString(R.string.woke_early, p.minutesBefore, reason)
            p.minutesAfter > 0 -> context.resources.getQuantityString(R.plurals.woke_late, p.minutesAfter, p.minutesAfter, reason)
            else -> context.getString(R.string.woke_on_time, reason)
        }
        add(morning(p.date) + ": " + timing)
    }
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

@Composable
internal fun WakeCard(w: WakeMoments, modifier: Modifier) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val label = labelStyle()
    val active = PhaseWakeTheme.brand.chartActive
    val styles = WakeKind.entries.associateWith { wakeStyle(it, active, scheme.onSurfaceVariant, scheme.error) }
    val description = remember(w) { wakeDescription(context, w) }
    ChartCard(stringResource(R.string.chart_wake_title), wakeTakeaway(context, w), modifier) {
        Canvas(Modifier.fillMaxWidth().height(CHART_HEIGHT).fadeIn().semantics { contentDescription = description }) {
            val r = 5.dp.toPx()
            // Links Platz für die Minuten, unten für die Daten.
            val labels = listOf(0, w.scaleMinutes / 2, w.scaleMinutes).map { measurer.measure(res.getString(R.string.duration_minutes, it.toLong()), label) }
            val left = labels.maxOf { it.size.width } + 6.dp.toPx()
            val axisHeight = 16.dp.toPx()
            val top = r + 2.dp.toPx()
            val plot = size.height - axisHeight - top - r
            fun y(minutes: Int) = top + plot * (1f - minutes.toFloat() / w.scaleMinutes)
            listOf(0, w.scaleMinutes / 2, w.scaleMinutes).forEachIndexed { i, m ->
                val gy = y(m)
                drawLine(if (m == 0) scheme.outline else scheme.outlineVariant, Offset(left, gy), Offset(size.width, gy), strokeWidth = 1.dp.toPx())
                drawText(labels[i], topLeft = Offset(0f, gy - labels[i].size.height / 2f))
            }
            val n = w.points.size
            val slot = (size.width - left) / n
            w.points.forEachIndexed { i, p ->
                val (shape, color) = styles.getValue(p.kind)
                mark(shape, color, Offset(left + slot * (i + 0.5f), y(p.minutesBefore)), r)
            }
            val first = measurer.measure(morning(w.points.first().date, "dM"), label)
            drawText(first, topLeft = Offset(left, size.height - first.size.height))
            if (n > 1) {
                val last = measurer.measure(morning(w.points.last().date, "dM"), label)
                drawText(last, topLeft = Offset(size.width - last.size.width, size.height - last.size.height))
            }
        }
        val present = w.points.map { it.kind }.toSet()
        Legend(
            listOf(
                WakeKind.LIGHT to R.string.reason_light,
                WakeKind.AWAKE to R.string.reason_awake,
                WakeKind.TARGET to R.string.chart_wake_target,
                WakeKind.LATE to R.string.chart_wake_late,
            ).filter { it.first in present }.map { (kind, text) ->
                val (shape, color) = styles.getValue(kind)
                LegendItem(stringResource(text), shape, color)
            },
        )
    }
}
