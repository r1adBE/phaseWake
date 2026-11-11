package com.phasewake.app.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Text
import com.phasewake.app.wear.R
import java.time.Instant
import java.time.ZoneId

/** Chip «Nacht aufzeichnen»; läuft eine Aufzeichnung, «Aufzeichnung stoppen» mit «Seit 23:10». */
@Composable
internal fun RecordChip(since: Instant?, zone: ZoneId, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
        label = { Text(stringResource(if (since != null) R.string.record_stop else R.string.record_start)) },
        secondaryLabel = since?.let {
            { Text(stringResource(R.string.record_since, timeFormatter().format(it.atZone(zone)))) }
        },
    )
}
