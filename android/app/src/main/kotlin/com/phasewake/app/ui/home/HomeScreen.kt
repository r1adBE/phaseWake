package com.phasewake.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import com.phasewake.app.core.MissHint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.CustomNote
import com.phasewake.app.core.SleepNotes
import com.phasewake.app.sounds.SleepSoundService
import com.phasewake.app.ui.notes.NightNotes
import com.phasewake.app.ui.notes.TonightNotesCard
import com.phasewake.app.core.RoutineItem
import com.phasewake.app.core.SoundClip
import com.phasewake.app.core.SoundEvents
import com.phasewake.app.ui.sounds.SoundClipsSection
import com.phasewake.app.ui.sounds.SoundRecordingCard
import java.io.File
import java.time.LocalDate
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.WakePlan
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.components.AnimatedNight
import com.phasewake.app.ui.components.HeroWave
import com.phasewake.app.ui.components.LogoMark
import com.phasewake.app.ui.components.Night
import com.phasewake.app.ui.components.NightBars
import com.phasewake.app.ui.theme.PhaseWakeTheme
import com.phasewake.app.ui.history.FeelingPicker
import com.phasewake.app.ui.history.nightDetails
import com.phasewake.app.ui.history.nightSummary
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
) {
    var now by remember { mutableStateOf(clock()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            now = clock()
        }
    }
    val zone = ZoneId.systemDefault()
    val plan = remember(alarms, now) { AlarmSchedule.earliestPlan(alarms, now, zone) }

    // Dunkel: die ganze Seite ist Nachthimmel. Hell: nur der Kopfbereich, darunter helle Karten.
    val dark = PhaseWakeTheme.isDark
    val list = @Composable {
    LazyColumn(
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "hero") {
            Header(plan, plan?.let { p -> alarms.first { it.id == p.alarmId } }, now, zone, fullPage = dark, onAdd = onAdd.takeIf { alarms.size < MAX_ALARMS }, onEdit = onEdit)
        }
        // Nacht-Check nur, wenn ein Wecker ansteht – sonst gibt es nichts abzusichern.
        if (check != null && plan != null) {
            item(key = "check") { NightCheckCard(check, cardColor(), onFix) }
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
        // Morgenbericht ohne aufgezeichnete Nacht (z. B. Uhr nicht getragen): nur die Geräusche. Nicht
        // während der Aufnahme und nicht für die Nacht, die gerade läuft.
        val soundsMorning = SoundEvents.nightsWithoutRecord(soundClips, listOfNotNull(lastNight?.let { SleepNotes.nightDate(it, zone) }))
            .firstOrNull()
            ?.takeIf { (night, last) ->
                Duration.between(last, now) < MORNING && soundRec?.recording != true &&
                    !(SleepNotes.entryVisible(now, zone, plan?.windowStart) && SleepNotes.nightDate(now, zone) == night)
            }
        soundsMorning?.let { (night, _) ->
            item(key = "soundsMorning") {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = cardColor()),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.home_morning), style = MaterialTheme.typography.titleSmall)
                        SoundClipsSection(SoundEvents.forNight(soundClips, night), soundFile, onDeleteClip, { onDeleteNightClips(night) })
                    }
                }
            }
        }
        if (alarms.isNotEmpty()) {
            item(key = "header") {
                Text(
                    stringResource(R.string.home_alarms),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 8.dp),
                )
            }
            items(alarms, key = { it.id }) { alarm ->
                AlarmRow(alarm, Modifier.animateItem(), onClick = { onEdit(alarm.id) }, onToggle = { onToggle(alarm, it) })
            }
            // Am Limit fehlt der Knopf «Neuer Wecker»; hier steht, warum.
            if (alarms.size >= MAX_ALARMS) {
                item(key = "limit") {
                    Text(
                        stringResource(R.string.home_max_alarms, MAX_ALARMS),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
        }
        item(key = "spacer") { Spacer(Modifier.height(72.dp)) }
    }
    }
    if (dark) AnimatedNight(Modifier.fillMaxSize()) { list() } else list()
}

/** Höchstens so viele Wecker: Die Uhr führt ohnehin nur eine Nacht zugleich, und die Liste bleibt übersichtlich. */
internal const val MAX_ALARMS = 8

/** Kartenfarbe: auf dem Nachthimmel durchscheinend, sonst die normale Fläche. */
@Composable
private fun cardColor(): Color =
    if (PhaseWakeTheme.isDark) Color.White.copy(alpha = 0.07f) else MaterialTheme.colorScheme.surfaceContainer

