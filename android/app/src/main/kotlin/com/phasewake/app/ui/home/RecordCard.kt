package com.phasewake.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.ui.Format
import java.time.Instant

/**
 * «Nacht aufzeichnen» auf der Startseite (nur mit einer Galaxy Watch, die es kennt): gemessen wird auf der Uhr, das
 * Handy schickt nur Start und Stopp. Läuft eine Aufzeichnung, steht «Aufzeichnung läuft seit …» da und ein Stopp-Knopf.
 */
@Composable
internal fun RecordCard(since: Instant?, onRecord: (Boolean) -> Unit) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = cardColor()),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_watch), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
                Text(stringResource(R.string.record_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (since != null) stringResource(R.string.record_since, Format.time(since)) else stringResource(R.string.record_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = { onRecord(since == null) }) {
                Text(stringResource(if (since != null) R.string.record_stop else R.string.record_start))
            }
        }
    }
}
