package com.phasewake.app.ui.editor

import android.app.Activity
import android.media.RingtoneManager
import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.phasewake.app.R
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.WindowAdvice
import com.phasewake.app.ring.WakeTonePreview
import com.phasewake.app.ring.WakeTones
import java.util.UUID
import kotlin.math.roundToInt

/** Wecker anlegen oder bearbeiten. [existing] = `null` für einen neuen Wecker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    existing: AlarmSpec?,
    advice: WindowAdvice.Suggestion?,
    onBack: () -> Unit,
    onSave: (AlarmSpec) -> Unit,
    onDelete: (AlarmSpec) -> Unit,
    /** «An Feiertagen nicht wecken» anbieten: nicht, solange die Uhr es nicht kennt (ausser es ist schon an). */
    holidaysAllowed: Boolean = true,
) {
    val context = LocalContext.current
    // Alle Felder überstehen Drehen und Neuaufbau (rememberSaveable), auch die Kennung eines neuen Weckers.
    val newId = rememberSaveable { UUID.randomUUID().toString() }
    // Neue Wecker wecken mit «Morgenvögel»; bestehende behalten ihren Ton (leer = Standard des Systems).
    val base = existing ?: AlarmSpec(id = newId, hour = 7, minute = 0, days = WEEKDAYS, sound = WakeTones.DEFAULT)
    val time = rememberTimePickerState(base.hour, base.minute, is24Hour = DateFormat.is24HourFormat(context))
    val state = rememberSaveable(saver = EditorState.saver(base)) { EditorState(base) }
    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
        if (result.resultCode == Activity.RESULT_OK) state.sound = uri?.toString().orEmpty()
    }
    // Probehören der eigenen Wecktöne; endet beim Verlassen des Editors und wenn die App in den Hintergrund geht.
    val preview = remember { WakeTonePreview(context) }
    DisposableEffect(preview) { onDispose { preview.stop() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { preview.stop() }

    // Zurück ohne Speichern: bei Änderungen zuerst fragen, auch bei der System-Zurück-Geste.
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val dirty = state.result(time) != base.copy(enabled = true)
    val back = { if (dirty) confirmDiscard = true else onBack() }
    BackHandler(enabled = dirty) { confirmDiscard = true }
    EditorDialogs(
        confirmDiscard = confirmDiscard,
        onDiscard = { confirmDiscard = false; onBack() },
        onKeepEditing = { confirmDiscard = false },
        deleting = existing.takeIf { confirmDelete },
        onDelete = { confirmDelete = false; onDelete(it) },
        onKeep = { confirmDelete = false },
    )

    // Randlos: Statusleiste oben, Navigationsleiste unten und Tastatur freihalten.
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_cancel)) }
            Text(
                stringResource(if (existing == null) R.string.editor_new else R.string.editor_edit),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { onSave(state.result(time)) }, modifier = Modifier.padding(end = 12.dp)) {
                Text(stringResource(R.string.editor_save))
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !state.sleepMode, onClick = { state.sleepMode = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                    Text(stringResource(R.string.editor_mode_clock))
                }
                SegmentedButton(selected = state.sleepMode, onClick = { state.sleepMode = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                    Text(stringResource(R.string.editor_mode_sleep))
                }
            }
            TimeSection(state, time)
            AnimatedVisibility(state.sleepMode) { SleepSection(state) }
            if (advice != null && state.window.roundToInt() < advice.windowMinutes) AdviceCard(state, advice)
            WindowSection(state, time)
            DaysSection(state, holidaysAllowed)
            AlertSection(state)
            SunriseSection(state)
            MoreSection(state, preview, soundPicker)
            if (existing != null) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.editor_delete), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 8.dp))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** Rückfragen des Editors: Änderungen verwerfen und Wecker löschen ([deleting] `null`: keine Rückfrage). */
@Composable
private fun EditorDialogs(
    confirmDiscard: Boolean,
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
    deleting: AlarmSpec?,
    onDelete: (AlarmSpec) -> Unit,
    onKeep: () -> Unit,
) {
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = onKeepEditing,
            title = { Text(stringResource(R.string.editor_discard_title)) },
            confirmButton = { TextButton(onClick = onDiscard) { Text(stringResource(R.string.editor_discard)) } },
            dismissButton = { TextButton(onClick = onKeepEditing) { Text(stringResource(R.string.editor_keep_editing)) } },
        )
    }
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = onKeep,
            title = { Text(stringResource(R.string.editor_delete_title)) },
            confirmButton = {
                TextButton(onClick = { onDelete(deleting) }) {
                    Text(stringResource(R.string.editor_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
