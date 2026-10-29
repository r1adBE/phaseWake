package com.phasewake.app.wear.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CheckboxButton
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Slider
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimePicker
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Codec
import com.phasewake.app.core.DayFormat
import com.phasewake.app.wear.R
import com.phasewake.app.wear.ui.theme.Brand
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID

/** Höchstens so viele Wecker, wie am Handy (Handy und iPhone verhindern mehr). */
internal const val MAX_ALARMS = 8

/** Weckton neuer Wecker wie am Handy («Morgenvögel», `WakeTones.DEFAULT`); die Uhr selbst spielt ihn nicht. */
private const val DEFAULT_SOUND = "raw:wake_birds"

private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

/** Neuer Wecker mit denselben Vorgaben wie im Editor am Handy: 7:00, Werktage, Fenster 30 Min. */
internal fun newAlarm(): AlarmSpec = AlarmSpec(id = UUID.randomUUID().toString(), hour = 7, minute = 0, days = WEEKDAYS, sound = DEFAULT_SOUND)

/**
 * Übernimmt aus dem Entwurf nur, was die Uhr bearbeitet (Zeit, Fenster, Tage, Ein/Aus, Überspringen); alles andere
 * (Sonnenaufgang, Töne, Handy-Wecker, Aufgabe, Schlafdauer, Name) bleibt wie im gespeicherten Wecker. Überspringen
 * bestimmt [skip] aus gespeichertem und bearbeitetem Stand (der Entwurf trägt den Schalter): Ohne Angabe gilt
 * [AlarmSchedule.keepSkip]; beim Speichern [AlarmSchedule.editedSkip], damit ein bewusst eingeschalteter Schalter
 * auch nach einer geänderten Zeit gilt.
 */
internal fun mergeEdit(
    stored: AlarmSpec?,
    draft: AlarmSpec,
    skip: (old: AlarmSpec, edited: AlarmSpec) -> Long = AlarmSchedule::keepSkip,
): AlarmSpec {
    stored ?: return draft
    val edited = stored.copy(
        hour = draft.hour, minute = draft.minute, windowMinutes = draft.windowMinutes, days = draft.days, enabled = draft.enabled,
        skipUntil = draft.skipUntil,
    )
    return edited.copy(skipUntil = skip(stored, edited))
}

/** Entwurf über das Format der Wecker sichern, damit er eine Drehung oder ein Ende des Prozesses übersteht. */
internal val draftSaver = androidx.compose.runtime.saveable.Saver<AlarmSpec?, String>(
    save = { spec -> spec?.let { Codec.encodeAlarms(listOf(it)) } },
    restore = { text -> runCatching { Codec.decodeAlarms(text).firstOrNull() }.getOrNull() },
)

/** Wochentage ab dem Wochenbeginn der Region (z. B. Sonntag in den USA). */
private fun weekOrder(locale: Locale): List<DayOfWeek> {
    val first = WeekFields.of(locale).firstDayOfWeek
    return (0L..6L).map { first.plus(it) }
}

/** Editor ohne eigenen Zustand; so auch für Vorschau und Bildschirmfotos nutzbar. */
@Composable
internal fun EditorContent(
    draft: AlarmSpec,
    isNew: Boolean,
    onPickTime: () -> Unit,
    onChange: (AlarmSpec) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    /** Datum des übersprungenen Termins (formatiert), `null` = keiner. */
    skipped: String? = null,
    /** «Nächstes Mal überspringen» ein/aus; `null` = kein Schalter (neuer oder einmaliger Wecker). */
    onSkip: ((Boolean) -> Unit)? = null,
) {
    val listState = rememberScalingLazyListState()
    val locale = LocalConfiguration.current.locales[0]
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val time = LocalTime.of(draft.hour, draft.minute)
    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(onClick = onSave) { Text(stringResource(R.string.editor_save)) }
        },
    ) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text(stringResource(if (isNew) R.string.editor_new else R.string.editor_edit)) } }
            item {
                // Bei Schlafdauer ist die Uhrzeit die späteste Weckzeit.
                Button(
                    onClick = onPickTime,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(time), style = MaterialTheme.typography.titleLarge) },
                    secondaryLabel = {
                        Text(stringResource(if (draft.sleepDurationMinutes != null) R.string.editor_latest else R.string.editor_target))
                    },
                )
            }
            item {
                Text(
                    stringResource(R.string.editor_window, draft.windowMinutes),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            item {
                Slider(
                    value = draft.windowMinutes,
                    onValueChange = { onChange(draft.copy(windowMinutes = it)) },
                    valueProgression = AlarmSpec.MIN_WINDOW_MINUTES..AlarmSpec.MAX_WINDOW_MINUTES step WINDOW_STEP,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { ListHeader { Text(stringResource(R.string.editor_days)) } }
            item {
                // Ohne Tag gilt der Wecker einmal.
                Text(
                    DayFormat.format(draft.days, locale, stringResource(R.string.alarm_once), stringResource(R.string.alarm_every_day)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                )
            }
            weekOrder(locale).forEach { day ->
                item(key = day.name) {
                    CheckboxButton(
                        checked = day in draft.days,
                        onCheckedChange = { on -> onChange(draft.copy(days = if (on) draft.days + day else draft.days - day)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(day.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.titlecase(locale) }) },
                    )
                }
            }
            item {
                SwitchButton(
                    checked = draft.enabled,
                    onCheckedChange = { onChange(draft.copy(enabled = it)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text(stringResource(R.string.editor_enabled)) },
                )
            }
            if (onSkip != null) {
                item {
                    SwitchButton(
                        checked = skipped != null,
                        onCheckedChange = onSkip,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.alarm_skip)) },
                        secondaryLabel = if (skipped != null) {
                            { Text(stringResource(R.string.alarm_skipped, skipped)) }
                        } else {
                            null
                        },
                    )
                }
            }
            if (!isNew) {
                item {
                    Button(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        label = { Text(stringResource(R.string.editor_delete)) },
                    )
                }
            }
        }
    }
    AlertDialog(
        visible = confirmDelete,
        onDismissRequest = { confirmDelete = false },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(onClick = {
                confirmDelete = false
                onDelete()
            })
        },
        title = { Text(stringResource(R.string.editor_delete_title)) },
    )
}

/** Uhrzeit wählen; die Krone bzw. der Drehrahmen verstellt die gewählte Spalte. */
@Composable
internal fun EditorTime(draft: AlarmSpec, onPicked: (LocalTime) -> Unit) {
    TimePicker(initialTime = LocalTime.of(draft.hour, draft.minute), onTimePicked = onPicked)
}

private const val WINDOW_STEP = 5
