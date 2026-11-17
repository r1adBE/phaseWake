package com.phasewake.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.phasewake.app.ui.theme.Creme
import com.phasewake.app.ui.theme.PhaseWakeTheme

// Himmel am Tag «Creme Caramel» (hell, Entscheid r1AD 17.11.2026): Kopfbereiche der Seiten und der Startseite. Dunkel
// bleibt der Nachthimmel ([Night], [AnimatedNight]). Gleich auf dem iPhone (`DaySky` in Sky.swift).

/**
 * Farben des Tageshimmels: Verlauf von hellem Caramel #F4E4CC nach Caramel #E9C9A0, am Abend ein Hauch Lavendel am
 * Horizont, vor dem Weckfenster Caramel als Sonnenaufgang, oben rechts ein weisser Lichtschein. Die Schrift darauf ist
 * dunkel; ihre Kontraste gelten für jede Stimmung und Höhe (SkyMoodTest).
 */
object Day {
    val Top = Creme.CaramelLight
    val Bottom = Creme.CaramelDeep
    /** Oben in tiefer Nacht (nur, wer «Abends dunkel» ausgeschaltet hat): ein Hauch Lavendel. */
    val NightTop = Color(0xFFE7DDEA)

    /** Abend: Lavendel am Horizont, darüber ein heller Lavendel-Schein. */
    val DuskLow = Creme.Lavender
    val DuskHigh = Color(0xFFB9AEFF)

    /** Sonnenaufgang: Caramel am Horizont, darüber ein goldener Schein. */
    val DawnLow = Creme.Caramel
    val DawnHigh = Color(0xFFF0A830)

    const val DUSK_ALPHA = 0.14f
    const val DUSK_SUN = 0.10f
    const val DAWN_ALPHA = 0.20f
    const val DAWN_SUN = 0.12f

    /** Lichtschein oben rechts: Weiss, macht den Himmel nur heller (dunkle Schrift gewinnt dort an Kontrast). */
    val Glow = Color.White
    const val GLOW_ALPHA = 0.45f

    /** Schrift: 7.8:1 an der dunkelsten Stelle. */
    val Text = Creme.Ink
    /** Nebentext: ≥ 5:1. */
    val Muted = Color(0xFF4A4058)
    /** Lavendel für Text und Textknöpfe im Kopf: ≥ 4.7:1. */
    val Accent = Color(0xFF4436A6)
    /** Welle, gefüllte Knöpfe (mit weisser Schrift 6.8:1) und Bogen: ≥ 3:1 zum Himmel. */
    val Wave = Creme.LavenderDeep
    val WaveEcho = Creme.Lavender
    /** Mond, Weckpunkt, Punkt «jetzt». */
    val Moon = Color(0xFF3B3486)
}

/**
 * Farben für Schrift und Zeichen auf dem Himmel der Kopfbereiche: auf dem Nachthimmel hell ([OnNight], bisherige Werte),
 * auf dem Tageshimmel dunkel ([OnDay]). [button]/[onButton]: gefüllter Knopf im Kopf. [dawn]: Morgenlicht des
 * Weckfensters im Bogen der Startseite (nachts warmes Orange, am Tag Caramel als Sonnenaufgang).
 */
data class SkyInk(
    val text: Color,
    val muted: Color,
    val accent: Color,
    val wave: Color,
    val waveEcho: Color,
    val moon: Color,
    val button: Color,
    val onButton: Color,
    val dawn: Color,
) {
    companion object {
        val OnNight = SkyInk(Night.Text, Night.Muted, Night.Wave, Night.Wave, Night.WaveEcho, Night.Moon, Night.Wave, Night.Top, Color(0xFFFFB27A))
        val OnDay = SkyInk(Day.Text, Day.Muted, Day.Accent, Day.Wave, Day.WaveEcho, Day.Moon, Day.Wave, Color.White, Creme.Caramel)
    }
}

/** Farben auf dem Himmel der Kopfbereiche: dunkles Design Nacht, helles Tag. */
@Composable
fun skyInk(): SkyInk = if (PhaseWakeTheme.isDark) SkyInk.OnNight else SkyInk.OnDay

/** Obere Farbe des Tageshimmels: in tiefer Nacht ein Hauch Lavendel. */
val SkyMood.dayTop: Color get() = mixColor(Day.Top, Day.NightTop, night.coerceIn(0f, 1f))

private val SkyMood.dayTint: Float get() = (dusk + dawn).coerceAtMost(1f)
private val SkyMood.dayMorning: Float get() = if (dusk + dawn <= 0f) 0f else dawn / (dusk + dawn)

