package ch.r1ad.phasewake.ui.theme

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
import ch.r1ad.phasewake.shared.R as SharedR

/** Wie die App aussehen soll; Standard folgt dem System. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

private fun manrope(weight: Int) = Font(
    SharedR.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Manrope als variable Schrift; jede Stärke stellt die Achse «wght» passend ein. */
val Manrope = FontFamily(manrope(400), manrope(500), manrope(600), manrope(700), manrope(800))

/** Farben, die über das Material-Schema hinausgehen (Welle, Echo, Hero-Karte). */
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
)

private val DarkBrand = BrandColors(
    hero = Color(0xFF122466),
    onHero = Color(0xFFF2F5FF),
    onHeroMuted = Color(0xFF9FB8FF),
    heroWindow = Color(0xFF1E3A8A),
    wave = Color(0xFF7DB3FF),
    echo = Color(0xFF2F4FB8),
    dot = Color.White,
    chartQuiet = Color(0xFF2F4FB8),
    chartActive = Color(0xFF7DB3FF),
    chartTrack = Color(0xFF24345A),
)

private val LightBrand = BrandColors(
    hero = Color(0xFFE3EAFF),
    onHero = Color(0xFF0B1A4A),
    onHeroMuted = Color(0xFF2F4FB8),
    heroWindow = Color(0xFFC9D6FF),
    wave = Color(0xFF2F4FB8),
    echo = Color(0xFF9FB4F2),
    dot = Color(0xFF0B1A4A),
    chartQuiet = Color(0xFFB7C5EE),
    chartActive = Color(0xFF2F4FB8),
    chartTrack = Color(0xFFE1E6F0),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8FB4FF),
    onPrimary = Color(0xFF0B1A4A),
    primaryContainer = Color(0xFF2F4FB8),
    onPrimaryContainer = Color(0xFFF2F5FF),
    secondary = Color(0xFF9FB8FF),
    onSecondary = Color(0xFF0B1A4A),
    secondaryContainer = Color(0xFF1E2C52),
    onSecondaryContainer = Color(0xFFDCE4FF),
    background = Color(0xFF0B1426),
    onBackground = Color(0xFFE8EEFF),
    surface = Color(0xFF0B1426),
    onSurface = Color(0xFFE8EEFF),
    surfaceVariant = Color(0xFF15213D),
    onSurfaceVariant = Color(0xFF8FA3CC),
    surfaceContainerLowest = Color(0xFF080F1E),
    surfaceContainerLow = Color(0xFF111B33),
    surfaceContainer = Color(0xFF15213D),
    surfaceContainerHigh = Color(0xFF1B2847),
    surfaceContainerHighest = Color(0xFF223055),
    outline = Color(0xFF3A4C78),
    outlineVariant = Color(0xFF24345A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF2F4FB8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF0B1A4A),
    secondary = Color(0xFF4A5C8C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EAFF),
    onSecondaryContainer = Color(0xFF0B1A4A),
    background = Color(0xFFF4F6FB),
    onBackground = Color(0xFF0B1A4A),
    surface = Color(0xFFF4F6FB),
    onSurface = Color(0xFF0B1A4A),
    surfaceVariant = Color(0xFFE6EAF3),
    onSurfaceVariant = Color(0xFF5B6B8C),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFBFE),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF0F3FA),
    surfaceContainerHighest = Color(0xFFE8ECF5),
    outline = Color(0xFFB4BED3),
    outlineVariant = Color(0xFFE1E6F0),
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
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

val LocalBrand = staticCompositionLocalOf { DarkBrand }

/** Zugriff auf die Markenfarben im aktuellen Modus. */
object PhaseWakeTheme {
    val brand: BrandColors @Composable get() = LocalBrand.current
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
    androidx.compose.runtime.CompositionLocalProvider(LocalBrand provides if (dark) DarkBrand else LightBrand) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
