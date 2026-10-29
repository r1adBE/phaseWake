package com.phasewake.app.ui.home

import com.phasewake.app.core.MissHint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.CustomNote
import com.phasewake.app.core.SleepNotes
import com.phasewake.app.sounds.SleepSoundService
import com.phasewake.app.ui.notes.TonightNotesCard
import com.phasewake.app.core.RoutineItem
import com.phasewake.app.core.SoundClip
import com.phasewake.app.core.SoundEvents
import com.phasewake.app.ui.sounds.SoundClipsSection
import com.phasewake.app.ui.sounds.SoundRecordingCard
import java.io.File
import java.time.LocalDate
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Transient
import com.phasewake.app.core.TransientPlan
import com.phasewake.app.ui.Format
import androidx.compose.ui.platform.LocalConfiguration
import com.phasewake.app.core.NightRecord
import com.phasewake.app.ui.components.AnimatedNight
import com.phasewake.app.ui.theme.PhaseWakeTheme
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay

/** Startseite: nächstes Weckfenster gross, letzte Nacht, alle Wecker. */
@Composable
fun HomeScreen(
    alarms: List<AlarmSpec>,
    lastNight: NightRecord?,
    check: NightCheckState?,
    onFix: (Check) -> Unit,
    contentPadding: PaddingValues,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onToggle: (AlarmSpec, Boolean) -> Unit,
    onFeeling: (NightRecord, Int) -> Unit = { _, _ -> },
    missHint: MissHint? = null,
    /** Schlafnotizen je Nachtdatum und die eigenen Notizen. */
    notes: Map<LocalDate, List<String>> = emptyMap(),
    customNotes: List<CustomNote> = emptyList(),
    onToggleNote: (LocalDate, String) -> Unit = { _, _ -> },
    /** Laufende Einschlafklänge; [onSounds] `null`: kein Einstieg (Bildschirmfotos). */
    soundPlaying: SleepSoundService.Playing? = null,
    onSounds: (() -> Unit)? = null,
    /** «Geräusche aufnehmen» für diese Nacht; `null`: kein Schalter (Bildschirmfotos). */
    soundRec: SoundRecState? = null,
    onSoundRec: (Boolean) -> Unit = {},
    /** Aufgenommene Geräusche (alle Nächte) für den Morgenbericht. */
    soundClips: List<SoundClip> = emptyList(),
    soundFile: (SoundClip) -> File? = { null },
    onDeleteClip: (SoundClip) -> Unit = {},
    onDeleteNightClips: (LocalDate) -> Unit = {},
    /** Abendroutine: Punkte und Haken je Nachtdatum. */
    routineItems: List<RoutineItem> = emptyList(),
    routineTicks: Map<LocalDate, List<String>> = emptyMap(),
    onToggleRoutine: (LocalDate, String) -> Unit = { _, _ -> },
    /** Uhrzeit-Quelle; Bildschirmfotos setzen eine feste Zeit, damit sie nicht vom Tag abhängen. */
    clock: () -> Instant = Instant::now,
    /** Scrollstand der Liste; die App liest ihn, um «Neuer Wecker» beim Scrollen einzuklappen. */
    listState: LazyListState = rememberLazyListState(),
    /** Probewecker, Powernap, Überspringen und Feiertage; ohne: nichts davon (Bildschirmfotos). */
    extras: HomeExtras = HomeExtras(),
) {
    var now by remember { mutableStateOf(clock()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            now = clock()
        }
    }
    val zone = ZoneId.systemDefault()
    val plan = remember(alarms, now, extras.holidays) { AlarmSchedule.earliestPlan(alarms, now, zone, extras.holidays) }
    val locale = LocalConfiguration.current.locales[0]
    // Feiertag, an dem ein Wecker sonst als Nächstes geweckt hätte (nur bis zum nächsten Weckfenster).
    val holidayNote = remember(alarms, now, extras.holidays, plan) {
        alarms.mapNotNull { AlarmSchedule.skippedHoliday(it, now, zone, extras.holidays) }.minOrNull()
            ?.takeIf { day -> plan == null || !day.isAfter(plan.target.atZone(zone).toLocalDate()) }
            ?.let { Format.shortDay(it, locale) }
    }

    // Dunkel: die ganze Seite ist Nachthimmel. Hell: nur der Kopfbereich, darunter helle Karten.
    val dark = PhaseWakeTheme.isDark
    val list = @Composable {
    // Unten Platz für Navigationsleiste (in [contentPadding]) und den Knopf «Neuer Wecker»: Die letzte
    // Karte bleibt ganz sichtbar und liegt am Ende nie unter dem Knopf.
    val fab = alarms.isNotEmpty() && alarms.size < MAX_ALARMS
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + if (fab) FAB_CLEARANCE else 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "hero") {
            Header(
                plan, plan?.let { p -> alarms.first { it.id == p.alarmId } }, now, zone, fullPage = dark,
                onAdd = onAdd.takeIf { alarms.size < MAX_ALARMS }, onEdit = onEdit, holidayNote = holidayNote,
            )
        }
        // Nacht-Check nur, wenn ein Wecker ansteht – sonst gibt es nichts abzusichern.
        if (check != null && plan != null) {
            item(key = "check") { NightCheckCard(check, cardColor(), onFix, extras.onTest, extras.testAt) }
        }
        // Am Abend bis zum Weckfenster: Notizen für die kommende Nacht.
        val evening = SleepNotes.entryVisible(now, zone, plan?.windowStart)
        if (evening) {
            val tonight = SleepNotes.nightDate(now, zone)
            item(key = "notes") {
                TonightNotesCard(notes[tonight].orEmpty(), customNotes, cardColor(), onToggle = { onToggleNote(tonight, it) })
            }
            if (routineItems.isNotEmpty()) {
                item(key = "routine") {
                    RoutineCard(routineItems, routineTicks[tonight].orEmpty(), cardColor(), onToggle = { onToggleRoutine(tonight, it) })
                }
            }
        }
        // Geräusche aufnehmen: am Abend, und solange die Aufnahme läuft auch danach (Stoppen, Restzeit), wie auf dem iPhone.
        soundRec?.takeIf { evening || it.recording }?.let { rec ->
            item(key = "soundRec") { SoundRecordingCard(rec.enabled, rec.recording, rec.permissionDenied, cardColor(), onSoundRec) }
        }
        if (onSounds != null) {
            item(key = "sounds") { SoundsEntry(soundPlaying, now, onSounds) }
        }
        extras.onNap?.let { onNap ->
            item(key = "nap") { NapCard(extras.nap, extras.napMinutes, onNap, extras.onStopNap) }
        }
        if (lastNight != null) {
            val date = SleepNotes.nightDate(lastNight, zone)
            item(key = "night") {
                LastNightCard(
                    lastNight, now, missHint,
                    onFeeling = { onFeeling(lastNight, it) },
                    notes = notes[date].orEmpty(),
                    customNotes = customNotes,
                    onToggleNote = { onToggleNote(date, it) },
                    clips = SoundEvents.forNight(soundClips, date),
                    soundFile = soundFile,
                    onDeleteClip = onDeleteClip,
                    onDeleteClips = { onDeleteNightClips(date) },
                )
            }
        }
        // Morgenbericht ohne aufgezeichnete Nacht (z. B. Uhr nicht getragen): nur die Geräusche.
        soundsMorningNight(soundClips, lastNight, soundRec, plan?.windowStart, now, zone)?.let { night ->
            item(key = "soundsMorning") { SoundsMorningCard(SoundEvents.forNight(soundClips, night), soundFile, onDeleteClip) { onDeleteNightClips(night) } }
        }
        if (alarms.isNotEmpty()) {
            item(key = "header") { AlarmsTitle() }
            items(alarms, key = { it.id }) { alarm ->
                AlarmRow(
                    alarm, Modifier.animateItem(), onClick = { onEdit(alarm.id) }, onToggle = { onToggle(alarm, it) },
                    skipped = AlarmSchedule.skippedDate(alarm, now, zone)?.let { Format.shortDay(it, locale) },
                    onSkip = extras.onSkip?.let { skip -> { skip(alarm) } },
                    onUndoSkip = { extras.onUndoSkip(alarm) },
                )
            }
            if (alarms.size >= MAX_ALARMS) {
                item(key = "limit") { AlarmLimitHint() }
            }
        }
    }
    }
    if (dark) AnimatedNight(Modifier.fillMaxSize()) { list() } else list()
}

