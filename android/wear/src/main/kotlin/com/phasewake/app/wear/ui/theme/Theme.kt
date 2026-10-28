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

/** Markenfarben aus dem Logo. */
object Brand {
    val Night = Color(0xFF0B1A4A)
    val NightRaised = Color(0xFF122466)
    val Deep = Color(0xFF1E3A8A)
    val Echo = Color(0xFF2F4FB8)
    val Wave = Color(0xFF7DB3FF)
    val Star = Color(0xFFBFDBFE)
    val Mist = Color(0xFF9FB8FF)
    val Muted = Color(0xFF8FA3CC)
    val Moon = Color.White
    val Track = Color(0xFF15213D)
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
 * Nachthimmel wie auf dem Handy: Tiefblau mit Lichtschein oben rechts. [strength] 0–1: auf dem
 * Startbildschirm schwach (OLED-schonend, Rand bleibt schwarz), beim Wecken voll.
 */
fun androidx.compose.ui.Modifier.nightGlow(strength: Float = 1f): androidx.compose.ui.Modifier =
    this.then(
        androidx.compose.ui.Modifier.drawBehind {
            drawRect(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color(0xFF0B2170).copy(alpha = strength), Color(0xFF050D2B).copy(alpha = strength), Color.Black),
                ),
            )
            drawRect(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    listOf(Color(0xFF3A74FF).copy(alpha = 0.5f * strength), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(size.width * 0.8f, size.height * 0.12f),
                    radius = size.maxDimension * 0.65f,
                ),
            )
        },
    )
