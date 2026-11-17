package com.phasewake.app.ui.home

import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.Learning
import com.phasewake.app.ui.theme.PhaseWakeTheme
import com.phasewake.app.ui.theme.outlinedBorder

// Karte «Nacht-Check» auf der Startseite.

/** Karte auf der Startseite: grün und knapp, wenn alles stimmt; sonst jede offene Prüfung mit Knopf. */
@Composable
internal fun NightCheckCard(
    state: NightCheckState,
    containerColor: androidx.compose.ui.graphics.Color,
    onFix: (Check) -> Unit,
    /** «In 2 Minuten testen»; `null` = kein Knopf. */
    onTest: (() -> Unit)? = null,
    /** Zielzeit eines laufenden Probeweckers. */
    testAt: java.time.Instant? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val ready = state.ready
    val readyText = stringResource(R.string.check_ready)
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (ready) containerColor else MaterialTheme.colorScheme.errorContainer,
        ),
        border = cardEdge(),
        elevation = cardLift(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .then(if (ready) Modifier.clickable(role = Role.Button) { expanded = !expanded } else Modifier)
                    .semantics { if (ready) stateDescription = readyText },
            ) {
                // Nicht bereit: Warndreieck (kein ✕, das wie ein Schliessen-Knopf aussieht), immer mit Text, nie nur
                // Farbe.
                Icon(
                    painterResource(if (ready) R.drawable.ic_check else R.drawable.ic_warning),
                    contentDescription = if (ready) null else stringResource(R.string.check_not_ready_icon),
                    tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(
                        stringResource(if (ready) R.string.check_ready else R.string.check_not_ready),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (ready) {
                        Text(
                            stringResource(R.string.check_ready_text), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            AnimatedVisibility(ready && expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Check.entries.filter { it.inReadyList }.forEach { check ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painterResource(R.drawable.ic_check), contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp),
                            )
                            Text(
                                stringResource(check.title), style = MaterialTheme.typography.bodySmall,
                                color = PhaseWakeTheme.brand.mutedSoft, modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
            TestButton(onTest, testAt)
            state.failed.forEach { check ->
                // Text über die ganze Breite, Knopf darunter rechts: lange Texte bleiben lesbar.
                Column(Modifier.fillMaxWidth()) {
                    Text(stringResource(check.problemTitle), style = MaterialTheme.typography.titleSmall)
                    val problem = when (check) {
                        Check.BATTERY, Check.WATCH_BATTERY -> stringResource(
                            check.problem, NightCheckState.LOW_BATTERY_PERCENT,
                        )
                        Check.WATCH_ONSET_BATTERY -> stringResource(
                            check.problem, NightCheckState.WATCH_ONSET_BATTERY_PERCENT,
                        )
                        Check.WATCH_LEARN_BATTERY -> stringResource(check.problem, Learning.MIN_BATTERY)
                        Check.BUILD_MISMATCH -> stringResource(
                            check.problem, state.builds?.first ?: 0, state.builds?.second ?: 0,
                        )
                        else -> stringResource(check.problem)
                    }
                    Text(problem, style = MaterialTheme.typography.bodyMedium)
                    check.action?.let { action ->
                        FilledTonalButton(
                            onClick = { onFix(check) }, modifier = Modifier.align(Alignment.End).padding(top = 6.dp),
                        ) {
                            Text(stringResource(action))
                        }
                    }
                }
            }
        }
    }
}

/** Probewecker: Knopf, oder während er läuft seine Zeit. */
@Composable
private fun TestButton(onTest: (() -> Unit)?, testAt: java.time.Instant?) {
    when {
        testAt != null -> Text(
            stringResource(R.string.test_running, com.phasewake.app.ui.Format.time(testAt)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        onTest != null -> androidx.compose.material3.OutlinedButton(onClick = onTest, border = outlinedBorder()) {
            Text(stringResource(R.string.test_button))
        }
    }
}
