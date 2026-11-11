package com.phasewake.app.ui.history

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalResources
import com.phasewake.app.core.SleepStage
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.OverviewBar
import com.phasewake.app.core.OverviewUnit
import com.phasewake.app.core.SleepOverview
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.theme.PhaseWakeTheme
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// --- Übersicht «Tage / Wochen / Monate» oben im Verlauf ---

private val unitLabels = mapOf(
    OverviewUnit.DAYS to R.string.overview_days,
    OverviewUnit.WEEKS to R.string.overview_weeks,
    OverviewUnit.MONTHS to R.string.overview_months,
)

/** Datum des Morgens zu einem Nachtdatum mit [skeleton] (Sprache des Geräts). */
private fun morningAs(date: LocalDate, skeleton: String): String {
    val locale = Locale.getDefault()
    return Format.digits(DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale), locale).format(date.plusDays(1))
}

/** Beschriftung unter der Säule: Wochentag, Wochenbeginn bzw. Monat (je des Morgens). */
private fun barLabel(unit: OverviewUnit, bar: OverviewBar): String = when (unit) {
    OverviewUnit.DAYS -> morningAs(bar.first, "EEE")
    OverviewUnit.WEEKS -> morningAs(bar.first, "dM")
    OverviewUnit.MONTHS -> morningAs(bar.first, "LLL")
}

/** Zeitraum der Säule für Blase und Bildschirmleser: «Mo., 9. Nov.», «2. Nov. – 8. Nov.» bzw. «Oktober 2026». */
private fun barPeriod(unit: OverviewUnit, bar: OverviewBar): String = when (unit) {
    OverviewUnit.DAYS -> morningAs(bar.first, "EEEdMMM")
    OverviewUnit.WEEKS -> morning(bar.first) + " – " + morning(bar.last)
    OverviewUnit.MONTHS -> morningAs(bar.first, "LLLLyyyy")
}

private fun barValue(context: Context, bar: OverviewBar): String =
    bar.asleepMinutes?.let { Format.duration(context, Duration.ofMinutes(it)) } ?: context.getString(R.string.chart_no_data)

/** Die drei Durchschnitte als (Bezeichnung, Wert). */
private fun averages(context: Context, o: SleepOverview): List<Pair<String, String>> = listOf(
    context.getString(R.string.overview_asleep) to Format.duration(context, Duration.ofMinutes(o.averageMinutes)),
    context.getString(R.string.overview_bedtime) to clock(o.bedtimeMinute),
    context.getString(R.string.overview_wake) to clock(o.wakeMinute),
)

private fun overviewDescription(context: Context, o: SleepOverview): String = buildList {
    add(context.getString(R.string.overview_title))
    add(context.getString(unitLabels.getValue(o.unit)))
    averages(context, o).forEach { (label, value) -> add("$label: $value") }
    add(context.getString(R.string.chart_goal, Format.duration(context, Duration.ofMinutes(o.goalMinutes.toLong()))))
    o.bars.forEach { add(barPeriod(o.unit, it) + ": " + barValue(context, it)) }
}.joinToString(". ")

/** Auswahl «Tage / Wochen / Monate» oben im Verlauf: gilt für die Übersicht und die Diagramme darunter ([periodNights]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OverviewSwitch(unit: OverviewUnit, onSelect: (OverviewUnit) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth().height(IntrinsicSize.Max)) {
        OverviewUnit.entries.forEachIndexed { i, value ->
            SegmentedButton(
                selected = unit == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, OverviewUnit.entries.size),
                modifier = Modifier.heightIn(min = 48.dp).fillMaxHeight(),
                icon = {},
            ) { Text(stringResource(unitLabels.getValue(value)), style = MaterialTheme.typography.labelMedium) }
        }
    }
}

/** Phasen von unten nach oben in einer Säule: tief, leicht, REM (Wach gehört nicht zur Schlafdauer). */
private val stackOrder = listOf(SleepStage.DEEP, SleepStage.LIGHT, SleepStage.REM)

/**
 * Übersicht oben im Verlauf für die gewählte Einteilung [unit]: Ø Schlafdauer gross, Ø Schlafenszeit und Ø Aufwachzeit,
 * erreichtes Schlafziel, darunter Säulen (je Nacht, Woche bzw. Monat der Durchschnitt). Mit Phasen ist jede Säule nach
 * tief, leicht und REM aufgeteilt (Farben wie im Hypnogramm), Säulen ohne Phasen daneben neutral gestreift (Woche/Monat:
 * nur wenn alle Nächte Phasen haben, [com.phasewake.app.core.OverviewBar.stageMinutes]); ganz ohne Phasen ein Verlauf in
 * der Markenfarbe. Erreichtes Ziel mit ✓.
 * Die Säulen wachsen beim Wechsel kurz hoch. Antippen zeigt den Wert in der Blase; bei «Tage» öffnet sie die Nacht ([opener]).
 */
