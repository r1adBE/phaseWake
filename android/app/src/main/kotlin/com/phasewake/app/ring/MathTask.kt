package com.phasewake.app.ring

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.MathChallenge

/**
 * Weckaufgabe: [MathChallenge.STEPS] Additionen mit eigener Zifferntastatur (grosse Tasten, für
 * Bildschirmleser beschriftet). Falsche Antwort: neue Aufgabe im selben Schritt. Gelöst: [onSolved].
 * Jede Eingabe meldet [onTyping] (der Weckton wird dabei leiser).
 */
@Composable
internal fun MathTaskPanel(
    challenge: MathChallenge,
    content: Color,
    onTyping: () -> Unit,
    onSolved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    // Neu zeichnen, wenn sich die Aufgabe ändert (MathChallenge selbst ist kein Compose-Zustand).
    var revision by remember { mutableIntStateOf(0) }
    val problem = remember(revision) { challenge.problem }
    val step = remember(revision) { challenge.step }

    fun type(digit: Char) {
        onTyping()
        wrong = false
        if (input.length < MathChallenge.MAX_DIGITS) input += digit
    }

    fun check() {
        onTyping()
        val value = input.toIntOrNull() ?: return
        val correct = challenge.submit(value)
        input = ""
        wrong = !correct
        revision++
        if (challenge.done) onSolved()
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.wake_task_solve, step, challenge.steps),
            style = MaterialTheme.typography.titleMedium,
            color = content.copy(alpha = 0.8f),
        )
        Text(
            "${problem.a} + ${problem.b} = " + input.ifEmpty { "?" },
            style = MaterialTheme.typography.displaySmall,
            color = content,
            modifier = Modifier.padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            if (wrong) stringResource(R.string.wake_task_wrong) else "",
            style = MaterialTheme.typography.bodyMedium,
            color = content,
            modifier = Modifier.height(24.dp).semantics { liveRegion = LiveRegionMode.Assertive },
        )
        val rows = listOf("123", "456", "789")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { digit -> Key(digit.toString(), content, Modifier.weight(1f)) { type(digit) } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                val delete = stringResource(R.string.wake_task_delete)
                OutlinedButton(
                    onClick = {
                        onTyping()
                        input = input.dropLast(1)
                    },
                    border = BorderStroke(1.dp, content.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.weight(1f).height(KEY_HEIGHT),
                ) { Icon(painterResource(R.drawable.ic_back), contentDescription = delete, tint = content, modifier = Modifier.size(24.dp)) }
                Key("0", content, Modifier.weight(1f)) { type('0') }
                Key(stringResource(R.string.wake_task_check), content, Modifier.weight(1f), enabled = input.isNotEmpty()) { check() }
            }
        }
    }
}

@Composable
private fun Key(label: String, content: Color, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        border = BorderStroke(1.dp, content.copy(alpha = if (enabled) 0.5f else 0.2f)),
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.height(KEY_HEIGHT),
    ) { Text(label, style = MaterialTheme.typography.headlineSmall, color = content.copy(alpha = if (enabled) 1f else 0.4f), maxLines = 1) }
}

private val KEY_HEIGHT = 60.dp
