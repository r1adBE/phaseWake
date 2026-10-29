package com.phasewake.app.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import kotlin.math.roundToInt

// Antippen und Ziehen in den Diagrammen: Auswahl einer Stelle, Blase mit Datum und Wert, leichte Vibration.

/**
 * Antippen meldet die Stelle unter dem Finger an [onTap] (`null`: keine; ob das die Auswahl setzt oder aufhebt,
 * entscheidet der Aufrufer); nach kurzem Halten folgt die Auswahl dem Finger ([onMove]). Ohne Halten bleibt
 * seitliches Wischen beim Blättern (Karten, Nächte). [indexAt] rechnet den Punkt in eine Stelle um.
 * [key] startet die Erkennung neu, wenn sich die Daten ändern.
 */
internal fun Modifier.chartTouch(
    key: Any?,
    indexAt: (Offset, IntSize) -> Int?,
    onTap: (Int?) -> Unit,
    onMove: (Int) -> Unit,
): Modifier = pointerInput(key) {
    detectTapGestures { p -> onTap(indexAt(p, size)) }
}.pointerInput(key) {
    detectDragGesturesAfterLongPress(
        onDragStart = { p -> indexAt(p, size)?.let(onMove) },
        onDrag = { change, _ ->
            change.consume()
            indexAt(change.position, size)?.let(onMove)
        },
    )
}

/** Leichte Klick-Vibration beim Wechsel der Stelle bzw. Phase (wie das Raster eines Schiebereglers). */
@Composable
internal fun rememberTick(): () -> Unit {
    val haptics = LocalHapticFeedback.current
    return { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
}

/** Auswahl nach Antippen: dieselbe Stelle nochmals hebt sie auf, sonst gilt die neue. */
internal fun toggled(current: Int?, tapped: Int?): Int? = if (tapped == null || tapped == current) null else tapped

/** Stelle aus einer waagrechten Lage: [n] gleich breite Felder ab [left] Pixel. */
internal fun slotAt(x: Float, width: Int, left: Float, n: Int): Int? =
    if (n <= 0 || width <= left) null else ((x - left) / ((width - left) / n)).toInt().coerceIn(0, n - 1)

/**
 * Blase über dem Diagramm, waagrecht bei [anchorX] (Pixel) und innerhalb der Breite gehalten. Mit [onOpen] führt
 * Antippen zur Nacht (Pfeil «›», mindestens 48 dp hoch); Bildschirmleser lesen neue Werte von selbst vor.
 */
@Composable
internal fun ChartBubble(text: String, anchorX: Float, onOpen: (() -> Unit)?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val open = stringResource(R.string.night_open)
    val inner = if (onOpen != null) {
        Modifier.minimumInteractiveComponentSize().clip(MaterialTheme.shapes.small).clickable(onClickLabel = open, onClick = onOpen)
    } else {
        Modifier.clip(MaterialTheme.shapes.small)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0))
                val x = (anchorX - placeable.width / 2f).roundToInt().coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
                layout(constraints.maxWidth, placeable.height) { placeable.place(x, 0) }
            }
            .then(inner)
            .background(scheme.onSurface)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = scheme.surface)
        if (onOpen != null) Text(" ›", style = MaterialTheme.typography.labelLarge, color = scheme.surface)
    }
}

/** Wie ein Diagramm mit Auswahl antwortet: Stelle unter dem Finger, Lage der Blase, ihr Text und wohin sie führt. */
internal class ChartPick(
    /** Stelle zu einem Punkt in der Zeichenfläche der Grösse `IntSize`; `null`: keine. */
    val indexAt: (Offset, IntSize) -> Int?,
    /** Waagrechte Lage der Blase in Pixeln für Stelle und Breite. */
    val anchor: (Int, Float) -> Float,
    /** Text der Blase («28. Okt. · 7 Std. 20 Min»). */
    val text: (Int) -> String,
    /** Antippen der Blase (Detailseite der Nacht); `null`: nur lesen. */
    val open: (Int) -> (() -> Unit)? = { null },
)

/**
 * Zeichenfläche eines Diagramms mit Antippen/Ziehen ([chartTouch]), Vibration beim Wechsel der Stelle und Blase oben.
 * [draw] zeichnet mit der gewählten Stelle (`null`: keine). [key] setzt die Auswahl zurück, wenn sich die Daten ändern.
 */
@Composable
internal fun TouchChart(key: Any?, description: String, pick: ChartPick, modifier: Modifier = Modifier, draw: DrawScope.(Int?) -> Unit) {
    var selected by remember(key) { mutableStateOf<Int?>(null) }
    var width by remember { mutableFloatStateOf(0f) }
    val tick = rememberTick()
    Box(modifier.fillMaxWidth().height(CHART_HEIGHT).onSizeChanged { width = it.width.toFloat() }) {
        Canvas(
            Modifier.fillMaxWidth().height(CHART_HEIGHT).fadeIn().semantics { contentDescription = description }.chartTouch(
                key = key,
                indexAt = pick.indexAt,
                onTap = { selected = toggled(selected, it) },
                onMove = { i ->
                    if (i != selected) tick()
                    selected = i
                },
            ),
        ) { draw(selected) }
        selected?.let { i -> ChartBubble(pick.text(i), pick.anchor(i, width), pick.open(i)) }
    }
}