/** Horizont am Tag mit seiner Deckkraft ganz unten; transparent ohne Stimmung. */
val SkyMood.dayHorizon: Color
    get() {
        if (dayTint <= 0f) return Color.Transparent
        val w = dayMorning
        return mixColor(Day.DuskLow, Day.DawnLow, w).copy(alpha = (Day.DUSK_ALPHA + (Day.DAWN_ALPHA - Day.DUSK_ALPHA) * w) * dayTint)
    }

/** Schein über dem Horizont am Tag mit seiner Deckkraft in der Mitte. */
val SkyMood.daySun: Color
    get() {
        if (dayTint <= 0f) return Color.Transparent
        val w = dayMorning
        return mixColor(Day.DuskHigh, Day.DawnHigh, w).copy(alpha = (Day.DUSK_SUN + (Day.DAWN_SUN - Day.DUSK_SUN) * w) * dayTint)
    }

/**
 * Farbe des Tageshimmels bei Höhe [y] (0 oben, 1 unten), ohne den hellen Lichtschein und ungünstig gerechnet (der Schein
 * über dem Horizont ab [SkyMood.HORIZON_START] überall voll), wie [SkyMood.skyAt]. Für die Kontrast-Tests; gezeichnet wird
 * mit denselben Werten in [drawDay].
 */
fun SkyMood.daySkyAt(y: Float): Color {
    val yy = y.coerceIn(0f, 1f)
    val base = mixColor(dayTop, Day.Bottom, yy)
    val f = ((yy - SkyMood.HORIZON_START) / (1f - SkyMood.HORIZON_START)).coerceIn(0f, 1f)
    val h = dayHorizon
    val lit = mixColor(base, h.copy(alpha = 1f), h.alpha * f)
    val s = daySun
    return if (yy >= SkyMood.HORIZON_START) mixColor(lit, s.copy(alpha = 1f), s.alpha) else lit
}

private fun DrawScope.drawDay(mood: SkyMood) {
    drawRect(Brush.verticalGradient(listOf(mood.dayTop, Day.Bottom)))
    val horizon = mood.dayHorizon
    if (horizon.alpha > 0f) {
        drawRect(Brush.verticalGradient(SkyMood.HORIZON_START to horizon.copy(alpha = 0f), 1f to horizon))
        val sun = mood.daySun
        drawRect(
            Brush.radialGradient(
                listOf(sun, sun.copy(alpha = sun.alpha * 0.45f), sun.copy(alpha = 0f)),
                center = Offset(size.width * 0.5f, size.height),
                radius = size.height * (1f - SkyMood.HORIZON_START),
            ),
        )
    }
    drawRect(
        Brush.radialGradient(
            listOf(Day.Glow.copy(alpha = Day.GLOW_ALPHA), Day.Glow.copy(alpha = 0f)),
            center = Offset(size.width * 0.82f, size.height * 0.08f),
            radius = size.maxDimension * 0.6f,
        ),
    )
}

/**
 * Tageshimmel mit der Stimmung [mood] als Hintergrund (Kopfbereiche im hellen Design). Ruhig, ohne wandernden Schein; die
 * Stimmung wechselt sanft (mit «Animationen entfernen» sofort).
 */
@Composable
fun AnimatedDay(modifier: Modifier = Modifier, mood: SkyMood = SkyMood.Plain, content: @Composable () -> Unit = {}) {
    val moving = motionAllowed()
    val dusk by animateFloatAsState(mood.dusk, if (moving) tween(DAY_MOOD_MS) else snap(), label = "dayDusk")
    val dawn by animateFloatAsState(mood.dawn, if (moving) tween(DAY_MOOD_MS) else snap(), label = "dayDawn")
    val night by animateFloatAsState(mood.night, if (moving) tween(DAY_MOOD_MS) else snap(), label = "dayNight")
    val shown = SkyMood(dusk, dawn, night)
    Box(modifier.drawBehind { drawDay(shown) }) { content() }
}

/**
 * Himmel eines Kopfbereichs: dunkles Design der Nachthimmel ([AnimatedNight]), helles der Tageshimmel ([AnimatedDay]).
 */
@Composable
fun AnimatedSky(modifier: Modifier = Modifier, mood: SkyMood = SkyMood.Plain, content: @Composable () -> Unit = {}) {
    if (PhaseWakeTheme.isDark) AnimatedNight(modifier, mood, content) else AnimatedDay(modifier, mood, content)
}

/** Übergang zwischen zwei Stimmungen (wie der Nachthimmel). */
private const val DAY_MOOD_MS = 1500
