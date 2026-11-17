package com.phasewake.app.ui.history

import com.phasewake.app.ui.components.skyCardColors
import com.phasewake.app.ui.components.skyCardEdge
import com.phasewake.app.ui.components.skyCardLift
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import com.phasewake.app.ui.components.PAGE_SIDE
import com.phasewake.app.ui.components.PageTitle
import com.phasewake.app.ui.components.SkyHeader
import com.phasewake.app.ui.components.SkyPage
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.phasewake.app.core.OverviewUnit
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.CustomNote
import com.phasewake.app.core.HealthHistory
import com.phasewake.app.core.HistoryItem
import com.phasewake.app.core.HistoryKind
import com.phasewake.app.core.NightHistory
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.NoteEffect
import com.phasewake.app.core.SleepGoal
import com.phasewake.app.core.SleepNotes
import com.phasewake.app.ui.notes.NightNotes
import com.phasewake.app.ui.notes.NoteEffectsCard
import com.phasewake.app.core.SoundClip
import com.phasewake.app.core.SoundEvents
import com.phasewake.app.core.WeeklySummary
import com.phasewake.app.core.Finding
import com.phasewake.app.ui.sounds.SoundClipsSection
import com.phasewake.app.ui.sounds.soundSummary
import androidx.compose.runtime.remember
import java.io.File
import java.time.LocalDate
import com.phasewake.app.core.StageSpan
import com.phasewake.app.core.Trends
import com.phasewake.app.core.WakeReason
import com.phasewake.app.shared.NightRecording
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.components.NightBars
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/** Text zum Weckgrund, gleich in Verlauf, Startseite und Diagrammen. */
fun reasonRes(reason: WakeReason): Int = when (reason) {
    WakeReason.LIGHT_SLEEP -> R.string.reason_light
    WakeReason.AWAKE -> R.string.reason_awake
    WakeReason.DEADLINE -> R.string.reason_deadline
    WakeReason.EXACT -> R.string.reason_exact
    WakeReason.SHORT_NIGHT -> R.string.reason_short_night
}

/**
 * «12 Min vor der Zielzeit · Grund», «Zur Zielzeit · Grund» oder «N Min nach der Zielzeit · Grund». Bei «Genau» und
 * kurzer Nacht zur Zielzeit nur der Grund: Er nennt die Zielzeit schon.
 */
fun wakeTimingText(context: Context, minutesBefore: Int, minutesAfter: Int, reason: WakeReason): String {
    val text = context.getString(reasonRes(reason))
    return when {
        minutesBefore > 0 -> context.getString(R.string.woke_early, minutesBefore, text)
        // Nach Schlummern über die Zielzeit hinaus: ehrlich als Verspätung, nicht als «zur Zielzeit».
        minutesAfter > 0 -> context.resources.getQuantityString(R.plurals.woke_late, minutesAfter, minutesAfter, text)
        reason == WakeReason.EXACT || reason == WakeReason.SHORT_NIGHT -> text
        else -> context.getString(R.string.woke_on_time, text)
    }
}

/** «12 Min vor der Zielzeit · Unruhig – wohl leichter Schlaf · ohne Schlummern» */
fun nightSummary(context: Context, night: NightRecord): String {
    val timing =
        wakeTimingText(context, night.minutesBeforeTarget.toInt(), night.minutesAfterTarget.toInt(), night.reason)
    val snooze = if (night.snoozes == 0) context.getString(R.string.no_snooze) else context.getString(
        R.string.snoozed_times, night.snoozes,
    )
    return "$timing · $snooze"
}

