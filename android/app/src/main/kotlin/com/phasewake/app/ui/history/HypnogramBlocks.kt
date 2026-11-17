package com.phasewake.app.ui.history

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.phasewake.app.core.ChartData
import com.phasewake.app.core.SleepStage
import com.phasewake.app.core.StageBlock
import com.phasewake.app.ui.components.mixColor
import java.time.Instant
import kotlin.math.roundToInt

// Zeichnen der Blöcke und des Weckfensters im Hypnogramm (gross im Verlauf, klein auf der Nachtkarte).

/**
 * Weckfenster als leuchtendes Band: in der Mitte am kräftigsten, oben und unten weich auslaufend, mit feinem Rand am
 * Beginn des Fensters.
 */
internal fun DrawScope.drawWindowBand(color: Color, topLeft: Offset, area: Size) {
    if (area.width <= 0f) return
    drawRect(
        Brush.verticalGradient(
            0f to color.copy(alpha = 0.05f),
            0.5f to color.copy(alpha = 0.18f),
            1f to color.copy(alpha = 0.05f),
            startY = topLeft.y,
            endY = topLeft.y + area.height,
        ),
        topLeft, area,
    )
    drawLine(color.copy(alpha = 0.35f), topLeft, Offset(topLeft.x, topLeft.y + area.height), strokeWidth = 1.dp.toPx())
}

/**
 * Aussehen der Blöcke: Phasenfarben [colors], Farbe der Stufenlinien [connector] (auch für unbekannte Phasen), Radius
 * [corner], [dark] für Glanz und Verlauf, [glow] für Schein und Schleier (siehe [drawBlocks]).
 */
internal data class BlockStyle(
    val colors: Map<SleepStage, Color>,
    val connector: Color,
    val corner: Float,
    val dark: Boolean,
    val glow: Boolean = false,
)

/**
 * Blöcke je Zeile, auf ganze Pixel gesetzt (keine weichen Kanten), mindestens 1 px breit und nie aufgeblasen; Ecken
 * nur gerundet, wo ein Block mindestens [ROUND_MIN] breit ist, sonst eckig.
 *
 * Beide Hypnogramme schlicht, ohne Schein – [BlockStyle.glow] bleibt nur als Option.
 * [BlockStyle.glow]: kräftige Phasenfarbe im Verlauf (dunkel oben aufgehellt, hell unten vertieft – so
 * entfernt sich die Farbe nie Richtung Karte und behält ihren Kontrast), oben eine feine Glanzkante; nur breite Blöcke
 * (ab [GLOW_MIN]) bekommen einen schwachen, schmalen Schein und einen leisen Farbschleier bis zum Boden. Stufenlinien
 * im Verlauf von der einen zur anderen Phasenfarbe.
 *
 * Ohne [BlockStyle.glow] (kleines Hypnogramm der Nachtkarte): flache, kräftige Phasenfarbe ohne Schein, Schleier und
 * Glanz; die Stufenlinien sind 1 px dünn in [BlockStyle.connector]. Viele kurze Blöcke (z. B. aus Samsung Health)
 * bleiben so scharf.
 */
internal fun DrawScope.drawBlocks(model: HypnoModel, style: BlockStyle, origin: Offset, area: Size) {
    val lane = area.height / stageOrder.size
    val barHeight = (lane * 0.76f).roundToInt().toFloat()
    val floor = origin.y + area.height
    fun x(at: Instant) = (origin.x + area.width * model.fraction(at)).roundToInt().toFloat()
    var previous: StageBlock? = null
    model.blocks.forEach { block ->
        val level = ChartData.level(block.stage) ?: return@forEach
        val cy = origin.y + lane * (level + 0.5f)
        val color = style.colors[block.stage] ?: style.connector
        val x0 = x(block.start)
        val w = (x(block.end) - x0).coerceAtLeast(1f)
        val top = (cy - barHeight / 2).roundToInt().toFloat()
        val wide = w >= GLOW_MIN.toPx()
        // Schleier bis zum Boden (nur breite Blöcke im grossen Hypnogramm): Das Profil wirkt wie eine farbige
        // Landschaft.
        if (style.glow && wide) {
            drawRect(
                Brush.verticalGradient(
                    listOf(color.copy(alpha = 0.16f), color.copy(alpha = 0f)), startY = cy, endY = floor,
                ),
                Offset(x0, cy), Size(w, (floor - cy).coerceAtLeast(0f)),
            )
        }
        previous?.let { p ->
            val pl = ChartData.level(p.stage)
            if (pl != null && !block.start.isAfter(p.end.plusSeconds(60))) {
                val fromColor = style.colors[p.stage] ?: style.connector
                drawStep(style, fromColor, color, Offset(x0, origin.y + lane * (pl + 0.5f)), Offset(x0, cy))
            }
        }
        drawBar(style, color, Offset(x0, top), Size(w, barHeight), wide)
        previous = block
    }
}

/** Stufenlinie von [from] (Farbe [fromColor]) zu [to] ([color]); ohne Schein eine Haarlinie in der Linienfarbe. */
private fun DrawScope.drawStep(style: BlockStyle, fromColor: Color, color: Color, from: Offset, to: Offset) {
    if (style.glow) {
        drawLine(
            Brush.linearGradient(listOf(fromColor, color), start = from, end = to), from, to,
            strokeWidth = 1.dp.toPx(),
        )
    } else {
        // Haarlinie auf der Pixelmitte, damit sie nicht über zwei Pixel verschwimmt.
        val px = Offset(0.5f, 0f)
        drawLine(style.connector, from + px, to + px, strokeWidth = 1f)
    }
}

/** Ein Block bei [topLeft] in [size]; mit Schein ([wide]: Block breit genug) samt Verlauf und Glanzkante. */
private fun DrawScope.drawBar(style: BlockStyle, color: Color, topLeft: Offset, size: Size, wide: Boolean) {
    val x0 = topLeft.x
    val top = topLeft.y
    val w = size.width
    val barHeight = size.height
    val r = if (w >= ROUND_MIN.toPx()) minOf(style.corner, w / 4) else 0f
    if (style.glow && wide) {
        val halo = 1.5.dp.toPx()
        drawRoundRect(
            color.copy(alpha = 0.14f), Offset(x0 - halo, top - halo), Size(w + 2 * halo, barHeight + 2 * halo),
            CornerRadius(r + halo),
        )
    }
    if (style.glow) {
        val (upper, lower) = if (style.dark) {
            mixColor(color, Color.White, 0.35f) to color
        } else {
            color to mixColor(color, Color.Black, 0.25f)
        }
        drawRoundRect(
            Brush.verticalGradient(listOf(upper, lower), startY = top, endY = top + barHeight), Offset(x0, top),
            Size(w, barHeight), CornerRadius(r),
        )
        // Glanzkante oben (nur wo der Block breit genug ist).
        if (w > 2 * r + 2.dp.toPx()) {
            drawLine(
                Color.White.copy(alpha = if (style.dark) 0.45f else 0.35f), Offset(x0 + r, top + 1.dp.toPx()),
                Offset(x0 + w - r, top + 1.dp.toPx()), strokeWidth = 1.dp.toPx(),
            )
        }
    } else {
        drawRoundRect(color, Offset(x0, top), Size(w, barHeight), CornerRadius(r))
    }
}

/** Mindestbreite eines Blocks für Schein und Schleier im grossen Hypnogramm. */
private val GLOW_MIN = 8.dp

/** Mindestbreite eines Blocks für gerundete Ecken; schmalere Blöcke bleiben eckig. */
private val ROUND_MIN = 6.dp
