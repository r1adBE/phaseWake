package com.phasewake.app.ring

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.MathChallenge
import com.phasewake.app.ui.components.rememberSkyInk

/**
 * Weckaufgabe: [MathChallenge.STEPS] Additionen mit eigener Zifferntastatur (grosse Tasten, für
 * Bildschirmleser beschriftet). Falsche Antwort: neue Aufgabe im selben Schritt. Gelöst: [onSolved].
 * Jede Eingabe meldet [onTyping] (der Weckton wird dabei leiser).
 */
@Composable
internal fun MathTaskPanel(
    challenge: MathChallenge,
    light: Float?,
    onTyping: () -> Unit,
    onSolved: () -> Unit,
    modifier: Modifier = Modifier,
    keyHeight: Dp = KEY_HEIGHT,
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

    // Jeder Text und jede Taste nimmt die Schrift, die an ihrer Stelle des Himmels lesbar ist (Audit UI-6).
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val (stepInk, stepPlace) = rememberSkyInk(light, NIGHT_INK)
        Text(
            stringResource(R.string.wake_task_solve, step, challenge.steps),
            style = MaterialTheme.typography.titleMedium,
            color = stepInk.muted,
            textAlign = TextAlign.Center,
            modifier = stepPlace,
        )
        val (sumInk, sumPlace) = rememberSkyInk(light, NIGHT_INK)
        Text(
            "${problem.a} + ${problem.b} = " + input.ifEmpty { "?" },
            style = MaterialTheme.typography.displaySmall,
            color = sumInk.content,
            modifier = Modifier.padding(vertical = 8.dp).then(sumPlace).semantics { liveRegion = LiveRegionMode.Polite },
        )
        val (wrongInk, wrongPlace) = rememberSkyInk(light, NIGHT_INK)
        Text(
            if (wrong) stringResource(R.string.wake_task_wrong) else "",
            style = MaterialTheme.typography.bodyMedium,
            color = wrongInk.content,
            textAlign = TextAlign.Center,
            modifier = Modifier.heightIn(min = 24.dp).then(wrongPlace).semantics { liveRegion = LiveRegionMode.Assertive },
        )
        val rows = listOf("123", "456", "789")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { digit -> Key(digit.toString(), light, keyHeight, Modifier.weight(1f)) { type(digit) } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                val delete = stringResource(R.string.wake_task_delete)
                val (ink, place) = rememberSkyInk(light, NIGHT_INK)
                OutlinedButton(
                    onClick = {
                        onTyping()
                        input = input.dropLast(1)
                    },
                    border = BorderStroke(1.dp, ink.content.copy(alpha = 0.6f)),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.weight(1f).heightIn(min = keyHeight).then(place),
                ) { Icon(painterResource(R.drawable.ic_back), contentDescription = delete, tint = ink.content, modifier = Modifier.size(24.dp)) }
                Key("0", light, keyHeight, Modifier.weight(1f)) { type('0') }
                Key(stringResource(R.string.wake_task_check), light, keyHeight, Modifier.weight(1f), enabled = input.isNotEmpty()) { check() }
            }
        }
    }
}

@Composable
private fun Key(label: String, light: Float?, height: Dp, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val (ink, place) = rememberSkyInk(light, NIGHT_INK)
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        border = BorderStroke(1.dp, ink.content.copy(alpha = if (enabled) 0.6f else 0.2f)),
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.heightIn(min = height).then(place),
    ) { Text(label, style = MaterialTheme.typography.headlineSmall, color = ink.content.copy(alpha = if (enabled) 1f else 0.4f), maxLines = 1) }
}

private val KEY_HEIGHT = 60.dp
