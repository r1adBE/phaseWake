package com.phasewake.app.wear.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.phasewake.app.wear.ui.theme.Brand
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ring am Bildschirmrand als 12-Stunden-Zifferblatt: Spur über den ganzen Kreis mit feinen Marken für
 * 12, 3, 6 und 9 Uhr, darauf das Weckfenster als Bogen mit dem Weckpunkt (runder Punkt) am Ende, ein
 * kurzer Zeiger für «jetzt» (Strich quer zur Spur) und der Einschlafpunkt (Weckzeit minus Schlafziel) als
 * feiner hohler Kreis. Drei Formen, damit nicht die Farbe allein unterscheidet.
 * [startFraction]/[endFraction]/[nowFraction]/[bedtimeFraction] sind Positionen auf dem Zifferblatt
 * (0–1, 0 = 12 Uhr); ohne [nowFraction] kein Zeiger, ohne [bedtimeFraction] kein Einschlafpunkt.
 */
@Composable
fun WindowRing(
    startFraction: Float,
    endFraction: Float,
    modifier: Modifier = Modifier,
    nowFraction: Float? = null,
    bedtimeFraction: Float? = null,
) {
    val glow by rememberInfiniteTransition(label = "ring").animateFloat(
        initialValue = 0.35f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "glow",
    )
    Canvas(modifier.clearAndSetSemantics { }) {
        val stroke = 6.dp.toPx()
        val inset = stroke / 2 + 2.dp.toPx()
        val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
        val topLeft = Offset(inset, inset)
        val r = arcSize.width / 2
        fun at(fraction: Float, radius: Float): Offset {
            val a = (fraction * 360f - 90f) / 180f * PI.toFloat()
            return Offset(center.x + radius * cos(a), center.y + radius * sin(a))
        }
        drawArc(Brand.Track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
        // Stundenmarken innen an der Spur; 12 Uhr etwas länger, damit oben erkennbar ist.
        for (k in 0 until 4) {
            val f = k / 4f
            val length = if (k == 0) stroke * 1.6f else stroke
            drawLine(Brand.Muted, at(f, r - stroke * 0.9f), at(f, r - stroke * 0.9f - length), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
        }
        val sweep = ((endFraction - startFraction + 1f) % 1f).coerceAtLeast(0.01f) * 360f
        val start = startFraction * 360f - 90f
        drawArc(Brand.Wave, start, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        // Hohl, damit die Spur durchscheint; Mist hat auf Spur und Nachthimmel über 8 : 1 Kontrast.
        bedtimeFraction?.let { bed ->
            drawCircle(Brand.Mist, radius = stroke * 0.7f, center = at(bed, r), style = Stroke(1.5.dp.toPx()))
        }
        nowFraction?.let { now ->
            drawLine(Brand.Moon, at(now, r - stroke * 1.3f), at(now, r + stroke * 1.3f), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
        }
        val dot = at(endFraction, r)
        drawCircle(Brand.Wave.copy(alpha = glow * 0.45f), radius = stroke * 1.6f, center = dot)
        drawCircle(Brand.Moon, radius = stroke * 0.75f, center = dot)
    }
}

/** Die Welle aus dem Logo, langsam fliessend. */
@Composable
fun FlowingWave(modifier: Modifier = Modifier, color: Color = Brand.Wave) {
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "phase",
    )
    Canvas(modifier.clearAndSetSemantics { }) {
        val path = Path()
        val amplitude = size.height * 0.32f
        val steps = 48
        for (i in 0..steps) {
            val x = size.width * i / steps
            // Nach rechts abflachend wie im Logo: die Welle läuft im Weckpunkt aus.
            val damp = 1f - 0.55f * i / steps
            val y = size.height / 2 + amplitude * damp * sin(phase + i / steps.toFloat() * 3.2f * PI.toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Pulsierende Ringe um den Weckpunkt (Logo-Motiv), für den Weckbildschirm. [color]/[dot]: hell auf Sonnenaufgang anpassbar. */
@Composable
fun PulsingDot(modifier: Modifier = Modifier, color: Color = Brand.Wave, dot: Color = Brand.Moon) {
    val t by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "t",
    )
    Canvas(modifier.clearAndSetSemantics { }) {
        val base = size.minDimension / 2
        for (k in 0 until 3) {
            val p = (t + k / 3f) % 1f
            drawCircle(
                color.copy(alpha = (1f - p) * 0.7f),
                radius = base * (0.25f + 0.75f * p),
                style = Stroke(1.5.dp.toPx()),
            )
        }
        drawCircle(dot, radius = base * 0.22f)
    }
}
