package com.phasewake.app.ui.sounds

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.SleepSound
import com.phasewake.app.core.SleepSounds
import com.phasewake.app.sounds.SleepSoundService
import com.phasewake.app.ui.Format
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * Einschlafklänge: zehn Klänge (Rauschen, Ventilator, Einschlaf-Wellen, Aufnahmen), Timer, Abspielen/Stoppen und Restzeit.
 *
 * @param canPlay `false`, solange ein Weckfenster läuft (Klänge überdecken nie das Wecken).
 */
@Composable
fun SoundsScreen(
    playing: SleepSoundService.Playing?,
    selected: SleepSound,
    timer: Int?,
    canPlay: Boolean,
    onBack: () -> Unit,
    onSelect: (SleepSound) -> Unit,
    onTimer: (Int?) -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_cancel)) }
            Text(stringResource(R.string.sounds_title), style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Zwei Kacheln je Zeile; bei ungerader Zahl steht die letzte allein.
            SleepSound.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { sound ->
                        SoundTile(sound, active = sound == (playing?.sound ?: selected), Modifier.weight(1f)) { onSelect(sound) }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            // Einschlaf-Wellen: links und rechts ein anderer Ton, darum mit Kopfhörern hören.
            if ((playing?.sound ?: selected) == SleepSound.BINAURAL) {
                Text(
                    stringResource(R.string.sound_binaural_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(stringResource(R.string.sounds_timer), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SleepSounds.TIMER_CHOICES.forEachIndexed { index, minutes ->
                    SegmentedButton(
                        selected = minutes == timer,
                        onClick = { onTimer(minutes) },
                        shape = SegmentedButtonDefaults.itemShape(index, SleepSounds.TIMER_CHOICES.size),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(
                            if (minutes == null) stringResource(R.string.sounds_timer_off) else stringResource(R.string.duration_minutes, minutes),
                            maxLines = 1,
                        )
                    }
                }
            }

            if (playing != null) {
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.sounds_stop)) }
                playing.endsAt?.let { endsAt ->
                    var now by remember { mutableStateOf(Instant.now()) }
                    LaunchedEffect(endsAt) {
                        while (true) {
                            now = Instant.now()
                            delay(1_000)
                        }
                    }
                    // Angefangene Minuten zählen voll: «noch 1 Min» bis zum Schluss.
                    val minutes = (Duration.between(now, endsAt).seconds.coerceAtLeast(0) + 59) / 60
                    Text(
                        stringResource(R.string.sounds_remaining, Format.duration(context, Duration.ofMinutes(minutes))),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            } else {
                Button(onClick = onPlay, enabled = canPlay, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.sounds_play)) }
            }
            Text(
                stringResource(R.string.sounds_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SoundTile(sound: SleepSound, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (active) colors.primaryContainer else colors.surfaceContainer,
            contentColor = if (active) colors.onPrimaryContainer else colors.onSurface,
        ),
        modifier = modifier.selectable(selected = active, role = Role.RadioButton, onClick = onClick),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            // Gewählt: zusätzlich ein Haken, nicht nur die Farbe (WCAG 1.4.1).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_sound), contentDescription = null)
                Spacer(Modifier.weight(1f))
                if (active) Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Text(stringResource(SleepSoundService.soundName(sound)), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
        }
    }
}