/** Verlauf: Kennzahlen und jede Nacht mit ihrer Bewegungskurve, neueste zuerst. */
@Composable
fun HistoryScreen(
    state: HistoryState,
    contentPadding: PaddingValues,
    actions: HistoryActions = HistoryActions(),
) {
    val model = rememberHistoryModel(state)
    val history = model.history
    // Eine Auswahl «Tage / Wochen / Monate» für Übersicht und Diagramme; gemerkt über Drehen und Zurückkehren.
    var unit by rememberSaveable { mutableStateOf(OverviewUnit.DAYS) }
    // Himmel wie auf der Startseite; der Rand liegt im Innenabstand, damit der Kopf randlos sein kann.
    val direction = LocalLayoutDirection.current
    val padding = PaddingValues(
        start = contentPadding.calculateStartPadding(direction) + PAGE_SIDE,
        top = contentPadding.calculateTopPadding(),
        end = contentPadding.calculateEndPadding(direction) + PAGE_SIDE,
        bottom = contentPadding.calculateBottomPadding(),
    )
    val charts = state.charts
    SkyPage {
    LazyColumn(
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "title") {
            SkyHeader(bleedSide = PAGE_SIDE, bleedTop = contentPadding.calculateTopPadding()) {
                PageTitle(stringResource(R.string.history_title))
            }
        }
        // Diagramme zuoberst, auch ohne Uhr-Nächte (Schlaf aus der Gesundheits-App).
        // Blase in einem Diagramm antippen: die Nacht dieses Nachtdatums, falls sie im Verlauf steht.
        val opener: (LocalDate) -> (() -> Unit)? =
            { date -> HealthHistory.itemFor(history, date)?.let { item -> { actions.onOpenNight(item.key) } } }
        // Oben die eine Auswahl des Zeitraums, darunter die Übersicht und die Diagramme zum Blättern.
        charts?.takeIf { it.hasPeriods }?.let { item(key = "period") { OverviewSwitch(unit, { unit = it }) } }
        charts?.overview?.takeIf { it.isNotEmpty() }?.let { item(key = "overview") { OverviewCard(it, unit, opener) } }
        charts?.takeIf { !it.isEmpty }?.let {
            item(key = "charts") {
                HistoryChartsSection(it, state.customNotes, opener = opener, nights = periodNights(unit))
            }
        }
        if (history.isEmpty() && state.recordings.isEmpty()) {
            emptyItems(state, actions, model)
            return@LazyColumn
        }
        summaryItems(state, actions)
        items(model.entries, key = { it.key }) { entry ->
            EntrySection(entry, state, actions, model, Modifier.animateItem())
        }
    }
    }
}

/** Leerer Verlauf: Hinweis (mit Health Connect) und Geräusche ohne Nacht. */
private fun LazyListScope.emptyItems(state: HistoryState, actions: HistoryActions, model: HistoryModel) {
    item(key = "empty") {
        HistoryEmptyCard(state.healthConnected, state.canConnectHealth, actions.onConnectHealth)
    }
    model.soundsOnly.forEach { (night, last) ->
        item(key = "sounds@$night") {
            SoundsOnlyCard(
                HistoryEntry.Sounds(night, last), state.soundClips, state.soundFile, actions.onDeleteClip,
                actions.onDeleteNightClips,
            )
        }
    }
}

/** Kennzahlen, Wochenrückblick, Trends und Wirkung der Notizen über den Nächten. */
private fun LazyListScope.summaryItems(state: HistoryState, actions: HistoryActions) {
    val customNotes = state.customNotes
    if (state.nights.isNotEmpty()) item(key = "stats") { Stats(state.nights) }
    state.weekly?.let { item(key = "weekly") { WeeklyCard(it, customNotes, state.weeklyDeep) } }
    state.trends?.let { item(key = "trends") {
        TrendsCard(
            it, state.healthConnected, actions.onConnectHealth, state.goalWeek, state.habits,
            state.charts?.covered.orEmpty(), state.findings,
        )
    } }
    state.noteEffects?.let { item(key = "effects") { NoteEffectsCard(it, customNotes) } }
}

/** Ein Eintrag der Liste: Geräusche ohne Nacht, Aufzeichnung, Nacht aus Health Connect oder eigene Nacht. */
@Composable
private fun EntrySection(
    entry: HistoryEntry,
    state: HistoryState,
    actions: HistoryActions,
    model: HistoryModel,
    modifier: Modifier,
) {
    val soundClips = state.soundClips
    when (entry) {
        is HistoryEntry.Sounds -> SoundsOnlyCard(
            entry, soundClips, state.soundFile, actions.onDeleteClip, actions.onDeleteNightClips, modifier,
        )
        is HistoryEntry.Recording -> RecordingCard(entry.recording, modifier)
        is HistoryEntry.Health -> {
            val date = entry.item.date
            HealthNightCard(
                entry.item.session ?: return,
                score = remember(entry.item, state.recordings, state.goalMinutes) {
                    nightScore(nightView(entry.item, emptyList()), state.recordings, state.goalMinutes)?.result
                },
                feeling = state.healthFeelings[date],
                extras = NightCardNotes(
                    state.notes[date].orEmpty(), state.customNotes, { actions.onToggleNote(date, it) },
                    SoundEvents.forNight(soundClips, date),
                ),
                onOpen = { actions.onOpenNight(entry.item.key) },
                modifier = modifier,
            )
        }
        is HistoryEntry.Night -> {
            val night = entry.item.night ?: return
            val date = entry.item.date
            NightCard(
                night, state.loadStages, modifier,
                NightCardNotes(
                    state.notes[date].orEmpty(), state.customNotes, { actions.onToggleNote(date, it) },
                    // Mehrere Nächte am selben Nachtdatum: die Geräusche nur bei der zuletzt geweckten.
                    if (model.latestByDate[date] === night) SoundEvents.forNight(soundClips, date) else emptyList(),
                ),
                onOpen = { actions.onOpenNight(entry.item.key) },
                recordings = state.recordings,
                goalMinutes = state.goalMinutes,
            )
        }
    }
}

