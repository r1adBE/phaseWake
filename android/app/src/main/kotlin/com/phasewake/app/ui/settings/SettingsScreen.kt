package com.phasewake.app.ui.settings

import com.phasewake.app.ui.components.PAGE_SIDE
import com.phasewake.app.ui.components.PageTitle
import com.phasewake.app.ui.components.SkyHeader
import com.phasewake.app.ui.components.SkyPage
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
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
import com.phasewake.app.ui.theme.EveningDarkSetting
import com.phasewake.app.ui.theme.ThemeMode

/**
 * Was die Einstellungen ([SettingsScreen]) zeigen.
 *
 * @property eveningDark «Abends dunkel ab»: an/aus und Beginn.
 * @property canExport Ohne Nächte gibt es nichts zu exportieren (nur eine Kopfzeile): dann ausgegraut.
 * @property nextAlarmNotice Leise Mitteilung «Nächster Wecker» auf dem Sperrbildschirm (Standard an).
 * @property autoOnset Einschlaf-Messung am Abend auf der Galaxy Watch (Standard an).
 */
data class SettingsState(
    val themeMode: ThemeMode,
    val version: String,
    val health: HealthState,
    val eveningDark: EveningDarkSetting = EveningDarkSetting(),
    val canExport: Boolean = true,
    val goalMinutes: Int = SleepGoal.DEFAULT_MINUTES,
    val bedtimeReminder: Boolean = false,
    val customNotes: List<CustomNote> = emptyList(),
    val weeklyReview: Boolean = false,
    val nextAlarmNotice: Boolean = true,
    val autoOnset: Boolean = true,
    val soundRecDefault: Boolean = false,
    val routineItems: List<RoutineItem> = emptyList(),
)

/** Aktionen der Einstellungen ([SettingsScreen]). */
data class SettingsActions(
    val onThemeMode: (ThemeMode) -> Unit,
    val onExport: () -> Unit,
    val onClearHistory: () -> Unit,
    val onConnectHealth: () -> Unit,
    val onEveningDark: (Boolean) -> Unit = {},
    val onEveningDarkFrom: (Int) -> Unit = {},
    val onGoal: (Int) -> Unit = {},
    val onBedtimeReminder: (Boolean) -> Unit = {},
    val onAddCustomNote: (String) -> Unit = {},
    val onRenameCustomNote: (String, String) -> Unit = { _, _ -> },
    val onDeleteCustomNote: (String) -> Unit = {},
    val onWeeklyReview: (Boolean) -> Unit = {},
    val onNextAlarmNotice: (Boolean) -> Unit = {},
    val onAutoOnset: (Boolean) -> Unit = {},
    val onSoundRecDefault: (Boolean) -> Unit = {},
    val onAddRoutineItem: (String) -> Unit = {},
    val onRemoveRoutineItem: (String) -> Unit = {},
)

/** Einstellungen: Darstellung, Schlafziel und Bettzeit-Erinnerung, eigene Notizen, Daten, Über. */
@Composable
fun SettingsScreen(
    state: SettingsState,
    actions: SettingsActions,
    contentPadding: PaddingValues,
    extras: SettingsExtras = SettingsExtras(),
) {
    // Himmel wie auf der Startseite; der Titel steht im Kopf, hell randlos im Himmel.
    SkyPage {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding)
            .padding(horizontal = PAGE_SIDE),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SkyHeader(bleedSide = PAGE_SIDE, bleedTop = contentPadding.calculateTopPadding()) {
            PageTitle(stringResource(R.string.settings_title))
        }

        ThemeSection(state.themeMode, state.eveningDark, actions)

        GoalSection(state, actions)

        // Sperrbildschirm und Einschlaferkennung gehören zum Wecker, nicht zum Schlafziel.
        Group(stringResource(R.string.nav_alarms)) {
            SwitchRow(
                stringResource(R.string.next_alarm_notif_title), stringResource(R.string.next_alarm_notif_hint),
                state.nextAlarmNotice, actions.onNextAlarmNotice,
            )
            SwitchRow(
                stringResource(R.string.auto_onset_title), stringResource(R.string.auto_onset_hint), state.autoOnset,
                actions.onAutoOnset,
            )
        }

        extras.learning?.let { LearningGroup(it) }

        extras.onTest?.let { TestGroup(it) }

        extras.holidays?.let { HolidaysGroup(it) }

        CustomNotesGroup(
            state.customNotes, actions.onAddCustomNote, actions.onRenameCustomNote, actions.onDeleteCustomNote,
        )

        RoutineGroup(state.routineItems, actions.onAddRoutineItem, actions.onRemoveRoutineItem)

        Group(stringResource(R.string.sound_rec_title)) {
            SwitchRow(
                stringResource(R.string.sound_rec_default), stringResource(R.string.sound_rec_default_hint),
                state.soundRecDefault, actions.onSoundRecDefault,
            )
        }

        DataGroup(actions.onExport, state.canExport, actions.onClearHistory, extras)

        HealthSection(state.health, actions.onConnectHealth)

        AboutGroup(state.version, extras)
    }
    }
}

