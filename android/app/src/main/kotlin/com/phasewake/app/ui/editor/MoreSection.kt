package com.phasewake.app.ui.editor

import android.content.Intent
import android.media.RingtoneManager
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.phasewake.app.R
import com.phasewake.app.core.PhoneAlert
import com.phasewake.app.ring.WakeTonePreview
import com.phasewake.app.ring.WakeTones

// Editor: zugeklappter Bereich «Mehr» mit Weckton und Name.

/**
 * Selten gebrauchte Optionen zugeklappt unter «Mehr»: Zum Beenden, Sonnenaufgang, Feiertage, Weckton und
 * Name. Sichtbar bleiben Zeit, Fenster, Wiederholen und Weckart; kürzerer Editor auf kleinen Handys.
 */
@Composable
internal fun ColumnScope.MoreSection(
    state: EditorState,
    preview: WakeTonePreview,
    soundPicker: ManagedActivityResultLauncher<Intent, ActivityResult>,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button) { state.more = !state.more }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.editor_more), style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painterResource(R.drawable.ic_expand),
            contentDescription = null,
            modifier = Modifier.size(20.dp).rotate(if (state.more) 180f else 0f),
        )
    }
    AnimatedVisibility(state.more) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            AnimatedVisibility(state.phone != PhoneAlert.OFF) { WakeTaskSection(state) }
            SunriseSection(state)
            AnimatedVisibility(state.days.isNotEmpty()) { HolidaySection(state) }
            AnimatedVisibility(state.phone == PhoneAlert.SOUND) { ToneSection(state, preview, soundPicker) }
            OutlinedTextField(
                value = state.label,
                onValueChange = { state.label = it.take(LABEL_MAX_LENGTH) },
                label = { Text(stringResource(R.string.editor_label)) },
                placeholder = { Text(stringResource(R.string.editor_label_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Höchstlänge des Namens eines Weckers in Zeichen. */
private const val LABEL_MAX_LENGTH = 40

/** Weckton des Handys: eigene Töne (Antippen spielt kurz an) oder ein Systemton aus der Auswahl. */
@Composable
private fun ToneSection(
    state: EditorState,
    preview: WakeTonePreview,
    soundPicker: ManagedActivityResultLauncher<Intent, ActivityResult>,
) {
    val context = LocalContext.current
    Section {
        // Zugeklappt oder ausgeblendet: Probehören endet.
        DisposableEffect(Unit) { onDispose { preview.stop() } }
        Text(
            stringResource(R.string.editor_sound), style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        WakeTones.ALL.forEach { tone ->
            ToneRow(stringResource(tone.label), selected = state.sound == tone.key) {
                state.sound = tone.key
                preview.play(tone)
            }
        }
        // Systemton: leer (Standard) oder eine URI aus der Auswahl des Systems.
        val system = WakeTones.find(state.sound) == null
        ToneRow(
            stringResource(R.string.wake_tone_system),
            selected = system,
            detail = if (system) soundTitle(context, state.sound) else null,
        ) {
            preview.stop()
            soundPicker.launch(
                Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    .putExtra(
                        RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                        state.sound.takeIf { system }?.ifEmpty { null }?.toUri(),
                    ),
            )
        }
    }
}
