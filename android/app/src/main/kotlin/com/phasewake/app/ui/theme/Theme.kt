package com.phasewake.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.phasewake.app.shared.R as SharedR

/** Wie die App aussehen soll; Standard folgt dem System. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

private fun manrope(weight: Int) = Font(
    SharedR.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Manrope als variable Schrift; jede Stärke stellt die Achse «wght» passend ein. */
val Manrope = FontFamily(manrope(400), manrope(500), manrope(600), manrope(700), manrope(800))

/**
 * Farben, die über das Material-Schema hinausgehen (Welle, Echo, Hero-Karte, Karten und Chips der
 * Startseite). Farbwelt «Mitternacht & Lavendel».
 */
@Immutable
data class BrandColors(
    val hero: Color,
    val onHero: Color,
    val onHeroMuted: Color,
    val heroWindow: Color,
    val wave: Color,
    val echo: Color,
    val dot: Color,
    val chartQuiet: Color,
    val chartActive: Color,
    val chartTrack: Color,
    /** Karten der Startseite: dunkel durchscheinend auf dem Nachthimmel, hell eine weisse Fläche. */
    val card: Color,
    /** Rand eines nicht gewählten Chips: mindestens 3:1 zur Karte (WCAG 1.4.11, Nicht-Text-Kontrast). */
    val chipBorder: Color,
    /** Fläche eines gewählten Chips. */
    val chipSelected: Color,
    /** Zusatzinfo (bodySmall): eine Stufe leiser als onSurfaceVariant, Kontrast ≥ 4.5:1. */
    val mutedSoft: Color,
    /**
     * Schlafphasen in allen Diagrammen (Hypnogramm, Nachtkarte, Detailseite, Legenden): verschiedene Farbtöne und
     * Helligkeiten, je ≥ 3:1 zur Diagrammkarte, auch unter Deuteranopie, Protanopie und Tritanopie (cvd.py). Gleich auf Apple.
     */
    val stageAwake: Color,
    /** REM: helles Cyan/Türkis, klar anders als die Lila-Töne von leicht und tief. */
    val stageRem: Color,
    /** Leichter Schlaf: Lavendel der Marke. */
    val stageLight: Color,
    /** Tiefschlaf: tiefes Indigo/Violett. */
    val stageDeep: Color,
)

private val Lavender = Color(0xFFB9AEFF)

private val DarkBrand = BrandColors(
    hero = Color(0xFF141A3D),
    onHero = Color(0xFFFBF7F0),
    onHeroMuted = Color(0xFFC9C3E6),
    heroWindow = Color(0xFF2A2760),
    wave = Lavender,
    echo = Color(0xFF7B6CF6),
    dot = Color(0xFFFBF7F0),
    chartQuiet = Color(0xFF4A43A0),
    chartActive = Lavender,
    chartTrack = Color(0xFF2A2D55),
    card = Color.White.copy(alpha = 0.06f),
    // 75 % (wie auf Apple): 5.2:1 auf der Karte (Weiss 6 % über #0C1027), ≥ 3:1 verlangt.
    chipBorder = Lavender.copy(alpha = 0.75f),
    chipSelected = Lavender.copy(alpha = 0.25f),
    // 80 % von #C9C3E6: auf dem Nachthimmel und den Karten ≥ 6:1.
    mutedSoft = Color(0xFFC9C3E6).copy(alpha = 0.8f),
    // Auf der Diagrammkarte #161B3A: 7.6, 12.4, 8.4 und 3.6 : 1.
    stageAwake = Color(0xFFFF8F85),
    stageRem = Color(0xFF6FF2E6),
    stageLight = Lavender,
    stageDeep = Color(0xFF6F5CF0),
)

private val LightBrand = BrandColors(
    hero = Color(0xFF141A3D),
    onHero = Color(0xFFFBF7F0),
    onHeroMuted = Color(0xFFC9C3E6),
    heroWindow = Color(0xFFE4DFFB),
    wave = Color(0xFF7B6CF6),
    echo = Color(0xFFB3A9F7),
    dot = Color(0xFF3B3486),
    chartQuiet = Color(0xFFDAD4FA),
    chartActive = Color(0xFF7B6CF6),
    chartTrack = Color(0xFFECE8F5),
    card = Color.White,
    // 90 %: 3.4:1 auf der weissen Karte, 3.2:1 auf dem Hintergrund #FBF7F0.
    chipBorder = Color(0xFF7B6CF6).copy(alpha = 0.9f),
    chipSelected = Color(0xFFE4DFFB),
    // 80 % Deckkraft wäre auf Hell zu schwach (< 4.5:1); daher ein fester, etwas hellerer Ton.
    mutedSoft = Color(0xFF6A6684),
    // Auf der weissen Diagrammkarte: 4.4, 4.0, 4.0 und 11.7 : 1.
    stageAwake = Color(0xFFCF4F3A),
    stageRem = Color(0xFF0E8C9E),
    stageLight = Color(0xFF7B6CF6),
    stageDeep = Color(0xFF2F2A85),
)

