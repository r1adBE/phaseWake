package com.phasewake.app.ui.components

import androidx.compose.ui.graphics.Color
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Stimmung des Nachthimmels nach Tageszeit: [dusk] 0–1 färbt den Horizont am Abend leicht rosé-violett, [dawn] 0–1
 * lässt ihn vor dem Weckfenster warm aufhellen (Morgendämmerung). Beides nur unten am Horizont und schwach genug,
 * dass Schrift darauf ≥ 4.5:1 behält (SkyMoodTest). Gleich auf Apple (`SkyMood` in Theme.swift).
 */
data class SkyMood(val dusk: Float = 0f, val dawn: Float = 0f) {
    companion object {
        /** Ruhiger Nachthimmel ohne Färbung (Tag, Begrüssung, Vorschau ohne Zeit). */
        val Plain = SkyMood()

        /** Weckbildschirm ohne Sonnenaufgang: volle Morgendämmerung. */
        val Wake = SkyMood(dawn = 1f)

        /** Abendrot: rosé-violetter Horizont am Abend. */
        val Dusk = Color(0xFF9B5AA8)

        /** Morgendämmerung: warmes Terrakotta-Rosé am Horizont (gedämpft, damit die Nacht ruhig bleibt). */
        val Dawn = Color(0xFFC86A5A)

        /** Höchste Deckkraft der Horizontfarbe ganz unten. */
        const val MAX_ALPHA = 0.20f

        /** Deckkraft des Sonnenscheins über dem Horizont, als Anteil der Horizont-Deckkraft. */
        const val SUN_ALPHA = 0.5f

        /** Ab hier (Anteil der Höhe von oben) beginnt der Horizont zu leuchten. */
        const val HORIZON_START = 0.5f

        /** Stunden vor dem Weckfenster, über die die Dämmerung aufzieht. */
        private const val DAWN_LEAD_MINUTES = 180f

        /**
         * Stimmung um [now]: Dämmerung zieht in den letzten drei Stunden vor [windowStart] auf und bleibt im Fenster
         * voll; am frühen Morgen (5–10 Uhr) ohnehin hell. Abendrot von 17 bis 23 Uhr, am stärksten 19–21 Uhr.
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
            return SkyMood(dusk = evening * (1f - dawn), dawn = dawn)
        }

        /** 0 vor [from], 1 ab [to], dazwischen weich. */
        private fun ramp(x: Float, from: Float, to: Float): Float {
            val f = ((x - from) / (to - from)).coerceIn(0f, 1f)
            return f * f * (3f - 2f * f)
        }
    }

    /** Horizontfarbe mit ihrer Deckkraft ganz unten; transparent ohne Stimmung. */
    val horizon: Color
        get() {
            val total = (dusk + dawn).coerceAtMost(1f)
            if (total <= 0f) return Color.Transparent
            val w = dawn / (dusk + dawn)
            val c = Color(
                red = Dusk.red + (Dawn.red - Dusk.red) * w,
                green = Dusk.green + (Dawn.green - Dusk.green) * w,
                blue = Dusk.blue + (Dawn.blue - Dusk.blue) * w,
            )
            return c.copy(alpha = MAX_ALPHA * total)
        }

    /**
     * Farbe des Himmels bei Höhe [y] (0 oben, 1 unten), ohne Lichtschein oben: Grundverlauf, Horizont und (ungünstig
     * gerechnet: überall in voller Stärke) der Sonnenschein. Für die Kontrast-Tests; gezeichnet wird mit denselben
     * Werten in [drawNight].
     */
    fun skyAt(y: Float): Color {
        val base = mixColor(Night.Top, Night.Bottom, y.coerceIn(0f, 1f))
        val h = horizon
        val f = ((y - HORIZON_START) / (1f - HORIZON_START)).coerceIn(0f, 1f)
        val alpha = 1f - (1f - h.alpha * f) * (1f - h.alpha * SUN_ALPHA * f)
        return mixColor(base, h.copy(alpha = 1f), alpha)
    }
}

/** Mischung im sRGB-Raum, wie Verläufe gezeichnet werden. */
internal fun mixColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
)
