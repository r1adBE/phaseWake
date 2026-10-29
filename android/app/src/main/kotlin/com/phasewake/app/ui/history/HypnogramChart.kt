package com.phasewake.app.ui.history

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.phasewake.app.core.ChartData
import com.phasewake.app.core.Hypnogram
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.SleepNotes
import com.phasewake.app.core.SleepStage
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.theme.PhaseWakeTheme
import java.time.Duration
import java.time.ZoneId
import kotlin.math.abs

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
internal fun HypnogramCard(h: Hypnogram, night: NightRecord, modifier: Modifier) {
    val context = LocalContext.current
    val res = LocalResources.current
    val brand = PhaseWakeTheme.brand
    val colors = stageColors()
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val label = labelStyle()
    val zone = ZoneId.systemDefault()
    val date = morning(SleepNotes.nightDate(night, zone))
    val description = remember(h) {
        listOfNotNull(
            res.getString(R.string.chart_stages_title) + " " + date,
            hypnogramTakeaway(context, h),
            Format.time(h.start, zone) + " – " + Format.time(h.end, zone),
            res.getString(R.string.chart_window) + " " + Format.time(night.windowStart, zone) + " – " + Format.time(night.target, zone),
            stageTotals(context, h),
        ).joinToString(". ")
    }
    val trailing = @Composable { Text(date, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant) }
    ChartCard(stringResource(R.string.chart_stages_title), hypnogramTakeaway(context, h), modifier, trailing = trailing) {
        Canvas(Modifier.fillMaxWidth().height(CHART_HEIGHT).fadeIn().semantics { contentDescription = description }) {
            val names = stageNames.map { measurer.measure(res.getString(it), label) }
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
                val color = colors[step.stage] ?: brand.dot
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
                LegendItem(res.getString(R.string.chart_window), LegendMark.SHADE, scheme.primary.copy(alpha = 0.25f)),
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
