package com.phasewake.app.ui.components

import androidx.compose.ui.graphics.Color
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Stimmung des Nachthimmels nach Tageszeit: [dusk] 0–1 färbt den Horizont am Abend magenta-rosé mit violettem Schein,
 * [dawn] 0–1 lässt ihn vor dem Weckfenster glutorange mit goldenem Schein aufgehen, [night] 0–1 vertieft oben das
 * Indigo und zeigt ruhende Sterne. So kräftig, wie Schrift darauf ≥ 4.5:1 behält (SkyMoodTest). Gleich auf Apple
 * (`SkyMood` in Theme.swift).
 */
data class SkyMood(val dusk: Float = 0f, val dawn: Float = 0f, val night: Float = 0f) {
    companion object {
        /** Ruhiger Nachthimmel ohne Färbung (Tag, Begrüssung, Vorschau ohne Zeit). */
        val Plain = SkyMood()

        /** Abend: Magenta-Rosé am Horizont, darüber ein violetter Schein. */
        val DuskLow = Color(0xFFC8308C)
        val DuskHigh = Color(0xFFB84CC8)

        /** Morgendämmerung: Glutorange am Horizont, darüber ein goldener Schein. */
        val DawnLow = Color(0xFFE8581C)
        val DawnHigh = Color(0xFFF0A830)

        /** Tiefe Nacht: Indigo oben. */
        val NightTop = Color(0xFF1C2366)

        /** Deckkraft des Horizonts ganz unten und des Scheins darüber, je Abend und Morgen. */
        const val DUSK_ALPHA = 0.28f
        const val DUSK_SUN = 0.16f
        const val DAWN_ALPHA = 0.26f
        const val DAWN_SUN = 0.08f

        /** Ab hier (Anteil der Höhe von oben) beginnt der Horizont zu leuchten; der Schein reicht nie darüber. */
        const val HORIZON_START = 0.4f

        /**
         * Deckkraft des Lichtscheins oben rechts in seiner Mitte; in tiefer Nacht um [NIGHT_GLOW_DIM] leiser (helleres
         * Indigo).
         */
        const val GLOW_ALPHA = 0.55f
        const val NIGHT_GLOW_DIM = 0.15f

        /** Stunden vor dem Weckfenster, über die die Dämmerung aufzieht. */
        private const val DAWN_LEAD_MINUTES = 180f

        /**
         * Stimmung um [now]: Dämmerung zieht in den letzten drei Stunden vor [windowStart] auf und bleibt im Fenster
         * voll; am frühen Morgen (5–10 Uhr) ohnehin hell. Abendrot von 17 bis 23 Uhr, am stärksten 19–21 Uhr. Tiefe
         * Nacht mit Sternen ab 21:30 (voll ab 23 Uhr) bis gegen 5 Uhr, sobald die Dämmerung kommt, weicht sie.
         */
        fun at(now: Instant, zone: ZoneId, windowStart: Instant?, target: Instant? = null): SkyMood {
            val minutesToWindow = windowStart?.let { Duration.between(now, it).toMinutes().toFloat() }
            val inWindow = windowStart != null && target != null && !now.isBefore(windowStart) && now.isBefore(target)
            val planDawn = when {
                inWindow -> 1f
                minutesToWindow == null || minutesToWindow < 0f -> 0f
                else -> (1f - minutesToWindow / DAWN_LEAD_MINUTES).coerceIn(0f, 1f)
            }
            val t = now.atZone(zone).toLocalTime()
            val hour = t.hour + t.minute / 60f
            val morning = ramp(hour, 5f, 7f) * (1f - ramp(hour, 10f, 11f))
            val dawn = maxOf(planDawn, morning)
            val evening = ramp(hour, 17f, 19f) * (1f - ramp(hour, 21f, 23f))
            val deep = if (hour >= 12f) ramp(hour, 21.5f, 23f) else 1f - ramp(hour, 4f, 5.5f)
            return SkyMood(dusk = evening * (1f - dawn), dawn = dawn, night = deep * (1f - dawn))
        }

        /** 0 vor [from], 1 ab [to], dazwischen weich. */
        private fun ramp(x: Float, from: Float, to: Float): Float {
            val f = ((x - from) / (to - from)).coerceIn(0f, 1f)
            return f * f * (3f - 2f * f)
        }
    }

    /** Anteil des Morgens an der Färbung (0 = nur Abend). */
    private val morningShare: Float get() = if (dusk + dawn <= 0f) 0f else dawn / (dusk + dawn)

    /** Stärke der Färbung insgesamt. */
    private val tint: Float get() = (dusk + dawn).coerceAtMost(1f)

    /** Obere Farbe des Grundverlaufs: in tiefer Nacht kräftiges Indigo. */
    val top: Color get() = mixColor(Night.Top, NightTop, night.coerceIn(0f, 1f))

    /** Horizontfarbe mit ihrer Deckkraft ganz unten; transparent ohne Stimmung. */
    val horizon: Color
        get() {
            if (tint <= 0f) return Color.Transparent
            val w = morningShare
            return mixColor(DuskLow, DawnLow, w).copy(alpha = (DUSK_ALPHA + (DAWN_ALPHA - DUSK_ALPHA) * w) * tint)
        }

    /** Schein über dem Horizont (violett am Abend, golden am Morgen) mit seiner Deckkraft in der Mitte. */
    val sun: Color
        get() {
            if (tint <= 0f) return Color.Transparent
            val w = morningShare
            return mixColor(DuskHigh, DawnHigh, w).copy(alpha = (DUSK_SUN + (DAWN_SUN - DUSK_SUN) * w) * tint)
        }

    /** Farbe des Lichtscheins oben rechts: Lavendel, am Abend zum Violett hin. */
    val glow: Color get() = mixColor(Night.Glow, DuskHigh, dusk.coerceIn(0f, 1f))

    /**
     * Farbe des Himmels bei Höhe [y] (0 oben, 1 unten), ohne Lichtschein oben und ohne Sterne: Grundverlauf, Horizont
     * und (ungünstig gerechnet: ab [HORIZON_START] überall in voller Stärke) der Schein. Für die Kontrast-Tests;
     * gezeichnet wird mit denselben Werten in [drawNight].
     */
    fun skyAt(y: Float): Color {
        val yy = y.coerceIn(0f, 1f)
        val base = mixColor(top, Night.Bottom, yy)
        val f = ((yy - HORIZON_START) / (1f - HORIZON_START)).coerceIn(0f, 1f)
        val h = horizon
        val lit = mixColor(base, h.copy(alpha = 1f), h.alpha * f)
        val s = sun
        return if (yy >= HORIZON_START) mixColor(lit, s.copy(alpha = 1f), s.alpha) else lit
    }

    /** Deckkraft des Lichtscheins in seiner Mitte. */
    val glowAlpha: Float get() = GLOW_ALPHA - NIGHT_GLOW_DIM * night.coerceIn(0f, 1f)

    /** Himmel in der Mitte des Lichtscheins oben rechts (ungünstigste Stelle oben), für die Kontrast-Tests. */
    fun glowCenter(): Color = mixColor(top, glow, glowAlpha)
}

/** Mischung im sRGB-Raum, wie Verläufe gezeichnet werden. */
internal fun mixColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
)
