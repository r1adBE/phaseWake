package com.phasewake.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phasewake.app.PhaseWakeApp
import com.phasewake.app.core.Finding
import com.phasewake.app.core.FindingKind
import com.phasewake.app.core.Findings
import com.phasewake.app.core.NightFacts
import com.phasewake.app.core.OwnOnset
import com.phasewake.app.core.SleepNotes
import com.phasewake.app.core.WeeklySummary
import com.phasewake.app.shared.NightRecording
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch

/**
 * Auffälligkeiten der letzten 7 Nächte: [all] für die Trends im Verlauf, [home] die eine
 * für die Startseite (nicht ausgeblendet), [homeBedtime] dieselbe, solange dort die Bettzeit-Karte steht (ohne
 * Bettzeit-Tipp), [weekDeep] Tiefschlaf-Anteil dieser Woche und der Vorwoche für den Wochenrückblick.
 */
internal class FindingsState(
    val all: List<Finding>,
    val home: Finding?,
    val homeBedtime: Finding?,
    val weekDeep: Pair<Int, Int>?,
    val onHide: (FindingKind) -> Unit,
)

/**
 * Rechnet die [FindingsState] aus den Sitzungen von Health Connect ([Insights.sessions]); ohne Freigabe nichts. Fehlt
 * dort die Einschlafdauer einer Nacht, gilt die eigene Messung der Uhr: zuerst die Nacht im Verlauf ([OwnOnset.of]),
 * sonst die Aufzeichnung ([NightRecording.ownOnset]).
 */
@Composable
internal fun rememberFindings(app: PhaseWakeApp, insights: Insights): FindingsState {
    val clearedAt by app.settings.historyClearedAt.collectAsStateWithLifecycle(initialValue = null)
    val hidden by app.settings.findingsHidden.collectAsStateWithLifecycle(initialValue = emptyMap())
    val recordings by app.recordings.recordings.collectAsStateWithLifecycle()
    val nights by app.nights.nights.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        app.recordings.load()
        app.nights.load()
    }
    val zone = ZoneId.systemDefault()
    val now = Instant.now()
    val today = SleepNotes.nightDate(now, zone)
    val own = remember(recordings) { recordings.orEmpty().mapNotNull(NightRecording::ownOnset) }
    val watch = remember(nights) { nights.orEmpty().mapNotNull(OwnOnset::of) }
    val facts = remember(insights.sessions, clearedAt, own, watch) {
        NightFacts.of(insights.sessions, clearedAt, zone, own, watch)
    }
    val all = remember(facts, today) { Findings.of(facts, today, zone) }
    val weekEnd = WeeklySummary.weekEnd(LocalDate.now(zone))
    val weekDeep = remember(facts, weekEnd) { Findings.weekDeep(facts, weekEnd) }
    return FindingsState(
        all, Findings.home(all, hidden, now), Findings.home(all, hidden, now, bedtimeAdvice = true), weekDeep,
    ) { kind -> app.appScope.launch { app.settings.hideFinding(kind) } }
}
