package com.phasewake.app.ui.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.HistoryItem
import com.phasewake.app.core.HistoryKind
import com.phasewake.app.core.SleepNotes
import com.phasewake.app.core.SleepScoreResult
import com.phasewake.app.core.SoundEvents
import com.phasewake.app.core.StageSpan
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.sounds.SoundClipsSection
import com.phasewake.app.ui.sounds.soundSummary
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

// Kompakte Zeilen der Einträge einer aufgeklappten Woche: eine Zeile je Nacht statt der ganzen Karte.

/** Was die kompakten Zeilen brauchen: Daten des Verlaufs ([state]) und Aktionen ([actions]). */
internal class CompactRows(val state: HistoryState, val actions: HistoryActions)

/** Breite der Spalte für den Schlafwert-Ring; ohne Wert bleibt sie leer, damit die Daten bündig stehen. */
private val LEAD = 36.dp

/** Ein Eintrag einer aufgeklappten Woche als kompakte Zeile, um [indent] eingerückt. */
@Composable
internal fun CompactEntry(entry: HistoryEntry, rows: CompactRows, indent: Dp) {
    val state = rows.state
    when (entry) {
        is HistoryEntry.Night -> CompactNight(entry.item, rows, indent)
        is HistoryEntry.Health -> CompactHealth(entry.item, rows, indent)
        is HistoryEntry.Recording -> {
            val recording = entry.recording
            val context = LocalContext.current
            val zone = ZoneId.systemDefault()
            val end = recording.end ?: recording.start.plus(recording.duration)
            CompactLayout(
                indent, Modifier.semantics(mergeDescendants = true) { },
                CompactText(
                    day(recording.start, zone),
                    stringResource(R.string.record_title) + " · " + recordingSummary(context, recording),
                    trailing = Format.time(recording.start, zone) + " – " + Format.time(end, zone),
                ),
            )
        }
        is HistoryEntry.Sounds -> CompactSounds(entry, state, rows.actions, indent)
    }
}

/** Eigene Nacht: Ring, Datum, Schlaf und Befinden, Weckzeit; Antippen öffnet die Detailseite. */
@Composable
private fun CompactNight(item: HistoryItem, rows: CompactRows, indent: Dp) {
    val night = item.night ?: return
    val state = rows.state
    val stages by produceState(emptyList<StageSpan>(), night, state.loadStages) {
        value = state.loadStages?.invoke(night).orEmpty()
    }
    val zone = ZoneId.systemDefault()
    val score = remember(night, stages, state.recordings, state.goalMinutes) {
        val item = HistoryItem(HistoryKind.PHASE_WAKE, SleepNotes.nightDate(night, zone), night = night)
        nightScore(nightView(item, stages), state.recordings, state.goalMinutes)?.result
    }
    CompactNightRow(
        NightLine(day(night.wokeAt, zone), Format.time(night.wokeAt, zone), state.asleep[item.date], night.feeling),
        score, health = false, indent = indent,
    ) { rows.actions.onOpenNight(item.key) }
}

/** Nacht aus Health Connect: wie [CompactNight], mit Herz-Symbol und in der Zone der Aufzeichnung. */
@Composable
private fun CompactHealth(item: HistoryItem, rows: CompactRows, indent: Dp) {
    val session = item.session ?: return
    val state = rows.state
    val zone = session.zoneOr(ZoneId.systemDefault())
    val score = remember(item, state.recordings, state.goalMinutes) {
        nightScore(nightView(item, emptyList()), state.recordings, state.goalMinutes)?.result
    }
    CompactNightRow(
        NightLine(
            day(session.end, zone), Format.time(session.end, zone), state.asleep[item.date] ?: session.asleepMinutes,
            state.healthFeelings[item.date],
        ),
        score, health = true, indent = indent,
    ) { rows.actions.onOpenNight(item.key) }
}

/** Inhalt einer kompakten Nachtzeile: Tag, Weckzeit, geschlafene Minuten und Befinden (falls bekannt). */
private data class NightLine(val day: String, val wake: String, val asleepMinutes: Long?, val feeling: Int?)

/**
 * Kompakte Nachtzeile: links der Ring des Schlafwerts, daneben Tag und darunter «7 Std. 6 Min · Befinden: Gut» (aus
 * Health Connect mit Herz-Symbol, nicht nur Farbe), rechts die Weckzeit und ein Pfeil. Für Bildschirmleser ein
 * Element mit Tag, Weckzeit, Dauer, Schlafwert und Befinden, Antippen öffnet die Detailseite.
 */
