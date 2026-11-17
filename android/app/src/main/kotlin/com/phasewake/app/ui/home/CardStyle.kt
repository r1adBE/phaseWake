package com.phasewake.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.phasewake.app.ui.components.Night
import com.phasewake.app.ui.theme.PhaseWakeTheme

// Aussehen der Karten auf Startseite, Verlauf und Einstellungen: Fläche, Kante, Tiefe.

/** Kartenfarbe: auf dem Nachthimmel Weiss mit 6 %, hell eine weisse Fläche. */
@Composable
internal fun cardColor(): Color = PhaseWakeTheme.brand.card

/**
 * Rand der Karten: auf dem Nachthimmel eine Glaskante (oben hell mit Lavendel-Schimmer, nach unten auslaufend) für
 * Tiefe; hell ein Hauch Lavendel als Kante. Reine Zierde, kein Bedienrand (die Fläche selbst trägt keine Information).
 */
@Composable
internal fun cardEdge(): BorderStroke? = if (PhaseWakeTheme.isDark) {
    BorderStroke(
        1.dp,
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.30f), Night.Wave.copy(alpha = 0.10f), Color.White.copy(alpha = 0.02f))
        ),
    )
} else {
    BorderStroke(1.dp, Night.Glow.copy(alpha = 0.10f))
}

/** Tiefe der Karten: hell ein weicher Schatten, dunkel keiner (die Glaskante trägt die Tiefe). */
@Composable
internal fun cardLift(): CardElevation =
    CardDefaults.cardElevation(defaultElevation = if (PhaseWakeTheme.isDark) 0.dp else 3.dp)
