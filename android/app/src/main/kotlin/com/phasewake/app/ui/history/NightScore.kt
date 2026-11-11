package com.phasewake.app.ui.history

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.Epoch
import com.phasewake.app.core.FactorScore
import com.phasewake.app.core.HealthSession
import com.phasewake.app.core.HistoryItem
import com.phasewake.app.core.PulseCurve
import com.phasewake.app.core.ScoreBand
import com.phasewake.app.core.ScoreFactor
import com.phasewake.app.core.ScoreInput
import com.phasewake.app.core.SleepScore
import com.phasewake.app.core.SleepScoreResult
import com.phasewake.app.core.SleepStage
import com.phasewake.app.health.HealthSource
import com.phasewake.app.shared.NightRecording
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.theme.PhaseWakeTheme
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

// Schlafwert einer Nacht (Bogen mit Faktoren), Kurzzeile in der Liste, Puls- und Bewegungsverlauf der Detailseite.

/** Woher die Werte des Schlafwerts stammen: Phasen der Gesundheits-App oder eigene Aufzeichnung der Uhr. */
internal enum class ScoreSource { HEALTH, RECORDING }

/** Schlafwert einer Nacht samt den Messwerten, aus denen er gerechnet ist. */
internal data class NightScore(val result: SleepScoreResult, val input: ScoreInput, val source: ScoreSource)

/** Messreihe einer Nacht (Bewegung und Puls je 30-s-Epoche ab [start]); [fullNight]: ganze Aufzeichnung statt Weckfenster. */
internal data class NightSeries(val epochs: List<Epoch>, val start: Instant, val fullNight: Boolean)

/** Ende einer Aufzeichnung (laufend: bis zur letzten Epoche). */
private val NightRecording.until: Instant get() = end ?: start.plus(duration)

/** Zeitraum eines Eintrags: eigene Nacht Fensterbeginn bis Wecken, Sitzung Beginn bis Ende. */
private fun HistoryItem.range(): Pair<Instant, Instant>? =
    night?.let { it.windowStart to it.wokeAt } ?: session?.let { it.start to it.end }

/** Abgeschlossene Aufzeichnung, die sich mit dem Eintrag am längsten überschneidet; keine: `null`. */
internal fun recordingFor(item: HistoryItem, recordings: List<NightRecording>): NightRecording? {
    val (from, to) = item.range() ?: return null
    return recordings.filter { it.finished && it.start.isBefore(to) && it.until.isAfter(from) }
        .maxByOrNull { Duration.between(maxOf(it.start, from), minOf(it.until, to)).toMillis() }
}

/**
 * Schlafwert einer Nacht ([SleepScore]): mit Phasen aus diesen, sonst bei eigener Nacht aus einer Aufzeichnung mit
 * erkanntem Einschlafen (bis zum Wecken), bei einer Sitzung ohne Phasen aus ihrer Dauer. Keine Werte: `null`.
 */
internal fun nightScore(view: NightView, recordings: List<NightRecording>, goalMinutes: Int): NightScore? {
    val stats = view.stats
    val session = view.item.session
    val night = view.item.night
    val (input, source) = when {
        stats != null -> SleepScore.inputOf(stats, goalMinutes) to ScoreSource.HEALTH
        session != null -> ScoreInput(session.asleepMinutes, goalMinutes) to ScoreSource.HEALTH
        night != null -> {
            val rec = recordingFor(view.item, recordings) ?: return null
            val asleep = rec.asleepAt ?: return null
            (SleepScore.inputOf(rec.bedAt, asleep, minOf(rec.until, night.wokeAt), goalMinutes) ?: return null) to ScoreSource.RECORDING
        }
        else -> return null
    }
    return SleepScore.of(input)?.let { NightScore(it, input, source) }
}