@Composable
internal fun OverviewCard(
    overview: Map<OverviewUnit, SleepOverview>,
    unit: OverviewUnit,
    opener: (LocalDate) -> (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val res = LocalResources.current
    val brand = PhaseWakeTheme.brand
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val label = labelStyle()
    val colors = stageColors()
    ChartCard(stringResource(R.string.overview_title), null, modifier) {
        val o = overview[unit]
        if (o == null) {
            NoDataText()
            return@ChartCard
        }
        val (asleep, bedtime, wake) = averages(context, o)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1.5f).clearAndSetSemantics { contentDescription = "${asleep.first}: ${asleep.second}" }) {
                Text(asleep.second, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Text(asleep.first, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            listOf(bedtime, wake).forEach { (name, value) ->
                Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = "$name: $value" }) {
                    Text(value, style = MaterialTheme.typography.titleMedium)
                    Text(name, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
        }
        Text(
            res.getQuantityString(R.plurals.goal_week, o.counted, o.reachedNights, o.counted),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
        )
        val description = remember(o) { overviewDescription(context, o) }
        val n = o.bars.size
        val pick = ChartPick(
            indexAt = { p, size -> slotAt(p.x, size.width, 0f, n) },
            anchor = { i, width -> width / n * (i + 0.5f) },
            text = { barPeriod(o.unit, o.bars[it]) + " · " + barValue(context, o.bars[it]) },
            open = { if (o.unit == OverviewUnit.DAYS) opener(o.bars[it].first) else null },
        )
        val animate = rememberAnimationsOn()
        val grow = remember(o) { Animatable(if (animate) 0f else 1f) }
        LaunchedEffect(o) { if (animate) grow.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
        val stacked = o.bars.any { stackTotal(it) > 0 }
        // Säulen mit Daten, aber ohne Phasen, neben solchen mit Phasen: neutral, Legende «Ohne Phasen».
        val neutral = stacked && o.bars.any { it.asleepMinutes != null && stackTotal(it) <= 0f }
        TouchChart(o, description, pick) { selected ->
            val labels = o.bars.map { measurer.measure(barLabel(o.unit, it), label) }
            val axisHeight = labels.maxOf { it.size.height } + 6.dp.toPx()
            val check = 5.dp.toPx()
            val top = check * 2.6f
            val plot = size.height - axisHeight - top
            val scale = o.scaleMinutes * 1.08f
            fun y(minutes: Double) = top + plot * (1f - (minutes / scale).toFloat())
            val slot = size.width / n
            val w = (slot * 0.52f).coerceAtMost(36.dp.toPx())
            val bottom = top + plot
            selected?.let { highlight(scheme.primary, it * slot, slot, bottom) }
            // Feine Hilfslinie am Boden, damit leere Tage nicht in der Luft hängen.
            drawLine(scheme.outlineVariant, Offset(0f, bottom), Offset(size.width, bottom), strokeWidth = 1.dp.toPx())
            val gradient = Brush.verticalGradient(listOf(brand.chartActive, brand.stageDeep), startY = top, endY = bottom)
            o.bars.forEachIndexed { i, bar ->
                val x = i * slot + (slot - w) / 2
                val minutes = bar.asleepMinutes
                if (minutes == null) {
                    drawRoundRect(brand.chartTrack, Offset(x, bottom - 4.dp.toPx()), Size(w, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
                } else {
                    val yTop = bottom - (bottom - y(minutes.toDouble())) * grow.value
                    val h = (bottom - yTop).coerceAtLeast(w.coerceAtMost(6.dp.toPx()))
                    val shape = Path().apply {
                        addRoundRect(RoundRect(x, bottom - h, x + w, bottom, CornerRadius(minOf(w / 2, 10.dp.toPx()))))
                    }
                    clipPath(shape) {
                        val total = stackTotal(bar)
                        if (stacked && total <= 0f) {
                            // Neben Säulen mit Phasen: Nacht (bzw. Woche/Monat) ohne Phasen neutral gestreift, gleiche Höhe.
                            stripes(scheme.onSurfaceVariant, Offset(x, bottom - h), Size(w, h), 6.dp.toPx())
                        } else if (total > 0) {
                            var y0 = bottom
                            val gap = 1.5.dp.toPx()
                            stackOrder.forEach { stage ->
                                val part = h * (bar.stageMinutes[stage] ?: 0L) / total
                                if (part > 0f) {
                                    drawRect(colors.getValue(stage), Offset(x, y0 - part), Size(w, (part - gap).coerceAtLeast(part / 2)))
                                    y0 -= part
                                }
                            }
                        } else {
                            drawRect(gradient, Offset(x, bottom - h), Size(w, h))
                        }
                    }
                    if (bar.reached && grow.value >= 1f) mark(LegendMark.CHECK, scheme.primary, Offset(x + w / 2, bottom - h - check * 1.4f), check * 0.8f)
                }
                val text = labels[i]
                drawText(text, topLeft = Offset(i * slot + (slot - text.size.width) / 2, size.height - text.size.height))
            }
            val goalY = y(o.goalMinutes.toDouble())
            dashed(scheme.primary, Offset(0f, goalY), Offset(size.width, goalY))
        }
        Legend(
            listOfNotNull(
                LegendItem(stringResource(R.string.chart_goal, Format.duration(context, Duration.ofMinutes(o.goalMinutes.toLong()))), LegendMark.DASH, scheme.primary),
                LegendItem(stringResource(R.string.chart_goal_met), LegendMark.NONE, scheme.primary),
            ) + (if (stacked) stackOrder.map { LegendItem(stageName(context, it), LegendMark.SQUARE, colors.getValue(it)) } else emptyList()) +
                (if (neutral) listOf(LegendItem(stringResource(R.string.overview_no_stages), LegendMark.STRIPES, scheme.onSurfaceVariant)) else emptyList()),
        )
    }
}

/** Minuten tief + leicht + REM einer Säule (0 ohne Phasen). */
private fun stackTotal(bar: OverviewBar): Float = stackOrder.sumOf { bar.stageMinutes[it] ?: 0L }.toFloat()

/** Ob Animationen laufen dürfen (nicht in Vorschau/Bildschirmfotos, nicht bei «Animationen entfernen»). */
@Composable
private fun rememberAnimationsOn(): Boolean {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    return remember {
        !inspection && Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}
