package ch.r1ad.phasewake.ui.history

import android.content.Context
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.r1ad.phasewake.R
import ch.r1ad.phasewake.core.NightRecord
import ch.r1ad.phasewake.core.WakeReason
import ch.r1ad.phasewake.ui.Format
import ch.r1ad.phasewake.ui.components.NightBars
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
    val timing = if (night.minutesBeforeTarget > 0) {
        context.getString(R.string.woke_early, night.minutesBeforeTarget.toInt(), reason)
    } else {
        context.getString(R.string.woke_on_time, reason)
    }
    val snooze = if (night.snoozes == 0) context.getString(R.string.no_snooze) else context.getString(R.string.snoozed_times, night.snoozes)
    return "$timing · $snooze"
}

/** Verlauf: Kennzahlen und jede Nacht mit ihrer Bewegungskurve, neueste zuerst. */
@Composable
fun HistoryScreen(nights: List<NightRecord>, contentPadding: PaddingValues, onExport: () -> Unit) {
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
        items(nights.asReversed(), key = { it.windowStart.toEpochMilli() }) { night ->
            NightCard(night, Modifier.animateItem())
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
private fun NightCard(night: NightRecord, modifier: Modifier) {
    val context = LocalContext.current
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
        }
    }
}