/** Neues der Startseite (Runde 3); Vorgaben = nichts davon, wie in den Bildschirmfotos. */
class HomeExtras(
    /** Feiertage für Wecker mit «An Feiertagen nicht wecken». */
    val holidays: Set<LocalDate> = emptySet(),
    /** Laufender Powernap; `null` = keiner. */
    val nap: TransientPlan? = null,
    /** Vorschlag der Dauer in Minuten. */
    val napMinutes: Int = Transient.NAP_DEFAULT_MINUTES,
    /** Powernap starten (Minuten); `null` = keine Karte. */
    val onNap: ((Int) -> Unit)? = null,
    val onStopNap: (String) -> Unit = {},
    /** «Nächstes Mal überspringen»; `null` = kein Menü. */
    val onSkip: ((AlarmSpec) -> Unit)? = null,
    val onUndoSkip: (AlarmSpec) -> Unit = {},
    /** «In 2 Minuten testen» auf dem Nacht-Check; `null` = kein Knopf. */
    val onTest: (() -> Unit)? = null,
    /** Zielzeit eines laufenden Probeweckers; `null` = keiner. */
    val testAt: Instant? = null,
)

/** Höchstens so viele Wecker: Die Uhr führt ohnehin nur eine Nacht zugleich, und die Liste bleibt übersichtlich. */
internal const val MAX_ALARMS = 8