/**
 * Kopfbereich im Nachthimmel-Verlauf, randlos bis unter die Statusleiste: Logo, Begrüssung und
 * das nächste Weckfenster gross; ohne Wecker eine Einladung, den ersten zu stellen.
 */
@Composable
private fun Header(
    plan: WakePlan?,
    alarm: AlarmSpec?,
    now: Instant,
    zone: ZoneId,
    fullPage: Boolean,
    /** `null`: Limit erreicht, kein Knopf «Neuer Wecker». */
    onAdd: (() -> Unit)?,
    onEdit: (String) -> Unit,
) {
    val context = LocalContext.current
    // In Vorschau und Bildschirmfotos gibt es keinen zweiten Frame: dort gleich sichtbar.
    val inspection = LocalInspectionMode.current
    var appeared by remember { mutableStateOf(inspection) }
    LaunchedEffect(Unit) { appeared = true }
    val shape = RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp)
    val base = Modifier
        .fillMaxWidth()
        .clip(shape)
        .then(if (plan != null) Modifier.clickable { onEdit(plan.alarmId) } else Modifier)
    // Auf der ganzseitigen Nacht (dunkel) braucht der Kopf keinen eigenen Hintergrund.
    val background: @Composable (Modifier, @Composable () -> Unit) -> Unit =
        if (fullPage) { m, c -> Box(m) { c() } } else { m, c -> AnimatedNight(m) { c() } }
    background(base) {
        Column(Modifier.statusBarsPadding().padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoMark(48.dp)
                Text(
                    "Phase Wake",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            AnimatedVisibility(appeared, enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { it / 4 }) {
                Column(Modifier.padding(top = 28.dp).semantics(mergeDescendants = true) { heading() }) {
                    Text(stringResource(greeting(now, zone)), style = MaterialTheme.typography.titleMedium, color = Night.Muted)
                    if (plan != null && alarm != null) {
                        val dayText = dayLabel(plan.target, now, zone)
                        Text(
                            Format.time(plan.windowStart, zone) + " – " + Format.time(plan.target, zone),
                            style = MaterialTheme.typography.displayMedium,
                            color = Color.White,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        // Zwei Zeilen statt eines Umbruchs mitten im Satz: wann und welcher – dann wie lange noch.
                        Text(
                            listOf(dayText, alarm.label).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Night.Muted,
                        )
                        Text(
                            if (now.isBefore(plan.windowStart)) {
                                stringResource(R.string.home_next_in, Format.duration(context, Duration.between(now, plan.windowStart)))
                            } else {
                                stringResource(R.string.home_window_running)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Night.Muted,
                        )
                        HeroWave(Modifier.fillMaxWidth().height(76.dp).padding(top = 18.dp), windowFraction = 0.32f, onNight = true)
                    } else {
                        Text(
                            stringResource(R.string.home_no_alarm_title),
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Text(stringResource(R.string.home_no_alarm_text), style = MaterialTheme.typography.bodyMedium, color = Night.Muted)
                        HeroWave(Modifier.fillMaxWidth().height(64.dp).padding(vertical = 14.dp), onNight = true)
                        if (onAdd != null) {
                            Button(
                                onClick = onAdd,
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Night.Bottom),
                            ) {
                                Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.home_add_alarm))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** «Heute», «Morgen» oder der Wochentag («Montag») – nie «Morgen» für einen Tag weiter weg. */
@Composable
private fun dayLabel(target: Instant, now: Instant, zone: ZoneId): String {
    val day = target.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    val locale = LocalConfiguration.current.locales[0]
    return when (day) {
        today -> stringResource(R.string.home_next_today)
        today.plusDays(1) -> stringResource(R.string.home_next_tomorrow)
        else -> day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, locale).replaceFirstChar { it.titlecase(locale) }
    }
}

/** «Guten Morgen» bis 11, «Guten Tag» bis 17, «Guten Abend» bis 22, danach «Gute Nacht». */
private fun greeting(now: Instant, zone: ZoneId): Int = when (now.atZone(zone).hour) {
    in 5..10 -> R.string.greeting_morning
    in 11..16 -> R.string.greeting_day
    in 17..21 -> R.string.greeting_evening
    else -> R.string.greeting_night
}

@Composable
private fun LastNightCard(
    night: NightRecord,
    now: Instant,
    missHint: MissHint?,
    onFeeling: (Int) -> Unit,
    notes: List<String>,
    customNotes: List<CustomNote>,
    onToggleNote: (String) -> Unit,
    clips: List<SoundClip> = emptyList(),
    soundFile: (SoundClip) -> File? = { null },
    onDeleteClip: (SoundClip) -> Unit = {},
    onDeleteClips: () -> Unit = {},
) {
    // Morgenbericht: am Morgen nach dem Wecken fragt die Karte nach dem Befinden.
    val morning = Duration.between(night.wokeAt, now) < MORNING
    val askFeeling = morning && night.feeling == null
    val context = LocalContext.current
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = cardColor()),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (morning) R.string.home_morning else R.string.home_last_night), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(Format.time(night.wokeAt), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            NightBars(
                night.epochs,
                Modifier.fillMaxWidth().height(48.dp).padding(top = 10.dp),
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
            missHint?.let { hint ->
                Column(
                    Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
                        .padding(12.dp),
                ) {
                    Text(LocalResources.current.getQuantityString(R.plurals.hint_title, MissHint.NIGHTS, MissHint.NIGHTS), style = MaterialTheme.typography.labelLarge)
                    Text(
                        stringResource(
                            when (hint) {
                                MissHint.GAPS -> R.string.hint_gaps
                                MissHint.HEART_MISSING -> R.string.hint_heart
                                MissHint.CALM -> R.string.hint_calm
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            AnimatedVisibility(askFeeling) {
                Column(Modifier.padding(top = 14.dp)) {
                    FeelingPicker(onPick = onFeeling)
                    Text(
                        stringResource(R.string.feeling_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            // Morgenbericht: Notizen des Abends nachtragen oder ändern.
            if (morning) NightNotes(notes, customNotes, onToggleNote, Modifier.padding(top = 6.dp))
            // Morgenbericht: Geräusche der Nacht (sonst im Verlauf).
            if (morning) SoundClipsSection(clips, soundFile, onDeleteClip, onDeleteClips)
        }
    }
}

/** Einstieg zu den Einschlafklängen; läuft ein Klang, mit Restzeit. */
@Composable
private fun SoundsEntry(playing: SleepSoundService.Playing?, now: Instant, onClick: () -> Unit) {
    val context = LocalContext.current
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = cardColor()),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Row(Modifier.clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_sound), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(stringResource(R.string.sounds_title), style = MaterialTheme.typography.titleSmall)
                if (playing != null) {
                    val name = stringResource(SleepSoundService.soundName(playing.sound))
                    // Auf 20 s genau (so oft aktualisiert die Startseite); angefangene Minuten zählen voll.
                    val left = playing.endsAt?.let { end ->
                        val minutes = (Duration.between(now, end).seconds.coerceAtLeast(0) + 59) / 60
                        stringResource(R.string.sounds_remaining, Format.duration(context, Duration.ofMinutes(minutes)))
                    }
                    Text(
                        listOfNotNull(name, left).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Schalter «Geräusche aufnehmen» für diese Nacht. */
data class SoundRecState(val enabled: Boolean, val recording: Boolean, val permissionDenied: Boolean)

/** So lange nach dem Wecken gilt die letzte Nacht als «Dein Morgen». */
private val MORNING: Duration = Duration.ofHours(12)

@Composable
private fun AlarmRow(alarm: AlarmSpec, modifier: Modifier, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = cardColor()),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Row(
            Modifier.clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                val title = alarm.sleepDurationMinutes?.let {
                    stringResource(R.string.alarm_after_sleep, Format.duration(context, Duration.ofMinutes(it.toLong())))
                } ?: Format.window(alarm)
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface else muted,
                )
                val details = buildList {
                    add(Format.days(context, alarm))
                    if (alarm.sleepDurationMinutes != null) {
                        add(res.getString(R.string.alarm_latest, Format.time(java.time.LocalTime.of(alarm.hour, alarm.minute))))
                    }
                    if (alarm.label.isNotBlank()) add(alarm.label)
                }
                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = muted)
            }
            // Der Schalter nennt den Wecker, den er schaltet (Bildschirmleser).
            val switchLabel = Format.window(alarm) + (alarm.label.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            Switch(checked = alarm.enabled, onCheckedChange = onToggle, modifier = Modifier.semantics { contentDescription = switchLabel })
        }
    }
}
