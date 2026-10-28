package com.phasewake.app.ui.history

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.ChartData
import com.phasewake.app.core.CustomNote
import com.phasewake.app.core.Durations
import com.phasewake.app.core.FeelingSeries
import com.phasewake.app.core.Hypnogram
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.SleepBars
import com.phasewake.app.core.SleepNotes
import com.phasewake.app.core.SleepStage
import com.phasewake.app.core.SleepSummary
import com.phasewake.app.core.StageSpan
import com.phasewake.app.core.WakeKind
import com.phasewake.app.core.WakeMoments
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.notes.noteName
import com.phasewake.app.ui.theme.PhaseWakeTheme
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

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
         * der letzten [ChartData.NIGHTS] Uhr-Nächte mit Schlafphasen.
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
                feeling = ChartData.feelingSeries(nights, notes, zone, today),
            )
        }
    }
}

/** Kurze Einblendung der Diagramme; mit «Animationen entfernen» und in Bildschirmfotos keine. */
private const val FADE_MS = 300

/** Höhe der Zeichenfläche: gleich auf allen Karten, damit die Karten beim Blättern nicht springen. */
private val CHART_HEIGHT = 168.dp

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

@Composable
private fun ChartCard(
    title: String,
    takeaway: String?,
    modifier: Modifier,
    trailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                trailing()
            }
            takeaway?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
            }
            content()
        }
    }
}

/** Einblenden über kurze Zeit (Modifier.graphicsLayer); sofort sichtbar bei «Animationen entfernen». */
@Composable
private fun Modifier.fadeIn(): Modifier {
    val context = LocalContext.current
    val noAnimations = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val still = LocalInspectionMode.current || noAnimations
    val alpha = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { if (!still) alpha.animateTo(1f, tween(FADE_MS)) }
    return graphicsLayer { this.alpha = alpha.value }
}

/** Nachtdatum als Datum des Morgens (wie die Nächte im Verlauf), z. B. «28. Okt.». */
private fun morning(date: LocalDate, skeleton: String = "dMMM"): String {
    val locale = Locale.getDefault()
    return DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(date.plusDays(1))
}

/** Minuten ab Mittag als Uhrzeit. */
private fun clock(minutesFromNoon: Int): String = Format.time(LocalTime.of(12, 0).plusMinutes(minutesFromNoon.toLong()))

private fun decimal(value: Double) = String.format(Locale.getDefault(), "%.1f", value)

private fun DrawScope.dashed(color: Color, from: Offset, to: Offset) =
    drawLine(color, from, to, strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))

@Composable
private fun labelStyle(): TextStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)

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
private fun SleepBarsCard(bars: SleepBars, modifier: Modifier) {
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
            Legend(listOf(LegendItem(context.getString(R.string.chart_bedtime, clock(it)), LegendMark.DASH, scheme.primary)))
        }
    }
}

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
private fun DurationsCard(week: Durations?, twoWeeks: Durations, modifier: Modifier) {
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
            val top = 6.dp.toPx()
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
                    val yTop = y(minutes)
                    drawRoundRect(if (bar.reached) brand.chartActive else brand.chartQuiet, Offset(x, yTop), Size(w, top + plot - yTop), CornerRadius(minOf(w / 2, 6.dp.toPx())))
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
        Legend(listOf(LegendItem(context.getString(R.string.chart_goal, Format.duration(context, Duration.ofMinutes(d.goalMinutes.toLong()))), LegendMark.DASH, scheme.primary)))
    }
}

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

private enum class LegendMark { DOT, DIAMOND, RING, CROSS, DASH, SHADE }

private data class LegendItem(val text: String, val mark: LegendMark, val color: Color)

