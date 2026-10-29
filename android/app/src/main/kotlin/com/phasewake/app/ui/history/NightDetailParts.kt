package com.phasewake.app.ui.history

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.HistoryItem
import com.phasewake.app.core.NightStages
import com.phasewake.app.core.SoundEvents
import com.phasewake.app.core.StageTotal
import com.phasewake.app.core.StageVerdict
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.notes.NightNotes
import com.phasewake.app.ui.sounds.SoundClipsSection
import java.text.NumberFormat
import java.time.Duration
import java.time.ZoneId

// Bausteine der Detailseite: Legende zum Hypnogramm, Phasen-Summen mit üblichem Bereich, Kennzahlen, Befinden und Notizen.

/** Textschlüssel des Satzes zur Einordnung. */
internal fun verdictRes(verdict: StageVerdict): Int = when (verdict) {
    StageVerdict.AWAKE_ABOVE -> R.string.stages_verdict_awake_above
    StageVerdict.DEEP_BELOW -> R.string.stages_verdict_deep_below
    StageVerdict.DEEP_ABOVE -> R.string.stages_verdict_deep_above
    StageVerdict.REM_BELOW -> R.string.stages_verdict_rem_below
    StageVerdict.REM_ABOVE -> R.string.stages_verdict_rem_above
    StageVerdict.LIGHT_BELOW -> R.string.stages_verdict_light_below
    StageVerdict.LIGHT_ABOVE -> R.string.stages_verdict_light_above
    StageVerdict.TYPICAL -> R.string.stages_verdict_typical
}

/** Legende: Phasen mit ihrer Farbe, bei eigener Nacht Weckfenster, Weckpunkt und Zielzeit. */
@Composable
internal fun HypnoLegend(model: HypnoModel) {
    val context = LocalContext.current
    val colors = stageColors()
    val scheme = MaterialTheme.colorScheme
    val zone = ZoneId.systemDefault()
    val items = stageOrder.map { LegendItem(stageName(context, it), LegendMark.SQUARE, colors.getValue(it)) } +
        listOfNotNull(
            model.window?.let { LegendItem(stringResource(R.string.chart_window), LegendMark.SHADE, scheme.primary.copy(alpha = 0.25f)) },
            model.window?.let { LegendItem(stringResource(R.string.night_woke, Format.time(it.wokeAt, zone)), LegendMark.DOT, scheme.primary) },
            model.window?.let { LegendItem(stringResource(R.string.night_target, Format.time(it.target, zone)), LegendMark.DASH, scheme.primary) },
        )
    Legend(items)
}

/** «Üblich 20–25 %» bzw. «Üblich unter 10 %». */
private fun typicalText(context: Context, total: StageTotal): String =
    if (total.range.low == 0) context.getString(R.string.stages_typical_below, total.range.high + 1)
    else context.getString(R.string.stages_typical_range, total.range.low, total.range.high)

/**
 * Phasen-Summen: je Phase Name, Anteil, Dauer und Balken über einem hellen, gestrichelt umrandeten «üblichen
 * Bereich»; darüber der Satz zur Einordnung, darunter die Legende und «Keine medizinische Bewertung.».
 */
