package com.phasewake.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import com.phasewake.app.ui.theme.PhaseWakeTheme

// Seiten im Stil der Startseite (Verlauf, Einstellungen, Editor, Einrichtung, Diagnose …): derselbe Himmel, dieselben
// Karten. Gleich auf dem iPhone (SkyPage.swift).

/**
 * Stimmung des Himmels für alle Seiten ausser der Startseite (die rechnet sie selbst). Die App setzt sie aus dem nächsten
 * Weckfenster ([SkyMood.at]); ohne Angabe ruhig ([SkyMood.Plain]).
 */
val LocalSkyMood = compositionLocalOf { SkyMood.Plain }

/**
 * Hintergrund einer Seite: dunkel ganzflächig der Nachthimmel mit der Stimmung [LocalSkyMood] (wie die Startseite), hell
 * die Creme-Fläche; den Tageshimmel trägt dort der Kopf ([SkyHeader]).
 */
@Composable
fun SkyPage(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (PhaseWakeTheme.isDark) {
        AnimatedNight(modifier.fillMaxSize(), LocalSkyMood.current, content)
    } else {
        Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
    }
}

/**
 * Kopfbereich einer Seite wie der Kopf der Startseite: hell ein Stück Tageshimmel ([AnimatedDay]) mit unten runden Ecken,
 * dunkel durchsichtig (der Nachthimmel liegt schon dahinter). Schrift und Symbole darin in [skyInk] (≥ 4.5:1 auf dem
 * Himmel, SkyMoodTest). [statusBar]: reicht bis unter die Statusleiste. [bleedSide]/[bleedTop]: ragt so weit über den Platz in
 * einer Liste hinaus (seitlicher Rand und oberer Innenabstand), damit der Himmel randlos ist; der Inhalt bleibt, wo er war.
 */
@Composable
fun SkyHeader(
    modifier: Modifier = Modifier,
    statusBar: Boolean = false,
    bleedSide: Dp = 0.dp,
    bleedTop: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = PhaseWakeTheme.isDark
    val ink = skyInk()
    val inner = @Composable {
        CompositionLocalProvider(LocalContentColor provides ink.text) {
            Column(
                Modifier.fillMaxWidth()
                    .then(if (statusBar) Modifier.statusBarsPadding() else Modifier)
                    .padding(start = bleedSide, end = bleedSide, top = bleedTop, bottom = if (dark) 0.dp else HEADER_BOTTOM),
                content = content,
            )
        }
    }
    if (dark) {
        Box(modifier.fillMaxWidth().bleed(bleedSide, bleedTop)) { inner() }
    } else {
        AnimatedDay(modifier.fillMaxWidth().bleed(bleedSide, bleedTop).clip(HEADER_SHAPE), LocalSkyMood.current) { inner() }
    }
}

/**
 * Seite mit Kopfzeile ([bar]: Zurück, Titel, Knöpfe) im Himmel-Kopf und [content] darunter; frei bleiben wie bisher
 * Statusleiste, Navigationsleiste und Tastatur.
 */
@Composable
fun SkyBarPage(bar: @Composable RowScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    SkyPage {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
            SkyHeader(statusBar = true) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically, content = bar)
            }
            Spacer(Modifier.height(BAR_GAP))
            content()
        }
    }
}

/**
 * Gefüllter Knopf im Himmel-Kopf: dunkel Lavendel mit Indigo-Schrift wie die Hauptfarbe, hell kräftiges Lavendel mit
 * weisser Schrift ([skyInk]).
 */
@Composable
fun skyButtonColors(): ButtonColors = skyInk().let { ButtonDefaults.buttonColors(containerColor = it.button, contentColor = it.onButton) }

/** Abstand zwischen Kopfzeile und Inhalt. */
private val BAR_GAP = 8.dp

/** Seitlicher Rand der Seiten; so weit ragt ein [SkyHeader] in einer Liste randlos hinaus. */
val PAGE_SIDE: Dp = 16.dp

/** Titel einer Seite (headlineMedium), im Kopf ([SkyHeader]) in dessen Schriftfarbe. */
@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.headlineMedium, modifier = modifier.padding(start = 4.dp, bottom = 4.dp))
}

/** Unten runde Ecken wie der Kopf der Startseite. */
private val HEADER_SHAPE = RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp)

/** Luft unter dem Inhalt im hellen Himmel-Kopf. */
private val HEADER_BOTTOM = 14.dp

/**
 * Misst um [side] links und rechts breiter und setzt um [top] höher an, meldet aber nur den eigenen Platz: Der Kopf ragt
 * randlos in Rand und oberen Innenabstand der Liste.
 */
private fun Modifier.bleed(side: Dp, top: Dp): Modifier = if (side == 0.dp && top == 0.dp) this else layout { measurable, constraints ->
    val s = side.roundToPx()
    val t = top.roundToPx()
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth + 2 * s else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    val height = (placeable.height - t).coerceAtLeast(0)
    layout(constraints.constrainWidth(placeable.width - 2 * s), height) { placeable.place(-s, -t) }
}

/**
 * Karten der Seiten: dunkel Glas auf dem Himmel, dichter als die Karten der Startseite ([Night.Panel]), damit Diagramme,
 * Phasenfarben und Ränder von Bedienelementen auch über dem Abendrot ≥ 3:1 behalten (SkyPageContrastTest); hell warmes
 * Weiss.
 */
@Composable
fun skyCardColors(): CardColors = CardDefaults.cardColors(containerColor = skyCard())

/** Fläche einer Karte der Seiten ([skyCardColors]). */
@Composable
fun skyCard(): Color = if (PhaseWakeTheme.isDark) Night.Panel else MaterialTheme.colorScheme.surfaceContainer

/**
 * Glaskante wie auf der Startseite: dunkel oben hell mit Lavendel-Schimmer, nach unten auslaufend; hell ein Hauch Lavendel.
 * Reine Zierde, kein Bedienrand.
 */
@Composable
fun skyCardEdge(): BorderStroke = if (PhaseWakeTheme.isDark) {
    BorderStroke(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Night.Wave.copy(alpha = 0.10f), Color.White.copy(alpha = 0.02f))))
} else {
    BorderStroke(1.dp, Night.Glow.copy(alpha = 0.10f))
}

/** Tiefe wie auf der Startseite: hell ein weicher Schatten, dunkel keiner (die Glaskante trägt die Tiefe). */
@Composable
fun skyCardLift(): CardElevation = CardDefaults.cardElevation(defaultElevation = if (PhaseWakeTheme.isDark) 0.dp else 3.dp)
