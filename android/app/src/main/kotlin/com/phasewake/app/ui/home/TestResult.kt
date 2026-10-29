package com.phasewake.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.TestRun
import com.phasewake.app.core.TestRuns
import com.phasewake.app.core.TestStep

/** Text je geprüftem Schritt. */
private val STEP_TEXT = mapOf(
    TestStep.RECEIVED to R.string.test_step_received,
    TestStep.WATCH_RANG to R.string.test_step_watch_rang,
    TestStep.PHONE_RANG to R.string.test_step_phone_rang,
    TestStep.STOP_ARRIVED to R.string.test_step_stop,
)

/**
 * Passende Prüfungen des Nacht-Checks je Schritt, in dieser Reihenfolge; die erste, die gerade nicht stimmt,
 * zeigt ihre Lösung. Stimmt gerade keine, gilt die erste als wahrscheinlichste Ursache.
 */
private val STEP_FIXES = mapOf(
    TestStep.RECEIVED to listOf(Check.WATCH_APP, Check.WATCH),
    TestStep.WATCH_RANG to listOf(Check.WATCH_SETUP, Check.WATCH_BATTERY, Check.WATCH),
    TestStep.PHONE_RANG to listOf(Check.NOTIFICATIONS, Check.FULL_SCREEN, Check.DND, Check.DND_UNKNOWN, Check.VOLUME, Check.EXACT_ALARMS),
    TestStep.STOP_ARRIVED to listOf(Check.WATCH),
)

/** Ergebnis des Probeweckers: ✓ oder ✗ je Schritt (Symbol und Text, nie nur Farbe), bei ✗ die passende Lösung. */
@Composable
internal fun TestResultDialog(run: TestRun, check: NightCheckState?, onFix: (Check) -> Unit, onClose: () -> Unit) {
    val results = TestStep.entries.associateWith { TestRuns.result(run, it) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.test_result_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                results.forEach { (step, ok) -> StepRow(step, ok, check, onFix) }
                if (results.values.none { it == false }) {
                    Text(stringResource(R.string.test_all_ok), style = MaterialTheme.typography.titleSmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.test_close)) } },
    )
}

@Composable
private fun StepRow(step: TestStep, ok: Boolean?, check: NightCheckState?, onFix: (Check) -> Unit) {
    val state = stringResource(
        when (ok) {
            true -> R.string.test_step_passed
            false -> R.string.test_step_failed
            null -> R.string.test_step_skipped
        },
    )
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { stateDescription = state }) {
            when (ok) {
                true -> Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                false -> Icon(painterResource(R.drawable.ic_close), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                null -> Text("–", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 5.dp))
            }
            Column(Modifier.padding(start = 10.dp)) {
                Text(stringResource(STEP_TEXT.getValue(step)), style = MaterialTheme.typography.bodyLarge)
                if (ok == null) Text(state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (ok == false) {
            val fixes = STEP_FIXES.getValue(step)
            val fix = fixes.firstOrNull { it in check?.failed.orEmpty() } ?: fixes.first()
            Text(
                if (fix == Check.WATCH_BATTERY) stringResource(fix.problem, NightCheckState.LOW_BATTERY_PERCENT) else stringResource(fix.problem),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 30.dp, top = 2.dp),
            )
            fix.action?.let { action ->
                FilledTonalButton(onClick = { onFix(fix) }, modifier = Modifier.padding(start = 30.dp, top = 4.dp)) { Text(stringResource(action)) }
            }
        }
    }
}
