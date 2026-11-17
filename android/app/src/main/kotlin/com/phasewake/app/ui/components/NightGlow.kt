package com.phasewake.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
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
    /**
     * Karten der Seiten ausser der Startseite: Diagrammkarte #161B3A mit 72 % als dichteres Glas. Darauf behalten
     * Phasenfarben und Ränder auch über dem Abendrot ≥ 3:1 und Schrift ≥ 4.5:1 (SkyPageContrastTest).
     */
    val Panel = Color(0xFF161B3A).copy(alpha = 0.72f)
}

/**
 * Hintergrund wie ein Nachthimmel kurz vor der Dämmerung: Verlauf von Mitternachts-Indigo nach Fast-Schwarz,
 * darüber ein weicher Lichtschein, der langsam wandert ([drift] 0–1).
 */
fun Modifier.nightGlow(drift: Float = 0f, mood: SkyMood = SkyMood.Plain): Modifier = drawBehind { drawNight(drift, mood) }

private fun DrawScope.drawNight(drift: Float, mood: SkyMood) {
    drawRect(Brush.verticalGradient(listOf(mood.top, Night.Bottom)))
    // Tiefe Nacht: ruhende Sterne im oberen Himmel (keine Bewegung).
    if (mood.night > 0f) drawStars(mood.night.coerceIn(0f, 1f))
    // Horizont nach Tageszeit ([SkyMood]): Abendrot oder Morgendämmerung, von unten aufsteigend.
    val horizon = mood.horizon
    if (horizon.alpha > 0f) {
        drawRect(
            Brush.verticalGradient(
                SkyMood.HORIZON_START to horizon.copy(alpha = 0f),
                1f to horizon,
            ),
        )
        // Schein über dem Horizont, wo die Sonne käme; reicht nie über HORIZON_START (wie im Kontrast-Test gerechnet).
        val sun = mood.sun
        drawRect(
            Brush.radialGradient(
                listOf(sun, sun.copy(alpha = sun.alpha * 0.45f), sun.copy(alpha = 0f)),
                center = Offset(size.width * 0.5f, size.height),
                radius = size.height * (1f - SkyMood.HORIZON_START),
            ),
        )
    }
    val glowCenter = Offset(size.width * (0.78f + 0.08f * drift), size.height * (0.08f + 0.06f * drift))
    val glow = mood.glow
    drawRect(
        Brush.radialGradient(
            listOf(glow.copy(alpha = mood.glowAlpha), glow.copy(alpha = 0.12f), Color.Transparent),
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

/**
 * Sterne der tiefen Nacht: feste Lage (immer dieselben), klein und gedämpft, nur in der oberen Hälfte; [strength] 0–1
 * blendet sie mit der Stimmung ein.
 */
private fun DrawScope.drawStars(strength: Float) {
    val unit = 1.dp.toPx()
    STARS.forEach { (x, y, r) ->
        val a = (0.35f + 0.45f * r) * strength
        drawCircle(Night.Moon.copy(alpha = a), radius = (0.6f + 0.9f * r) * unit, center = Offset(size.width * x, size.height * y))
    }
}

/** Lage (x, y als Anteil) und Grösse (0–1) der Sterne; aus einem festen Zufallsstrom, damit sie nie springen. */
private val STARS: List<Triple<Float, Float, Float>> = run {
    var seed = 0x5EEDL
    fun next(): Float {
        seed = (seed * 1103515245L + 12345L) and 0x7FFFFFFFL
        return seed / 2147483647f
    }
    List(STAR_COUNT) { Triple(next(), next() * 0.5f, next()) }
}

private const val STAR_COUNT = 46

/**
 * Vollflächiger, langsam lebender Nachthimmel mit der Stimmung [mood]. Mit «Animationen entfernen» ruht der
 * Lichtschein; die Stimmung wechselt sanft, wenn sie sich ändert (sonst sofort). Die Sterne ruhen immer.
 */
@Composable
fun AnimatedNight(modifier: Modifier = Modifier, mood: SkyMood = SkyMood.Plain, content: @Composable () -> Unit = {}) {
    val moving = motionAllowed()
    val dusk by animateFloatAsState(mood.dusk, if (moving) tween(MOOD_MS) else snap(), label = "dusk")
    val dawn by animateFloatAsState(mood.dawn, if (moving) tween(MOOD_MS) else snap(), label = "dawn")
    val night by animateFloatAsState(mood.night, if (moving) tween(MOOD_MS) else snap(), label = "night")
    val shown = SkyMood(dusk, dawn, night)
    if (moving) {
        val drift by rememberInfiniteTransition(label = "night").animateFloat(
            0f, 1f, infiniteRepeatable(tween(9000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "drift",
        )
        // Der Himmel in einer eigenen Ebene hinter dem Inhalt: Der Drift zeichnet je Bild nur diese neu,
        // nicht den Inhalt darüber.
        Box(modifier) {
            Canvas(Modifier.matchParentSize().graphicsLayer {}) { drawNight(drift, shown) }
            content()
        }
    } else {
        Box(modifier.drawBehind { drawNight(0f, shown) }) { content() }
    }
}

/** Übergang zwischen zwei Stimmungen des Himmels. */
private const val MOOD_MS = 1500

/**
 * Das Logo als Zeichnung: Mondsichel, Welle der Schlafzyklen, Weckpunkt. Ohne Hintergrund. [moon]: Farbe von Mond und
 * Weckpunkt; auf hellem Grund dunkel, sonst verschwände der Mond. [wave]: Farbe der Welle (auf dem
 * Tageshimmel kräftiger).
 */
@Composable
fun LogoMark(size: Dp, modifier: Modifier = Modifier, moon: Color = Night.Moon, wave: Color = Night.Wave) {
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        val s = this.size.minDimension / 200f
        scale(s, pivot = Offset.Zero) {
            run {
                val moonPath = Path().apply {
                    // Mondsichel = Kreis (74|72, r 40) ohne Kreis (91|59, r 38), wie im Logo.
                    moveTo(108.1f, 92.9f)
                    arcTo(Rect(34f, 32f, 114f, 112f), 31.5f, 222.1f, false)
                    arcTo(Rect(53f, 21f, 129f, 97f), 221.9f, -158.7f, false)
                    close()
                }
                drawPath(moonPath, moon)
                val wavePath = Path().apply {
                    moveTo(16f, 158f)
                    cubicTo(30f, 158f, 30f, 122f, 44f, 122f)
                    cubicTo(58f, 122f, 58f, 158f, 72f, 158f)
                    cubicTo(86f, 158f, 86f, 118f, 100f, 118f)
                    cubicTo(114f, 118f, 114f, 152f, 128f, 152f)
                    cubicTo(140f, 152f, 146f, 134f, 158f, 122f)
                }
                drawPath(wavePath, wave, style = Stroke(9f, cap = StrokeCap.Round))
                drawCircle(moon, radius = 8f, center = Offset(164f, 116f))
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
