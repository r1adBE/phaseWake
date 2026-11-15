package com.phasewake.app.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.phasewake.app.ui.components.Night
import com.phasewake.app.ui.components.rememberReveal
import java.time.Duration
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Countdown zum nächsten Wecker als grosser Bogen über dem Weckfenster (wie eine Sonnenbahn): Der Bogen steht für die
 * letzten [ARC_HOURS] Stunden vor der Zielzeit ([arcPosition]). Links beginnend füllt er sich bis «jetzt» (heller
 * Punkt), rechts leuchtet das Weckfenster als warmes Segment und endet im Weckpunkt. Feine Marken als Skala. Zeichnet
 * sich beim Erscheinen einmal ein, danach ruht er (bewegt sich nur mit der Zeit). Reine Zierde: Die Texte im Bogen sagen
 * dasselbe.
 */
@Composable
internal fun WakeArc(now: Instant, windowStart: Instant, target: Instant, exact: Boolean, modifier: Modifier = Modifier) {
    val reveal = rememberReveal(target)
    val start = if (exact) target else windowStart
    val nowAt = arcPosition(now, start, target)
    val windowAt = arcPosition(start, start, target)
    Canvas(modifier.clearAndSetSemantics { }) {
        val stroke = ARC_STROKE.dp.toPx()
        val pad = stroke * 1.6f
        val r = size.width / 2 - pad
        val center = Offset(size.width / 2, pad + r)
        val topLeft = Offset(center.x - r, center.y - r)
        val arc = Size(2 * r, 2 * r)
        fun angle(p: Float) = ARC_START + ARC_SWEEP * p
        fun at(p: Float, radius: Float = r): Offset {
            val a = angle(p) / 180f * PI.toFloat()
            return Offset(center.x + radius * cos(a), center.y + radius * sin(a))
        }
        drawArc(Night.Text.copy(alpha = 0.10f), ARC_START, ARC_SWEEP, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
        for (k in 0..ARC_HOURS) {
            val p = k / ARC_HOURS.toFloat()
            drawCircle(Night.Muted.copy(alpha = if (k % 3 == 0) 0.55f else 0.28f), radius = (if (k % 3 == 0) 1.6f else 1.1f).dp.toPx(), center = at(p, r - stroke * 1.35f))
        }
        val shown = reveal
        // Vergangene Zeit: vom Anfang bis «jetzt», vom tiefen Violett zum Lavendel.
        val filled = minOf(nowAt, windowAt) * shown
        if (filled > 0f) gradientArc(0f, filled, Night.WaveEcho.copy(alpha = 0.55f), Night.Wave, topLeft, arc, stroke)
        // Weckfenster: warmes, leuchtendes Segment mit weichem Schein.
        if (!exact && shown > windowAt) {
            val end = shown.coerceAtMost(1f)
            drawArc(DAWN_GLOW.copy(alpha = 0.18f), angle(windowAt), ARC_SWEEP * (end - windowAt), false, topLeft, arc, style = Stroke(stroke * 2.6f, cap = StrokeCap.Round))
            gradientArc(windowAt, end, Night.Wave, DAWN_GLOW, topLeft, arc, stroke * 1.15f)
        }
        if (shown < 1f) return@Canvas
        // Weckpunkt am Ende: warmer Schein, heller Punkt mit Ring.
        val wake = at(1f)
        drawCircle(Brush.radialGradient(listOf(DAWN_GLOW.copy(alpha = 0.55f), DAWN_GLOW.copy(alpha = 0f)), center = wake, radius = stroke * 2.6f), radius = stroke * 2.6f, center = wake)
        drawCircle(Night.Moon.copy(alpha = 0.7f), radius = stroke * 1.05f, center = wake, style = Stroke(1.5.dp.toPx()))
        drawCircle(Night.Moon, radius = stroke * 0.62f, center = wake)
        // «Jetzt»: heller Punkt mit Lavendel-Schein, solange die Zielzeit vorn liegt.
        if (now.isBefore(target)) {
            val p = at(nowAt)
            drawCircle(Brush.radialGradient(listOf(Night.Wave.copy(alpha = 0.6f), Night.Wave.copy(alpha = 0f)), center = p, radius = stroke * 1.9f), radius = stroke * 1.9f, center = p)
            drawCircle(Night.Moon, radius = stroke * 0.5f, center = p)
        }
    }
}

/** Bogen von [from] bis [to] (Anteile) im Verlauf [start] → [end], in kurzen Stücken, mit runden Enden. */
private fun DrawScope.gradientArc(from: Float, to: Float, start: Color, end: Color, topLeft: Offset, arc: Size, stroke: Float) {
    val sweep = (to - from) * ARC_SWEEP
    if (sweep <= 0f) return
    val pieces = (sweep / PIECE_DEGREES).toInt().coerceAtLeast(1)
    val piece = sweep / pieces
    for (k in 0 until pieces) {
        val t = (k + 0.5f) / pieces
        val cap = if (k == 0 || k == pieces - 1) StrokeCap.Round else StrokeCap.Butt
        drawArc(
            lerp(start, end, t), ARC_START + from * ARC_SWEEP + k * piece, piece + if (k < pieces - 1) 0.6f else 0f, false,
            topLeft, arc, style = Stroke(stroke, cap = cap),
        )
    }
}

/**
 * Lage von [at] auf dem Bogen (0 = [ARC_HOURS] vor der Zielzeit, 1 = [target]). Das Weckfenster ([windowStart] bis
 * [target]) bekommt immer [WINDOW_SHARE] des Bogens, damit es auch bei 20 Minuten sichtbar leuchtet; die Zeit davor teilt
 * sich den Rest gleichmässig. Ohne Fenster («Genau») gehört der ganze Bogen der Zeit davor.
 */
internal fun arcPosition(at: Instant, windowStart: Instant, target: Instant): Float {
    val windowMinutes = Duration.between(windowStart, target).toMinutes().toFloat()
    val share = if (windowMinutes > 0f) WINDOW_SHARE else 0f
    if (!at.isBefore(windowStart)) {
        if (windowMinutes <= 0f) return 1f
        return (1f - share + share * Duration.between(windowStart, at).toMinutes() / windowMinutes).coerceIn(0f, 1f)
    }
    val lead = (ARC_HOURS * 60f - windowMinutes).coerceAtLeast(1f)
    val before = Duration.between(at, windowStart).toMinutes().toFloat()
    return ((1f - share) * (1f - before / lead)).coerceIn(0f, 1f - share)
}

/** Anteil des Bogens für das Weckfenster. */
internal const val WINDOW_SHARE = 0.16f

/** Der Bogen umfasst diese Stunden vor der Zielzeit. */
internal const val ARC_HOURS = 12

/** Seitenverhältnis (Breite : Höhe) der Fläche des Bogens. */
internal const val ARC_ASPECT = 1.5f

/** Beginn links etwas unter der Waagrechten und Länge des Bogens in Grad. */
private const val ARC_START = 165f
private const val ARC_SWEEP = 210f
private const val ARC_STROKE = 9f
private const val PIECE_DEGREES = 3f

/** Warmes Morgenlicht des Weckfensters (wie das Morgenrot am Schlafwert-Bogen). */
private val DAWN_GLOW = Color(0xFFFFB27A)
