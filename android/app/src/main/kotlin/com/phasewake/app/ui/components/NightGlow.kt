package com.phasewake.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Farben des Nachthimmels «Mitternacht & Lavendel»: Indigo mit einem Lavendel-Schein von oben rechts. */
object Night {
    val Top = Color(0xFF141A3D)
    val Bottom = Color(0xFF070914)
    val Glow = Color(0xFF7B6CF6)
    val Beam = Color(0xFFB9AEFF)
    /** Mond, Weckpunkt und Text: warmes Weiss. */
    val Moon = Color(0xFFFBF7F0)
    val Text = Color(0xFFFBF7F0)
    val Wave = Color(0xFFB9AEFF)
    val WaveEcho = Color(0xFF7B6CF6)
    // Kontrast ≥ 4.5:1 auch im Kern des Lichtscheins (WCAG AA).
    val Muted = Color(0xFFC9C3E6)
    /** Stufe für Zusatzinfos: Muted mit 80 % (≥ 6:1 auf dem Verlauf). */
    val MutedSoft = Color(0xFFC9C3E6).copy(alpha = 0.8f)
    /** Navigationsleiste auf dem Nachthimmel: untere Verlaufsfarbe mit 93 %. */
    val Bar = Color(0xFF070914).copy(alpha = 0.93f)
}

/**
 * Hintergrund wie ein Nachthimmel kurz vor der Dämmerung: Verlauf von Mitternachts-Indigo nach Fast-Schwarz,
 * darüber ein weicher Lichtschein, der langsam wandert ([drift] 0–1).
 */
fun Modifier.nightGlow(drift: Float = 0f): Modifier = drawBehind { drawNight(drift) }

private fun DrawScope.drawNight(drift: Float) {
    drawRect(Brush.verticalGradient(listOf(Night.Top, Night.Bottom)))
    val glowCenter = Offset(size.width * (0.78f + 0.08f * drift), size.height * (0.08f + 0.06f * drift))
    drawRect(
        Brush.radialGradient(
            listOf(Night.Glow.copy(alpha = 0.55f), Night.Glow.copy(alpha = 0.12f), Color.Transparent),
            center = glowCenter,
            radius = size.maxDimension * 0.7f,
        ),
    )
    // Schräger Lichtstreifen, wie ein Schein durchs Fenster.
    drawRect(
        Brush.linearGradient(
            0.0f to Color.Transparent,
            0.45f to Night.Beam.copy(alpha = 0.10f + 0.05f * drift),
            0.6f to Color.Transparent,
            start = Offset(size.width, 0f),
            end = Offset(0f, size.height),
        ),
    )
}

/** Vollflächiger, langsam lebender Nachthimmel. */
@Composable
fun AnimatedNight(modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    val drift by rememberInfiniteTransition(label = "night").animateFloat(
        0f, 1f, infiniteRepeatable(tween(9000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "drift",
    )
    Box(modifier.drawBehind { drawNight(drift) }) { content() }
}

/** Das Logo als Zeichnung: Mondsichel, Welle der Schlafzyklen, Weckpunkt. Ohne Hintergrund. */
@Composable
fun LogoMark(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        val s = this.size.minDimension / 200f
        scale(s, pivot = Offset.Zero) {
            run {
                val moon = Path().apply {
                    // Mondsichel = Kreis (74|72, r 40) ohne Kreis (91|59, r 38), wie im Logo.
                    moveTo(108.1f, 92.9f)
                    arcTo(Rect(34f, 32f, 114f, 112f), 31.5f, 222.1f, false)
                    arcTo(Rect(53f, 21f, 129f, 97f), 221.9f, -158.7f, false)
                    close()
                }
                drawPath(moon, Night.Moon)
                val wave = Path().apply {
                    moveTo(16f, 158f)
                    cubicTo(30f, 158f, 30f, 122f, 44f, 122f)
                    cubicTo(58f, 122f, 58f, 158f, 72f, 158f)
                    cubicTo(86f, 158f, 86f, 118f, 100f, 118f)
                    cubicTo(114f, 118f, 114f, 152f, 128f, 152f)
                    cubicTo(140f, 152f, 146f, 134f, 158f, 122f)
                }
                drawPath(wave, Night.Wave, style = Stroke(9f, cap = StrokeCap.Round))
                drawCircle(Night.Moon, radius = 8f, center = Offset(164f, 116f))
            }
        }
    }
}

/**
 * Begrüssung beim Start: Nachthimmel, das Logo leuchtet auf, der Name folgt, dann blendet alles in
 * die App über. Dauert gut eine Sekunde und erscheint einmal pro App-Start.
 */
@Composable
fun Intro(onFinished: () -> Unit) {
    val logo = remember { Animatable(0f) }
    val name = remember { Animatable(0f) }
    val out = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        launch { logo.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
        delay(300)
        name.animateTo(1f, tween(450))
        delay(350)
        out.animateTo(0f, tween(380))
        onFinished()
    }
    AnimatedNight(Modifier.fillMaxSize().graphicsLayer { alpha = out.value }) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            LogoMark(
                112.dp,
                Modifier.graphicsLayer {
                    alpha = logo.value
                    val scale = 0.82f + 0.18f * logo.value
                    scaleX = scale
                    scaleY = scale
                },
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "Phase Wake",
                style = MaterialTheme.typography.headlineMedium,
                color = Night.Text,
                modifier = Modifier.graphicsLayer {
                    alpha = name.value
                    translationY = (1f - name.value) * 24f
                },
            )
        }
    }
}