@Composable
private fun Stats(nights: List<NightRecord>) {
    val average = nights.map { it.minutesBeforeTarget }.average().roundToInt()
    // Nur Nächte, in denen früher wecken erlaubt war (ohne «Genau» und kurze Nächte); ohne solche keine Kachel.
    val lightShare = NightHistory.lightShare(nights)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        StatTile(stringResource(R.string.history_average, average), Modifier.weight(1f))
        lightShare?.let { StatTile(stringResource(R.string.history_light_share, it), Modifier.weight(1f)) }
    }
}

/** Kennzahl-Kachel wie auf der Detailseite: Glaskarte mit getönter Oberkante. */
@Composable
private fun StatTile(text: String, modifier: Modifier) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = skyCardColors(),
        border = skyCardEdge(),
        elevation = skyCardLift(),
        modifier = modifier,
    ) {
        Text(
            text, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.background(tileTint(MaterialTheme.colorScheme.primary)).padding(16.dp),
        )
    }
}

/**
 * Eigene Nacht im Verlauf, kompakt: Datum und Weckzeit, mit Schlafphasen das Mini-Hypnogramm samt Summen, sonst die
 * Bewegung im Weckfenster; Weckmoment, Abgleich, Notizen und Geräusche je eine Zeile. Antippen öffnet die Detailseite.
 */
@Composable
private fun NightCard(
    night: NightRecord,
    loadStages: (suspend (NightRecord) -> List<StageSpan>)?,
    modifier: Modifier,
    extras: NightCardNotes,
    onOpen: () -> Unit,
    recordings: List<NightRecording>,
    goalMinutes: Int,
) {
    val context = LocalContext.current
    val stages by produceState(emptyList<StageSpan>(), night, loadStages) {
        value = loadStages?.invoke(night).orEmpty()
    }
    val zone = ZoneId.systemDefault()
    val date = Format.digits(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)).format(night.wokeAt.atZone(zone))
    val view = remember(night, stages) {
        nightView(HistoryItem(HistoryKind.PHASE_WAKE, SleepNotes.nightDate(night, zone), night = night), stages)
    }
    val score = remember(view, recordings, goalMinutes) { nightScore(view, recordings, goalMinutes)?.result }
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = skyCardColors(),
        border = skyCardEdge(),
        elevation = skyCardLift(),
        modifier = modifier.fillMaxWidth()
            .opensDetail(MaterialTheme.shapes.medium, stringResource(R.string.night_open), onOpen),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(date, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    Format.time(night.wokeAt, zone), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            score?.let { ScoreLine(it) }
            val model = view.hypno
            val stats = view.stats
            if (model != null && stats != null) {
                MiniStages(model, stats)
            } else {
                NightBars(
                    night.epochs,
                    Modifier.fillMaxWidth().height(44.dp).padding(top = 10.dp),
                    totalEpochs = (Duration.between(night.windowStart, night.target).toMinutes() * 2).toInt(),
                )
            }
            Text(
                nightSummary(context, night),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                nightDetails(context, night), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            soundSummary(context, extras.clips)?.let { CompactLine(it) }
            NightNotes(extras.notes, extras.customNotes, extras.onToggleNote, Modifier.padding(top = 4.dp)) {
                DetailsLink()
            }
        }
    }
}

/**
 * Geräusche einer Nacht ohne aufgezeichnete Nacht: Datum (des jüngsten Ausschnitts, wie bei den
 * Nächten das Weckdatum) und die Ausschnitte.
 */
@Composable
private fun SoundsOnlyCard(
    entry: HistoryEntry.Sounds,
    soundClips: List<SoundClip>,
    soundFile: (SoundClip) -> File?,
    onDeleteClip: (SoundClip) -> Unit,
    onDeleteNightClips: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val night = entry.night
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = skyCardColors(),
        border = skyCardEdge(),
        elevation = skyCardLift(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                Format.digits(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                    .format(entry.last.atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.titleSmall,
            )
            SoundClipsSection(
                SoundEvents.forNight(soundClips, night), soundFile, onDeleteClip, { onDeleteNightClips(night) },
            )
        }
    }
}
