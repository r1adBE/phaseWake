package com.phasewake.app.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Himmel des Weckbildschirms ohne Sonnenaufgangslicht: ein kräftiger Sonnenaufgang von Indigo oben über Violett und
 * Magenta-Rosé bis zur Glut unten, darüber ein warmer Schein am unteren Rand und der Lavendel-Schein oben rechts.
 * Ruhend. So dunkel gehalten, dass helle Schrift überall ≥ 4.5:1 behält (WakeSkyTest). Gleich auf Wear OS und der Apple
 * Watch.
 */
object WakeSky {
    /** Verlauf von oben (0) nach unten (1). */
    val Stops: List<Pair<Float, Color>> = listOf(
        0f to Color(0xFF161A4A),
        0.35f to Color(0xFF2E1A5C),
        0.62f to Color(0xFF5E1E5E),
        0.84f to Color(0xFF82244A),
        1f to Color(0xFF8A2E0E),
    )

    /** Warmer Schein am unteren Rand (Mitte), Deckkraft in seiner Mitte; reicht bis [SUN_FROM] hinauf. */
    val Sun = Color(0xFFC2481A)
    const val SUN_ALPHA = 0.12f
    const val SUN_FROM = 0.5f

    /** Lavendel-Schein oben rechts. */
    const val GLOW_ALPHA = 0.28f

    /**
     * Farbe bei Höhe [y] (0 oben, 1 unten), ungünstig gerechnet: unten der warme Schein überall in voller Stärke, oben
     * der Lavendel-Schein in voller Stärke. Für die Kontrast-Tests.
     */
    fun at(y: Float): Color {
        val yy = y.coerceIn(0f, 1f)
        val i = Stops.indexOfLast { it.first <= yy }.coerceAtMost(Stops.size - 2)
        val (a, ca) = Stops[i]
        val (b, cb) = Stops[i + 1]
        val base = mixColor(ca, cb, ((yy - a) / (b - a)).coerceIn(0f, 1f))
        val warm = if (yy >= SUN_FROM) mixColor(base, Sun, SUN_ALPHA) else base
        return if (yy <= GLOW_REACH) mixColor(warm, Night.Glow, GLOW_ALPHA) else warm
    }

    /** Bis hier (Anteil der Höhe) zählt der Test den Lavendel-Schein oben voll. */
    private const val GLOW_REACH = 0.45f
}

/** Weckhimmel ([WakeSky]) als Hintergrund. */
fun Modifier.wakeSky(): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(*WakeSky.Stops.toTypedArray()))
    drawRect(
        Brush.radialGradient(
            listOf(WakeSky.Sun.copy(alpha = WakeSky.SUN_ALPHA), WakeSky.Sun.copy(alpha = 0f)),
            center = Offset(size.width * 0.5f, size.height),
            radius = size.height * (1f - WakeSky.SUN_FROM),
        ),
    )
    drawRect(
        Brush.radialGradient(
            listOf(Night.Glow.copy(alpha = WakeSky.GLOW_ALPHA), Color.Transparent),
            center = Offset(size.width * 0.8f, size.height * 0.1f),
            radius = size.height * 0.35f,
        ),
    )
}