/**
 * Platz unter der letzten Karte für den Knopf «Neuer Wecker»: Knopfhöhe 56 dp + sein Abstand zur
 * Navigationsleiste 16 dp + 16 dp Luft.
 */
internal val FAB_CLEARANCE: Dp = 88.dp

/** Kartenfarbe: auf dem Nachthimmel Weiss mit 6 %, hell eine weisse Fläche. */
@Composable
internal fun cardColor(): Color = PhaseWakeTheme.brand.card

/** Schalter «Geräusche aufnehmen» für diese Nacht. */
data class SoundRecState(val enabled: Boolean, val recording: Boolean, val permissionDenied: Boolean)

/** So lange nach dem Wecken gilt die letzte Nacht als «Dein Morgen». */
internal val MORNING: Duration = Duration.ofHours(12)

/**
 * Nachtdatum für den Morgenbericht ohne aufgezeichnete Nacht: das jüngste mit Geräuschen, solange es
 * «Morgen» ist ([MORNING]). Nicht während der Aufnahme und nicht für die Nacht, die gerade läuft. Sonst `null`.
 */
private fun soundsMorningNight(
    clips: List<SoundClip>,
    lastNight: NightRecord?,
    soundRec: SoundRecState?,
    windowStart: Instant?,
    now: Instant,
    zone: ZoneId,
): LocalDate? = SoundEvents.nightsWithoutRecord(clips, listOfNotNull(lastNight?.let { SleepNotes.nightDate(it, zone) }))
    .firstOrNull()
    ?.takeIf { (night, last) ->
        Duration.between(last, now) < MORNING && soundRec?.recording != true &&
            !(SleepNotes.entryVisible(now, zone, windowStart) && SleepNotes.nightDate(now, zone) == night)
    }
    ?.first

/** Morgenbericht nur mit den Geräuschen einer Nacht. */
@Composable
private fun SoundsMorningCard(clips: List<SoundClip>, soundFile: (SoundClip) -> File?, onDeleteClip: (SoundClip) -> Unit, onDeleteClips: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = cardColor()),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.home_morning), style = MaterialTheme.typography.titleMedium)
            SoundClipsSection(clips, soundFile, onDeleteClip, onDeleteClips)
        }
    }
}

/** Überschrift der Wecker-Liste. */
@Composable
private fun AlarmsTitle() {
    Text(
        stringResource(R.string.home_alarms),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 20.dp, top = 8.dp),
    )
}

/** Am Limit fehlt der Knopf «Neuer Wecker»; hier steht, warum. */
@Composable
private fun AlarmLimitHint() {
    Text(
        stringResource(R.string.home_max_alarms, MAX_ALARMS),
        style = MaterialTheme.typography.bodySmall,
        color = PhaseWakeTheme.brand.mutedSoft,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
}