/** Darstellung: System, Hell oder Dunkel, dazu «Abends dunkel ab». */
@Composable
private fun ThemeSection(themeMode: ThemeMode, eveningDark: EveningDarkSetting, actions: SettingsActions) {
    Group(stringResource(R.string.settings_theme)) {
        val modes = listOf(
            ThemeMode.SYSTEM to R.string.settings_theme_system,
            ThemeMode.LIGHT to R.string.settings_theme_light,
            ThemeMode.DARK to R.string.settings_theme_dark,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Max)) {
            modes.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = themeMode == mode,
                    onClick = { actions.onThemeMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    modifier = Modifier.heightIn(min = 48.dp).fillMaxHeight(),
                ) { Text(stringResource(label)) }
            }
        }
        // Abends dunkel: nur bei «System» und «Hell» wirksam (Dunkel bleibt dunkel).
        if (themeMode != ThemeMode.DARK) EveningDarkRows(eveningDark, actions.onEveningDark, actions.onEveningDarkFrom)
    }
}

/** Schlafziel, Bettzeit-Erinnerung und Wochenrückblick. */
@Composable
private fun GoalSection(state: SettingsState, actions: SettingsActions) {
    Group(stringResource(R.string.goal_title)) {
        val context = LocalContext.current
        // Beim Ziehen nur anzeigen, gespeichert (und die Erinnerung neu geplant) beim Loslassen.
        var dragging by remember(state.goalMinutes) { mutableFloatStateOf(state.goalMinutes.toFloat()) }
        Text(
            stringResource(
                R.string.goal_value, Format.duration(context, Duration.ofMinutes(dragging.roundToInt().toLong())),
            ),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Slider(
            value = dragging,
            onValueChange = { dragging = SleepGoal.clamp(it.roundToInt()).toFloat() },
            onValueChangeFinished = { actions.onGoal(dragging.roundToInt()) },
            valueRange = SleepGoal.MIN_MINUTES.toFloat()..SleepGoal.MAX_MINUTES.toFloat(),
            steps = (SleepGoal.MAX_MINUTES - SleepGoal.MIN_MINUTES) / SleepGoal.STEP_MINUTES - 1,
        )
        Text(
            stringResource(R.string.goal_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH).toggleable(
                value = state.bedtimeReminder, role = Role.Switch, onValueChange = actions.onBedtimeReminder,
            ).padding(top = 8.dp),
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
            Switch(checked = state.bedtimeReminder, onCheckedChange = null)
        }
        SwitchRow(
            stringResource(R.string.weekly_title), stringResource(R.string.weekly_toggle), state.weeklyReview,
            actions.onWeeklyReview,
        )
    }
}

/** Health Connect: Hinweis und je nach Stand Verbinden, Aktualisieren oder «Verbunden». */
@Composable
private fun HealthSection(health: HealthState, onConnectHealth: () -> Unit) {
    val uri = LocalUriHandler.current
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
            HealthState.AVAILABLE -> Button(onClick = onConnectHealth) {
                Text(stringResource(R.string.health_connect))
            }
            HealthState.UPDATE_REQUIRED -> Button(onClick = { uri.openUri(HEALTH_CONNECT_STORE) }) {
                Text(stringResource(R.string.health_update))
            }
        }
    }
}

/** Health Connect: fehlt, muss aktualisiert werden, ist bereit oder schon verbunden. */
enum class HealthState { UNAVAILABLE, UPDATE_REQUIRED, AVAILABLE, CONNECTED }

private const val HEALTH_CONNECT_STORE =
    "https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"
internal const val PRIVACY_URL = "https://github.com/r1adBE/phaseWake/blob/main/docs/DATENSCHUTZ.md"
internal const val REPO_URL = "https://github.com/r1adBE/phaseWake"

