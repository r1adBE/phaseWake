package com.phasewake.app.ui.settings

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import com.phasewake.app.ui.editor.timePickerColors
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.EveningDark
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.theme.EveningDarkSetting
import com.phasewake.app.ui.theme.PhaseWakeTheme
import java.time.LocalTime

/**
 * «Abends dunkel ab» unter der Wahl des Designs: Schalter mit Erklärung (von – bis), darunter
 * die Uhrzeit des Beginns; antippen öffnet den Zeitwähler. Gilt die Regel gerade, ein Hinweis «Gilt jetzt – hell ab …».
 * Gleich auf dem iPhone (`EveningDarkSettingsSection`).
 */
@Composable
internal fun EveningDarkRows(setting: EveningDarkSetting, onEnabled: (Boolean) -> Unit, onFrom: (Int) -> Unit) {
    val from = Format.time(LocalTime.of(setting.fromMinutes / 60, setting.fromMinutes % 60))
    val until = Format.time(LocalTime.of(EveningDark.UNTIL_MINUTES / 60, EveningDark.UNTIL_MINUTES % 60))
    SwitchRow(
        stringResource(R.string.settings_evening_dark),
        stringResource(R.string.settings_evening_dark_text, from, until),
        setting.enabled,
        onEnabled,
    )
    if (!setting.enabled) return
    // Gilt die Regel gerade, bleibt die App auch bei «Hell» dunkel: sagen, warum und bis wann.
    if (PhaseWakeTheme.isEvening) {
        Text(
            stringResource(R.string.settings_evening_dark_now, until), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp),
        )
    }
    var picking by remember { mutableStateOf(false) }
    Row(
        // Mindestens 48 dp hoch (Tippziel).
        Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH).clickable(role = Role.Button) { picking = true }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.settings_evening_dark_from), style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Text(from, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
    if (picking) {
        EveningTimeDialog(
            setting.fromMinutes, onPick = { picking = false; onFrom(it) }, onDismiss = { picking = false },
        )
    }
}

/** Zeitwähler für den Beginn; «Speichern» übernimmt, Abbrechen lässt ihn, wie er war. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EveningTimeDialog(minutes: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val time = rememberTimePickerState(minutes / 60, minutes % 60, is24Hour = DateFormat.is24HourFormat(context))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_evening_dark_from)) },
        text = {
            TimePicker(
                state = time,
                colors = timePickerColors(),
            )
        },
        confirmButton = { TextButton(onClick = { onPick(time.hour * 60 + time.minute) }) {
            Text(stringResource(R.string.editor_save))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
