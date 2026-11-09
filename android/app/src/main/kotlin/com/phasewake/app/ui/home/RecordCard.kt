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
 * Handy schickt nur Start und Stopp. Aufbau (Wunsch r1AD 08.11.2026): Symbol und Titel in einer Zeile, darunter der
 * Hinweis über die ganze Breite, dann der Knopf rechts wie bei den anderen Karten. Läuft eine Aufzeichnung, steht
 * «Aufzeichnung läuft seit …» über dem Stopp-Knopf.
 */
@Composable
internal fun RecordCard(since: Instant?, onRecord: (Boolean) -> Unit) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = cardColor()),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_watch), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.record_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 14.dp))
            }
            Text(
                stringResource(R.string.record_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (since != null) {
                Text(
                    stringResource(R.string.record_since, Format.time(since)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            FilledTonalButton(onClick = { onRecord(since == null) }, modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) {
                Text(stringResource(if (since != null) R.string.record_stop else R.string.record_start))
            }
        }
    }
}
