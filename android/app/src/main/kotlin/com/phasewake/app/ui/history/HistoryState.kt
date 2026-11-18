package com.phasewake.app.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.phasewake.app.core.CustomNote
import com.phasewake.app.core.Finding
import com.phasewake.app.core.HealthHistory
import com.phasewake.app.core.HistoryGroup
import com.phasewake.app.core.HistoryGroups
import com.phasewake.app.core.HistoryItem
import com.phasewake.app.core.HistoryKind
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.NoteEffect
import com.phasewake.app.core.SleepGoal
import com.phasewake.app.core.SleepNotes
import com.phasewake.app.core.SoundClip
import com.phasewake.app.core.SoundEvents
import com.phasewake.app.core.StageSpan
import com.phasewake.app.core.Trends
import com.phasewake.app.core.WeeklySummary
import com.phasewake.app.shared.NightRecording
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields

/**
 * Was der Verlauf ([HistoryScreen]) zeigt.
 *
 * @property loadStages Schlafphasen einer Nacht laden; `null`: keine.
 * @property goalWeek Schlafziel erreicht (Nächte, gezählt) aus der Gesundheits-App; `null` ohne Daten.
 * @property noteEffects Wirkung der Notizen; `null`: Karte nicht zeigen (Bildschirmfotos).
 * @property habits Gewohnheiten in der Trends-Karte.
 * @property weekly Wochenrückblick (Sonntag und Montag); `null`: keine Karte.
 * @property weeklyDeep Tiefschlaf-Anteil (%) der Woche des Rückblicks und der Vorwoche; `null`: keine Zeile.
 * @property findings Auffälligkeiten der letzten 7 Nächte für die Trends ([com.phasewake.app.core.Findings.of]).
 * @property soundClips Aufgenommene Geräusche (alle Nächte); [soundFile] liefert die Datei.
 * @property charts Diagramme oben im Verlauf; `null` oder leer: keine.
 * @property items Verlauf mit Nächten aus Health Connect ([HealthHistory.merge]); `null`: nur [nights].
 * @property canConnectHealth Health Connect ist nutzbar, die Freigabe fehlt noch (Knopf im leeren Verlauf).
 * @property healthFeelings Befinden zu Nächten aus anderen Apps je Nachtdatum (nur bei Phase Wake gespeichert).
 * @property recordings Abgeschlossene Aufzeichnungen («Nacht aufzeichnen»), älteste zuerst; nach ihrem Ende zwischen
 *   die Nächte.
 * @property goalMinutes Schlafziel in Minuten für den Schlafwert der Nächte.
 * @property asleep Schlaf je Nachtdatum aus Health Connect ([com.phasewake.app.core.NoteEffect.asleepByNight]) für
 *   den Schnitt der Wochen- und Jahreszeilen.
 * @property today Heute für die Jahresgrenze der Liste ([HistoryGroups]); `null`: Datum des Geräts (Bildschirmfotos
 *   setzen es fest).
 * @property openGroups Beim Öffnen aufgeklappte Wochen und Jahre ([groupKey]); nur für Bildschirmfotos, in der App
 *   ist alles zugeklappt.
 */
data class HistoryState(
    val nights: List<NightRecord>,
    val loadStages: (suspend (NightRecord) -> List<StageSpan>)?,
    val trends: Trends? = null,
    val healthConnected: Boolean = false,
    val goalWeek: Pair<Int, Int>? = null,
    val notes: Map<LocalDate, List<String>> = emptyMap(),
    val customNotes: List<CustomNote> = emptyList(),
    val noteEffects: List<NoteEffect>? = null,
    val habits: HabitLines? = null,
    val weekly: WeeklySummary? = null,
    val weeklyDeep: Pair<Int, Int>? = null,
    val findings: List<Finding> = emptyList(),
    val soundClips: List<SoundClip> = emptyList(),
    val soundFile: (SoundClip) -> File? = { null },
    val charts: HistoryCharts? = null,
    val items: List<HistoryItem>? = null,
    val canConnectHealth: Boolean = false,
    val healthFeelings: Map<LocalDate, Int> = emptyMap(),
    val recordings: List<NightRecording> = emptyList(),
    val goalMinutes: Int = SleepGoal.DEFAULT_MINUTES,
    val asleep: Map<LocalDate, Long> = emptyMap(),
    val today: LocalDate? = null,
    val openGroups: Set<String> = emptySet(),
)

