package com.phasewake.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.BorderStroke
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
 * Startseite). Dunkel «Mitternacht & Lavendel», hell «Creme Caramel».
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
    /** Karten der Startseite: dunkel durchscheinend auf dem Nachthimmel, hell warmes Weiss. */
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

/**
 * Hell: «Creme Caramel» (Variante Caramel + Lavendel). Fläche Creme #FBF3E6, Karten warmes
 * Weiss, Lavendel #7B6CF6 bleibt der Hauptakzent (Welle, Diagramme, Ränder), Caramel der sparsame Zweitakzent (gewählte
 * Chips, Tertiär, Sonnenaufgang). Kontraste: SkyPageContrastTest.
 */
internal val LightBrand = BrandColors(
    hero = Color(0xFF141A3D),
    onHero = Color(0xFFFBF7F0),
    onHeroMuted = Color(0xFFC9C3E6),
    heroWindow = Color(0xFFE4DFFB),
    wave = Color(0xFF7B6CF6),
    echo = Color(0xFFB3A9F7),
    dot = Color(0xFF3B3486),
    chartQuiet = Color(0xFFDAD4FA),
    chartActive = Color(0xFF7B6CF6),
    chartTrack = Color(0xFFF1E7D8),
    card = Creme.Card,
    // 90 %: 3.3:1 auf der Karte, 3.1:1 auf der Fläche #FBF3E6.
    chipBorder = Color(0xFF7B6CF6).copy(alpha = 0.9f),
    // Gewählter Chip in hellem Caramel (Zweitakzent); die Schrift darauf #2A2340 hat 11.9:1.
    chipSelected = Creme.CaramelLight,
    // 80 % Deckkraft wäre auf Hell zu schwach (< 4.5:1); daher ein fester Ton: 5.4:1 auf der Fläche, 4.8:1 auf #F4E4CC.
    mutedSoft = Color(0xFF6A5F74),
    // Auf der Karte #FFFCF7: 4.3, 3.9, 3.9 und 11.5 : 1.
    stageAwake = Color(0xFFCF4F3A),
    stageRem = Color(0xFF0E8C9E),
    stageLight = Color(0xFF7B6CF6),
    stageDeep = Color(0xFF2F2A85),
)

/**
 * Farben von «Creme Caramel» (hell). Caramel #B86B25 hat auf Creme nur 3.7:1: genug für Flächen, Ränder und grosse
 * Schrift (≥ 3:1), nicht für Text. Für Text darum [CaramelText] (#8A4A10, 6.2:1 auf Creme, 6.7:1 auf der Karte).
 */
object Creme {
    /** Fläche der Seiten. */
    val Paper = Color(0xFFFBF3E6)
    /** Karten: warmes Weiss. */
    val Card = Color(0xFFFFFCF7)
    /** Schrift. */
    val Ink = Color(0xFF2A2340)
    /** Lavendel, Hauptakzent (Welle, Diagramme, Ränder; als Text zu hell: 3.6:1). */
    val Lavender = Color(0xFF7B6CF6)
    /** Lavendel als Hauptfarbe für Knöpfe und Text: Weiss darauf 6.8:1, auf Creme 6.2:1. */
    val LavenderDeep = Color(0xFF5546C8)
    /** Caramel, Zweitakzent für Flächen, Ränder und Sonnenaufgang (nicht für Text). */
    val Caramel = Color(0xFFB86B25)
    /** Caramel für Text (Tertiär). */
    val CaramelText = Color(0xFF8A4A10)
    /** Helles Caramel: oberer Himmel am Tag, gewählte Chips. */
    val CaramelLight = Color(0xFFF4E4CC)
    /** Unteres Caramel des Tageshimmels. */
    val CaramelDeep = Color(0xFFE9C9A0)
    /** Nebentext: 6.3:1 auf Creme, 5.5:1 auf #F4E4CC. */
    val Muted = Color(0xFF5E5670)
}

private val DarkScheme = darkColorScheme(
    primary = Lavender,
    onPrimary = Color(0xFF141A3D),
    primaryContainer = Color(0xFF3B3486),
    onPrimaryContainer = Color(0xFFFBF7F0),
    secondary = Color(0xFFC9C3E6),
    onSecondary = Color(0xFF141A3D),
    secondaryContainer = Color(0xFF2A2760),
    onSecondaryContainer = Color(0xFFFBF7F0),
    // Tertiär in Lavendel statt dem Material-Rosa (z. B. AM/PM im Zeitwähler).
    tertiary = Lavender,
    onTertiary = Color(0xFF141A3D),
    tertiaryContainer = Color(0xFF3B3486),
    onTertiaryContainer = Color(0xFFFBF7F0),
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

internal val LightScheme = lightColorScheme(
    primary = Creme.LavenderDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E2FB),
    onPrimaryContainer = Creme.Ink,
    secondary = Creme.Muted,
    onSecondary = Color.White,
    // Zweitakzent Caramel: gewählte Chips und die Markierung der Navigationsleiste.
    secondaryContainer = Creme.CaramelLight,
    onSecondaryContainer = Creme.Ink,
    // Tertiär in Caramel statt dem Material-Rosa (z. B. AM/PM im Zeitwähler).
    tertiary = Creme.CaramelText,
    onTertiary = Color.White,
    tertiaryContainer = Creme.CaramelLight,
    onTertiaryContainer = Creme.Ink,
    background = Creme.Paper,
    onBackground = Creme.Ink,
    surface = Creme.Paper,
    onSurface = Creme.Ink,
    surfaceVariant = Creme.CaramelLight,
    onSurfaceVariant = Creme.Muted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFDF8EF),
    surfaceContainer = Creme.Card,
    surfaceContainerHigh = Color(0xFFF7ECDB),
    surfaceContainerHighest = Color(0xFFF1E2CB),
    // Rand (Felder, Standard-Chips): ≥ 3:1 gegen Karte, Fläche und #F4E4CC (WCAG 1.4.11).
    outline = Color(0xFF857A8E),
    outlineVariant = Color(0xFFE9DCC6),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

/**
 * Rand der OutlinedButtons in «outline» (≥ 3:1 zu Karte und Hintergrund, WCAG 1.4.11). Material 3 nimmt sonst das
 * blasse «outlineVariant», das auch Trennlinien und Raster zeichnet.
 */
@Composable
fun outlinedBorder(enabled: Boolean = true): BorderStroke = BorderStroke(
    1.dp,
    if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
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

/**
 * Design der App. [eveningDark]: «Abends dunkel ab» gilt gerade ([EveningDarkTicker]); dann ist auch «System» und «Hell»
 * dunkel.
 */
@Composable
fun PhaseWakeTheme(mode: ThemeMode, eveningDark: Boolean = false, content: @Composable () -> Unit) {
    val dark = resolveDark(mode, isSystemInDarkTheme(), eveningDark)
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

/** Ob das dunkle Design gilt: «Dunkel» immer, sonst am Abend ([eveningDark]) oder wie das System bei «System». */
fun resolveDark(mode: ThemeMode, systemDark: Boolean, eveningDark: Boolean): Boolean = when (mode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> eveningDark
    ThemeMode.SYSTEM -> eveningDark || systemDark
}