private val DarkScheme = darkColorScheme(
    primary = Lavender,
    onPrimary = Color(0xFF141A3D),
    primaryContainer = Color(0xFF3B3486),
    onPrimaryContainer = Color(0xFFFBF7F0),
    secondary = Color(0xFFC9C3E6),
    onSecondary = Color(0xFF141A3D),
    secondaryContainer = Color(0xFF2A2760),
    onSecondaryContainer = Color(0xFFFBF7F0),
    background = Color(0xFF0C1027),
    onBackground = Color(0xFFFBF7F0),
    surface = Color(0xFF0C1027),
    onSurface = Color(0xFFFBF7F0),
    surfaceVariant = Color(0xFF1A1F45),
    onSurfaceVariant = Color(0xFFC9C3E6),
    surfaceContainerLowest = Color(0xFF070914),
    surfaceContainerLow = Color(0xFF11152F),
    surfaceContainer = Color(0xFF161B3A),
    surfaceContainerHigh = Color(0xFF1C2245),
    surfaceContainerHighest = Color(0xFF232A52),
    // Rand (Felder, Standard-Chips): mindestens 3:1 gegen Karte und Hintergrund, auch simuliert (WCAG 1.4.11).
    outline = Color(0xFF726EA4),
    outlineVariant = Color(0xFF2A2D55),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF3B3486),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4DFFB),
    onPrimaryContainer = Color(0xFF1A1830),
    secondary = Color(0xFF5C5876),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFECE8F8),
    onSecondaryContainer = Color(0xFF1A1830),
    background = Color(0xFFFBF7F0),
    onBackground = Color(0xFF1A1830),
    surface = Color(0xFFFBF7F0),
    onSurface = Color(0xFF1A1830),
    surfaceVariant = Color(0xFFF3F0FA),
    onSurfaceVariant = Color(0xFF5C5876),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFDFBF7),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF3F0FA),
    surfaceContainerHighest = Color(0xFFEAE6F5),
    outline = Color(0xFF8C87A8),
    outlineVariant = Color(0xFFE2DEEF),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

private val BaseTypography = Typography()

private fun TextStyle.m(weight: Int, size: Int? = null, tracking: Double? = null) = copy(
    fontFamily = Manrope,
    fontWeight = FontWeight(weight),
    fontSize = size?.sp ?: fontSize,
    letterSpacing = tracking?.sp ?: letterSpacing,
)

private val AppTypography = Typography(
    displayLarge = BaseTypography.displayLarge.m(700, tracking = -1.5),
    displayMedium = BaseTypography.displayMedium.m(700, tracking = -1.0),
    displaySmall = BaseTypography.displaySmall.m(700, tracking = -0.5),
    headlineLarge = BaseTypography.headlineLarge.m(700, tracking = -0.5),
    headlineMedium = BaseTypography.headlineMedium.m(700, tracking = -0.25),
    headlineSmall = BaseTypography.headlineSmall.m(700),
    titleLarge = BaseTypography.titleLarge.m(700),
    titleMedium = BaseTypography.titleMedium.m(600),
    titleSmall = BaseTypography.titleSmall.m(600),
    bodyLarge = BaseTypography.bodyLarge.m(500),
    bodyMedium = BaseTypography.bodyMedium.m(500),
    bodySmall = BaseTypography.bodySmall.m(500),
    labelLarge = BaseTypography.labelLarge.m(600),
    labelMedium = BaseTypography.labelMedium.m(600),
    labelSmall = BaseTypography.labelSmall.m(600),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    // Karten überall mit 22 dp.
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

val LocalBrand = staticCompositionLocalOf { DarkBrand }
private val LocalDark = staticCompositionLocalOf { true }

/** Zugriff auf die Markenfarben und den aufgelösten Modus. */
object PhaseWakeTheme {
    val brand: BrandColors @Composable get() = LocalBrand.current

    /** `true`, wenn gerade das dunkle Design gilt (gewählt oder vom System). */
    val isDark: Boolean @Composable get() = LocalDark.current
}

@Composable
fun PhaseWakeTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme: ColorScheme = if (dark) DarkScheme else LightScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalBrand provides (if (dark) DarkBrand else LightBrand), LocalDark provides dark) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
