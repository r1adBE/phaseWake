package com.phasewake.app.ui.history

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import com.phasewake.app.core.SleepNotes
import com.phasewake.app.ui.notes.NightNotes
import com.phasewake.app.ui.notes.NoteEffectsCard
import com.phasewake.app.core.SoundClip
import com.phasewake.app.core.SoundEvents
import com.phasewake.app.core.WeeklySummary
import com.phasewake.app.ui.sounds.SoundClipsSection
import com.phasewake.app.ui.sounds.soundSummary
import androidx.compose.runtime.remember
import java.io.File
import java.time.LocalDate
import com.phasewake.app.core.SleepStage
import com.phasewake.app.core.StageCheck
import com.phasewake.app.core.StageSpan
import com.phasewake.app.core.Trends
import com.phasewake.app.core.WakeReason
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
    val timing = wakeTimingText(context, night.minutesBeforeTarget.toInt(), night.minutesAfterTarget.toInt(), night.reason)
    val snooze = if (night.snoozes == 0) context.getString(R.string.no_snooze) else context.getString(R.string.snoozed_times, night.snoozes)
    return "$timing · $snooze"
}

/** Verlauf: Kennzahlen und jede Nacht mit ihrer Bewegungskurve, neueste zuerst. */
@Composable
fun HistoryScreen(
    nights: List<NightRecord>,
    contentPadding: PaddingValues,
    loadStages: (suspend (NightRecord) -> List<StageSpan>)?,
    onExport: () -> Unit,
    trends: Trends? = null,
    healthConnected: Boolean = false,
    onConnectHealth: () -> Unit = {},
    /** Schlafziel erreicht (Nächte, gezählt) aus der Gesundheits-App; `null` ohne Daten. */
    goalWeek: Pair<Int, Int>? = null,
    notes: Map<LocalDate, List<String>> = emptyMap(),
    customNotes: List<CustomNote> = emptyList(),
    onToggleNote: (LocalDate, String) -> Unit = { _, _ -> },
    /** Wirkung der Notizen; `null`: Karte nicht zeigen (Bildschirmfotos). */
    noteEffects: List<NoteEffect>? = null,
    /** Gewohnheiten in der Trends-Karte. */
    habits: HabitLines? = null,
    /** Wochenrückblick (Sonntag und Montag); `null`: keine Karte. */
    weekly: WeeklySummary? = null,
    /** Aufgenommene Geräusche (alle Nächte). */
    soundClips: List<SoundClip> = emptyList(),
    soundFile: (SoundClip) -> File? = { null },
    onDeleteClip: (SoundClip) -> Unit = {},
    onDeleteNightClips: (LocalDate) -> Unit = {},
    /** Diagramme oben im Verlauf; `null` oder leer: keine. */
    charts: HistoryCharts? = null,
    /** Verlauf mit Nächten aus Health Connect ([HealthHistory.merge]); `null`: nur [nights]. */
    items: List<HistoryItem>? = null,
    /** Health Connect ist nutzbar, die Freigabe fehlt noch (Knopf im leeren Verlauf). */
    canConnectHealth: Boolean = false,
    /** Befinden zu Nächten aus anderen Apps je Nachtdatum und wie es gesetzt wird (nur bei Phase Wake gespeichert). */
    healthFeelings: Map<LocalDate, Int> = emptyMap(),
    /** Detailseite einer Nacht öffnen ([HistoryItem.key]); auch aus den Blasen der Diagramme. */
    onOpenNight: (String) -> Unit = {},
) {
    val history = remember(items, nights) { items ?: HealthHistory.merge(nights, emptyList(), null, ZoneId.systemDefault()) }
    val latestByDate = remember(nights) {
        nights.groupBy { SleepNotes.nightDate(it, ZoneId.systemDefault()) }.mapValues { (_, list) -> list.maxBy { it.wokeAt } }
    }
    val healthDates = remember(history) { history.filter { it.kind == HistoryKind.HEALTH }.map { it.date }.toSet() }
    // Geräusche ohne Nacht (z. B. Uhr nicht getragen, keine Gesundheitsdaten): eigene Karte, nach Zeit zwischen die Nächte.
    val soundsOnly = remember(soundClips, latestByDate, healthDates) { SoundEvents.nightsWithoutRecord(soundClips, latestByDate.keys + healthDates) }
    val entries = remember(history, soundsOnly) {
        buildList<HistoryEntry> {
            val pending = ArrayDeque(soundsOnly.map { HistoryEntry.Sounds(it.first, it.second) })
            for (item in history) {
                while (pending.isNotEmpty() && pending.first().last.isAfter(item.at)) add(pending.removeFirst())
                add(if (item.night != null) HistoryEntry.Night(item) else HistoryEntry.Health(item))
            }
            addAll(pending)
        }
    }
    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
    ) {
        item(key = "title") {
            Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        }
        // Diagramme zuoberst, auch ohne Uhr-Nächte (Schlaf aus der Gesundheits-App).
        // Blase in einem Diagramm antippen: die Nacht dieses Nachtdatums, falls sie im Verlauf steht.
        val opener: (LocalDate) -> (() -> Unit)? = { date -> HealthHistory.itemFor(history, date)?.let { item -> { onOpenNight(item.key) } } }
        charts?.takeIf { !it.isEmpty }?.let { item(key = "charts") { HistoryChartsSection(it, customNotes, opener = opener) } }
        if (history.isEmpty()) {
            item(key = "empty") { HistoryEmptyCard(healthConnected, canConnectHealth, onConnectHealth) }
            soundsOnly.forEach { (night, last) ->
                item(key = "sounds@$night") { SoundsOnlyCard(HistoryEntry.Sounds(night, last), soundClips, soundFile, onDeleteClip, onDeleteNightClips) }
            }
            return@LazyColumn
        }
        if (nights.isNotEmpty()) item(key = "stats") { Stats(nights) }
        weekly?.let { item(key = "weekly") { WeeklyCard(it, customNotes) } }
        trends?.let { item(key = "trends") { TrendsCard(it, healthConnected, onConnectHealth, goalWeek, habits, charts?.covered.orEmpty()) } }
        noteEffects?.let { item(key = "effects") { NoteEffectsCard(it, customNotes) } }
        items(entries, key = { it.key }) { entry ->
            when (entry) {
                is HistoryEntry.Sounds -> SoundsOnlyCard(entry, soundClips, soundFile, onDeleteClip, onDeleteNightClips, Modifier.animateItem())
                is HistoryEntry.Health -> {
                    val date = entry.item.date
                    HealthNightCard(
                        entry.item.session ?: return@items,
                        feeling = healthFeelings[date],
                        notes = notes[date].orEmpty(),
                        customNotes = customNotes,
                        onToggleNote = { onToggleNote(date, it) },
                        clips = SoundEvents.forNight(soundClips, date),
                        onOpen = { onOpenNight(entry.item.key) },
                        modifier = Modifier.animateItem(),
                    )
                }
                is HistoryEntry.Night -> {
                    val night = entry.item.night ?: return@items
                    val date = entry.item.date
                    NightCard(
                        night, loadStages, Modifier.animateItem(), notes[date].orEmpty(), customNotes,
                        onToggleNote = { onToggleNote(date, it) },
                        // Mehrere Nächte am selben Nachtdatum: die Geräusche nur bei der zuletzt geweckten.
                        clips = if (latestByDate[date] === night) SoundEvents.forNight(soundClips, date) else emptyList(),
                        onOpen = { onOpenNight(entry.item.key) },
                    )
                }
            }
        }
        if (nights.isNotEmpty()) item(key = "export") {
            OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Icon(painterResource(R.drawable.ic_export), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.history_export), modifier = Modifier.padding(start = 8.dp))
            }
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

