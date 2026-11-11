package com.phasewake.app.ui.history

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.shared.NightRecording
import com.phasewake.app.shared.RecordEnd
import com.phasewake.app.ui.Format
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Text zum Ende einer Aufzeichnung. */
fun recordEndRes(reason: RecordEnd?): Int = when (reason) {
    RecordEnd.WATCH, RecordEnd.PHONE -> R.string.record_end_manual
    RecordEnd.CAP -> R.string.record_end_cap
    RecordEnd.BATTERY -> R.string.record_end_battery
    RecordEnd.SERVICE, null -> R.string.record_end_interrupted
    RecordEnd.WAKE -> R.string.record_end_wake
}

/** «7 h 12 min · Gestoppt» */
fun recordingSummary(context: Context, recording: NightRecording): String =
    Format.duration(context, recording.duration) + " · " + context.getString(recordEndRes(recording.endReason))

/**
 * Aufzeichnung («Nacht aufzeichnen») im Verlauf, schlicht: Datum, Titel, Beginn bis Ende, Dauer und Grund des Endes.
 * Die Werte selbst stehen im Export; der Detektor nutzt sie noch nicht.
 */
@Composable
internal fun RecordingCard(recording: NightRecording, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val end = recording.end ?: recording.start.plus(recording.duration)
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Format.digits(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)).format(recording.start.atZone(zone)),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    Format.time(recording.start, zone) + " – " + Format.time(end, zone),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(stringResource(R.string.record_title), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            Text(
                recordingSummary(context, recording),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
