package com.phasewake.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import com.phasewake.app.ui.animationsEnabled

// Bewegung der Gestaltung: einmal pro Bildschirm prüfen, ob sie laufen darf.

/**
 * Ob Zierbewegung laufen darf: nicht bei «Animationen entfernen» (Bedienungshilfen) und nicht in Vorschau und
 * Bildschirmfotos (dort gibt es keinen zweiten Frame; das ruhende Bild ist das fertige).
 */
@Composable
internal fun motionAllowed(): Boolean {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    return remember(inspection) { !inspection && animationsEnabled(context) }
}

/**
 * Einmaliges Erscheinen 0 → 1 über [durationMs], neu bei geändertem [key]; ohne Bewegung ([motionAllowed]) sofort 1.
 * Keine Wiederholung: nach dem Erscheinen ruht das Bild.
 */
@Composable
internal fun rememberReveal(key: Any?, durationMs: Int = REVEAL_MS): Float {
    val allowed = motionAllowed()
    val progress = remember(key) { Animatable(if (allowed) 0f else 1f) }
    LaunchedEffect(key, allowed) {
        if (allowed) progress.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing)) else progress.snapTo(1f)
    }
    return progress.value
}

/** Dauer des Erscheinens von Welle und Diagrammen. */
internal const val REVEAL_MS = 1100
