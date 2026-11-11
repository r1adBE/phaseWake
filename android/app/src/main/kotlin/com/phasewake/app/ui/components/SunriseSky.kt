package com.phasewake.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
     * Schrift für einen Text auf [area]: hell oder dunkel, je nachdem, welche über die Fläche ([GRID] × [GRID] Punkte)
     * den grösseren kleinsten Kontrast hat. Der leisere Ton nur, wenn auch er überall ≥ [MIN_CONTRAST] erreicht.
     */
    fun ink(progress: Float, area: Area = Area.CENTER, aspect: Float = PORTRAIT): Ink = pick(samples(progress, area, aspect))

    /** Schild hinter einem Text (Gestaltung 5): [color] ist der Gegenpol der Schrift, [alpha] ihre Deckkraft. */
    data class Plaque(val color: Color, val alpha: Float)

    /**
     * Kleinste Deckkraft des Schilds (gleich wie am iPhone): Es ist immer sichtbar, auch wo die Schrift allein schon
     * reicht; wo nicht, wird es deckender.
     */
    const val MIN_PLAQUE = 0.3f

    /** Das Schild rechnet mit etwas Reserve über [MIN_CONTRAST], damit es auch zwischen den Messpunkten reicht. */
    private const val PLAQUE_MARGIN = 0.3f

    /** Schritt, in dem die Deckkraft des Schilds wächst. */
    private const val PLAQUE_STEP = 0.02f

    /** Messpunkte je Richtung über die Fläche eines Texts. */
    const val GRID = 5

    /**
     * Schild für [ink] auf [area]: so deckend wie nötig, dass Haupt- und leiser Ton über der ganzen Fläche ≥
     * [MIN_CONTRAST] erreichen (mindestens [MIN_PLAQUE]). Mit voller Deckkraft sind es immer über 14:1.
     */
    fun plaque(progress: Float, ink: Ink, area: Area, aspect: Float = PORTRAIT): Plaque {
        val back = if (luminance(ink.content) > 0.5) Color.Black else Color.White
        val backgrounds = samples(progress, area, aspect)
        val target = MIN_CONTRAST + PLAQUE_MARGIN
        var alpha = MIN_PLAQUE
        while (alpha < 1f && backgrounds.any { bg ->
                val under = mix(bg, back, alpha)
                contrast(ink.content, under) < target || contrast(ink.muted, under) < target
            }
        ) alpha += PLAQUE_STEP
        return Plaque(back, alpha.coerceAtMost(1f))
    }

    /** Farbe unter dem Text: Himmel an der Stelle ([x], [y]) mit dem Schild [plaque] darüber. */
    fun underPlaque(progress: Float, plaque: Plaque, x: Float, y: Float, aspect: Float = PORTRAIT): Color =
        mix(backgroundAt(progress, x, y, aspect), plaque.color, plaque.alpha)

    /** Himmel an [GRID] × [GRID] Punkten der Fläche (Ecken, Kanten, Mitte und dazwischen). */
    private fun samples(progress: Float, area: Area, aspect: Float): List<Color> {
        val c = colors(progress)
        val p = progress.coerceIn(0f, 1f)
        val steps = (0 until GRID).map { it / (GRID - 1f) }
        return steps.flatMap { fx ->
            steps.map { fy ->
                background(p, c.top, c.bottom, c.sun, area.left + (area.right - area.left) * fx, area.top + (area.bottom - area.top) * fy, aspect)
            }
        }
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
 *
 * Bis die Lage bekannt ist (erstes Bild), bleibt der Text unsichtbar statt kurz in der Schrift für die Mitte zu
 * erscheinen (Audit N-4). Mit [plaque] liegt ein halbdurchsichtiges Schild in Pillenform hinter dem Text
 * ([SunriseSky.plaque], Gestaltung 5), [padding] ragt es über den Text hinaus (Knöpfe: 0, sie haben die Form schon).
 */
@Composable
fun rememberSkyInk(
    progress: Float?,
    night: SunriseSky.Ink,
    plaque: Boolean = true,
    padding: Dp = PLAQUE_PADDING,
): Pair<SunriseSky.Ink, Modifier> {
    val place = remember { mutableStateOf<Pair<SunriseSky.Area, Float>?>(null) }
    if (progress == null) return night to Modifier
    // In Vorschau und Bildschirmfotos gleich sichtbar (dort gibt es nicht immer ein zweites Bild).
    val inspection = LocalInspectionMode.current
    val composed = place.value
    val ink = SunriseSky.ink(progress, composed?.first ?: SunriseSky.Area.CENTER, composed?.second ?: SunriseSky.PORTRAIT)
    val measure = Modifier.onGloballyPositioned { coords ->
        val root = coords.findRootCoordinates().size
        if (root.width <= 0 || root.height <= 0) return@onGloballyPositioned
        val b = coords.boundsInRoot()
        val next = SunriseSky.Area(b.left / root.width, b.top / root.height, b.right / root.width, b.bottom / root.height) to
            root.height.toFloat() / root.width
        if (next != place.value) place.value = next
    }
    val shown = Modifier.graphicsLayer { alpha = if (composed != null || inspection) 1f else 0f }
    if (!plaque) return ink to measure.then(shown)
    val shield = Modifier.drawBehind {
        // Gelesen beim Zeichnen: Das Schild passt schon im ersten Bild zur tatsächlichen Lage.
        val (area, aspect) = place.value ?: return@drawBehind
        val p = SunriseSky.plaque(progress, ink, area, aspect)
        val pad = padding.toPx()
        drawRoundRect(
            color = p.color.copy(alpha = p.alpha),
            topLeft = Offset(-pad, -pad / PLAQUE_RATIO),
            size = Size(size.width + 2 * pad, size.height + 2 * pad / PLAQUE_RATIO),
            cornerRadius = CornerRadius(size.height / 2 + pad / PLAQUE_RATIO),
        )
    }
    return ink to measure.then(shown).then(shield)
}

/** Wie weit das Schild seitlich über einen Text hinausragt (wie am iPhone 14 dp, oben und unten 4 dp). */
val PLAQUE_PADDING = 14.dp
private const val PLAQUE_RATIO = 3.5f

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