@Composable
private fun StatTile(text: String, modifier: Modifier) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = modifier,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
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
    notes: List<String>,
    customNotes: List<CustomNote>,
    onToggleNote: (String) -> Unit,
    clips: List<SoundClip>,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    val stages by produceState(emptyList<StageSpan>(), night, loadStages) { value = loadStages?.invoke(night).orEmpty() }
    val zone = ZoneId.systemDefault()
    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(night.wokeAt.atZone(zone))
    val view = remember(night, stages) { nightView(HistoryItem(HistoryKind.PHASE_WAKE, SleepNotes.nightDate(night, zone), night = night), stages) }
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth().opensDetail(MaterialTheme.shapes.medium, stringResource(R.string.night_open), onOpen),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(date, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(Format.time(night.wokeAt, zone), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
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
            Text(nightDetails(context, night), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (stages.isNotEmpty()) {
                verdict(context, stages, night)?.let { (text, good) ->
                    Text(
                        text,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            NightNotes(notes, customNotes, onToggleNote, Modifier.padding(top = 4.dp))
            soundSummary(context, clips)?.let { CompactLine(it) }
            DetailsLink()
        }
    }
}

/** Eintrag im Verlauf: eine aufgezeichnete Nacht oder nur Geräusche eines Nachtdatums. */
private sealed interface HistoryEntry {
    val key: String

    // Schlüssel wie bisher: Wecker und Fensterbeginn (zwei Wecker können gleich starten).
    data class Night(val item: HistoryItem) : HistoryEntry {
        override val key: String get() = item.night?.let { "${it.alarmId}@${it.windowStart.toEpochMilli()}" } ?: item.key
    }

    /** Nacht aus Health Connect ohne Phase-Wake-Wecker; höchstens eine je Nachtdatum. */
    data class Health(val item: HistoryItem) : HistoryEntry {
        override val key: String get() = "health@${item.date}"
    }

    /** [last] = Beginn des jüngsten Ausschnitts. */
    data class Sounds(val night: LocalDate, val last: Instant) : HistoryEntry {
        override val key: String get() = "sounds@$night"
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(entry.last.atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.titleSmall,
            )
            SoundClipsSection(SoundEvents.forNight(soundClips, night), soundFile, onDeleteClip, { onDeleteNightClips(night) })
        }
    }
}

/** Abgleich mit der Gesundheits-App, z. B. «Gesundheitsdaten: leichter Schlaf ✓». */
private fun verdict(context: Context, stages: List<StageSpan>, night: NightRecord): Pair<String, Boolean>? =
    when (StageCheck.stageAtWake(stages, night.wokeAt)) {
        SleepStage.LIGHT -> context.getString(R.string.verdict_good, context.getString(R.string.stage_light)) to true
        SleepStage.REM -> context.getString(R.string.verdict_good, context.getString(R.string.stage_rem)) to true
        SleepStage.AWAKE -> context.getString(R.string.verdict_good, context.getString(R.string.stage_awake)) to true
        SleepStage.DEEP -> context.getString(R.string.verdict_deep) to false
        SleepStage.UNKNOWN, null -> null
    }
