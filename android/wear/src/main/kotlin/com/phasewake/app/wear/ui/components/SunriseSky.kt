package com.phasewake.app.wear.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Farben eines Sonnenaufgangs zum Fortschritt 0–1, gleich wie `SunriseSky` der Handy-App (die Uhr
 * kann das App-Modul nicht einbinden): von fast schwarzem Dunkelrot über Glutorange bis zu warmem,
 * hellem Weiss. Fortschritt und Helligkeit kommen aus dem Kern ([com.phasewake.app.core.Sunrise]).
 */
object SunriseSky {
    private class Key(val at: Float, val top: Color, val bottom: Color, val sun: Color)

    private val keys = listOf(
        Key(0.00f, Color(0xFF050208), Color(0xFF1E0604), Color(0xFF4A1006)),
        Key(0.25f, Color(0xFF14071A), Color(0xFF5A1508), Color(0xFFA8320C)),
        Key(0.50f, Color(0xFF3B1530), Color(0xFFC2410C), Color(0xFFFF7A1A)),
        Key(0.75f, Color(0xFFD9774A), Color(0xFFFFA25C), Color(0xFFFFD08A)),
        Key(1.00f, Color(0xFFFFE6C2), Color(0xFFFFF6E8), Color(0xFFFFFFFF)),
    )

    data class Colors(val top: Color, val bottom: Color, val sun: Color, val content: Color, val contentMuted: Color)

    /** Ab hier ist dunkle Schrift kontrastreicher als weisse (wie auf dem Handy). */
    private const val DARK_CONTENT_FROM = 0.635f

    fun colors(progress: Float): Colors {
        val p = progress.coerceIn(0f, 1f)
        val i = keys.indexOfLast { it.at <= p }.coerceAtMost(keys.size - 2)
        val a = keys[i]
        val b = keys[i + 1]
        val t = ((p - a.at) / (b.at - a.at)).coerceIn(0f, 1f)
        val dark = p >= DARK_CONTENT_FROM
        return Colors(
            top = lerp(a.top, b.top, t),
            bottom = lerp(a.bottom, b.bottom, t),
            sun = lerp(a.sun, b.sun, t),
            content = if (dark) Color(0xFF070914) else Color.White,
            contentMuted = if (dark) Color(0xFF3A1E08) else Color(0xFFFFD9C2),
        )
    }
}

/** Zeichnet den Sonnenaufgang zum Fortschritt [progress]; die Sonne steigt dabei von unten auf. */
fun Modifier.sunriseSky(progress: Float): Modifier = drawBehind {
    val c = SunriseSky.colors(progress)
    val p = progress.coerceIn(0f, 1f)
    drawRect(Brush.verticalGradient(listOf(c.top, c.bottom)))
    val center = Offset(size.width / 2f, size.height * (1.18f - 0.5f * p))
    val radius = size.width * (0.45f + 1.05f * p)
    drawCircle(
        Brush.radialGradient(
            0f to c.sun,
            0.35f to c.sun.copy(alpha = 0.55f),
            1f to c.sun.copy(alpha = 0f),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}
