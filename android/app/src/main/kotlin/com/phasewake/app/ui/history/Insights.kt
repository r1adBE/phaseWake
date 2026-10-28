package com.phasewake.app.ui.history

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.DataQuality
import com.phasewake.app.core.NightQuality
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.Trends
import com.phasewake.app.ui.Format
import java.time.Duration
import java.util.Locale
import kotlin.math.abs

private val feelingNames = listOf(R.string.feeling_1, R.string.feeling_2, R.string.feeling_3, R.string.feeling_4, R.string.feeling_5)

fun feelingName(context: Context, feeling: Int): String = context.getString(feelingNames[(feeling - 1).coerceIn(0, 4)])

/** Worauf die Weckentscheidung beruhte – ehrlich, auch wenn der Puls fehlte. */
fun qualityText(context: Context, night: NightRecord): String = context.getString(
    when (NightQuality.of(night)) {
        DataQuality.FULL -> R.string.quality_full
        DataQuality.MOTION_ONLY -> R.string.quality_motion_only
        DataQuality.PARTIAL -> R.string.quality_partial
        DataQuality.NONE -> R.string.quality_none
    },
)

/** Datenqualität und – falls angegeben – Befinden in einer Zeile. */
fun nightDetails(context: Context, night: NightRecord): String =
    listOfNotNull(
        qualityText(context, night),
        night.feeling?.let { context.getString(R.string.feeling_value, feelingName(context, it)) },
    ).joinToString(" · ")

/** Fünf runde Knöpfe von «erschöpft» bis «erholt»; ein Tipp genügt. */
@Composable
fun FeelingPicker(onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val res = LocalResources.current
    Column(modifier) {
        Text(stringResource(R.string.feeling_question), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            for (value in NightRecord.FEELING_MIN..NightRecord.FEELING_MAX) {
                val label = res.getString(R.string.feeling_rate, value, feelingName(context, value))
                OutlinedButton(
                    onClick = { onPick(value) },
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.weight(1f).aspectRatio(1f).semantics { contentDescription = label },
                ) { Text(value.toString(), style = MaterialTheme.typography.titleMedium) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(stringResource(R.string.feeling_1), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.feeling_5), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Wochenvergleich: Schlafdauer, Regelmässigkeit, Befinden – nur was die Daten tragen. */
@Composable
fun TrendsCard(trends: Trends, healthConnected: Boolean, onConnectHealth: () -> Unit, goalWeek: Pair<Int, Int>? = null) {
    val context = LocalContext.current
    val res = LocalResources.current
    val lines = buildList {
        trends.asleepThisWeek?.let { minutes ->
            add(res.getString(R.string.trends_asleep, Format.duration(context, Duration.ofMinutes(minutes))))
            trends.asleepChange?.let { change ->
                add(
                    when {
                        abs(change) < 5 -> res.getString(R.string.trends_asleep_change_same)
                        change > 0 -> res.getString(R.string.trends_asleep_change_more, Format.duration(context, Duration.ofMinutes(change)))
                        else -> res.getString(R.string.trends_asleep_change_less, Format.duration(context, Duration.ofMinutes(-change)))
                    },
                )
            }
        }
        // Schlafziel: nur mit Schlafdauer aus der Gesundheits-App (sonst deckt der Hinweis unten das ab).
        goalWeek?.takeIf { healthConnected && it.second > 0 }?.let { (reached, total) ->
            add(res.getQuantityString(R.plurals.goal_week, total, reached, total))
        }
        trends.bedtimeSpreadMinutes?.let { spread ->
            add(res.getString(R.string.trends_bedtime, Format.duration(context, Duration.ofMinutes(spread))))
            if (spread > IRREGULAR_MINUTES) add(res.getString(R.string.trends_bedtime_hint))
        }
        trends.feelingThisWeek?.let { feeling ->
            val last = trends.feelingLastWeek?.let { " · " + res.getString(R.string.trends_feeling_change, decimal(it)) }.orEmpty()
            add(res.getString(R.string.trends_feeling, decimal(feeling)) + last)
        }
    }
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.trends_title), style = MaterialTheme.typography.titleSmall)
            if (lines.isEmpty()) {
                Text(stringResource(R.string.trends_not_enough), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (!healthConnected) {
                // Untereinander: Der Hinweis braucht die ganze Breite, sonst wird er zur schmalen Spalte.
                Text(
                    stringResource(R.string.trends_health_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                TextButton(onClick = onConnectHealth, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.health_connect)) }
            }
        }
    }
}

/** Ab dieser Schwankung der Einschlafzeit gibt es den Hinweis auf regelmässige Schlafzeiten. */
private const val IRREGULAR_MINUTES = 45L

private fun decimal(value: Double) = String.format(Locale.getDefault(), "%.1f", value)
