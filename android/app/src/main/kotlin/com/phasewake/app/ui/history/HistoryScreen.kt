package com.phasewake.app.ui.history

import android.content.Context
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.ChartData
import com.phasewake.app.core.CustomNote
import com.phasewake.app.core.HealthHistory
import com.phasewake.app.core.HistoryItem
import com.phasewake.app.core.HistoryKind
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.NoteEffect
import com.phasewake.app.core.SleepNotes
import com.phasewake.app.ui.notes.NightNotes
import com.phasewake.app.ui.notes.NoteEffectsCard
import com.phasewake.app.core.SoundClip
import com.phasewake.app.core.SoundEvents
import com.phasewake.app.core.WeeklySummary
import com.phasewake.app.ui.sounds.SoundClipsSection
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
import com.phasewake.app.ui.theme.PhaseWakeTheme
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/** «12 Min vor der Zielzeit · Leichter Schlaf · ohne Schlummern» */
fun nightSummary(context: Context, night: NightRecord): String {
    val reason = context.getString(
        when (night.reason) {
            WakeReason.LIGHT_SLEEP -> R.string.reason_light
            WakeReason.AWAKE -> R.string.reason_awake
            WakeReason.DEADLINE -> R.string.reason_deadline
        },
    )
    val late = night.minutesAfterTarget.toInt()
    val timing = when {
        night.minutesBeforeTarget > 0 -> context.getString(R.string.woke_early, night.minutesBeforeTarget.toInt(), reason)
        // Nach Schlummern über die Zielzeit hinaus: ehrlich als Verspätung, nicht als «zur Zielzeit».
        late > 0 -> context.resources.getQuantityString(R.plurals.woke_late, late, late, reason)
        else -> context.getString(R.string.woke_on_time, reason)
    }
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
                add(item.night?.let { HistoryEntry.Night(it) } ?: HistoryEntry.Health(item))
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
        charts?.takeIf { !it.isEmpty }?.let { item(key = "charts") { HistoryChartsSection(it, customNotes) } }
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
            val night = when (entry) {
                is HistoryEntry.Night -> entry.night
                is HistoryEntry.Sounds -> {
                    SoundsOnlyCard(entry, soundClips, soundFile, onDeleteClip, onDeleteNightClips, Modifier.animateItem())
                    return@items
                }
                is HistoryEntry.Health -> {
                    val date = entry.item.date
                    HealthNightCard(
                        entry.item.session ?: return@items, notes[date].orEmpty(), customNotes,
                        onToggleNote = { onToggleNote(date, it) },
                        sounds = NightSounds(SoundEvents.forNight(soundClips, date), soundFile, onDeleteClip) { onDeleteNightClips(date) },
                        modifier = Modifier.animateItem(),
                    )
                    return@items
                }
            }
            val date = SleepNotes.nightDate(night, ZoneId.systemDefault())
            NightCard(
                night, loadStages, Modifier.animateItem(), notes[date].orEmpty(), customNotes,
                onToggleNote = { onToggleNote(date, it) },
                // Mehrere Nächte am selben Nachtdatum: die Geräusche nur bei der zuletzt geweckten.
                clips = if (latestByDate[date] === night) SoundEvents.forNight(soundClips, date) else emptyList(),
                soundFile = soundFile,
                onDeleteClip = onDeleteClip,
                onDeleteClips = { onDeleteNightClips(date) },
            )
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
    val lightShare = (100.0 * nights.count { it.reason == WakeReason.LIGHT_SLEEP } / nights.size).roundToInt()
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        StatTile(stringResource(R.string.history_average, average), Modifier.weight(1f))
        StatTile(stringResource(R.string.history_light_share, lightShare), Modifier.weight(1f))
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

@Composable
private fun NightCard(
    night: NightRecord,
    loadStages: (suspend (NightRecord) -> List<StageSpan>)?,
    modifier: Modifier,
    notes: List<String>,
    customNotes: List<CustomNote>,
    onToggleNote: (String) -> Unit,
    clips: List<SoundClip> = emptyList(),
    soundFile: (SoundClip) -> File? = { null },
    onDeleteClip: (SoundClip) -> Unit = {},
    onDeleteClips: () -> Unit = {},
) {
    val context = LocalContext.current
    val stages by produceState(emptyList<StageSpan>(), night, loadStages) { value = loadStages?.invoke(night).orEmpty() }
    val zone = ZoneId.systemDefault()
    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(night.wokeAt.atZone(zone))
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(date, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(Format.time(night.wokeAt, zone), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            NightBars(
                night.epochs,
                Modifier.fillMaxWidth().height(44.dp).padding(top = 10.dp),
                totalEpochs = (Duration.between(night.windowStart, night.target).toMinutes() * 2).toInt(),
            )
            Text(
                nightSummary(context, night),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                nightDetails(context, night),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (stages.isNotEmpty()) {
                StageStrip(
                    StageCheck.clip(stages, night.windowStart, night.target),
                    night.windowStart, night.target,
                    Modifier.fillMaxWidth().height(16.dp).padding(top = 2.dp),
                )
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
            SoundClipsSection(clips, soundFile, onDeleteClip, onDeleteClips)
        }
    }
}

/** Eintrag im Verlauf: eine aufgezeichnete Nacht oder nur Geräusche eines Nachtdatums. */
private sealed interface HistoryEntry {
    val key: String

    // Schlüssel wie bisher: Wecker und Fensterbeginn (zwei Wecker können gleich starten).
    data class Night(val night: NightRecord) : HistoryEntry {
        override val key: String get() = "${night.alarmId}@${night.windowStart.toEpochMilli()}"
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

/** Schlafphasen im Weckfenster als Band mit vier Höhen (oben wach, dann REM, leicht, unten tief) und je eigener Farbe. */
@Composable
internal fun StageStrip(spans: List<StageSpan>, from: java.time.Instant, to: java.time.Instant, modifier: Modifier) {
    val brand = PhaseWakeTheme.brand
    val colors = stageColors()
    val total = Duration.between(from, to).toMillis().toFloat().coerceAtLeast(1f)
    Canvas(modifier.padding(top = 6.dp)) {
        drawRoundRect(brand.chartTrack, cornerRadius = CornerRadius(size.height / 2))
        spans.forEach { span ->
            val x0 = size.width * Duration.between(from, span.start).toMillis() / total
            val x1 = size.width * Duration.between(from, span.end).toMillis() / total
            // Höhe nach Phase (wie im Schlafprofil: oben wach, unten tief), damit nicht nur die Farbe unterscheidet.
            val level = ChartData.level(span.stage) ?: return@forEach
            val color = colors.getValue(span.stage)
            val lane = size.height / 4
            drawRect(color, Offset(x0, lane * level), Size((x1 - x0).coerceAtLeast(1f), lane))
        }
    }
}
