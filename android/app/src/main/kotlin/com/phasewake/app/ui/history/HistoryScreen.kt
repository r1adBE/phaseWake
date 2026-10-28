package com.phasewake.app.ui.history

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.SleepStage
import com.phasewake.app.core.StageCheck
import com.phasewake.app.core.StageSpan
import com.phasewake.app.core.Trends
import com.phasewake.app.core.WakeReason
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.components.NightBars
import com.phasewake.app.ui.theme.PhaseWakeTheme
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/** «12 Min vor der Zielzeit · Leichter Schlaf · ohne Schlummern» */
fun nightSummary(context: Context, night: NightRecord): String {
    val reason = context.getString(
        when (night.reason) {
            WakeReason.LIGHT_SLEEP -> R.string.reason_light
            WakeReason.AWAKE -> R.string.reason_awake
            WakeReason.DEADLINE -> R.string.reason_deadline
        },
    )
    val late = night.minutesAfterTarget.toInt()
    val timing = when {
        night.minutesBeforeTarget > 0 -> context.getString(R.string.woke_early, night.minutesBeforeTarget.toInt(), reason)
        // Nach Schlummern über die Zielzeit hinaus: ehrlich als Verspätung, nicht als «zur Zielzeit».
        late > 0 -> context.resources.getQuantityString(R.plurals.woke_late, late, late, reason)
        else -> context.getString(R.string.woke_on_time, reason)
    }
    val snooze = if (night.snoozes == 0) context.getString(R.string.no_snooze) else context.getString(R.string.snoozed_times, night.snoozes)
    return "$timing · $snooze"
}

/** Verlauf: Kennzahlen und jede Nacht mit ihrer Bewegungskurve, neueste zuerst. */
@Composable
fun HistoryScreen(
    nights: List<NightRecord>,
    contentPadding: PaddingValues,
    loadStages: (suspend (NightRecord) -> List<StageSpan>)?,
    onExport: () -> Unit,
    trends: Trends? = null,
    healthConnected: Boolean = false,
    onConnectHealth: () -> Unit = {},
) {
    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
    ) {
        item(key = "title") {
            Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        }
        if (nights.isEmpty()) {
            item(key = "empty") {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(stringResource(R.string.history_empty_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.history_empty_text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            return@LazyColumn
        }
        item(key = "stats") { Stats(nights) }
        trends?.let { item(key = "trends") { TrendsCard(it, healthConnected, onConnectHealth) } }
        // Schlüssel wie im Verlauf: Wecker und Fensterbeginn (zwei Wecker können gleich starten).
        items(nights.asReversed(), key = { "${it.alarmId}@${it.windowStart.toEpochMilli()}" }) { night ->
            NightCard(night, loadStages, Modifier.animateItem())
        }
        item(key = "export") {
            OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Icon(painterResource(R.drawable.ic_export), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.history_export), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun Stats(nights: List<NightRecord>) {
    val average = nights.map { it.minutesBeforeTarget }.average().roundToInt()
    val lightShare = (100.0 * nights.count { it.reason == WakeReason.LIGHT_SLEEP } / nights.size).roundToInt()
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        StatTile(stringResource(R.string.history_average, average), Modifier.weight(1f))
        StatTile(stringResource(R.string.history_light_share, lightShare), Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(text: String, modifier: Modifier) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = modifier,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun NightCard(night: NightRecord, loadStages: (suspend (NightRecord) -> List<StageSpan>)?, modifier: Modifier) {
    val context = LocalContext.current
    val stages by produceState(emptyList<StageSpan>(), night, loadStages) { value = loadStages?.invoke(night).orEmpty() }
    val zone = ZoneId.systemDefault()
    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(night.wokeAt.atZone(zone))
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(date, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(Format.time(night.wokeAt, zone), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            NightBars(
                night.epochs,
                Modifier.fillMaxWidth().height(44.dp).padding(top = 10.dp),
                totalEpochs = (Duration.between(night.windowStart, night.target).toMinutes() * 2).toInt(),
            )
            Text(
                nightSummary(context, night),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                nightDetails(context, night),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (stages.isNotEmpty()) {
                StageStrip(
                    StageCheck.clip(stages, night.windowStart, night.target),
                    night.windowStart, night.target,
                    Modifier.fillMaxWidth().height(8.dp).padding(top = 2.dp),
                )
                verdict(context, stages, night)?.let { (text, good) ->
                    Text(
                        text,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/** Abgleich mit der Gesundheits-App, z. B. «Gesundheitsdaten: leichter Schlaf ✓». */
private fun verdict(context: Context, stages: List<StageSpan>, night: NightRecord): Pair<String, Boolean>? =
    when (StageCheck.stageAtWake(stages, night.wokeAt)) {
        SleepStage.LIGHT -> context.getString(R.string.verdict_good, context.getString(R.string.stage_light)) to true
        SleepStage.REM -> context.getString(R.string.verdict_good, context.getString(R.string.stage_rem)) to true
        SleepStage.AWAKE -> context.getString(R.string.verdict_good, context.getString(R.string.stage_awake)) to true
        SleepStage.DEEP -> context.getString(R.string.verdict_deep) to false
        SleepStage.UNKNOWN, null -> null
    }

/** Schlafphasen im Weckfenster als farbiges Band: Tief dunkel, Leicht hell, REM mittel, Wach weiss. */
@Composable
private fun StageStrip(spans: List<StageSpan>, from: java.time.Instant, to: java.time.Instant, modifier: Modifier) {
    val brand = PhaseWakeTheme.brand
    val total = Duration.between(from, to).toMillis().toFloat().coerceAtLeast(1f)
    Canvas(modifier.padding(top = 6.dp)) {
        drawRoundRect(brand.chartTrack, cornerRadius = CornerRadius(size.height / 2))
        spans.forEach { span ->
            val x0 = size.width * Duration.between(from, span.start).toMillis() / total
            val x1 = size.width * Duration.between(from, span.end).toMillis() / total
            val color = when (span.stage) {
                SleepStage.DEEP -> brand.echo
                SleepStage.REM -> brand.chartQuiet
                SleepStage.LIGHT -> brand.wave
                SleepStage.AWAKE -> brand.dot
                SleepStage.UNKNOWN -> brand.chartTrack
            }
            drawRect(color, Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(1f), size.height))
        }
    }
}
