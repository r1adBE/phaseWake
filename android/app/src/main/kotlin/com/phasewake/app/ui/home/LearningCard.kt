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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.Learning
import com.phasewake.app.ui.Format
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Lernphase auf der Startseite: solange sie läuft «Lernphase: Nacht N von 7» ([night]) mit kurzer Erklärung, danach
 * einmal «Angepasst an dich» mit den gelernten Werten ([values]) und «OK» ([onSeen]). [autoHint]: «Einschlafen automatisch
 * erkennen» ist aus – dann der Hinweis, wie die Nächte trotzdem zählen. [onConsent] gesetzt: noch nicht gefragt, die Karte
 * fragt einmal «Lernphase starten» / «Später» (Audit F-02).
 */
class HomeLearning(
    val night: Int?,
    val values: Learning.Values?,
    val autoHint: Boolean = false,
    val onSeen: () -> Unit = {},
    val onConsent: ((Boolean) -> Unit)? = null,
)

/** Karte der Lernphase, siehe [HomeLearning]. */
@Composable
internal fun LearningCard(learning: HomeLearning) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = cardColor()),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
            val values = learning.values
            val onConsent = learning.onConsent
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_watch), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    when {
                        onConsent != null -> stringResource(R.string.learn_consent_title)
                        values != null -> stringResource(R.string.learned_title)
                        else -> stringResource(R.string.learn_progress, learning.night ?: 1, Learning.NIGHTS)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 14.dp),
                )
            }
            if (onConsent != null) {
                LearningConsent(onConsent, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.padding(top = 6.dp))
            } else if (values != null) {
                LearnedLines(values, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.padding(top = 6.dp))
                FilledTonalButton(onClick = learning.onSeen, modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) {
                    Text(stringResource(R.string.wake_task_check))
                }
            } else {
                Text(
                    stringResource(R.string.learn_text, Learning.NIGHTS, Learning.MIN_BATTERY),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (learning.autoHint) {
                    Text(
                        stringResource(R.string.learn_auto_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * Frage zur Lernphase (Einrichtung und Startseite): was die Uhr aufzeichnet, Akku, Daten, abschaltbar; dann «Lernphase
 * starten» ([onConsent] `true`) und «Später» (`false`).
 */
@Composable
internal fun LearningConsent(onConsent: (Boolean) -> Unit, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(stringResource(R.string.learn_consent_text, Learning.NIGHTS), style = MaterialTheme.typography.bodyMedium, color = color)
        Row(Modifier.align(Alignment.End).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onConsent(false) }) { Text(stringResource(R.string.learn_consent_later)) }
            FilledTonalButton(onClick = { onConsent(true) }, modifier = Modifier.padding(start = 8.dp)) {
                Text(stringResource(R.string.learn_consent_start))
            }
        }
    }
}

/** Gelernte Werte in Worten: Bewegungsschwellen, Ruhepuls, Einschlafdauer (auch in den Einstellungen). */
@Composable
internal fun LearnedLines(values: Learning.Values, color: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier) {
        val lines = listOf(
            stringResource(if (Learning.raisesThresholds(values.floor)) R.string.learned_motion_raised else R.string.learned_motion_default),
            values.restingHeartRate?.let { stringResource(R.string.learned_pulse, it.roundToInt()) } ?: stringResource(R.string.learned_pulse_none),
            values.latencyMinutes?.let { stringResource(R.string.learned_latency, Format.duration(context, Duration.ofMinutes(it.toLong()))) }
                ?: stringResource(R.string.learned_latency_none),
        )
        for (line in lines) Text(line, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/** Empfohlene Schlafenszeit am Abend (nach der Lernphase): «Geh heute zwischen [from] und [until] ins Bett». */
@Composable
internal fun BedtimeAdviceCard(from: Instant, until: Instant) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = cardColor()),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_stat_moon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 14.dp)) {
                Text(stringResource(R.string.bedtime_advice, Format.time(from), Format.time(until)), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.bedtime_advice_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
