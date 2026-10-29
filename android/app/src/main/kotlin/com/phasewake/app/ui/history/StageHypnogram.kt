package com.phasewake.app.ui.history

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.ChartData
import com.phasewake.app.core.SleepStage
import com.phasewake.app.core.StageBlock
import com.phasewake.app.ui.Format
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// Gestuftes Hypnogramm: vier Zeilen (oben wach, REM, leicht, unten tief), gefüllte, abgerundete Blöcke je Phase.

/**
 * Weckfenster einer Phase-Wake-Nacht im Hypnogramm: Band, Zielzeit-Linie und Weckpunkt. Bei [exact] («Genau») ist die
 * Messzeit davor kein Weckfenster: dann ohne Band, nur Zielzeit-Linie und Weckpunkt.
 */
internal data class HypnoWindow(val start: Instant, val target: Instant, val wokeAt: Instant, val exact: Boolean = false)

/** Was das Hypnogramm zeigt: Zeitachse [from] bis [to], Blöcke ([StageBlock]) und das Weckfenster (nur eigene Nächte). */
internal data class HypnoModel(val from: Instant, val to: Instant, val blocks: List<StageBlock>, val window: HypnoWindow? = null) {
    /** Länge in Minuten (mindestens 1, damit nie durch 0 geteilt wird). */
    val totalMinutes: Int get() = Duration.between(from, to).toMinutes().toInt().coerceAtLeast(1)

    /** Lage von [at] auf der Achse, 0 bis 1. */
    fun fraction(at: Instant): Float =
        (Duration.between(from, at).toMillis().toFloat() / Duration.between(from, to).toMillis().coerceAtLeast(1)).coerceIn(0f, 1f)

    /** Block zur Minute [minute] ab [from]; `null` in einer Lücke. */
    fun blockAt(minute: Int): StageBlock? {
        val at = from.plusSeconds(minute * 60L)
        return blocks.lastOrNull { !at.isBefore(it.start) && at.isBefore(it.end) }
    }
}

internal val stageOrder = listOf(SleepStage.AWAKE, SleepStage.REM, SleepStage.LIGHT, SleepStage.DEEP)
internal val stageNames = listOf(R.string.chart_stage_awake, R.string.chart_stage_rem, R.string.chart_stage_light, R.string.chart_stage_deep)

/** Name einer Phase («REM», «Leicht» …); unbekannt: leer. */
internal fun stageName(context: Context, stage: SleepStage): String =
    stageOrder.indexOf(stage).takeIf { it >= 0 }?.let { context.getString(stageNames[it]) }.orEmpty()

/** Blase: «05:42 · REM · seit 18 Min»; in einer Lücke «05:42 · keine Daten». */
internal fun hypnoBubble(context: Context, model: HypnoModel, minute: Int, zone: ZoneId): String {
    val at = model.from.plusSeconds(minute * 60L)
    val block = model.blockAt(minute) ?: return Format.time(at, zone) + " · " + context.getString(R.string.chart_no_data)
    return context.getString(R.string.hypno_bubble, Format.time(at, zone), stageName(context, block.stage), Format.duration(context, Duration.between(block.start, at)))
}

/** Höhe der Zeitachse unter dem grossen Hypnogramm. */
private val AXIS = 18.dp

/**
 * Grosses Hypnogramm mit Beschriftung links, Zeitachse (Beginn, zwei Zwischenzeiten, Ende), Weckfenster und Auswahl:
 * Antippen oder nach kurzem Halten Ziehen zeigt eine senkrechte Linie und die Blase ([hypnoBubble]); beim
 * Phasenwechsel vibriert es leicht. Bildschirmleser lesen jede Phase als eigenen Abschnitt («REM von 05:24 bis 05:46»).
 *
 * @param bubbleSpace Platz über den Zeilen für die Blase.
 * @param onOpen Antippen der Blase (z. B. Detailseite öffnen); `null`: Blase nur zum Lesen.
 * @param initialMinute Anfangs gewählte Minute ab Beginn (Bildschirmfotos des gewählten Zustands); sonst keine.
 */