@Composable
internal fun StageTotalsCard(stats: NightStages) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    // Titel ist der Satz zur Einordnung (zu wenig Daten: der Titel der Phasen), damit nichts doppelt steht.
    ChartCard(verdictText(context, stats) ?: stringResource(R.string.chart_stages_title), null, Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { stats.totals.forEach { StageTotalRow(it) } }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp).clearAndSetSemantics { }) {
            Canvas(Modifier.size(width = 22.dp, height = 10.dp)) { typicalBand(scheme.onSurfaceVariant, Offset.Zero, size) }
            Text(stringResource(R.string.stages_typical), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
        }
        Text(
            stringResource(R.string.stages_not_medical),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Heller, gestrichelt umrandeter Bereich («üblicher Bereich»); nicht nur Farbe: der Rand zeigt ihn auch ohne Farbsehen. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.typicalBand(color: Color, topLeft: Offset, area: Size) {
    val corner = CornerRadius(3.dp.toPx())
    drawRoundRect(color.copy(alpha = 0.18f), topLeft, area, corner)
    drawRoundRect(
        color,
        topLeft,
        area,
        corner,
        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
    )
}

/** Eine Phase: Name mit Farbmarke, Anteil und Dauer, Balken mit üblichem Bereich, darunter der Bereich als Text. */
@Composable
private fun StageTotalRow(total: StageTotal) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val color = stageColors().getValue(total.stage)
    val brand = com.phasewake.app.ui.theme.PhaseWakeTheme.brand
    val name = stageName(context, total.stage)
    val share = stringResource(R.string.stages_share, total.percent)
    val duration = Format.duration(context, Duration.ofMinutes(total.minutes))
    val typical = typicalText(context, total)
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$name: $share, $duration. $typical" }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(10.dp)) { drawRoundRect(color, cornerRadius = CornerRadius(2.dp.toPx())) }
            Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp).weight(1f))
            Text(share, style = MaterialTheme.typography.titleSmall)
            Text(duration, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp))
        }
        Canvas(Modifier.fillMaxWidth().height(14.dp).padding(top = 4.dp)) {
            val corner = CornerRadius(size.height / 2)
            drawRoundRect(brand.chartTrack, cornerRadius = corner)
            // «Unter 10 %» reicht bis 10, «20–25 %» von 20 bis 25.
            val x0 = size.width * total.range.low / PERCENT
            val x1 = size.width * (if (total.range.low == 0) total.range.high + 1 else total.range.high) / PERCENT
            typicalBand(scheme.onSurfaceVariant, Offset(x0, 0f), Size(x1 - x0, size.height))
            // Balken schmaler als der Bereich, damit dieser oben und unten sichtbar bleibt.
            val w = size.width * total.percent / PERCENT
            val bar = size.height * 0.6f
            if (w > 0f) drawRoundRect(color, Offset(0f, (size.height - bar) / 2), Size(w.coerceAtLeast(bar), bar), CornerRadius(bar / 2))
        }
        Text(typical, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    }
}

private const val PERCENT = 100f

/** Kennzahlen als Kacheln: Einschlafdauer (falls bekannt), Schlafeffizienz, Wachphasen, längster Tiefschlaf. */
@Composable
internal fun NightTiles(stats: NightStages) {
    val context = LocalContext.current
    val tiles = listOfNotNull(
        stats.latencyMinutes?.let { stringResource(R.string.night_latency) to Format.duration(context, Duration.ofMinutes(it)) },
        stringResource(R.string.night_efficiency) to stringResource(R.string.stages_share, stats.efficiencyPercent),
        stringResource(R.string.night_awakenings) to NumberFormat.getIntegerInstance().format(stats.awakenings),
        stringResource(R.string.night_longest_deep) to Format.duration(context, Duration.ofMinutes(stats.longestDeepMinutes)),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { (label, value) -> NightTile(label, value, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NightTile(label: String, value: String, modifier: Modifier) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.semantics(mergeDescendants = true) { },
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Befinden, Notizen und Geräusche der Nacht (wie bisher in der Karte des Verlaufs). */
@Composable
internal fun NightExtras(item: HistoryItem, data: NightDetailData, actions: NightDetailActions) {
    val context = LocalContext.current
    val date = item.date
    val night = item.night
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (night == null) {
                SessionFeeling(NightFeeling(data.healthFeelings[date]) { actions.onHealthFeeling(date, it) })
            } else {
                night.feeling?.let {
                    Text(stringResource(R.string.feeling_value, feelingName(context, it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            NightNotes(data.notes[date].orEmpty(), data.customNotes, { actions.onToggleNote(date, it) }, Modifier.padding(top = 4.dp))
            // Mehrere eigene Nächte am selben Nachtdatum: die Geräusche nur bei der zuletzt geweckten (wie im Verlauf).
            val clips = if (data.items.firstOrNull { it.date == date }?.key == item.key) SoundEvents.forNight(data.soundClips, date) else emptyList()
            SoundClipsSection(clips, actions.soundFile, actions.onDeleteClip, { actions.onDeleteNightClips(date) })
        }
    }
}
