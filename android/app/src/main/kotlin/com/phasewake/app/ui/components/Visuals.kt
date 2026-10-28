package com.phasewake.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.phasewake.app.core.Epoch
import com.phasewake.app.ui.theme.PhaseWakeTheme
import kotlin.math.PI
import kotlin.math.sin

/**
 * Die Welle aus dem Logo, fliessend: Schlafzyklen, die im Weckfenster ([windowFraction] des rechten
 * Rands) auslaufen und im pulsierenden Weckpunkt enden.
 */
@Composable
fun HeroWave(modifier: Modifier = Modifier, windowFraction: Float = 0.3f, onNight: Boolean = false) {
    val themed = PhaseWakeTheme.brand
    // Auf dem Nachthimmel immer die hellen Nachtfarben, unabhängig vom Hell/Dunkel-Modus.
    val brand = if (onNight) {
        themed.copy(heroWindow = Color.White.copy(alpha = 0.08f), wave = Night.Wave, echo = Night.WaveEcho, dot = Color.White)
    } else {
        themed
    }
    val transition = rememberInfiniteTransition(label = "hero")
    val phase by transition.animateFloat(
        0f, (2 * PI).toFloat(), infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "phase",
    )
    val pulse by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "pulse")
    // Reine Zierde: für Bildschirmleser unsichtbar.
    Canvas(modifier.clearAndSetSemantics { }) {
        val windowLeft = size.width * (1f - windowFraction)
        drawRoundRect(
            brand.heroWindow, Offset(windowLeft, 0f), Size(size.width - windowLeft, size.height),
            CornerRadius(14.dp.toPx()),
        )
        val dotX = size.width - 14.dp.toPx()
        val steps = 80
        fun y(i: Int): Float {
            val f = i / steps.toFloat()
            val damp = 1f - 0.6f * f
            return size.height * 0.55f + size.height * 0.32f * damp * sin(phase + f * 4.6f * PI.toFloat())
        }
        val echo = Path()
        val wave = Path()
        for (i in 0..steps) {
            val x = dotX * i / steps
            if (i == 0) {
                echo.moveTo(x + 6f, y(i) + 8f)
                wave.moveTo(x, y(i))
            } else {
                echo.lineTo(x + 6f, y(i) + 8f)
                wave.lineTo(x, y(i))
            }
        }
        drawPath(echo, brand.echo, style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round))
        drawPath(wave, brand.wave, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
        val dot = Offset(dotX, y(steps))
        drawCircle(brand.wave.copy(alpha = (1f - pulse) * 0.6f), radius = 6.dp.toPx() + 12.dp.toPx() * pulse, center = dot, style = Stroke(1.5.dp.toPx()))
        drawCircle(brand.dot, radius = 5.dp.toPx(), center = dot)
    }
}

/**
 * Bewegungskurve eines Weckfensters als Balken. Ruhige Epochen gedämpft, die letzten vor dem
 * Wecken kräftig, darüber der Weckpunkt. Wächst beim Erscheinen von unten herauf.
 */
@Composable
fun NightBars(
    epochs: List<Epoch>,
    modifier: Modifier = Modifier,
    totalEpochs: Int = epochs.size,
    description: String? = null,
) {
    val brand = PhaseWakeTheme.brand
    val grow = remember(epochs) { Animatable(0f) }
    LaunchedEffect(epochs) { grow.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    // Die Kurve selbst ist für Bildschirmleser nicht lesbar; der Text daneben sagt dasselbe.
    Canvas(modifier.clearAndSetSemantics { description?.let { contentDescription = it } }) {
        val slots = totalEpochs.coerceAtLeast(epochs.size).coerceAtLeast(1)
        val gap = 2.dp.toPx()
        val barWidth = ((size.width - gap * (slots - 1)) / slots).coerceAtLeast(1f)
        val maxMotion = epochs.maxOfOrNull { it.motion ?: 0.0 }?.coerceAtLeast(0.01) ?: 0.01
        val minBar = 3.dp.toPx()
        val chartTop = 10.dp.toPx()
        epochs.forEachIndexed { i, e ->
            val m = e.motion
            val h = if (m == null) minBar else (minBar + (size.height - chartTop - minBar) * (m / maxMotion).toFloat()) * grow.value
            val x = i * (barWidth + gap)
            val active = i >= epochs.size - 3
            drawRoundRect(
                if (m == null) brand.chartTrack else if (active) brand.chartActive else brand.chartQuiet,
                Offset(x, size.height - h), Size(barWidth, h), CornerRadius(barWidth / 2),
            )
        }
        if (epochs.size < slots) {
            val x0 = epochs.size * (barWidth + gap)
            drawLine(
                brand.chartTrack, Offset(x0, size.height - 1.dp.toPx()), Offset(size.width, size.height - 1.dp.toPx()),
                strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 8f)),
            )
        }
        if (epochs.isNotEmpty()) {
            val x = (epochs.size - 1) * (barWidth + gap) + barWidth / 2
            drawCircle(brand.dot, radius = 4.dp.toPx() * grow.value, center = Offset(x, chartTop / 2 + 1.dp.toPx()))
        }
    }
}

/** Pulsierende Ringe um den Weckpunkt (Logo-Motiv), für den Weckbildschirm. */
@Composable
fun PulsingDot(modifier: Modifier = Modifier, color: Color, dot: Color) {
    val t by rememberInfiniteTransition(label = "pulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "t",
    )
    Canvas(modifier.clearAndSetSemantics { }) {
        val base = size.minDimension / 2
        for (k in 0 until 3) {
            val p = (t + k / 3f) % 1f
            drawCircle(color.copy(alpha = (1f - p) * 0.7f), radius = base * (0.22f + 0.78f * p), style = Stroke(2.dp.toPx()))
        }
        drawCircle(dot, radius = base * 0.2f)
    }
}
