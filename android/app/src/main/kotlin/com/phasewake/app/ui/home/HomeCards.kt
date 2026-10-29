package com.phasewake.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import com.phasewake.app.core.MissHint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.CustomNote
import com.phasewake.app.sounds.SleepSoundService
import com.phasewake.app.ui.notes.NightNotes
import com.phasewake.app.core.SoundClip
import com.phasewake.app.ui.sounds.SoundClipsSection
import java.io.File
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.NightRecord
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.components.NightBars
import com.phasewake.app.ui.theme.PhaseWakeTheme
import com.phasewake.app.ui.history.FeelingPicker
import com.phasewake.app.ui.history.nightDetails
import com.phasewake.app.ui.history.nightSummary
import java.time.Duration
import java.time.Instant

// Karten der Startseite: letzte Nacht, Einschlafklänge, Wecker-Zeile.

@Composable
internal fun LastNightCard(
    night: NightRecord,
    now: Instant,
    missHint: MissHint?,
    onFeeling: (Int) -> Unit,
    notes: List<String>,
    customNotes: List<CustomNote>,
    onToggleNote: (String) -> Unit,
    clips: List<SoundClip> = emptyList(),
    soundFile: (SoundClip) -> File? = { null },
    onDeleteClip: (SoundClip) -> Unit = {},
    onDeleteClips: () -> Unit = {},
) {
    // Morgenbericht: am Morgen nach dem Wecken fragt die Karte nach dem Befinden.
    val morning = Duration.between(night.wokeAt, now) < MORNING
    val askFeeling = morning && night.feeling == null
    val context = LocalContext.current
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = cardColor()),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (morning) R.string.home_morning else R.string.home_last_night), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (!morning) Text(Format.time(night.wokeAt), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            // Morgenbericht: «Geweckt 6:42» gross, der Grund steht gleich darunter in der Erklärung.
            if (morning) {
                Text(
                    stringResource(R.string.night_woke, Format.time(night.wokeAt)),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            NightBars(
                night.epochs,
                Modifier.fillMaxWidth().height(48.dp).padding(top = 10.dp),
                totalEpochs = (Duration.between(night.windowStart, night.target).toMinutes() * 2).toInt(),
            )
            // Erklärung (bodyMedium, leise), darunter die Zusatzinfos (bodySmall, noch leiser).
            Text(
                nightSummary(context, night),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                nightDetails(context, night),
                style = MaterialTheme.typography.bodySmall,
                color = PhaseWakeTheme.brand.mutedSoft,
                modifier = Modifier.padding(top = 2.dp),
            )
            missHint?.let { hint ->
                Column(
                    Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
                        .padding(12.dp),
                ) {
                    Text(LocalResources.current.getQuantityString(R.plurals.hint_title, MissHint.NIGHTS, MissHint.NIGHTS), style = MaterialTheme.typography.labelLarge)
                    Text(
                        stringResource(
                            when (hint) {
                                MissHint.GAPS -> R.string.hint_gaps
                                MissHint.HEART_MISSING -> R.string.hint_heart
                                MissHint.CALM -> R.string.hint_calm
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            AnimatedVisibility(askFeeling) {
                Column(Modifier.padding(top = 14.dp)) {
                    FeelingPicker(onPick = onFeeling)
                    Text(
                        stringResource(R.string.feeling_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = PhaseWakeTheme.brand.mutedSoft,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            // Morgenbericht: Notizen des Abends nachtragen oder ändern.
            if (morning) NightNotes(notes, customNotes, onToggleNote, Modifier.padding(top = 6.dp))
            // Morgenbericht: Geräusche der Nacht (sonst im Verlauf).
            if (morning) SoundClipsSection(clips, soundFile, onDeleteClip, onDeleteClips)
        }
    }
}

/** Einstieg zu den Einschlafklängen; läuft ein Klang, mit Restzeit. */
@Composable
internal fun SoundsEntry(playing: SleepSoundService.Playing?, now: Instant, onClick: () -> Unit) {
    val context = LocalContext.current
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = cardColor()),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Row(Modifier.clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_sound), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(stringResource(R.string.sounds_title), style = MaterialTheme.typography.titleMedium)
                if (playing != null) {
                    val name = stringResource(SleepSoundService.soundName(playing.sound))
                    // Auf 20 s genau (so oft aktualisiert die Startseite); angefangene Minuten zählen voll.
                    val left = playing.endsAt?.let { end ->
                        val minutes = (Duration.between(now, end).seconds.coerceAtLeast(0) + 59) / 60
                        stringResource(R.string.sounds_remaining, Format.duration(context, Duration.ofMinutes(minutes)))
                    }
                    Text(
                        listOfNotNull(name, left).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Zeile eines Weckers: antippen öffnet den Editor, der Schalter schaltet. Das Menü daneben bietet
 * «Nächstes Mal überspringen» (nur wiederholte, eingeschaltete Wecker); übersprungen steht das Datum da,
 * mit «Rückgängig».
 */
@Composable
internal fun AlarmRow(
    alarm: AlarmSpec,
    modifier: Modifier,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    /** Datum des übersprungenen Termins, schon formatiert; `null` = nichts übersprungen. */
    skipped: String? = null,
    onSkip: (() -> Unit)? = null,
    onUndoSkip: () -> Unit = {},
    /** Datum, das «Überspringen» treffen würde, schon formatiert (im Menü genannt); `null` = ohne Datum. */
    skipDate: String? = null,
) {
    val context = LocalContext.current
    val res = LocalResources.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // Schalter, Menü und «Rückgängig» nennen den Wecker, für den sie gelten (Bildschirmleser).
    val switchLabel = Format.window(context, alarm) + (alarm.label.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = cardColor()),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Row(
            Modifier.clickable(onClick = onClick).padding(start = 18.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                val title = alarm.sleepDurationMinutes?.let {
                    stringResource(R.string.alarm_after_sleep, Format.duration(context, Duration.ofMinutes(it.toLong())))
                } ?: Format.window(context, alarm)
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface else muted,
                )
                val details = buildList {
                    add(Format.days(context, alarm))
                    if (alarm.sleepDurationMinutes != null) {
                        add(res.getString(R.string.alarm_latest, Format.time(java.time.LocalTime.of(alarm.hour, alarm.minute))))
                    }
                    if (alarm.label.isNotBlank()) add(alarm.label)
                }
                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = muted)
                if (skipped != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.alarm_skipped, skipped), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        val undoLabel = stringResource(R.string.alarm_skip_undo_for, "$skipped · $switchLabel")
                        TextButton(onClick = onUndoSkip, modifier = Modifier.semantics { contentDescription = undoLabel }) {
                            Text(stringResource(R.string.alarm_skip_undo))
                        }
                    }
                }
            }
            Switch(checked = alarm.enabled, onCheckedChange = onToggle, modifier = Modifier.semantics { contentDescription = switchLabel })
            AlarmMenu(switchLabel, onSkip.takeIf { skipped == null && alarm.enabled && !alarm.isOneShot }, skipDate)
        }
    }
}

/**
 * Menü «Mehr» der Wecker-Zeile mit «Do 30.10. überspringen» ([skipDate]; ohne Datum «Nächstes Mal überspringen»).
 * Ohne [onSkip] nur ein Platzhalter gleicher Breite ohne Bedienelement: Zeilen bleiben bündig, und der Bildschirmleser
 * findet keinen namenlosen Knopf.
 */
@Composable
private fun AlarmMenu(name: String, onSkip: (() -> Unit)?, skipDate: String?) {
    if (onSkip == null) {
        Spacer(Modifier.size(MENU_SIZE))
    } else {
        var open by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { open = true }) {
                Icon(painterResource(R.drawable.ic_more), contentDescription = stringResource(R.string.alarm_actions, name))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(skipDate?.let { stringResource(R.string.alarm_skip_on, it) } ?: stringResource(R.string.alarm_skip)) },
                    onClick = {
                        open = false
                        onSkip()
                    },
                )
            }
        }
    }
}

/** Breite des Menüknopfs (Mindestgrösse eines Bedienelements), auch für den Platzhalter. */
private val MENU_SIZE = 48.dp