@Composable
internal fun StageHypnogram(
    model: HypnoModel,
    height: Dp,
    modifier: Modifier = Modifier,
    bubbleSpace: Dp = 40.dp,
    onOpen: (() -> Unit)? = null,
    initialMinute: Int? = null,
) {
    val context = LocalContext.current
    val res = LocalResources.current
    val density = LocalDensity.current
    val zone = ZoneId.systemDefault()
    val colors = stageColors()
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val label = labelStyle()
    val names = remember(measurer, label) { stageNames.map { measurer.measure(res.getString(it), label) } }
    val left = names.maxOf { it.size.width } + with(density) { 8.dp.toPx() }
    var selected by remember(model) { mutableStateOf(initialMinute) }
    val tick = rememberTick()
    val summary = res.getString(R.string.chart_stages_title) + ". " + Format.time(model.from, zone) + " – " + Format.time(model.to, zone)
    var width by remember { mutableFloatStateOf(0f) }
    Box(modifier.fillMaxWidth().height(height).onSizeChanged { width = it.width.toFloat() }) {
        Canvas(
            Modifier.fillMaxSize().fadeIn().semantics { contentDescription = summary }.chartTouch(
                key = model,
                indexAt = { p, size -> if (size.width <= left) null else (((p.x - left) / (size.width - left)) * model.totalMinutes).toInt().coerceIn(0, model.totalMinutes - 1) },
                // Antippen in derselben Phase hebt die Auswahl auf (eine Minute genau zu treffen wäre zu schwer).
                onTap = { minute -> selected = if (minute == null || (selected != null && model.blockAt(minute) == selected?.let(model::blockAt))) null else minute },
                onMove = { minute ->
                    if (model.blockAt(minute) != selected?.let(model::blockAt)) tick()
                    selected = minute
                },
            ),
        ) {
            val top = bubbleSpace.toPx()
            val plot = size.height - top - AXIS.toPx()
            val lane = plot / stageOrder.size
            fun x(at: Instant) = left + (size.width - left) * model.fraction(at)
            model.window?.takeUnless { it.exact }?.let { w -> drawRect(scheme.primary.copy(alpha = 0.12f), Offset(x(w.start), top), Size(x(w.target) - x(w.start), plot)) }
            names.forEachIndexed { i, text ->
                val cy = top + lane * (i + 0.5f)
                drawText(text, topLeft = Offset(0f, cy - text.size.height / 2f))
                drawLine(scheme.outlineVariant, Offset(left, cy), Offset(size.width, cy), strokeWidth = 1.dp.toPx())
            }
            drawBlocks(model, colors, Offset(left, top), Size(size.width - left, plot), scheme.outline, 4.dp.toPx())
            model.window?.let { w ->
                dashed(scheme.primary, Offset(x(w.target), top), Offset(x(w.target), top + plot))
                val level = model.blocks.lastOrNull { !w.wokeAt.isBefore(it.start) && w.wokeAt.isBefore(it.end) }?.let { ChartData.level(it.stage) } ?: 0
                val c = Offset(x(w.wokeAt), top + lane * (level + 0.5f))
                // Weckpunkt wie im Logo: gefüllter Kreis, mit Rand in Kartenfarbe, damit er sich vom Block abhebt.
                drawCircle(scheme.surfaceContainer, 7.dp.toPx(), c)
                drawCircle(scheme.primary, 5.dp.toPx(), c)
            }
            selected?.let { minute ->
                val sx = left + (size.width - left) * (minute.toFloat() / model.totalMinutes)
                drawLine(scheme.onSurface, Offset(sx, top - 4.dp.toPx()), Offset(sx, top + plot), strokeWidth = 1.5.dp.toPx())
            }
            drawTimeAxis(axisTicks(model).map { measurer.measure(Format.time(it, zone), label) to x(it) })
        }
        BlockSemantics(model, with(density) { left.toDp() }, bubbleSpace, context)
        selected?.let { minute ->
            val anchor = left + (width - left).coerceAtLeast(0f) * (minute.toFloat() / model.totalMinutes)
            ChartBubble(hypnoBubble(context, model, minute, zone), anchorX = anchor, onOpen = onOpen)
        }
    }
}

/** Blöcke als gerundete Rechtecke je Zeile, verbunden mit dünnen Stufenlinien, wenn sie aneinanderstossen. */
internal fun DrawScope.drawBlocks(model: HypnoModel, colors: Map<SleepStage, Color>, origin: Offset, area: Size, connector: Color, corner: Float) {
    val lane = area.height / stageOrder.size
    val barHeight = lane * 0.72f
    fun x(at: Instant) = origin.x + area.width * model.fraction(at)
    var previous: StageBlock? = null
    model.blocks.forEach { block ->
        val level = ChartData.level(block.stage) ?: return@forEach
        val cy = origin.y + lane * (level + 0.5f)
        previous?.let { p ->
            val pl = ChartData.level(p.stage)
            if (pl != null && !block.start.isAfter(p.end.plusSeconds(60))) {
                drawLine(connector, Offset(x(block.start), origin.y + lane * (pl + 0.5f)), Offset(x(block.start), cy), strokeWidth = 1.dp.toPx())
            }
        }
        val x0 = x(block.start)
        val w = (x(block.end) - x0).coerceAtLeast(2.dp.toPx())
        drawRoundRect(colors[block.stage] ?: connector, Offset(x0, cy - barHeight / 2), Size(w, barHeight), CornerRadius(minOf(corner, w / 2)))
        previous = block
    }
}

