package com.phasewake.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Farben eines Sonnenaufgangs zum Fortschritt 0–1: von fast schwarzem Dunkelrot über Glutorange
 * bis zu warmem, hellem Weiss. Oben bleibt der Himmel länger dunkel, unten geht die Sonne auf.
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

    /** [content]/[contentMuted]: Schrift in der Mitte eines Hochformats; Texte an anderer Stelle nehmen [ink]. */
    data class Colors(val top: Color, val bottom: Color, val sun: Color, val content: Color, val contentMuted: Color)

    /** Schrift auf dem Himmel: Haupttext und leiserer Text. */
    data class Ink(val content: Color, val muted: Color)

    /** Fläche eines Texts als Anteil der Himmelsfläche (0–1, links/oben = 0). */
    data class Area(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        companion object {
            val CENTER = Area(0.5f, 0.5f, 0.5f, 0.5f)
        }
    }

    /** Helle Schrift und ihr leiserer Ton. */
    private val Light = Ink(Color.White, Color(0xFFFFD9C2))

    /**
     * Dunkle Schrift: fast Schwarz, damit dort, wo helle und dunkle Schrift gleich gut sind, beide noch ≥ 4.5:1
     * erreichen (mit #070914 wären es dort nur 4.4:1).
     */
    private val Dark = Ink(Color(0xFF02030A), Color(0xFF3A1E08))

    /** Mindestkontrast für Text (WCAG AA). */
    const val MIN_CONTRAST = 4.5f

    fun colors(progress: Float): Colors {
        val p = progress.coerceIn(0f, 1f)
        val i = keys.indexOfLast { it.at <= p }.coerceAtMost(keys.size - 2)
        val a = keys[i]
        val b = keys[i + 1]
        val t = ((p - a.at) / (b.at - a.at)).coerceIn(0f, 1f)
        val top = lerp(a.top, b.top, t)
        val bottom = lerp(a.bottom, b.bottom, t)
        val sun = lerp(a.sun, b.sun, t)
        val center = pick(listOf(background(p, top, bottom, sun, 0.5f, 0.5f, PORTRAIT)))
        return Colors(top = top, bottom = bottom, sun = sun, content = center.content, contentMuted = center.muted)
    }

    /** Seitenverhältnis (Höhe / Breite) eines üblichen Handys im Hochformat. */
    const val PORTRAIT = 2.1f

    /**
     * Farbe des Himmels an der Stelle ([x], [y]) als Anteil der Fläche, [aspect] = Höhe / Breite. Rechnet wie
     * [sunriseSky] zeichnet: senkrechter Verlauf, darüber der Schein der Sonne.
     */
    fun backgroundAt(progress: Float, x: Float, y: Float, aspect: Float): Color {
        val c = colors(progress)
        return background(progress.coerceIn(0f, 1f), c.top, c.bottom, c.sun, x, y, aspect)
    }

    private fun background(p: Float, top: Color, bottom: Color, sun: Color, x: Float, y: Float, aspect: Float): Color {
        val base = mix(top, bottom, y.coerceIn(0f, 1f))
        // Wie [sunriseSky], in Breiten gemessen: Mitte (0.5, aspect · (1.18 − 0.5p)), Radius 0.45 + 1.05p.
        val d = hypot(x - 0.5f, aspect * (y - (1.18f - 0.5f * p))) / (0.45f + 1.05f * p)
        val alpha = when {
            d >= 1f -> 0f
            d <= 0.35f -> 1f - 0.45f * d / 0.35f
            else -> 0.55f * (1f - (d - 0.35f) / 0.65f)
        }
        return mix(base, sun, alpha)
    }

    /**
     * Schrift für einen Text auf [area]: hell oder dunkel, je nachdem, welche über die Fläche (Ecken, Kanten, Mitte)
     * den grösseren kleinsten Kontrast hat. Der leisere Ton nur, wenn auch er überall ≥ [MIN_CONTRAST] erreicht.
     */
    fun ink(progress: Float, area: Area = Area.CENTER, aspect: Float = PORTRAIT): Ink {
        val c = colors(progress)
        val p = progress.coerceIn(0f, 1f)
        val xs = listOf(area.left, (area.left + area.right) / 2, area.right)
        val ys = listOf(area.top, (area.top + area.bottom) / 2, area.bottom)
        return pick(xs.flatMap { x -> ys.map { y -> background(p, c.top, c.bottom, c.sun, x, y, aspect) } })
    }

    private fun pick(backgrounds: List<Color>): Ink {
        fun worst(color: Color) = backgrounds.minOf { contrast(color, it) }
        val ink = if (worst(Light.content) >= worst(Dark.content)) Light else Dark
        return if (worst(ink.muted) >= MIN_CONTRAST) ink else Ink(ink.content, ink.content)
    }

    /** Kontrast nach WCAG 2 (1–21). */
    fun contrast(a: Color, b: Color): Float {
        val la = luminance(a)
        val lb = luminance(b)
        return ((maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)).toFloat()
    }

    private fun luminance(c: Color): Double {
        fun lin(v: Float): Double = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    /** Mischung im sRGB-Raum, wie Verläufe gezeichnet werden. */
    private fun mix(a: Color, b: Color, t: Float): Color = Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
    )
}

/**
 * Schrift für einen Text auf dem Sonnenaufgang [progress] (`null` = Nachthimmel, dann [night]): Der Text misst
 * seine Lage mit dem zurückgegebenen Modifier, die Farbe passt dann zur Stelle des Himmels (Audit UI-6).
 */
@Composable
fun rememberSkyInk(progress: Float?, night: SunriseSky.Ink): Pair<SunriseSky.Ink, Modifier> {
    var place by remember { mutableStateOf(SunriseSky.Area.CENTER to SunriseSky.PORTRAIT) }
    if (progress == null) return night to Modifier
    val ink = SunriseSky.ink(progress, place.first, place.second)
    return ink to Modifier.onGloballyPositioned { coords ->
        val root = coords.findRootCoordinates().size
        if (root.width <= 0 || root.height <= 0) return@onGloballyPositioned
        val b = coords.boundsInRoot()
        val next = SunriseSky.Area(b.left / root.width, b.top / root.height, b.right / root.width, b.bottom / root.height) to
            root.height.toFloat() / root.width
        if (next != place) place = next
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

@Composable
fun SunriseBackground(progress: Float, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.sunriseSky(progress)) { content() }
}
