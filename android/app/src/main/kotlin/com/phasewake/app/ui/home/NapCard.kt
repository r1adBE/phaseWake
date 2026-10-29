package com.phasewake.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.Transient
import com.phasewake.app.core.TransientPlan
import com.phasewake.app.ui.Format
import kotlin.math.roundToInt

/**
 * Powernap auf der Startseite: zugeklappt eine Zeile, aufgeklappt Dauer (10–90 Min) und Start. Läuft ein
 * Powernap, steht seine Weckzeit da und ein Knopf zum Beenden.
 */
@Composable
internal fun NapCard(running: TransientPlan?, minutes: Int, onStart: (Int) -> Unit, onStop: (String) -> Unit) {
    val res = LocalResources.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var chosen by remember(minutes) { mutableFloatStateOf(minutes.toFloat()) }
    val length = res.getQuantityString(R.plurals.nap_minutes, chosen.roundToInt(), chosen.roundToInt())
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = cardColor()),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        val open = expanded && running == null
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = running == null, role = Role.Button) { expanded = !expanded }
                .semantics { if (running == null) stateDescription = length }
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_nap), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(stringResource(R.string.nap_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (running != null) stringResource(R.string.nap_running, Format.time(running.plan.target)) else length,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (running != null) {
                FilledTonalButton(onClick = { onStop(running.spec.id) }) { Text(stringResource(R.string.nap_stop)) }
            }
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp)) {
                Slider(
                    value = chosen,
                    onValueChange = { chosen = it },
                    valueRange = Transient.NAP_MIN_MINUTES.toFloat()..Transient.NAP_MAX_MINUTES.toFloat(),
                    steps = (Transient.NAP_MAX_MINUTES - Transient.NAP_MIN_MINUTES) / Transient.NAP_STEP_MINUTES - 1,
                )
                Text(stringResource(R.string.nap_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = {
                        expanded = false
                        onStart(chosen.roundToInt())
                    },
                    modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
                ) { Text(stringResource(R.string.nap_start)) }
            }
        }
    }
}
