package com.phasewake.app.ui.history

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.phasewake.app.core.SleepStage
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.theme.PhaseWakeTheme
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Gemeinsame Bausteine der Diagramme im Verlauf: Karte, Einblenden, Beschriftung, Legende und Marken.

/** Kurze Einblendung der Diagramme; mit «Animationen entfernen» und in Bildschirmfotos keine. */
private const val FADE_MS = 300

@Composable
internal fun ChartCard(
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
internal fun Modifier.fadeIn(): Modifier {
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
internal fun morning(date: LocalDate, skeleton: String = "dMMM"): String {
    val locale = Locale.getDefault()
    return DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(date.plusDays(1))
}

/** Minuten ab Mittag als Uhrzeit. */
internal fun clock(minutesFromNoon: Int): String = Format.time(LocalTime.of(12, 0).plusMinutes(minutesFromNoon.toLong()))

/**
 * Wochendurchschnitt als durchgezogene Linie durch die Mitte der Felder ([ChartData.weekAverage]); `null`-Stellen
 * unterbrechen sie. [x] und [y] rechnen Stelle und Wert in Pixel um.
 */
internal fun DrawScope.averageLine(values: List<Double?>, color: Color, x: (Int) -> Float, y: (Double) -> Float) {
    for (i in 1 until values.size) {
        val a = values[i - 1] ?: continue
        val b = values[i] ?: continue
        drawLine(color, Offset(x(i - 1), y(a)), Offset(x(i), y(b)), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

/** Hervorhebung der gewählten Stelle: helle Fläche über die ganze Höhe. */
internal fun DrawScope.highlight(color: Color, x0: Float, width: Float, bottom: Float) =
    drawRoundRect(color.copy(alpha = 0.14f), Offset(x0, 0f), Size(width, bottom), CornerRadius(4.dp.toPx()))

internal fun DrawScope.dashed(color: Color, from: Offset, to: Offset) =
    drawLine(color, from, to, strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))

@Composable
internal fun labelStyle(): TextStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)

internal enum class LegendMark { DOT, TRIANGLE, SQUARE, RING, CROSS, CHECK, DASH, LINE, SHADE, NONE }

internal data class LegendItem(val text: String, val mark: LegendMark, val color: Color)

internal fun DrawScope.mark(mark: LegendMark, color: Color, center: Offset, r: Float) {
    when (mark) {
        LegendMark.DOT -> drawCircle(color, r, center)
        LegendMark.TRIANGLE -> drawPath(
            Path().apply {
                moveTo(center.x, center.y - r * 1.2f)
                lineTo(center.x + r * 1.15f, center.y + r * 0.85f)
                lineTo(center.x - r * 1.15f, center.y + r * 0.85f)
                close()
            },
            color,
        )
        LegendMark.SQUARE -> drawRect(color, Offset(center.x - r * 0.85f, center.y - r * 0.85f), Size(r * 1.7f, r * 1.7f))
        LegendMark.RING -> drawCircle(color, r * 0.85f, center, style = Stroke(r * 0.45f))
        LegendMark.CROSS -> {
            val s = r * 0.9f
            drawLine(color, Offset(center.x - s, center.y - s), Offset(center.x + s, center.y + s), strokeWidth = r * 0.5f, cap = StrokeCap.Round)
            drawLine(color, Offset(center.x - s, center.y + s), Offset(center.x + s, center.y - s), strokeWidth = r * 0.5f, cap = StrokeCap.Round)
        }
        LegendMark.CHECK -> drawPath(
            Path().apply {
                moveTo(center.x - r, center.y)
                lineTo(center.x - r * 0.3f, center.y + r * 0.7f)
                lineTo(center.x + r, center.y - r * 0.7f)
            },
            color,
            style = Stroke(r * 0.45f, cap = StrokeCap.Round),
        )
        LegendMark.DASH -> dashed(color, Offset(center.x - r * 2, center.y), Offset(center.x + r * 2, center.y))
        LegendMark.LINE -> drawLine(color, Offset(center.x - r * 2, center.y), Offset(center.x + r * 2, center.y), strokeWidth = r * 0.55f, cap = StrokeCap.Round)
        LegendMark.SHADE -> drawRoundRect(color, Offset(center.x - r * 2, center.y - r), Size(r * 4, r * 2), CornerRadius(r / 2))
        // Ohne Marke: Das Zeichen steht schon im Text (z. B. «✓ Ziel erreicht»).
        LegendMark.NONE -> Unit
    }
}

/** Legende unter einem Diagramm; für Bildschirmleser steht alles schon in der Beschreibung. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Legend(items: List<LegendItem>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clearAndSetSemantics { },
    ) {
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.mark != LegendMark.NONE) Canvas(Modifier.size(width = 16.dp, height = 12.dp)) { mark(item.mark, item.color, center, 3.5.dp.toPx()) }
                Text(item.text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

/**
 * Farbe je Schlafphase aus dem Theme ([com.phasewake.app.ui.theme.BrandColors.stageAwake] usw.), in allen Diagrammen gleich;
 * jede mindestens 3:1 zur Karte (WCAG 1.4.11). Unterschieden wird zusätzlich über die Höhe (Zeile) und die Beschriftung.
 */
@Composable
internal fun stageColors(): Map<SleepStage, Color> {
    val brand = PhaseWakeTheme.brand
    return mapOf(
        SleepStage.AWAKE to brand.stageAwake,
        SleepStage.REM to brand.stageRem,
        SleepStage.LIGHT to brand.stageLight,
        SleepStage.DEEP to brand.stageDeep,
    )
}
