package com.phasewake.app.ui.history

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.CustomNote
import com.phasewake.app.core.FeelingSeries
import com.phasewake.app.ui.notes.noteName
import com.phasewake.app.ui.theme.PhaseWakeTheme
import java.time.LocalDate
import java.util.Locale

// --- 5. Befinden ---

/** Eine Nachkommastelle nach Sprache (wie in den Trends). */
private fun decimal(value: Double) = String.format(Locale.getDefault(), "%.1f", value)

fun feelingTakeaway(context: Context, f: FeelingSeries): String? {
    val last = f.lastWeek?.let { context.getString(R.string.trends_feeling_change, decimal(it)) }
    val thisWeek = f.thisWeek ?: return last
    return context.getString(R.string.trends_feeling, decimal(thisWeek)) + (last?.let { " · $it" } ?: "")
}

/** «28. Okt. · Befinden: Gut · Notizen: Koffein, Sport» */
private fun feelingLine(context: Context, date: LocalDate, feeling: Int?, notes: List<String>, custom: List<CustomNote>): String {
    val names = notes.mapNotNull { noteName(context, it, custom) }
    return listOfNotNull(
        morning(date),
        feeling?.let { context.getString(R.string.feeling_value, feelingName(context, it)) },
        names.takeIf { it.isNotEmpty() }?.let { context.getString(R.string.notes_value, it.joinToString(", ")) },
    ).joinToString(" · ")
}

@Composable
internal fun FeelingCard(f: FeelingSeries, custom: List<CustomNote>, modifier: Modifier) {
    val context = LocalContext.current
    val res = LocalResources.current
    val brand = PhaseWakeTheme.brand
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val label = labelStyle()
    var selected by remember(f) { mutableStateOf<Int?>(null) }
    val withNotes = f.points.any { p -> p.feeling != null && p.notes.any { noteName(context, it, custom) != null } }
    val description = remember(f, custom) {
        (listOfNotNull(res.getString(R.string.chart_feeling_title), feelingTakeaway(context, f)) +
            f.points.filter { it.feeling != null }.map { feelingLine(context, it.date, it.feeling, it.notes, custom) }).joinToString(". ")
    }
    ChartCard(stringResource(R.string.chart_feeling_title), feelingTakeaway(context, f), modifier) {
        Box {
            Canvas(
                Modifier.fillMaxWidth().height(CHART_HEIGHT).fadeIn()
                    .semantics { contentDescription = description }
                    .pointerInput(f) {
                        detectTapGestures { tap ->
                            val n = f.points.size
                            val left = 16.dp.toPx()
                            val slot = (size.width - left) / n
                            val i = ((tap.x - left) / slot).toInt().coerceIn(0, n - 1)
                            selected = if (f.points[i].feeling != null && selected != i) i else null
                        }
                    },
            ) {
                val one = measurer.measure("1", label)
                val five = measurer.measure("5", label)
                val left = 16.dp.toPx()
                val axisHeight = 16.dp.toPx()
                val r = 5.dp.toPx()
                val top = r * 2
                val plot = size.height - axisHeight - top - r * 2
                fun y(v: Int) = top + plot * (1f - (v - 1) / 4f)
                for (v in 1..5) {
                    drawLine(if (v == 1) scheme.outline else scheme.outlineVariant, Offset(left, y(v)), Offset(size.width, y(v)), strokeWidth = 1.dp.toPx())
                }
                drawText(five, topLeft = Offset(0f, y(5) - five.size.height / 2f))
                drawText(one, topLeft = Offset(0f, y(1) - one.size.height / 2f))
                val n = f.points.size
                val slot = (size.width - left) / n
                fun x(i: Int) = left + slot * (i + 0.5f)
                // Linie nur zwischen benachbarten Nächten mit Angabe.
                for (i in 1 until n) {
                    val a = f.points[i - 1].feeling ?: continue
                    val b = f.points[i].feeling ?: continue
                    drawLine(brand.chartQuiet, Offset(x(i - 1), y(a)), Offset(x(i), y(b)), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                }
                f.points.forEachIndexed { i, p ->
                    val v = p.feeling ?: return@forEachIndexed
                    val c = Offset(x(i), y(v))
                    if (i == selected) drawCircle(scheme.primary.copy(alpha = 0.2f), r * 2.4f, c)
                    drawCircle(brand.chartActive, r, c)
                    // Nächte mit Notiz: kleiner Ring.
                    if (p.notes.any { noteName(context, it, custom) != null }) drawCircle(scheme.primary, r * 1.7f, c, style = Stroke(1.5.dp.toPx()))
                }
                val first = measurer.measure(morning(f.points.first().date, "dM"), label)
                drawText(first, topLeft = Offset(left, size.height - first.size.height))
                val last = measurer.measure(morning(f.points.last().date, "dM"), label)
                drawText(last, topLeft = Offset(size.width - last.size.width, size.height - last.size.height))
            }
        }
        if (withNotes) Legend(listOf(LegendItem(stringResource(R.string.notes_label), LegendMark.RING, scheme.primary)))
        val point = selected?.let { f.points.getOrNull(it) }
        when {
            point != null -> Text(
                feelingLine(context, point.date, point.feeling, point.notes, custom),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            withNotes -> Text(
                stringResource(R.string.chart_feeling_hint),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp).clearAndSetSemantics { },
            )
        }
    }
}
