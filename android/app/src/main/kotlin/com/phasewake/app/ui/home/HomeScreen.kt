package com.phasewake.app.ui.home

import com.phasewake.app.core.Finding
import com.phasewake.app.core.FindingKind
import com.phasewake.app.core.MissHint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.CustomNote
import com.phasewake.app.core.Learning
import com.phasewake.app.core.WakePlan
import com.phasewake.app.core.ShortNight
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
import com.phasewake.app.ui.Format
import androidx.compose.ui.platform.LocalConfiguration
import com.phasewake.app.core.NightRecord
import com.phasewake.app.ui.components.AnimatedNight
import com.phasewake.app.ui.components.SkyMood
import com.phasewake.app.ui.theme.PhaseWakeTheme
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.delay

/** Startseite: nächstes Weckfenster gross, letzte Nacht, alle Wecker. */
@Composable
fun HomeScreen(
    state: HomeState,
    actions: HomeActions,
    contentPadding: PaddingValues,
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
    val view = rememberHomeView(state, extras, now)
    val list = @Composable { HomeList(state, actions, view, contentPadding, listState, extras) }
    if (view.dark) AnimatedNight(Modifier.fillMaxSize(), view.mood) { list() } else list()
}

/** Die Liste der Startseite: Kopf, Nacht-Check, Wecker, Abendkarten, Hinweise und letzte Nacht. */
@Composable
private fun HomeList(
    state: HomeState,
    actions: HomeActions,
    view: HomeView,
    contentPadding: PaddingValues,
    listState: LazyListState,
    extras: HomeExtras,
) {
    val alarms = state.alarms
    val plan = view.plan
    // Unten Platz für Navigationsleiste (in [contentPadding]) und den Knopf «Neuer Wecker»: Die letzte
    // Karte bleibt ganz sichtbar und liegt am Ende nie unter dem Knopf.
    val fab = alarms.isNotEmpty() && alarms.size < MAX_ALARMS
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(
            bottom = contentPadding.calculateBottomPadding() + if (fab) FAB_CLEARANCE else 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "hero") {
            Header(
                HeroState(
                    plan, plan?.let { p -> alarms.first { it.id == p.alarmId } }, view.holidayNote, view.autoOnset,
                    view.mood,
                ),
                view.now, view.zone, fullPage = view.dark,
                onAdd = actions.onAdd.takeIf { alarms.size < MAX_ALARMS }, onEdit = actions.onEdit,
            )
        }
        if (view.morningFirst) item(key = "night") { NightSection(state, actions, view) }
        // Nacht-Check nur, wenn ein Wecker ansteht – sonst gibt es nichts abzusichern.
        if (state.check != null && plan != null) {
            item(key = "check") {
                NightCheckCard(state.check, cardColor(), actions.onFix, extras.onTest, extras.testAt)
            }
        }
        // «Deine Wecker» gleich nach dem Nacht-Check, vor den Abendkarten; Reihenfolge wie iPhone.
        alarmItems(state, actions, view, extras)
        extras.onNap?.let { onNap ->
            item(key = "nap") { NapCard(extras.nap, extras.napMinutes, onNap, extras.onStopNap) }
        }
        // Am Abend bis zum Weckfenster: Notizen für die kommende Nacht.
        if (view.evening) eveningItems(state, actions, view)
        // Geräusche aufnehmen: am Abend, und solange die Aufnahme läuft auch danach (Stoppen, Restzeit), wie auf dem
        // iPhone.
        state.soundRec?.takeIf { view.evening || it.recording }?.let { rec ->
            item(key = "soundRec") {
                SoundRecordingCard(rec.enabled, rec.recording, rec.permissionDenied, cardColor(), actions.onSoundRec)
            }
        }
        actions.onSounds?.let { onSounds ->
            item(key = "sounds") { SoundsEntry(state.soundPlaying, view.now, onSounds) }
        }
        learningItems(extras)
        (if (view.advice != null) state.bedtimeFinding else state.finding)?.let { f ->
            item(key = "finding") { FindingCard(f) { actions.onHideFinding(f.kind) } }
        }
        if (state.lastNight != null && !view.morningFirst) item(key = "night") { NightSection(state, actions, view) }
        // Morgenbericht ohne aufgezeichnete Nacht (z. B. Uhr nicht getragen): nur die Geräusche.
        soundsMorningNight(state.soundClips, state.lastNight, state.soundRec, plan?.windowStart, view.now, view.zone)
            ?.let { night ->
                item(key = "soundsMorning") {
                    SoundsMorningCard(
                        SoundEvents.forNight(state.soundClips, night), state.soundFile, actions.onDeleteClip,
                    ) { actions.onDeleteNightClips(night) }
                }
            }
    }
}

