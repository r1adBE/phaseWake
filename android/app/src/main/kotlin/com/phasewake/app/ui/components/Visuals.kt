package com.phasewake.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
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
 * Rands) auslaufen und im pulsierenden Weckpunkt enden. Das Weckfenster leuchtet als weiches Band, die Welle wird zum
 * Weckpunkt hin heller und zeichnet sich beim Erscheinen einmal von links her. Mit «Animationen entfernen» ruht sie.
 */
@Composable
fun HeroWave(modifier: Modifier = Modifier, windowFraction: Float = 0.3f, onNight: Boolean = false) {
    val themed = PhaseWakeTheme.brand
    // Im Himmel eines Kopfbereichs ([onNight]) dessen Farben: dunkel die hellen Nachtfarben, hell die des Tageshimmels.
    val dark = PhaseWakeTheme.isDark
    val ink = skyInk()
    val brand = if (onNight) {
        themed.copy(heroWindow = if (dark) Night.Beam.copy(alpha = 0.16f) else Day.Wave.copy(alpha = 0.12f), wave = ink.wave, echo = ink.waveEcho, dot = ink.moon)
    } else {
        themed
    }
    val moving = motionAllowed()
    val reveal = rememberReveal(Unit)
    // Die laufenden Werte erst beim Zeichnen lesen (Audit PERF-08): Dann zeichnet nur der Canvas je Bild neu, statt
    // dass sich die Welle je Bild neu zusammensetzt.
    val phaseState: State<Float>
    val pulseState: State<Float>
    if (moving) {
        val transition = rememberInfiniteTransition(label = "hero")
        phaseState = transition.animateFloat(
            0f, (2 * PI).toFloat(), infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "phase",
        )
        pulseState = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "pulse")
    } else {
        phaseState = remember { mutableFloatStateOf(0f) }
        pulseState = remember { mutableFloatStateOf(STILL_PULSE) }
    }
    // Reine Zierde: für Bildschirmleser unsichtbar.
    Canvas(modifier.clearAndSetSemantics { }) {
        val phase = phaseState.value
        val pulse = pulseState.value
        drawWindowBand(brand.heroWindow, windowFraction, reveal)
        val dotX = size.width - 14.dp.toPx()
        val steps = 80
        fun y(i: Int): Float {
            val f = i / steps.toFloat()
            val damp = 1f - 0.6f * f
            return size.height * 0.55f + size.height * 0.32f * damp * sin(phase + f * 4.6f * PI.toFloat())
        }
        // Beim Erscheinen nur bis zum Fortschritt [reveal] zeichnen.
        val shown = (steps * reveal).toInt().coerceIn(1, steps)
        val echo = Path()
        val wave = Path()
        for (i in 0..shown) {
            val x = dotX * i / steps
            if (i == 0) {
                echo.moveTo(x + 6f, y(i) + 8f)
                wave.moveTo(x, y(i))
            } else {
                echo.lineTo(x + 6f, y(i) + 8f)
                wave.lineTo(x, y(i))
            }
        }
        // Echo leise, Welle zum Weckpunkt hin heller: Die Nacht läuft auf das Wecken zu.
        val glowing = Brush.horizontalGradient(
            0f to brand.wave.copy(alpha = 0.7f),
            0.55f to brand.wave,
            1f to brand.dot,
            startX = 0f,
            endX = dotX,
        )
        drawPath(echo, brand.echo.copy(alpha = 0.8f), style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round))
        drawPath(wave, glowing, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
        if (shown < steps) return@Canvas
        val dot = Offset(dotX, y(steps))
        // Leuchten um den Weckpunkt, darüber der Puls-Ring (ruhend: ein stiller Ring).
        drawCircle(
            Brush.radialGradient(listOf(brand.wave.copy(alpha = 0.45f), brand.wave.copy(alpha = 0f)), center = dot, radius = 22.dp.toPx()),
            radius = 22.dp.toPx(),
            center = dot,
        )
        drawCircle(brand.wave.copy(alpha = (1f - pulse) * 0.6f), radius = 6.dp.toPx() + 12.dp.toPx() * pulse, center = dot, style = Stroke(1.5.dp.toPx()))
        drawCircle(brand.dot, radius = 5.dp.toPx(), center = dot)
    }
}

/**
 * Das Weckfenster als leuchtendes Band am rechten Rand: senkrecht weich auslaufend, links mit feinem Lichtrand,
 * erscheint mit [reveal] von unten.
 */
private fun DrawScope.drawWindowBand(color: Color, windowFraction: Float, reveal: Float) {
    if (windowFraction <= 0f) return
    val left = size.width * (1f - windowFraction)
    val width = size.width - left
    val alpha = color.alpha * reveal
    drawRoundRect(
        Brush.verticalGradient(
            0f to color.copy(alpha = 0f),
            0.5f to color.copy(alpha = alpha),
            1f to color.copy(alpha = alpha * 0.4f),
        ),
        Offset(left, 0f), Size(width, size.height),
        CornerRadius(14.dp.toPx()),
    )
    // Lichtrand: Beginn des Fensters als feine, oben und unten auslaufende Linie.
    drawLine(
        Brush.verticalGradient(
            0f to color.copy(alpha = 0f),
            0.5f to color.copy(alpha = (alpha * 3f).coerceAtMost(1f)),
            1f to color.copy(alpha = 0f),
        ),
        Offset(left, 0f), Offset(left, size.height),
        strokeWidth = 1.dp.toPx(),
    )
}

/** Puls-Ring ohne Bewegung: als stiller Ring um den Weckpunkt. */
private const val STILL_PULSE = 0.45f

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
    // Wächst einmal von unten herauf; mit «Animationen entfernen» gleich ganz da.
    val grow = rememberReveal(epochs, durationMs = 900)
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
            val h = if (m == null) minBar else (minBar + (size.height - chartTop - minBar) * (m / maxMotion).toFloat()) * grow
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
            drawCircle(brand.dot, radius = 4.dp.toPx() * grow, center = Offset(x, chartTop / 2 + 1.dp.toPx()))
        }
    }
}

/** Pulsierende Ringe um den Weckpunkt (Logo-Motiv), für den Weckbildschirm; mit «Animationen entfernen» ruhend. */
@Composable
fun PulsingDot(modifier: Modifier = Modifier, color: Color, dot: Color) {
    // Erst beim Zeichnen gelesen (Audit PERF-08), wie bei [HeroWave].
    val progress: State<Float> = if (motionAllowed()) {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "t",
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    Canvas(modifier.clearAndSetSemantics { }) {
        val t = progress.value
        val base = size.minDimension / 2
        for (k in 0 until 3) {
            val p = (t + k / 3f) % 1f
            drawCircle(color.copy(alpha = (1f - p) * 0.7f), radius = base * (0.22f + 0.78f * p), style = Stroke(2.dp.toPx()))
        }
        drawCircle(dot, radius = base * 0.2f)
    }
}