private fun DrawScope.mark(mark: LegendMark, color: Color, center: Offset, r: Float) {
    when (mark) {
        LegendMark.DOT -> drawCircle(color, r, center)
        LegendMark.DIAMOND -> drawPath(
            Path().apply {
                moveTo(center.x, center.y - r * 1.2f)
                lineTo(center.x + r * 1.2f, center.y)
                lineTo(center.x, center.y + r * 1.2f)
                lineTo(center.x - r * 1.2f, center.y)
                close()
            },
            color,
        )
        LegendMark.RING -> drawCircle(color, r * 0.85f, center, style = Stroke(r * 0.45f))
        LegendMark.CROSS -> {
            val s = r * 0.9f
            drawLine(color, Offset(center.x - s, center.y - s), Offset(center.x + s, center.y + s), strokeWidth = r * 0.5f, cap = StrokeCap.Round)
            drawLine(color, Offset(center.x - s, center.y + s), Offset(center.x + s, center.y - s), strokeWidth = r * 0.5f, cap = StrokeCap.Round)
        }
        LegendMark.DASH -> dashed(color, Offset(center.x - r * 2, center.y), Offset(center.x + r * 2, center.y))
        LegendMark.SHADE -> drawRoundRect(color, Offset(center.x - r * 2, center.y - r), Size(r * 4, r * 2), CornerRadius(r / 2))
    }
}

/** Legende unter einem Diagramm; für Bildschirmleser steht alles schon in der Beschreibung. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(items: List<LegendItem>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clearAndSetSemantics { },
    ) {
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(width = 16.dp, height = 12.dp)) { mark(item.mark, item.color, center, 3.5.dp.toPx()) }
                Text(item.text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

/** Form und Farbe je Weckart: Form allein unterscheidet schon (nicht nur Farbe). */
private fun wakeStyle(kind: WakeKind, active: Color, muted: Color, error: Color): Pair<LegendMark, Color> = when (kind) {
    WakeKind.LIGHT -> LegendMark.DOT to active
    WakeKind.AWAKE -> LegendMark.DIAMOND to muted
    WakeKind.TARGET -> LegendMark.RING to muted
    WakeKind.LATE -> LegendMark.CROSS to error
}

@Composable
private fun WakeCard(w: WakeMoments, modifier: Modifier) {
    val context = LocalContext.current
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
            val labels = listOf(0, w.scaleMinutes / 2, w.scaleMinutes).map { measurer.measure(context.getString(R.string.duration_minutes, it.toLong()), label) }
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
                LegendItem(context.getString(text), shape, color)
            },
        )
    }
}

// --- 4. Schlafphasen der Nacht ---

private val stageNames = listOf(R.string.chart_stage_awake, R.string.chart_stage_rem, R.string.chart_stage_light, R.string.chart_stage_deep)
private val stageOrder = listOf(SleepStage.AWAKE, SleepStage.REM, SleepStage.LIGHT, SleepStage.DEEP)

/** Wie der Abgleich in der Nachtkarte: «Gesundheitsdaten: leichter Schlaf ✓». */
fun hypnogramTakeaway(context: Context, h: Hypnogram): String? = when (h.stageAtWake) {
    SleepStage.LIGHT -> context.getString(R.string.verdict_good, context.getString(R.string.stage_light))
    SleepStage.REM -> context.getString(R.string.verdict_good, context.getString(R.string.stage_rem))
    SleepStage.AWAKE -> context.getString(R.string.verdict_good, context.getString(R.string.stage_awake))
    SleepStage.DEEP -> context.getString(R.string.verdict_deep)
    SleepStage.UNKNOWN, null -> null
}

/** «Wach 20 Min · REM 1 Std. 20 Min · Leicht 2 Std. 10 Min · Tief 1 Std.» */
private fun stageTotals(context: Context, h: Hypnogram): String = stageOrder.mapIndexedNotNull { i, stage ->
    h.minutesOf(stage).takeIf { it > 0 }?.let { context.getString(stageNames[i]) + " " + Format.duration(context, Duration.ofMinutes(it)) }
}.joinToString(" · ")