/** Letzte Nacht bzw. Morgenbericht ([LastNightCard]); ohne Nacht nichts. */
@Composable
private fun NightSection(state: HomeState, actions: HomeActions, view: HomeView) {
    val lastNight = state.lastNight ?: return
    val date = SleepNotes.nightDate(lastNight, view.zone)
    LastNightCard(
        lastNight, view.now, state.missHint,
        onFeeling = { actions.onFeeling(lastNight, it) },
        extras = MorningExtras(
            notes = state.notes[date].orEmpty(),
            customNotes = state.customNotes,
            onToggleNote = { actions.onToggleNote(date, it) },
            clips = SoundEvents.forNight(state.soundClips, date),
            soundFile = state.soundFile,
            onDeleteClip = actions.onDeleteClip,
            onDeleteClips = { actions.onDeleteNightClips(date) },
        ),
    )
}

/** «Deine Wecker»: Überschrift, je Wecker eine Zeile und am Limit der Hinweis. */
private fun LazyListScope.alarmItems(state: HomeState, actions: HomeActions, view: HomeView, extras: HomeExtras) {
    val alarms = state.alarms
    if (alarms.isEmpty()) return
    val (now, zone, locale) = view
    item(key = "header") { AlarmsTitle() }
    items(alarms, key = { it.id }) { alarm ->
        AlarmRow(
            alarm, Modifier.animateItem(), onClick = { actions.onEdit(alarm.id) },
            onToggle = { actions.onToggle(alarm, it) },
            skip = AlarmSkip(
                skipped = AlarmSchedule.skippedDate(alarm, now, zone)?.let { Format.shortDay(it, locale) },
                onSkip = extras.onSkip?.let { skip -> { skip(alarm) } },
                onUndoSkip = { extras.onUndoSkip(alarm) },
                skipDate = AlarmSchedule.nextSkipDate(alarm, now, zone, extras.holidays, extras.doneUntil[alarm.id])
                    ?.let { Format.shortDay(it, locale) },
            ),
        )
    }
    if (alarms.size >= MAX_ALARMS) {
        item(key = "limit") { AlarmLimitHint() }
    }
}

/** Am Abend: empfohlene Schlafenszeit, Notizen und Abendroutine für die kommende Nacht. */
private fun LazyListScope.eveningItems(state: HomeState, actions: HomeActions, view: HomeView) {
    val tonight = SleepNotes.nightDate(view.now, view.zone)
    // Nach der Lernphase: empfohlene Schlafenszeit für den Wecker dieser Nacht.
    view.advice?.let { (from, until) ->
        item(key = "bedtimeAdvice") { BedtimeAdviceCard(from, until) }
    }
    item(key = "notes") {
        TonightNotesCard(
            state.notes[tonight].orEmpty(), state.customNotes, cardColor(),
            onToggle = { actions.onToggleNote(tonight, it) },
        )
    }
    if (state.routineItems.isNotEmpty()) {
        item(key = "routine") {
            RoutineCard(
                state.routineItems, state.routineTicks[tonight].orEmpty(), cardColor(),
                onToggle = { actions.onToggleRoutine(tonight, it) },
            )
        }
    }
}

/** Lernphase und «Nacht aufzeichnen». */
private fun LazyListScope.learningItems(extras: HomeExtras) {
    extras.learning?.let { learning ->
        item(key = "learning") { LearningCard(learning, extras.record, extras.onRecord) }
    }
    // Während der Lernphase steckt «Nacht aufzeichnen» in ihrer Karte, sonst eine eigene Karte. Solange
    // die Frage «Lernphase starten / Später» offen ist, auch keine – ausser es läuft schon eine Aufzeichnung
    // (Stopp).
    extras.record?.takeIf { record ->
        val learning = extras.learning
        learning?.inProgress != true && (learning?.onConsent == null || record.since != null)
    }?.let { record ->
        item(key = "record") { RecordCard(record.since, extras.onRecord) }
    }
}

/** Morgen nach [night]: geweckt heute (in [zone]), jetzt danach und vor 12 Uhr. */
internal fun isMorningOf(night: NightRecord, now: Instant, zone: ZoneId): Boolean {
    val woke = night.wokeAt.atZone(zone)
    val local = now.atZone(zone)
    return !now.isBefore(night.wokeAt) &&
        woke.toLocalDate() == local.toLocalDate() && local.hour < MORNING_FIRST_UNTIL_HOUR
}

/** Bis zu dieser Stunde (Ortszeit) steht der Morgenbericht zuerst. */
internal const val MORNING_FIRST_UNTIL_HOUR = 12

/** Höchstens so viele Wecker: Die Uhr führt ohnehin nur eine Nacht zugleich, und die Liste bleibt übersichtlich. */
internal const val MAX_ALARMS = 8

/**
 * Platz unter der letzten Karte für den Knopf «Neuer Wecker»: Knopfhöhe 56 dp + sein Abstand zur
 * Navigationsleiste 16 dp + 16 dp Luft.
 */
internal val FAB_CLEARANCE: Dp = 88.dp

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
private fun SoundsMorningCard(
    clips: List<SoundClip>,
    soundFile: (SoundClip) -> File?,
    onDeleteClip: (SoundClip) -> Unit,
    onDeleteClips: () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = cardColor()),
        border = cardEdge(),
        elevation = cardLift(),
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
