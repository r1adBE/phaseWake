package com.phasewake.app.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Typography
import com.phasewake.app.shared.R as SharedR

/** Markenfarben aus dem Logo, Farbwelt «Mitternacht & Lavendel» wie auf dem Handy. */
object Brand {
    val Night = Color(0xFF141A3D)
    val NightBottom = Color(0xFF070914)
    val NightRaised = Color(0xFF1E2550)
    val Deep = Color(0xFF3B3486)
    val Echo = Color(0xFF4F45B8)
    val Glow = Color(0xFF7B6CF6)
    val Wave = Color(0xFFB9AEFF)
    val Star = Color(0xFFE4DFFF)
    val Mist = Color(0xFFC9C3E6)
    // Auf Schwarz und auf den Knöpfen ≥ 4.5:1 (WCAG AA).
    val Muted = Color(0xFFA9A3CF)
    val Moon = Color(0xFFFBF7F0)
    val Track = Color(0xFF1E2347)
}

private fun manrope(weight: Int) = Font(
    SharedR.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Manrope als variable Schrift; jede Stärke stellt die Achse «wght» passend ein. */
val Manrope = FontFamily(manrope(400), manrope(500), manrope(600), manrope(700), manrope(800))

private val colors = ColorScheme(
    primary = Brand.Wave,
    primaryDim = Brand.Echo,
    primaryContainer = Brand.Deep,
    onPrimary = Brand.Night,
    onPrimaryContainer = Brand.Moon,
    secondary = Brand.Mist,
    secondaryContainer = Brand.NightRaised,
    onSecondaryContainer = Brand.Moon,
    surfaceContainerLow = Brand.Track,
    surfaceContainer = Brand.NightRaised,
    surfaceContainerHigh = Brand.Deep,
    onSurface = Brand.Moon,
    onSurfaceVariant = Brand.Muted,
    background = Color.Black,
    onBackground = Brand.Moon,
)

private fun TextStyle.manrope() = copy(fontFamily = Manrope)

private val typography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.manrope(),
        displayMedium = t.displayMedium.manrope(),
        displaySmall = t.displaySmall.manrope(),
        titleLarge = t.titleLarge.manrope(),
        titleMedium = t.titleMedium.manrope(),
        titleSmall = t.titleSmall.manrope(),
        labelLarge = t.labelLarge.manrope(),
        labelMedium = t.labelMedium.manrope(),
        labelSmall = t.labelSmall.manrope(),
        bodyLarge = t.bodyLarge.manrope(),
        bodyMedium = t.bodyMedium.manrope(),
        bodySmall = t.bodySmall.manrope(),
        bodyExtraSmall = t.bodyExtraSmall.manrope(),
        numeralExtraLarge = t.numeralExtraLarge.manrope(),
        numeralLarge = t.numeralLarge.manrope(),
        numeralMedium = t.numeralMedium.manrope(),
        numeralSmall = t.numeralSmall.manrope(),
        numeralExtraSmall = t.numeralExtraSmall.manrope(),
    )
}

@Composable
fun PhaseWakeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

/**
 * Nachthimmel wie auf dem Handy: Mitternachts-Indigo mit Lavendel-Schein oben rechts. [strength] 0–1: auf dem
 * Startbildschirm schwach (OLED-schonend, Rand bleibt schwarz), beim Wecken voll.
 */
fun androidx.compose.ui.Modifier.nightGlow(strength: Float = 1f): androidx.compose.ui.Modifier =
    this.then(
        androidx.compose.ui.Modifier.drawBehind {
            drawRect(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Brand.Night.copy(alpha = strength), Brand.NightBottom.copy(alpha = strength), Color.Black),
                ),
            )
            drawRect(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    listOf(Brand.Glow.copy(alpha = 0.5f * strength), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(size.width * 0.8f, size.height * 0.12f),
                    radius = size.maxDimension * 0.65f,
                ),
            )
        },
    )