/**
 * Aktionen des Verlaufs ([HistoryScreen]).
 *
 * @property onOpenNight Detailseite einer Nacht öffnen ([HistoryItem.key]); auch aus den Blasen der Diagramme.
 */
data class HistoryActions(
    val onConnectHealth: () -> Unit = {},
    val onToggleNote: (LocalDate, String) -> Unit = { _, _ -> },
    val onDeleteClip: (SoundClip) -> Unit = {},
    val onDeleteNightClips: (LocalDate) -> Unit = {},
    val onOpenNight: (String) -> Unit = {},
)

/** Notizen ([notes], [customNotes], umschalten mit [onToggleNote]) und Geräusche ([clips]) einer Nacht im Verlauf. */
internal data class NightCardNotes(
    val notes: List<String>,
    val customNotes: List<CustomNote>,
    val onToggleNote: (String) -> Unit,
    val clips: List<SoundClip>,
)

/**
 * Verlauf für die Liste: alle Nächte ([history]), die zuletzt geweckte je Nachtdatum ([latestByDate]), Geräusche ohne
 * Nacht ([soundsOnly]), alles zusammen in Listenfolge ([entries]) und so gruppiert, wie die Liste es zeigt ([groups]).
 */
internal data class HistoryModel(
    val history: List<HistoryItem>,
    val latestByDate: Map<LocalDate, NightRecord>,
    val soundsOnly: List<Pair<LocalDate, Instant>>,
    val entries: List<HistoryEntry>,
    val groups: List<HistoryGroup<HistoryEntry>>,
)

/** Verlauf für die Liste ([HistoryModel]) aus [state]. */
@Composable
internal fun rememberHistoryModel(state: HistoryState): HistoryModel {
    val nights = state.nights
    val history = remember(state.items, nights) {
        state.items ?: HealthHistory.merge(nights, emptyList(), null, ZoneId.systemDefault())
    }
    val latestByDate = remember(nights) {
        nights.groupBy { SleepNotes.nightDate(it, ZoneId.systemDefault()) }
            .mapValues { (_, list) -> list.maxBy { it.wokeAt } }
    }
    val healthDates = remember(history) { history.filter { it.kind == HistoryKind.HEALTH }.map { it.date }.toSet() }
    // Geräusche ohne Nacht (z. B. Uhr nicht getragen, keine Gesundheitsdaten): eigene Karte, nach Zeit zwischen die
    // Nächte.
    val soundsOnly = remember(state.soundClips, latestByDate, healthDates) {
        SoundEvents.nightsWithoutRecord(state.soundClips, latestByDate.keys + healthDates)
    }
    val entries = remember(history, soundsOnly, state.recordings) {
        val nightly = buildList<HistoryEntry> {
            val pending = ArrayDeque(soundsOnly.map { HistoryEntry.Sounds(it.first, it.second) })
            for (item in history) {
                while (pending.isNotEmpty() && pending.first().last.isAfter(item.at)) add(pending.removeFirst())
                add(if (item.night != null) HistoryEntry.Night(item) else HistoryEntry.Health(item))
            }
            addAll(pending)
        }
        withRecordings(nightly, state.recordings)
    }
    // Die neusten Nächte einzeln, ältere je Woche bzw. Jahr; Wochenbeginn nach der Sprache des Geräts.
    val today = state.today ?: LocalDate.now()
    val firstDay = WeekFields.of(LocalConfiguration.current.locales[0]).firstDayOfWeek
    val groups = remember(entries, today, firstDay) {
        val zone = ZoneId.systemDefault()
        HistoryGroups.of(entries, { it.at.atZone(zone).toLocalDate() }, { it.nightDate != null }, today, firstDay)
    }
    return HistoryModel(history, latestByDate, soundsOnly, entries, groups)
}
