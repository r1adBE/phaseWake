package com.phasewake.app.ui.history

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.ChartData
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

/**
 * Befinden je Nacht (1–5) über den gewählten Zeitraum ([nights]; `null`: «Keine Daten»), Nächte mit Notiz mit Ring; ab 14 Nächten
 * der Wochendurchschnitt als Linie. Antippen oder Ziehen zeigt Datum, Befinden und Notizen in der Blase; Antippen der
 * Blase öffnet die Nacht ([opener]).
 */
@Composable
internal fun FeelingCard(chart: FeelingSeries?, nights: Int, custom: List<CustomNote>, opener: (LocalDate) -> (() -> Unit)?, modifier: Modifier) {
    val context = LocalContext.current
    val res = LocalResources.current
    val brand = PhaseWakeTheme.brand
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val label = labelStyle()
    ChartCard(stringResource(R.string.chart_feeling_title), null, modifier) {
        val f = chart
        if (f == null) {
            NoDataText()
            return@ChartCard
        }
        feelingTakeaway(context, f)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 10.dp)) }
        val withNotes = f.points.any { p -> p.feeling != null && p.notes.any { noteName(context, it, custom) != null } }
        val description = remember(f, custom) {
            (listOfNotNull(res.getString(R.string.chart_feeling_title), feelingTakeaway(context, f)) +
                f.points.filter { it.feeling != null }.map { feelingLine(context, it.date, it.feeling, it.notes, custom) }).joinToString(". ")
        }
        val average = remember(f) { ChartData.weekAverage(f.points.map { it.feeling?.toDouble() }) }
        val left = with(LocalDensity.current) { 16.dp.toPx() }
        val n = f.points.size
        val pick = ChartPick(
            // Nur Nächte mit Angabe sind wählbar.
            indexAt = { p, size -> slotAt(p.x, size.width, left, n)?.takeIf { f.points[it].feeling != null } },
            anchor = { i, width -> left + (width - left) / n * (i + 0.5f) },
            text = { f.points[it].let { p -> feelingLine(context, p.date, p.feeling, p.notes, custom) } },
            open = { opener(f.points[it].date) },
        )
        TouchChart(f, description, pick) { selected ->
            val axisHeight = 16.dp.toPx()
            val r = 5.dp.toPx()
            val top = r * 2
            val plot = size.height - axisHeight - top - r * 2
            fun y(v: Double) = top + plot * (1f - ((v - 1) / 4).toFloat())
            for (v in 1..5) {
                drawLine(if (v == 1) scheme.outline else scheme.outlineVariant, Offset(left, y(v.toDouble())), Offset(size.width, y(v.toDouble())), strokeWidth = 1.dp.toPx())
            }
            val five = measurer.measure("5", label)
            val one = measurer.measure("1", label)
            drawText(five, topLeft = Offset(0f, y(5.0) - five.size.height / 2f))
            drawText(one, topLeft = Offset(0f, y(1.0) - one.size.height / 2f))
            val slot = (size.width - left) / n
            fun x(i: Int) = left + slot * (i + 0.5f)
            selected?.let { highlight(scheme.primary, left + slot * it, slot, size.height - axisHeight) }
            // Linie nur zwischen benachbarten Nächten mit Angabe.
            averageLine(f.points.map { it.feeling?.toDouble() }, brand.chartQuiet, ::x, ::y)
            if (nights > ChartData.SHORT_NIGHTS) averageLine(average, scheme.onSurface.copy(alpha = 0.75f), ::x, ::y)
            f.points.forEachIndexed { i, p ->
                val v = p.feeling ?: return@forEachIndexed
                val c = Offset(x(i), y(v.toDouble()))
                drawCircle(brand.chartActive, r, c)
                // Nächte mit Notiz: kleiner Ring.
                if (p.notes.any { noteName(context, it, custom) != null }) drawCircle(scheme.primary, r * 1.7f, c, style = Stroke(1.5.dp.toPx()))
            }
            val first = measurer.measure(morning(f.points.first().date, "dM"), label)
            drawText(first, topLeft = Offset(left, size.height - first.size.height))
            val last = measurer.measure(morning(f.points.last().date, "dM"), label)
            drawText(last, topLeft = Offset(size.width - last.size.width, size.height - last.size.height))
        }
        listOfNotNull(
            if (withNotes) LegendItem(stringResource(R.string.notes_label), LegendMark.RING, scheme.primary) else null,
            if (nights > ChartData.SHORT_NIGHTS) LegendItem(stringResource(R.string.chart_week_average), LegendMark.LINE, scheme.onSurface.copy(alpha = 0.75f)) else null,
        ).takeIf { it.isNotEmpty() }?.let { Legend(it) }
        if (withNotes) {
            Text(
                stringResource(R.string.chart_feeling_hint),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp).clearAndSetSemantics { },
            )
        }
    }
}
