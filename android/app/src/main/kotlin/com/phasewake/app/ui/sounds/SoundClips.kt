package com.phasewake.app.ui.sounds

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.SoundClip
import com.phasewake.app.core.SoundEvents
import com.phasewake.app.core.SoundType
import com.phasewake.app.ui.Format
import java.io.File
import java.time.Duration

fun soundTypeName(context: Context, type: SoundType): String = context.getString(
    when (type) {
        SoundType.SNORE -> R.string.sound_type_snore
        SoundType.TALK -> R.string.sound_type_talk
        SoundType.OTHER -> R.string.sound_type_other
    },
)

/** «Schnarchen: 12 Min (8 Ausschnitte) · Sprechen: 2 Ausschnitte»; `null` ohne Schnarchen und Sprechen. */
fun soundSummary(context: Context, clips: List<SoundClip>): String? {
    val summary = SoundEvents.summary(clips)
    val res = context.resources
    val parts = buildList {
        if (summary.snoreClips > 0) {
            val minutes = Format.duration(context, Duration.ofMinutes(summary.snoreMinutes.toLong()))
            add(res.getQuantityString(R.plurals.sound_summary, summary.snoreClips, minutes, summary.snoreClips))
        }
        if (summary.talkClips > 0) add(res.getQuantityString(R.plurals.sound_summary_talk, summary.talkClips, summary.talkClips))
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * «Geräusche» einer Nacht (Morgenbericht, Verlauf): Bilanz und die Ausschnitte mit Zeit, Art, Abspielen
 * und Löschen; dazu «Alle löschen». Ohne Ausschnitte nichts.
 *
 * @param fileOf Datei eines Ausschnitts (`null`: nicht abspielbar).
 */
@Composable
fun SoundClipsSection(
    clips: List<SoundClip>,
    fileOf: (SoundClip) -> File?,
    onDelete: (SoundClip) -> Unit,
    onDeleteAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (clips.isEmpty()) return
    val context = LocalContext.current
    var playing by remember { mutableStateOf<String?>(null) }
    val player = remember { mutableStateOf<MediaPlayer?>(null) }
    fun stop() {
        player.value?.let { runCatching { it.stop() }; it.release() }
        player.value = null
        playing = null
    }
    fun play(clip: SoundClip) {
        stop()
        val file = fileOf(clip)?.takeIf { it.exists() } ?: return
        try {
            player.value = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(file.path)
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
            playing = clip.file
        } catch (e: Exception) {
            Log.w("SoundClips", "Ausschnitt nicht abspielbar", e)
            stop()
        }
    }
    DisposableEffect(Unit) { onDispose { stop() } }
    var confirmAll by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.sound_clips_title), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        soundSummary(context, clips)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        clips.sortedBy { it.at }.forEach { clip ->
            val time = Format.time(clip.at)
            val active = playing == clip.file
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (active) stop() else play(clip) }) {
                    Icon(
                        painterResource(if (active) R.drawable.ic_stop else R.drawable.ic_play),
                        contentDescription = stringResource(if (active) R.string.sounds_stop else R.string.sounds_play),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(time, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 10.dp))
                Text(soundTypeName(context, clip.type), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    if (active) stop()
                    onDelete(clip)
                }) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.sound_clip_delete, time))
                }
            }
        }
        TextButton(onClick = { confirmAll = true }, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.sound_clips_delete_all), color = MaterialTheme.colorScheme.error)
        }
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(stringResource(R.string.sound_clips_delete_all)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmAll = false
                    stop()
                    onDeleteAll()
                }) { Text(stringResource(R.string.sound_clips_delete_all), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * Startseite am Abend: «Geräusche aufnehmen» für diese Nacht ein- oder ausschalten. Die ganze Zeile
 * schaltet. Läuft die Aufnahme, der Hinweis zum Laden; fehlt das Mikrofon-Recht, die Erklärung.
 */
@Composable
fun SoundRecordingCard(
    enabled: Boolean,
    recording: Boolean,
    permissionDenied: Boolean,
    containerColor: Color,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().toggleable(value = enabled, role = Role.Switch, onValueChange = onToggle).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_mic), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(stringResource(R.string.sound_rec_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        when {
                            permissionDenied && !recording -> R.string.sound_rec_permission
                            recording -> R.string.sound_rec_plug_in
                            else -> R.string.sound_rec_hint
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = null)
        }
    }
}
