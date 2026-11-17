package com.phasewake.app.wear.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
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
    /** Bogen des Weckfensters zeichnen; `false` bei «Genau» (nur der Weckpunkt). */
    showArc: Boolean = true,
) {
    // Einmal beim Öffnen: Das Weckfenster zeichnet sich ein. Keine Dauerbewegung (Akku); ohne Bewegung gleich ganz.
    val draw = rememberWatchReveal(startFraction to endFraction)
    Canvas(modifier.clearAndSetSemantics { }) {
        val stroke = 8.dp.toPx()
        val inset = stroke / 2 + 4.dp.toPx()
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
            val length = if (k == 0) stroke * 1.4f else stroke * 0.9f
            drawLine(
                Brand.Muted,
                at(f, r - stroke * 0.9f),
                at(f, r - stroke * 0.9f - length),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        val sweep = ((endFraction - startFraction + 1f) % 1f).coerceAtLeast(0.01f) * 360f
        val start = startFraction * 360f - 90f
        // Countdown: von «jetzt» bis zum Weckfenster ein ruhiger Lavendel-Verlauf, wie der Bogen auf dem Handy.
        nowFraction?.let { now ->
            val lead = ((startFraction - now + 1f) % 1f) * 360f
            if (lead in 1f..359f) drawLeadArc(now * 360f - 90f, lead * draw, topLeft, arcSize, stroke)
        }
        if (showArc) {
            // Leuchtendes Weckfenster: breiter weicher Schein, darauf der Bogen im Verlauf.
            drawArc(
                Brand.Wave.copy(alpha = 0.22f),
                start,
                sweep * draw,
                false,
                topLeft,
                arcSize,
                style = Stroke(stroke * 2.2f, cap = StrokeCap.Round),
            )
            drawGlowArc(start, sweep * draw, topLeft, arcSize, stroke)
        }
        // Hohl, damit die Spur durchscheint; Mist hat auf Spur und Nachthimmel über 8 : 1 Kontrast.
        bedtimeFraction?.let { bed ->
            drawCircle(Brand.Mist, radius = stroke * 0.6f, center = at(bed, r), style = Stroke(1.5.dp.toPx()))
        }
        nowFraction?.let { now ->
            drawLine(
                Brand.Moon,
                at(now, r - stroke * 1.2f),
                at(now, r + stroke * 1.2f),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        // Weckpunkt mit ruhendem Schein und Ring (keine Dauerbewegung).
        val dot = at(endFraction, r)
        drawCircle(
            Brush.radialGradient(
                listOf(Brand.Wave.copy(alpha = 0.6f), Brand.Wave.copy(alpha = 0f)),
                center = dot,
                radius = stroke * 2.4f,
            ),
            radius = stroke * 2.4f,
            center = dot,
        )
        drawCircle(Brand.Moon.copy(alpha = 0.7f), radius = stroke * 1.0f, center = dot, style = Stroke(1.5.dp.toPx()))
        drawCircle(Brand.Moon, radius = stroke * 0.6f, center = dot)
    }
}

/** Strecke von «jetzt» bis zum Weckfenster: vom tiefen Violett leise zum Lavendel-Schein, schmaler als das Fenster. */
private fun DrawScope.drawLeadArc(start: Float, sweep: Float, topLeft: Offset, arcSize: Size, stroke: Float) {
    if (sweep <= 0f) return
    val pieces = (sweep / ARC_PIECE_DEGREES).toInt().coerceAtLeast(1)
    val piece = sweep / pieces
    for (k in 0 until pieces) {
        val t = (k + 0.5f) / pieces
        drawArc(
            lerp(Brand.Deep, Brand.Echo, t),
            start + k * piece,
            piece + if (k < pieces - 1) 0.6f else 0f,
            false,
            topLeft,
            arcSize,
            style = Stroke(stroke * 0.55f, cap = StrokeCap.Butt),
        )
    }
}

/**
 * Weckfenster als Bogen im Verlauf vom Lavendel-Schein ([Brand.Glow], ≥ 3:1 zur Spur) zum hellen Lavendel am Weckpunkt:
 * in kurzen Stücken gezeichnet, damit der Verlauf auch über 12 Uhr hinweg stimmt; runde Enden.
 */
private fun DrawScope.drawGlowArc(start: Float, sweep: Float, topLeft: Offset, arcSize: Size, stroke: Float) {
    if (sweep <= 0f) return
    val pieces = (sweep / ARC_PIECE_DEGREES).toInt().coerceAtLeast(1)
    val piece = sweep / pieces
    for (k in 0 until pieces) {
        val t = (k + 0.5f) / pieces
        val color = if (t < 0.7f) {
            lerp(Brand.Glow, Brand.Wave, t / 0.7f)
        } else {
            lerp(Brand.Wave, Brand.Moon, (t - 0.7f) / 0.3f)
        }
        val cap = if (k == 0 || k == pieces - 1) StrokeCap.Round else StrokeCap.Butt
        // Ein Hauch Überlappung, damit keine Fugen zwischen den Stücken sichtbar sind.
        drawArc(
            color,
            start + k * piece,
            piece + if (k < pieces - 1) 0.6f else 0f,
            false,
            topLeft,
            arcSize,
            style = Stroke(stroke, cap = cap),
        )
    }
}

/** Länge eines Bogenstücks im Verlauf. */
private const val ARC_PIECE_DEGREES = 4f

/**
 * Die Welle aus dem Logo, zum Weckpunkt hin auslaufend und heller werdend. Ruhend: Auf der Uhr keine Dauerbewegung
 * (Akku).
 */
@Composable
fun FlowingWave(modifier: Modifier = Modifier, color: Color = Brand.Wave) {
    Canvas(modifier.clearAndSetSemantics { }) {
        val path = Path()
        val amplitude = size.height * 0.32f
        val steps = 48
        for (i in 0..steps) {
            val x = size.width * i / steps
            // Nach rechts abflachend wie im Logo: die Welle läuft im Weckpunkt aus.
            val damp = 1f - 0.55f * i / steps
            val y = size.height / 2 + amplitude * damp * sin(WAVE_PHASE + i / steps.toFloat() * 3.2f * PI.toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val brush = Brush.horizontalGradient(listOf(color.copy(alpha = 0.6f), color, Brand.Moon))
        drawPath(path, brush, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Fester Phasenversatz der ruhenden Welle (Bogen beginnt wie im Logo unten). */
private const val WAVE_PHASE = 0.6f

/**
 * Ringe um den Weckpunkt (Logo-Motiv), für den Weckbildschirm. [color]/[dot]: hell auf Sonnenaufgang anpassbar.
 * Pulsiert fortlaufend, solange [pulsing] (es klingelt, bis zum Stoppen); sonst und ohne Bewegung ruhend. Sonst keine
 * Dauerbewegung auf der Uhr.
 */
@Composable
fun PulsingDot(
    modifier: Modifier = Modifier,
    pulsing: Boolean = true,
    color: Color = Brand.Wave,
    dot: Color = Brand.Moon,
) {
    val t = if (pulsing && watchMotionAllowed()) {
        val v by rememberInfiniteTransition(label = "pulse").animateFloat(
            0f, 1f, infiniteRepeatable(tween(PULSE_MS, easing = LinearEasing)), label = "t",
        )
        v
    } else {
        STILL_PULSE
    }
    Canvas(modifier.clearAndSetSemantics { }) {
        val base = size.minDimension / 2
        for (k in 0 until 3) {
            val p = (t + k / 3f) % 1f
            drawCircle(
                color.copy(alpha = (1f - p) * 0.75f),
                radius = base * (0.25f + 0.75f * p),
                style = Stroke(2.dp.toPx()),
            )
        }
        drawCircle(dot, radius = base * 0.22f)
    }
}

/** Dauer eines Pulses. */
private const val PULSE_MS = 1800

/** Ruhestand der Ringe. */
private const val STILL_PULSE = 0.2f

/** Ob Zierbewegung laufen darf: nicht bei «Animationen entfernen», nicht in Vorschau und Bildschirmfotos. */
@Composable
internal fun watchMotionAllowed(): Boolean {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    return remember(inspection) {
        !inspection && Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    }
}

/** Einmaliges Erscheinen 0 → 1 (neu bei geändertem [key]); ohne Bewegung sofort 1. */
@Composable
internal fun rememberWatchReveal(key: Any?): Float {
    val allowed = watchMotionAllowed()
    val progress = remember(key) { Animatable(if (allowed) 0f else 1f) }
    LaunchedEffect(key, allowed) {
        if (allowed) progress.animateTo(1f, tween(REVEAL_MS, easing = FastOutSlowInEasing)) else progress.snapTo(1f)
    }
    return progress.value
}

/** Dauer des Einzeichnens auf der Uhr. */
private const val REVEAL_MS = 700