/** Messreihe für Puls und Bewegung: überschneidende Aufzeichnung (ganze Nacht), sonst bei eigener Nacht das Weckfenster. */
internal fun nightSeries(item: HistoryItem, recordings: List<NightRecording>): NightSeries? =
    recordingFor(item, recordings)?.let { NightSeries(it.epochs, it.start, fullNight = true) }
        ?: item.night?.let { NightSeries(it.epochs, it.windowStart, fullNight = false) }

/**
 * Quelle unter dem Schlafwert: «Aus deiner Aufzeichnung der Galaxy Watch.» bzw. «Aus Samsung Health.» (Sitzung, die die
 * eigene Nacht überdeckt; unbekannt: «Aus deinen Gesundheitsdaten.»). Bei einer Sitzung `null`: Der Kopf nennt die Quelle schon.
 */
internal fun scoreSourceText(context: Context, item: HistoryItem, score: NightScore, sessions: List<HealthSession>): String? {
    if (score.source == ScoreSource.RECORDING) return context.getString(R.string.score_source_recording)
    val night = item.night ?: return null
    val session = sessions.filter { it.hasStages && it.start.isBefore(night.wokeAt) && it.end.isAfter(night.windowStart) }.maxByOrNull { it.durationMinutes }
    return HealthSource.name(context, session?.source)?.let { context.getString(R.string.score_source, it) }
        ?: context.getString(R.string.score_source_health)
}

private val bandNames = mapOf(
    ScoreBand.EXCELLENT to R.string.score_excellent,
    ScoreBand.GOOD to R.string.score_good,
    ScoreBand.FAIR to R.string.score_fair,
    ScoreBand.POOR to R.string.score_poor,
)

/** «Ausgezeichnet», «Gut», «Mittel», «Schwach». */
internal fun bandName(context: Context, band: ScoreBand): String = context.getString(bandNames.getValue(band))

/** «Schlafwert 82 von 100: Gut» (Bildschirmleser), vorläufig mit «Ohne Schlafphasen – vorläufiger Wert». */
internal fun scoreDescription(context: Context, result: SleepScoreResult): String =
    listOfNotNull(
        context.getString(R.string.score_value, result.score, bandName(context, result.band)),
        if (result.provisional) context.getString(R.string.score_provisional) else null,
    ).joinToString(". ")

/** Bogen von 240°, unten offen (wie der Weg des Mondes über den Nachthimmel). */
private const val ARC_START = 150f
private const val ARC_SWEEP = 240f

