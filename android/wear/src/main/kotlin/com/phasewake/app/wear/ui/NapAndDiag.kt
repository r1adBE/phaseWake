package com.phasewake.app.wear.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Slider
import androidx.wear.compose.material3.Text
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.DiagEntry
import com.phasewake.app.core.DiagLog
import com.phasewake.app.core.SleepGoal
import com.phasewake.app.core.Transient
import com.phasewake.app.core.TransientPlan
import com.phasewake.app.wear.R
import com.phasewake.app.wear.ui.theme.Brand
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// Powernap und Diagnose auf der Uhr.

/** Neues des Startbildschirms (Runde 3); Vorgaben = nichts davon (Bildschirmfotos). */
internal class WatchHomeExtras(
    /** Laufender Powernap; `null` = keiner. */
    val nap: TransientPlan? = null,
    /** Powernap wählen; `null` = kein Chip. */
    val onNap: (() -> Unit)? = null,
    val onStopNap: (String) -> Unit = {},
    /** Datum eines übersprungenen Termins je Wecker, schon formatiert. */
    val skipped: (AlarmSpec) -> String? = { null },
    /** Tippen auf das Weckfenster (zählt für das Freischalten der Diagnose). */
    val onWindowTap: () -> Unit = {},
    /** Diagnose freigeschaltet: Knopf unten. */
    val diagnostics: Boolean = false,
    val onDiagnostics: () -> Unit = {},
    /** Jetzt, für den Zeiger auf dem Ring; `null` = ohne Zeiger. In Bildschirmfotos fest, damit sie gleich bleiben. */
    val now: Instant? = null,
    /** Schlafziel vom Handy in Minuten (ohne Handy der Standard); setzt den Einschlafpunkt auf dem Ring. */
    val sleepGoalMinutes: Int = SleepGoal.DEFAULT_MINUTES,
)

/** Chip «Powernap»: öffnet die Dauerwahl; läuft einer, «Bis 14:20» und antippen beendet ihn. */
@Composable
internal fun NapChip(nap: TransientPlan?, zone: ZoneId, onNap: () -> Unit, onStop: (String) -> Unit) {
    Button(
        onClick = { if (nap != null) onStop(nap.spec.id) else onNap() },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
        label = { Text(stringResource(if (nap != null) R.string.nap_stop else R.string.nap_title)) },
        secondaryLabel = if (nap != null) {
            { Text(stringResource(R.string.nap_running, shortTime(nap.plan.target.atZone(zone)))) }
        } else {
            null
        },
    )
}

/** Dauer des Powernaps (10–90 Min, Schritt 5) wählen und starten. */
@Composable
internal fun NapScreen(initial: Int, onStart: (Int) -> Unit) {
    val res = LocalResources.current
    var minutes by rememberSaveable { mutableIntStateOf(initial) }
    val listState = rememberScalingLazyListState()
    ScreenScaffold(
        scrollState = listState,
        edgeButton = { EdgeButton(onClick = { onStart(minutes) }) { Text(stringResource(R.string.nap_start)) } },
    ) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text(stringResource(R.string.nap_title)) } }
            item {
                Text(
                    res.getQuantityString(R.plurals.nap_minutes, minutes, minutes),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Slider(
                    value = minutes,
                    onValueChange = { minutes = it },
                    valueProgression = Transient.NAP_MIN_MINUTES..Transient.NAP_MAX_MINUTES step Transient.NAP_STEP_MINUTES,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Diagnose der Uhr: nächster Wecker, Version, Ereignisse (neueste zuerst), Leeren, Ausblenden. */
@Composable
internal fun DiagScreen(next: String?, version: String, entries: List<DiagEntry>, onClear: () -> Unit, onHide: () -> Unit) {
    val listState = rememberScalingLazyListState()
    val zone = ZoneId.systemDefault()
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text(stringResource(R.string.diag_title)) } }
            item { Text(stringResource(R.string.diag_next, next ?: "–"), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
            item { Text(stringResource(R.string.diag_version, version), style = MaterialTheme.typography.bodySmall, color = Brand.Muted) }
            item { Button(onClick = onClear, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.diag_clear)) }) }
            item { Button(onClick = onHide, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.diag_hide)) }) }
            if (entries.isEmpty()) item { Text(stringResource(R.string.diag_empty), style = MaterialTheme.typography.bodySmall) }
            entries.sortedByDescending { it.at }.forEach { entry ->
                item {
                    Text(
                        DiagLog.format(listOf(entry), zone),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

private fun shortTime(t: java.time.ZonedDateTime): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(t)