/** Beginn, zwei Zwischenzeiten (auf volle Stunden, wenn möglich) und Ende. */
private fun axisTicks(model: HypnoModel): List<Instant> {
    val total = Duration.between(model.from, model.to)
    val middle = listOf(1, 2).map { k ->
        val raw = model.from.plus(total.multipliedBy(k.toLong()).dividedBy(3))
        val hour = raw.plusSeconds(HALF_HOUR_SECONDS).truncatedTo(ChronoUnit.HOURS)
        if (hour.isAfter(model.from) && hour.isBefore(model.to)) hour else raw
    }.distinct()
    return listOf(model.from) + middle + model.to
}

private fun DrawScope.drawTimeAxis(ticks: List<Pair<TextLayoutResult, Float>>) {
    ticks.forEach { (text, x) ->
        val left = (x - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
        drawText(text, topLeft = Offset(left, size.height - text.size.height))
    }
}

/** Je Phase ein unsichtbarer Abschnitt in ihrer Breite, damit Bildschirmleser die Phasen der Reihe nach vorlesen. */
@Composable
private fun BlockSemantics(model: HypnoModel, left: Dp, top: Dp, context: Context) {
    val zone = ZoneId.systemDefault()
    val res = LocalResources.current
    val total = Duration.between(model.from, model.to).toMillis().coerceAtLeast(1).toFloat()
    Row(Modifier.fillMaxSize().padding(start = left, top = top, bottom = AXIS)) {
        var cursor = model.from
        model.blocks.forEach { block ->
            val gap = Duration.between(cursor, block.start).toMillis()
            if (gap > 0) Spacer(Modifier.weight(gap / total).fillMaxHeight())
            val text = res.getString(R.string.hypno_block, stageName(context, block.stage), Format.time(block.start, zone), Format.time(block.end, zone))
            Box(Modifier.weight((Duration.between(maxOf(cursor, block.start), block.end).toMillis() / total).coerceAtLeast(MIN_WEIGHT)).fillMaxHeight().semantics { contentDescription = text })
            cursor = maxOf(cursor, block.end)
        }
        val rest = Duration.between(cursor, model.to).toMillis()
        if (rest > 0) Spacer(Modifier.weight(rest / total).fillMaxHeight())
    }
}

/** Halbe Stunde: Zwischenzeiten werden auf die nächste volle Stunde gerundet. */
private const val HALF_HOUR_SECONDS = 30 * 60L

/** Kleinstes Gewicht eines Abschnitts (Compose verlangt mehr als 0). */
private const val MIN_WEIGHT = 0.0001f

/** Kompaktes Hypnogramm für die Nachtkarte (≈ 64 dp): vier Stufen in Phasenfarben, ohne Beschriftung und Auswahl. */
@Composable
internal fun MiniHypnogram(model: HypnoModel, modifier: Modifier = Modifier) {
    val colors = stageColors()
    val scheme = MaterialTheme.colorScheme
    Canvas(modifier.fillMaxWidth().height(64.dp).clearAndSetSemantics { }) {
        val lane = size.height / stageOrder.size
        for (i in 0..stageOrder.size) drawLine(scheme.outlineVariant, Offset(0f, lane * i), Offset(size.width, lane * i), strokeWidth = 0.5.dp.toPx())
        model.window?.takeUnless { it.exact }?.let { w ->
            val x0 = size.width * model.fraction(w.start)
            drawRect(scheme.primary.copy(alpha = 0.12f), Offset(x0, 0f), Size(size.width * model.fraction(w.target) - x0, size.height))
        }
        drawBlocks(model, colors, Offset.Zero, size, scheme.outline, 3.dp.toPx())
        model.window?.let { w -> drawCircle(scheme.primary, 3.5.dp.toPx(), Offset(size.width * model.fraction(w.wokeAt), 3.5.dp.toPx())) }
    }
}
