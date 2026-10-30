package com.phasewake.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.Bedtime
import com.phasewake.app.core.CustomNote
import com.phasewake.app.core.SleepGoal
import com.phasewake.app.core.SleepNotes
import com.phasewake.app.core.Routine
import com.phasewake.app.core.RoutineItem
import com.phasewake.app.ui.home.routineName
import com.phasewake.app.ui.Format
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import java.time.Duration
import kotlin.math.roundToInt
import com.phasewake.app.ui.theme.ThemeMode

/** Einstellungen: Darstellung, Schlafziel und Bettzeit-Erinnerung, eigene Notizen, Daten, Über. */
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    version: String,
    contentPadding: PaddingValues,
    onThemeMode: (ThemeMode) -> Unit,
    onExport: () -> Unit,
    /** Ohne Nächte gibt es nichts zu exportieren (nur eine Kopfzeile): dann ausgegraut. */
    canExport: Boolean = true,
    onClearHistory: () -> Unit,
    health: HealthState,
    onConnectHealth: () -> Unit,
    goalMinutes: Int = SleepGoal.DEFAULT_MINUTES,
    onGoal: (Int) -> Unit = {},
    bedtimeReminder: Boolean = false,
    onBedtimeReminder: (Boolean) -> Unit = {},
    customNotes: List<CustomNote> = emptyList(),
    onAddCustomNote: (String) -> Unit = {},
    onRenameCustomNote: (String, String) -> Unit = { _, _ -> },
    onDeleteCustomNote: (String) -> Unit = {},
    weeklyReview: Boolean = false,
    onWeeklyReview: (Boolean) -> Unit = {},
    /** Leise Mitteilung «Nächster Wecker» auf dem Sperrbildschirm (Standard an). */
    nextAlarmNotice: Boolean = true,
    onNextAlarmNotice: (Boolean) -> Unit = {},
    soundRecDefault: Boolean = false,
    onSoundRecDefault: (Boolean) -> Unit = {},
    routineItems: List<RoutineItem> = emptyList(),
    onAddRoutineItem: (String) -> Unit = {},
    onRemoveRoutineItem: (String) -> Unit = {},
    extras: SettingsExtras = SettingsExtras(),
) {
    val uri = LocalUriHandler.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))

        Group(stringResource(R.string.settings_theme)) {
            val modes = listOf(
                ThemeMode.SYSTEM to R.string.settings_theme_system,
                ThemeMode.LIGHT to R.string.settings_theme_light,
                ThemeMode.DARK to R.string.settings_theme_dark,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = themeMode == mode,
                        onClick = { onThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(label)) }
                }
            }
        }

        Group(stringResource(R.string.goal_title)) {
            val context = LocalContext.current
            // Beim Ziehen nur anzeigen, gespeichert (und die Erinnerung neu geplant) beim Loslassen.
            var dragging by remember(goalMinutes) { mutableFloatStateOf(goalMinutes.toFloat()) }
            Text(
                stringResource(R.string.goal_value, Format.duration(context, Duration.ofMinutes(dragging.roundToInt().toLong()))),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Slider(
                value = dragging,
                onValueChange = { dragging = SleepGoal.clamp(it.roundToInt()).toFloat() },
                onValueChangeFinished = { onGoal(dragging.roundToInt()) },
                valueRange = SleepGoal.MIN_MINUTES.toFloat()..SleepGoal.MAX_MINUTES.toFloat(),
                steps = (SleepGoal.MAX_MINUTES - SleepGoal.MIN_MINUTES) / SleepGoal.STEP_MINUTES - 1,
            )
            Text(stringResource(R.string.goal_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                Modifier.fillMaxWidth().toggleable(value = bedtimeReminder, role = Role.Switch, onValueChange = onBedtimeReminder).padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.bedtime_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.bedtime_text, Bedtime.LEAD_MINUTES),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Die ganze Zeile schaltet; der Schalter zeigt nur den Zustand.
                Switch(checked = bedtimeReminder, onCheckedChange = null)
            }
            SwitchRow(stringResource(R.string.weekly_title), stringResource(R.string.weekly_toggle), weeklyReview, onWeeklyReview)
            SwitchRow(stringResource(R.string.next_alarm_notif_title), stringResource(R.string.next_alarm_notif_hint), nextAlarmNotice, onNextAlarmNotice)
        }

        extras.onTest?.let { TestGroup(it) }

        extras.holidays?.let { HolidaysGroup(it) }

        CustomNotesGroup(customNotes, onAddCustomNote, onRenameCustomNote, onDeleteCustomNote)

        RoutineGroup(routineItems, onAddRoutineItem, onRemoveRoutineItem)

        Group(stringResource(R.string.sound_rec_title)) {
            SwitchRow(stringResource(R.string.sound_rec_default), stringResource(R.string.sound_rec_default_hint), soundRecDefault, onSoundRecDefault)
        }

        DataGroup(onExport, canExport, onClearHistory, extras)

        Group(stringResource(R.string.health_connect)) {
            Text(stringResource(R.string.health_connect_hint), style = MaterialTheme.typography.bodyMedium)
            when (health) {
                HealthState.UNAVAILABLE -> Text(
                    stringResource(R.string.health_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HealthState.CONNECTED -> Text(
                    stringResource(R.string.health_connected),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                HealthState.AVAILABLE -> Button(onClick = onConnectHealth) { Text(stringResource(R.string.health_connect)) }
                HealthState.UPDATE_REQUIRED -> Button(onClick = { uri.openUri(HEALTH_CONNECT_STORE) }) { Text(stringResource(R.string.health_update)) }
            }
        }

        AboutGroup(version, extras)
    }
}

/** Health Connect: fehlt, muss aktualisiert werden, ist bereit oder schon verbunden. */
enum class HealthState { UNAVAILABLE, UPDATE_REQUIRED, AVAILABLE, CONNECTED }

private const val HEALTH_CONNECT_STORE = "https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"
internal const val SPONSOR_URL = "https://github.com/sponsors/r1adBE"
internal const val PRIVACY_URL = "https://github.com/r1adBE/phaseWake/blob/main/docs/DATENSCHUTZ.md"
internal const val REPO_URL = "https://github.com/r1adBE/phaseWake"