/** Ob Animationen laufen dürfen (nicht in Vorschau/Bildschirmfotos, nicht bei «Animationen entfernen»). */
@Composable
private fun animationsOn(): Boolean {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    return remember {
        !inspection && Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}

/**
 * Schlafwert der Detailseite: Bogen mit dem Wert, der in einem Weckpunkt endet (Logo-Motiv), darunter die Bewertung
 * (ohne Schlafphasen mit dem Hinweis «vorläufig»); je Faktor eine Kachel mit Wert, Bewertung und Balken der Punkte. Fehlende Faktoren fehlen. Unten Quelle und Gewichtung.
 */
@Composable
internal fun ScoreCard(score: NightScore, source: String?, goalMinutes: Int, modifier: Modifier = Modifier) {
    val result = score.result
    val scheme = MaterialTheme.colorScheme
    ChartCard(stringResource(R.string.score_title), null, modifier) {
        ScoreArc(result, Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))
        if (result.provisional) {
            // Im Bogen schon vorgelesen (scoreDescription).
            Text(
                stringResource(R.string.score_provisional),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.CenterHorizontally).clearAndSetSemantics {},
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
            result.factors.chunked(2).forEach { row ->
                // Gleich hohe Kacheln je Zeile (die Schlafdauer hat eine Zeile mehr).
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    row.forEach { FactorTile(it, score.input, goalMinutes, Modifier.weight(1f).fillMaxHeight()) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Text(
            listOfNotNull(source, stringResource(R.string.score_weights)).joinToString(" "),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/** Morgenrot am Ende des Bogens (wie der Sonnenaufgang des Weckers), je Modus mit genug Kontrast zur Karte. */
private val DAWN_DARK = Color(0xFFFFB27A)
private val DAWN_LIGHT = Color(0xFFE07A4F)

/**
 * Grosser Bogen: Spur, Wert als Bogen mit rundem Ende im Verlauf von der Nacht (tief) über Lavendel zum Morgenrot,
 * Weckpunkt mit leichtem Schein am Ende; Zahl und Bewertung in der Mitte.
 */
@Composable
private fun ScoreArc(result: SleepScoreResult, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val brand = PhaseWakeTheme.brand
    val scheme = MaterialTheme.colorScheme
    val dawn = if (PhaseWakeTheme.isDark) DAWN_DARK else DAWN_LIGHT
    val animate = animationsOn()
    val sweep = remember(result) { Animatable(if (animate) 0f else result.score / 100f) }
    // Ein einziger Moment Bewegung auf der Seite: Der Bogen läuft beim Öffnen bis zum Wert.
    LaunchedEffect(result) { if (animate) sweep.animateTo(result.score / 100f, tween(900, easing = FastOutSlowInEasing)) }
    val description = scoreDescription(context, result)
    Box(modifier.size(width = 200.dp, height = 172.dp).clearAndSetSemantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val dot = 7.dp.toPx()
            val diameter = minOf(size.width, size.height / 0.86f) - stroke - dot
            val topLeft = Offset((size.width - diameter) / 2, stroke / 2 + dot / 2)
            val arcSize = Size(diameter, diameter)
            drawArc(brand.chartTrack, ARC_START, ARC_SWEEP, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val f = sweep.value
            val r = diameter / 2
            val center = Offset(topLeft.x + r, topLeft.y + r)
            if (f > 0f) drawArc(arcBrush(center, brand.stageDeep, brand.chartActive, dawn), ARC_START, ARC_SWEEP * f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val angle = Math.toRadians((ARC_START + ARC_SWEEP * f).toDouble())
            val end = Offset(center.x + r * cos(angle).toFloat(), center.y + r * sin(angle).toFloat())
            drawCircle(brand.chartActive.copy(alpha = 0.22f), dot * 2.3f, end)
            drawCircle(scheme.surfaceContainer, dot + 3.dp.toPx(), end)
            drawCircle(brand.dot, dot, end)
        }
        Column(Modifier.align(Alignment.Center).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Format.number(result.score), style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.ExtraBold))
            Text(bandName(context, result.band), style = MaterialTheme.typography.titleMedium, color = scheme.primary)
        }
    }
}

/**
 * Verlauf entlang des Bogens (Winkel ab 3 Uhr im Uhrzeigersinn): Beginn bei 150° tief, oben (270°) Lavendel, Ende bei 30°
 * Morgenrot. Die Lücke unten (30°–150°) ist unsichtbar; dort nur der Übergang für die runden Enden.
 */
private fun arcBrush(center: Offset, deep: Color, mid: Color, dawn: Color): Brush {
    val atZero = lerp(mid, dawn, 0.75f)
    return Brush.sweepGradient(
        0f to atZero,
        (30f / 360f) to dawn,
        (40f / 360f) to dawn,
        (140f / 360f) to deep,
        (ARC_START / 360f) to deep,
        (270f / 360f) to mid,
        1f to atZero,
        center = center,
    )
}

/** Name, Wert, Zusatz (Ziel) und Farbe der Phase eines Faktors (`null`: keine Phase). */
private data class FactorView(val label: String, val value: String, val extra: String?, val stage: Color?)

@Composable
private fun factorView(factor: FactorScore, input: ScoreInput, goalMinutes: Int): FactorView {
    val context = LocalContext.current
    val res = LocalResources.current
    val brand = PhaseWakeTheme.brand
    fun duration(minutes: Long?) = Format.duration(context, Duration.ofMinutes(minutes ?: 0))
    fun share(percent: Int?) = res.getString(R.string.stages_share, percent ?: 0)
    return when (factor.factor) {
        ScoreFactor.DURATION -> FactorView(
            res.getString(R.string.score_duration),
            duration(input.asleepMinutes),
            res.getString(R.string.chart_goal, duration(goalMinutes.toLong())),
            null,
        )
        ScoreFactor.DEEP -> FactorView(stageName(context, SleepStage.DEEP), share(input.deepPercent), null, brand.stageDeep)
        ScoreFactor.REM -> FactorView(stageName(context, SleepStage.REM), share(input.remPercent), null, brand.stageRem)
        ScoreFactor.AWAKE -> FactorView(stageName(context, SleepStage.AWAKE), share(input.awakePercent), null, brand.stageAwake)
        ScoreFactor.LATENCY -> FactorView(res.getString(R.string.night_latency), duration(input.latencyMinutes), null, null)
    }
}

/** Kachel eines Faktors: Name, Wert, Balken der Punkte (0–100) in der Farbe der Phase, Bewertung. */
@Composable
private fun FactorTile(factor: FactorScore, input: ScoreInput, goalMinutes: Int, modifier: Modifier) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val brand = PhaseWakeTheme.brand
    val view = factorView(factor, input, goalMinutes)
    val band = bandName(context, factor.band)
    val spoken = listOfNotNull(view.label + ": " + view.value, view.extra, band).joinToString(". ")
    Column(
        modifier
            .clip(MaterialTheme.shapes.small)
            .background(scheme.surfaceContainerHigh)
            .padding(12.dp)
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Farbmarke der Phase wie in Legende und Phasen-Summen; der Balken selbst zeigt die Punkte (immer gleiche Farbe).
            view.stage?.let { color -> Canvas(Modifier.padding(end = 6.dp).size(8.dp)) { drawRoundRect(color, cornerRadius = CornerRadius(2.dp.toPx())) } }
            Text(view.label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        }
        Text(view.value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 2.dp))
        view.extra?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant) }
        // Balken und Bewertung unten bündig, auch neben der höheren Kachel der Schlafdauer.
        Spacer(Modifier.weight(1f))
        Canvas(Modifier.padding(top = 8.dp).fillMaxWidth().height(6.dp)) {
            val corner = CornerRadius(size.height / 2)
            drawRoundRect(brand.chartTrack, cornerRadius = corner)
            val w = size.width * factor.points / 100f
            if (w > 0f) drawRoundRect(brand.chartActive, size = Size(w.coerceAtLeast(size.height), size.height), cornerRadius = corner)
        }
        Text(band, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
    }
}

/**
 * Kurzzeile in der Karte des Verlaufs: kleiner Ring mit dem Wert, daneben «Schlafwert» und die Bewertung. Vorläufiger
 * Wert (ohne Schlafphasen): Ring gestrichelt und hohl statt voll.
 */
@Composable
internal fun ScoreLine(result: SleepScoreResult, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val brand = PhaseWakeTheme.brand
    val scheme = MaterialTheme.colorScheme
    val description = scoreDescription(context, result)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.padding(top = 10.dp).clearAndSetSemantics { contentDescription = description }) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 4.dp.toPx()
                val inset = stroke / 2
                val arc = Size(size.width - stroke, size.height - stroke)
                drawArc(brand.chartTrack, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                val sweep = 360f * result.score / 100f
                if (result.provisional) {
                    // Gestrichelt in halber Stärke: «noch nicht ganz gemessen», ohne neue Farbe.
                    val dash = 3.dp.toPx()
                    val thin = Stroke(stroke / 2, cap = StrokeCap.Butt, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 0.8f)))
                    drawArc(brand.chartActive, -90f, sweep, false, Offset(inset, inset), arc, style = thin)
                } else {
                    drawArc(brand.chartActive, -90f, sweep, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            Text(Format.number(result.score), style = MaterialTheme.typography.labelLarge)
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(stringResource(R.string.score_title), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            Text(bandName(context, result.band), style = MaterialTheme.typography.titleSmall)
        }
    }
}
