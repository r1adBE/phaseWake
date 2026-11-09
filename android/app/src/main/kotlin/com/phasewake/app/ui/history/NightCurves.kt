package com.phasewake.app.ui.history

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.Epoch
import com.phasewake.app.core.FactorScore
import com.phasewake.app.core.HealthSession
import com.phasewake.app.core.HistoryItem
import com.phasewake.app.core.PulseCurve
import com.phasewake.app.core.ScoreBand
import com.phasewake.app.core.ScoreFactor
import com.phasewake.app.core.ScoreInput
import com.phasewake.app.core.SleepScore
import com.phasewake.app.core.SleepScoreResult
import com.phasewake.app.core.SleepStage
import com.phasewake.app.health.HealthSource
import com.phasewake.app.shared.NightRecording
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.theme.PhaseWakeTheme
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

// Puls- und Bewegungsverlauf der Nacht auf der Detailseite.

/** Höhe des Puls- und des Bewegungsverlaufs. */
private val PULSE_HEIGHT = 128.dp
private val MOTION_HEIGHT = 56.dp

/**
 * Pulsverlauf der Nacht ([com.phasewake.app.core.SleepOverviews.pulse]): Linie je 5 Min (Lücken unterbrochen), Mittel
 * gestrichelt, Beginn und Ende als Uhrzeit. Ohne ganze Aufzeichnung steht dabei, dass es das Weckfenster ist.
 */
@Composable
internal fun PulseCard(curve: PulseCurve, fullNight: Boolean, modifier: Modifier = Modifier) {
    val brand = PhaseWakeTheme.brand
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val label = labelStyle()
    val title = stringResource(R.string.pulse_title)
    val average = stringResource(R.string.pulse_average, curve.average)
    val from = Format.time(curve.start)
    val to = Format.time(curve.end)
    val window = if (fullNight) null else stringResource(R.string.chart_window)
    val description = listOfNotNull(title, window, average, "$from – $to").joinToString(". ")
    ChartCard(title, window, modifier) {
        Canvas(Modifier.fillMaxWidth().height(PULSE_HEIGHT).padding(top = 4.dp).fadeIn().clearAndSetSemantics { contentDescription = description }) {
            val axis = 16.dp.toPx()
            val plot = size.height - axis - 4.dp.toPx()
            val lo = floor(curve.low) - 3
            val hi = ceil(curve.high) + 3
            fun y(v: Double) = 2.dp.toPx() + plot * (1f - ((v - lo) / (hi - lo)).toFloat())
            val n = curve.buckets.size
            fun x(i: Int) = if (n <= 1) size.width / 2 else size.width * i / (n - 1)
            val path = Path()
            // Fläche unter der Linie, je zusammenhängendes Stück, leise ausblendend.
            val area = Path()
            val base = 2.dp.toPx() + plot
            var runStart = -1
            curve.buckets.forEachIndexed { i, v ->
                if (v == null) {
                    if (runStart >= 0) {
                        area.lineTo(x(i - 1), base)
                        area.close()
                    }
                    runStart = -1
                } else if (runStart < 0) {
                    path.moveTo(x(i), y(v))
                    area.moveTo(x(i), base)
                    area.lineTo(x(i), y(v))
                    runStart = i
                } else {
                    path.lineTo(x(i), y(v))
                    area.lineTo(x(i), y(v))
                }
            }
            if (runStart >= 0) {
                area.lineTo(x(curve.buckets.lastIndex), base)
                area.close()
            }
            drawPath(area, Brush.verticalGradient(listOf(brand.stageAwake.copy(alpha = 0.28f), brand.stageAwake.copy(alpha = 0f)), startY = 0f, endY = base))
            // Einzelne Stücke zwischen Lücken als Punkt, damit sie sichtbar bleiben.
            curve.buckets.forEachIndexed { i, v ->
                if (v != null && curve.buckets.getOrNull(i - 1) == null && curve.buckets.getOrNull(i + 1) == null) drawCircle(brand.stageAwake, 2.dp.toPx(), Offset(x(i), y(v)))
            }
            drawPath(path, brand.stageAwake, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            val avgY = y(curve.average.toDouble())
            dashed(scheme.primary, Offset(0f, avgY), Offset(size.width, avgY))
            val start = measurer.measure(from, label)
            drawText(start, topLeft = Offset(0f, size.height - start.size.height))
            val end = measurer.measure(to, label)
            drawText(end, topLeft = Offset(size.width - end.size.width, size.height - end.size.height))
        }
        Legend(listOf(LegendItem(average, LegendMark.DASH, scheme.primary)))
    }
}

/** Bewegung über die ganze Aufzeichnung: je 5 Min die stärkste Bewegung als runde Säule; ohne Messwert ein flacher Strich. */
@Composable
internal fun MotionCard(series: NightSeries, modifier: Modifier = Modifier) {
    val brand = PhaseWakeTheme.brand
    val title = stringResource(R.string.motion_title)
    val from = Format.time(series.start)
    val to = Format.time(series.start.plusSeconds(series.epochs.size * 30L))
    val buckets = remember(series) {
        series.epochs.chunked(PulseCurve.BUCKET_EPOCHS).map { chunk -> chunk.mapNotNull { it.motion }.maxOrNull() }
    }
    ChartCard(title, null, modifier) {
        Canvas(Modifier.fillMaxWidth().height(MOTION_HEIGHT).fadeIn().clearAndSetSemantics { contentDescription = "$title. $from – $to" }) {
            val n = buckets.size.coerceAtLeast(1)
            val gap = if (n > 60) 1.dp.toPx() else 2.dp.toPx()
            val w = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
            val top = buckets.filterNotNull().maxOrNull()?.coerceAtLeast(0.01) ?: 0.01
            val min = 2.dp.toPx()
            buckets.forEachIndexed { i, m ->
                val h = if (m == null) min else min + (size.height - min) * (m / top).toFloat().coerceIn(0f, 1f)
                drawRoundRect(
                    if (m == null) brand.chartTrack else brand.chartActive,
                    Offset(i * (w + gap), size.height - h),
                    Size(w, h),
                    CornerRadius(w / 2),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp).clearAndSetSemantics { }) {
            Text(from, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(to, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
