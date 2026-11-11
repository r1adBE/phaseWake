package com.phasewake.app.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.phasewake.app.ui.components.Night
import kotlin.math.PI
import kotlin.math.sin

/**
 * Kleines Bild über dem Titel eines Einrichtungsschritts (Gestaltung 2, gleich wie `OnboardingGlyph` am iPhone): die
 * Welle aus dem Logo mit dem Mond als Weckpunkt, der mit jedem Schritt weiter nach rechts wandert – so zeigt das Bild
 * zugleich, wie weit die Einrichtung ist. Nur Schmuck (für Bildschirmleser verborgen; «Schritt x von y» steht darüber).
 * [step]: aktueller Schritt ab 0, [steps]: Anzahl Schritte vor «Fertig».
 */
@Composable
internal fun OnboardingGlyph(step: Int, steps: Int, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    // Mond hell mit Rand im dunklen Modus, dunkel auf hellem Grund (wie das Logo, Audit N-8).
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val moon = if (dark) Night.Moon else Night.Top
    Canvas(modifier.size(96.dp, 36.dp).clearAndSetSemantics { }) {
        val fraction = glyphPosition(step, steps)
        val mid = size.height * 0.62f
        val amplitude = size.height * 0.24f
        fun y(f: Float) = mid + amplitude * sin(f * 3 * PI.toFloat())
        val wave = Path()
        for (i in 0..GLYPH_STEPS) {
            val f = i / GLYPH_STEPS.toFloat()
            if (i == 0) wave.moveTo(0f, y(f)) else wave.lineTo(size.width * f, y(f))
        }
        drawPath(wave, accent.copy(alpha = 0.85f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        val center = Offset(size.width * fraction, y(fraction))
        drawCircle(moon, radius = 7.dp.toPx(), center = center)
        drawCircle(accent, radius = 7.dp.toPx(), center = center, style = Stroke(1.5.dp.toPx()))
    }
}

/** Punkte, aus denen die Welle gezeichnet wird. */
private const val GLYPH_STEPS = 60

/** Lage des Monds auf der Welle (0–1): vom ersten bis zum letzten Schritt gleichmässig, an den Rändern etwas Luft. */
internal fun glyphPosition(step: Int, steps: Int): Float {
    if (steps <= 1) return 0.5f
    return 0.1f + 0.8f * step.coerceIn(0, steps) / steps
}