@Composable
private fun HypnogramCard(h: Hypnogram, night: NightRecord, modifier: Modifier) {
    val context = LocalContext.current
    val brand = PhaseWakeTheme.brand
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val label = labelStyle()
    val zone = ZoneId.systemDefault()
    val date = morning(SleepNotes.nightDate(night, zone))
    val description = remember(h) {
        listOfNotNull(
            context.getString(R.string.chart_stages_title) + " " + date,
            hypnogramTakeaway(context, h),
            Format.time(h.start, zone) + " – " + Format.time(h.end, zone),
            context.getString(R.string.chart_window) + " " + Format.time(night.windowStart, zone) + " – " + Format.time(night.target, zone),
            stageTotals(context, h),
        ).joinToString(". ")
    }
    ChartCard(
        stringResource(R.string.chart_stages_title),
        hypnogramTakeaway(context, h),
        modifier,
        trailing = { Text(date, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant) },
    ) {
        Canvas(Modifier.fillMaxWidth().height(CHART_HEIGHT).fadeIn().semantics { contentDescription = description }) {
            val names = stageNames.map { measurer.measure(context.getString(it), label) }
            val left = names.maxOf { it.size.width } + 8.dp.toPx()
            val axisHeight = 16.dp.toPx()
            val plot = size.height - axisHeight
            val lane = plot / 4
            val total = h.totalMinutes.toFloat().coerceAtLeast(1f)
            fun x(minute: Double) = left + (size.width - left) * (minute.toFloat() / total)
            // Weckfenster als Fläche.
            drawRect(scheme.primary.copy(alpha = 0.12f), Offset(x(h.windowFrom), 0f), Size(x(h.windowTo) - x(h.windowFrom), plot))
            names.forEachIndexed { i, text ->
                val cy = lane * (i + 0.5f)
                drawText(text, topLeft = Offset(0f, cy - text.size.height / 2f))
                drawLine(scheme.outlineVariant, Offset(left, cy), Offset(size.width, cy), strokeWidth = 1.dp.toPx())
            }
            // Stufen: Balken je Phase, dünne Verbindungen zwischen aufeinanderfolgenden Phasen.
            val barHeight = lane * 0.5f
            var previous: Pair<Float, Float>? = null
            h.steps.forEach { step ->
                val level = ChartData.level(step.stage) ?: return@forEach
                val cy = lane * (level + 0.5f)
                val x0 = x(step.fromMinute)
                val x1 = x(step.toMinute)
                previous?.let { (px, py) ->
                    if (abs(px - x0) < 1.dp.toPx()) drawLine(scheme.onSurfaceVariant, Offset(x0, py), Offset(x0, cy), strokeWidth = 1.dp.toPx())
                }
                val color = when (step.stage) {
                    SleepStage.DEEP -> brand.echo
                    SleepStage.REM -> brand.chartQuiet
                    SleepStage.LIGHT -> brand.wave
                    else -> brand.dot
                }
                drawRoundRect(color, Offset(x0, cy - barHeight / 2), Size((x1 - x0).coerceAtLeast(1.dp.toPx()), barHeight), CornerRadius(2.dp.toPx()))
                previous = x1 to cy
            }
            // Weckmoment.
            val wx = x(h.wokeAt)
            drawLine(scheme.primary, Offset(wx, 0f), Offset(wx, plot), strokeWidth = 2.dp.toPx())
            drawCircle(scheme.primary, 4.dp.toPx(), Offset(wx, 4.dp.toPx()))
            val start = measurer.measure(Format.time(h.start, zone), label)
            drawText(start, topLeft = Offset(left, size.height - start.size.height))
            val end = measurer.measure(Format.time(h.end, zone), label)
            drawText(end, topLeft = Offset(size.width - end.size.width, size.height - end.size.height))
        }
        Legend(
            listOf(
                LegendItem(context.getString(R.string.chart_window), LegendMark.SHADE, scheme.primary.copy(alpha = 0.25f)),
                LegendItem(Format.time(night.wokeAt, zone), LegendMark.DOT, scheme.primary),
            ),
        )
        Text(
            stageTotals(context, h),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp).clearAndSetSemantics { },
        )
    }
}

// --- 5. Befinden ---

fun feelingTakeaway(context: Context, f: FeelingSeries): String? {
    val last = f.lastWeek?.let { context.getString(R.string.trends_feeling_change, decimal(it)) }
    return when {
        f.thisWeek != null -> context.getString(R.string.trends_feeling, decimal(f.thisWeek)) + (last?.let { " · $it" } ?: "")
        else -> last
    }
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
private fun FeelingCard(f: FeelingSeries, custom: List<CustomNote>, modifier: Modifier) {
    val context = LocalContext.current
    val brand = PhaseWakeTheme.brand
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val label = labelStyle()
    var selected by remember(f) { mutableStateOf<Int?>(null) }
    val withNotes = f.points.any { p -> p.feeling != null && p.notes.any { noteName(context, it, custom) != null } }
    val description = remember(f, custom) {
        (listOfNotNull(context.getString(R.string.chart_feeling_title), feelingTakeaway(context, f)) +
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