@Composable
private fun CompactNightRow(
    line: NightLine,
    score: SleepScoreResult?,
    health: Boolean,
    indent: Dp,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    val duration = line.asleepMinutes?.let { Format.duration(context, Duration.ofMinutes(it)) }
    val feeling = line.feeling?.let { stringResource(R.string.feeling_value, feelingName(context, it)) }
    val openLabel = stringResource(R.string.night_open)
    val description = listOfNotNull(
        line.day, line.wake, duration, score?.let { scoreDescription(context, it) }, feeling,
        if (health) healthMark(context) else null,
    ).joinToString(", ")
    CompactLayout(
        indent,
        Modifier.clickable(onClickLabel = openLabel, onClick = onOpen).clearAndSetSemantics {
            contentDescription = description
            role = Role.Button
            onClick(openLabel) { onOpen(); true }
        },
        CompactText(
            line.day, listOfNotNull(duration, feeling).joinToString(" · ").ifEmpty { null }, line.wake,
            trailingColor = if (health) Color.Unspecified else MaterialTheme.colorScheme.primary,
            healthIcon = health,
        ),
        lead = { score?.let { ScoreRing(it, LEAD, MaterialTheme.typography.labelMedium) } },
    ) {
        Icon(
            painterResource(R.drawable.ic_chevron_end), contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
        )
    }
}

/** Geräusche ohne Nacht: Tag und Bilanz; Antippen klappt die Ausschnitte (Abspielen, Löschen) darunter auf. */
@Composable
private fun CompactSounds(entry: HistoryEntry.Sounds, state: HistoryState, actions: HistoryActions, indent: Dp) {
    val context = LocalContext.current
    val clips = remember(state.soundClips, entry.night) { SoundEvents.forNight(state.soundClips, entry.night) }
    var open by rememberSaveable(entry.key) { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "turn")
    val stateText = stringResource(if (open) R.string.history_group_expanded else R.string.history_group_collapsed)
    Column {
        CompactLayout(
            indent,
            Modifier.clickable(role = Role.Button) { open = !open }.semantics { stateDescription = stateText },
            CompactText(
                day(entry.last, ZoneId.systemDefault()),
                soundSummary(context, clips) ?: stringResource(R.string.sound_clips_title),
            ),
        ) {
            Icon(
                painterResource(R.drawable.ic_expand), contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp).rotate(turn),
            )
        }
        AnimatedVisibility(open) {
            SoundClipsSection(
                clips, state.soundFile, actions.onDeleteClip, { actions.onDeleteNightClips(entry.night) },
                Modifier.padding(start = 16.dp + indent + LEAD + 12.dp, end = 16.dp, bottom = 12.dp),
            )
        }
    }
}

/**
 * Texte einer kompakten Zeile: [title], darunter [detail] (mit [healthIcon] das Herz davor), rechts [trailing] in
 * [trailingColor] (ohne: leise).
 */
private data class CompactText(
    val title: String,
    val detail: String?,
    val trailing: String? = null,
    val trailingColor: Color = Color.Unspecified,
    val healthIcon: Boolean = false,
)

/** Gerüst einer kompakten Zeile: Spalte [lead] (Ring oder leer), die [text]e und am Ende [end] (Pfeil); ≥ 56 dp. */
@Composable
private fun CompactLayout(
    indent: Dp,
    modifier: Modifier,
    text: CompactText,
    lead: @Composable () -> Unit = {},
    end: @Composable RowScope.() -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).then(modifier)
            .padding(start = 16.dp + indent, end = 12.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Box(Modifier.size(LEAD), contentAlignment = Alignment.Center) { lead() }
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(text.title, style = MaterialTheme.typography.titleSmall)
            text.detail?.let {
                // Herz oben bündig mit der ersten Zeile, auch wenn der Text umbricht.
                Row {
                    if (text.healthIcon) {
                        Icon(
                            painterResource(R.drawable.ic_health), contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp, end = 4.dp).size(14.dp),
                        )
                    }
                    Text(
                        it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        text.trailing?.let {
            val color = text.trailingColor.takeOrElse { MaterialTheme.colorScheme.onSurfaceVariant }
            Text(it, style = MaterialTheme.typography.titleSmall, color = color)
        }
        end()
    }
}

/** «Di., 14.10.»: Tag des Morgens mit kurzem Wochentag nach Sprache und Region. */
@Composable
private fun day(at: Instant, zone: ZoneId): String =
    Format.shortDay(at.atZone(zone).toLocalDate(), LocalConfiguration.current.locales[0])
