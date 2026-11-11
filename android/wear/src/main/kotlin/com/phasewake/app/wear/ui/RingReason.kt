package com.phasewake.app.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Bis zu diesem Anteil der Schriftgrösse wird der Grund verkleinert, damit er auf eine Zeile passt. */
private const val REASON_MIN_SCALE = 0.85f

/** Zeilenabstand des zweizeiligen Grunds im Verhältnis zur Schriftgrösse (enger als üblich, spart Höhe). */
private const val REASON_TWO_LINE_HEIGHT = 1.15f

/** Schriftgrösse, Zeilenhöhe und Zeilenzahl für den Grund beim Wecken. */
internal data class ReasonFit(val fontSize: TextUnit, val lineHeight: TextUnit, val lines: Int)

/**
 * Grund beim Wecken ganz lesbar statt mit «…»: zuerst einzeilig, nötigenfalls etwas kleiner (bis [REASON_MIN_SCALE]);
 * sonst zweizeilig mit engerem Zeilenabstand, ebenso nötigenfalls etwas kleiner. Erst was auch so nicht passt, endet mit «…».
 */
@Composable
internal fun rememberReasonFit(text: String, style: TextStyle, maxWidthDp: Int): ReasonFit {
    val measurer = rememberTextMeasurer()
    val maxWidthPx = with(LocalDensity.current) { maxWidthDp.dp.roundToPx() }.coerceAtLeast(0)
    return remember(text, style, maxWidthPx, measurer) {
        val full = style.fontSize
        if (!full.isSp) return@remember ReasonFit(full, style.lineHeight, 2)
        val min = full.value * REASON_MIN_SCALE
        fun fits(fit: ReasonFit) = !measurer.measure(
            text, style.copy(fontSize = fit.fontSize, lineHeight = fit.lineHeight), maxLines = fit.lines,
            constraints = Constraints(maxWidth = maxWidthPx),
        ).hasVisualOverflow
        for (lines in 1..2) {
            var size = full.value
            while (size >= min) {
                val fit = ReasonFit(size.sp, if (lines == 1) style.lineHeight else (size * REASON_TWO_LINE_HEIGHT).sp, lines)
                if (fits(fit)) return@remember fit
                size -= 0.5f
            }
        }
        ReasonFit(min.sp, (min * REASON_TWO_LINE_HEIGHT).sp, 2)
    }
}
