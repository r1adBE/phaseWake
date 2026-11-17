package com.phasewake.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phasewake.app.PhaseWakeApp
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.HealthFeeling
import com.phasewake.app.core.HealthFeelings
import com.phasewake.app.core.HealthHistory
import com.phasewake.app.core.HealthSession
import com.phasewake.app.core.HistoryItem
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.NoteEffect
import com.phasewake.app.core.Regularity
import com.phasewake.app.core.Routine
import com.phasewake.app.core.RoutineItem
import com.phasewake.app.core.SleepGoal
import com.phasewake.app.core.SleepNotes
import com.phasewake.app.core.SleepSummary
import com.phasewake.app.core.StageSpan
import com.phasewake.app.core.Streak
import com.phasewake.app.core.Trends
import com.phasewake.app.core.WeeklySummary
import com.phasewake.app.health.HealthReadGate
import com.phasewake.app.ui.history.HabitLines
import com.phasewake.app.ui.history.HistoryCharts
import com.phasewake.app.ui.settings.HealthState
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Auswertungen für Verlauf und Einstellungen (aus AppData.kt): Health Connect lesen, dann im Hintergrund rechnen.

/** Auswertungen für Verlauf und Einstellungen: Health Connect, Trends, Gewohnheiten, Diagramme, Wochenrückblick. */
internal class Insights(
    val health: HealthState,
    /** Schlafphasen für den ganzen Verlauf (eine Abfrage statt einer je Nacht beim Scrollen). */
    val stages: List<StageSpan>,
    val trends: Trends,
    /** Schlafziel der Woche: (erreicht, gezählt). */
    val goalWeek: Pair<Int, Int>,
    val noteEffects: List<NoteEffect>,
    val habits: HabitLines,
    val charts: HistoryCharts,
    /** Wochenrückblick als Karte am Sonntag und Montag; sonst `null`. */
    val weekly: WeeklySummary?,
    /** Verlauf: eigene Nächte und Nächte aus Health Connect, neueste zuerst ([HealthHistory.merge]). */
    val history: List<HistoryItem>,
    /** Gelesene Sitzungen aus Health Connect (nur im Speicher): Quelle der Phasen beim Schlafwert. */
    val sessions: List<HealthSession> = emptyList(),
)

/**
 * Rechnet die [Insights]. [healthCheck] zählt bei jedem Fortsetzen und beim Öffnen des Verlaufs hoch: Dann wird Health
 * Connect neu geprüft und höchstens alle 5 Min neu gelesen ([HealthReadGate]); [healthGrant] zählt jede
 * Erteilung der Berechtigung, danach wird gleich gelesen.
 *
 * Gerechnet wird auf [Dispatchers.Default], nicht beim Zusammensetzen auf dem Hauptthread; bis das neue
 * Ergebnis da ist, bleibt das vorige stehen. Nur das erste Ergebnis und das erste mit gelesenem Verlauf (vorher `null`)
 * entstehen gleich beim Zusammensetzen: Sonst zeigten Verlauf und Detailseite kurz «keine Nächte» (die Detailseite
 * kehrte dann sogar zurück).
 */
@Composable
internal fun rememberInsights(app: PhaseWakeApp, data: AppData, healthCheck: Int, healthGrant: Int = 0): Insights {
    val nights = data.nights
    var health by remember { mutableStateOf(HealthState.UNAVAILABLE) }
    LaunchedEffect(healthCheck) {
        health = when {
            app.health.needsUpdate -> HealthState.UPDATE_REQUIRED
            !app.health.isAvailable -> HealthState.UNAVAILABLE
            app.health.isGranted() -> HealthState.CONNECTED
            else -> HealthState.AVAILABLE
        }
    }
    val clearedAt by app.settings.historyClearedAt.collectAsStateWithLifecycle(initialValue = null)
    // Eine Abfrage für alles (Verlauf, Diagramme, Trends, Wirkung der Notizen, Phasen der eigenen Nächte):
    // so weit zurück wie der Verlauf, mindestens bis vor die älteste eigene Nacht. Nur im Speicher; dazwischen bleibt
    // das zuletzt Gelesene.
    val reads = remember { HealthReadGate() }
    val sessions by produceState(emptyList<HealthSession>(), health, healthCheck, healthGrant, nights?.size) {
        if (health != HealthState.CONNECTED) {
            reads.reset()
            value = emptyList()
            return@produceState
        }
        val now = Instant.now()
        val from = listOfNotNull(
            now.minus(Duration.ofDays(HealthHistory.DAYS + 1)),
            nights?.minOfOrNull { it.windowStart }?.minus(STAGES_BEFORE),
        ).min()
        if (!reads.due(now, from, healthGrant)) return@produceState
        value = app.health.sessions(from, now)
        reads.mark(now, from, healthGrant)
    }
    val inputs = InsightInputs(
        nights, sessions, clearedAt, data.healthFeelings, data.notes, data.sleepGoal, data.alarms, data.routineItems,
        data.routineTicks, data.learning?.values?.latencyMinutes, ZoneId.systemDefault(),
    )
    val computed by produceState<Derived?>(null, inputs) {
        value = withContext(Dispatchers.Default) { derive(inputs) }
    }
    val derived = computed?.takeIf { it.inputs.nights != null || inputs.nights == null } ?: remember(inputs) { derive(inputs) }
    val sleeps = derived.sleeps
    val zone = inputs.zone
    // Text der nächsten Wochenrückblick-Mitteilung hier vorberechnen (App offen, neue Nacht, Befinden,
    // Notizen): Im Hintergrund liest Health Connect ohne eigene Berechtigung keine Schlafdaten.
    LaunchedEffect(data.weeklyReview, nights, sleeps, data.notes, data.sleepGoal, data.customNotes, derived.feelings) {
        val list = nights ?: return@LaunchedEffect
        if (data.weeklyReview) app.weekly.prepare(list, sleeps, data.notes, data.sleepGoal, data.customNotes, derived.feelings)
    }
    // Widget «Details von heute»: Schlafdauer der jüngsten eigenen Nacht ablegen (wie Verlauf und Detailseite).
    // Nur mit geladenen Daten, damit die leere Anfangsliste den Wert nicht kurz löscht.
    LaunchedEffect(health, nights, sleeps) {
        val last = nights?.maxByOrNull { it.wokeAt } ?: return@LaunchedEffect
        if (health != HealthState.CONNECTED || sleeps.isEmpty()) return@LaunchedEffect
        val night = SleepNotes.nightDate(last, zone)
        com.phasewake.app.widget.NextAlarmWidget.saveAsleep(app, night, NoteEffect.asleepByNight(sleeps, zone)[night])
    }
    return Insights(
        health, derived.stages, derived.trends, derived.goalWeek, derived.noteEffects, derived.habits, derived.charts,
        derived.weekly, derived.history, sessions,
    )
}

/** Alles, wovon die Auswertungen abhängen; gleich: nichts neu zu rechnen. */
private data class InsightInputs(
    val nights: List<NightRecord>?,
    val sessions: List<HealthSession>,
    val clearedAt: Instant?,
    val healthFeelings: Map<LocalDate, Int>,
    val notes: Map<LocalDate, List<String>>,
    val sleepGoal: Int,
    val alarms: List<AlarmSpec>,
    val routineItems: List<RoutineItem>,
    val routineTicks: Map<LocalDate, List<String>>,
    val latency: Int?,
    val zone: ZoneId,
)

/** Ergebnis von [derive] zu [inputs]. */
private class Derived(
    val inputs: InsightInputs,
    val sleeps: List<SleepSummary>,
    val stages: List<StageSpan>,
    val history: List<HistoryItem>,
    val feelings: List<HealthFeeling>,
    val trends: Trends,
    val goalWeek: Pair<Int, Int>,
    val noteEffects: List<NoteEffect>,
    val habits: HabitLines,
    val charts: HistoryCharts,
    val weekly: WeeklySummary?,
)

/** Rechnet alle Auswertungen (reine Rechnung, auf jedem Thread). */
private fun derive(i: InsightInputs): Derived {
    val zone = i.zone
    val nights = i.nights.orEmpty()
    val sleeps = HealthHistory.summaries(i.sessions, i.clearedAt, zone)
    val stages = HealthHistory.stages(i.sessions, i.clearedAt, zone)
    val history = HealthHistory.merge(nights, i.sessions, i.clearedAt, zone)
    // Befinden zu Nächten aus anderen Apps, die im Verlauf stehen: zählt in Diagrammen und Trends wie das eigene.
    val feelings = HealthFeelings.of(history, i.healthFeelings)
    val now = Instant.now()
    return Derived(
        inputs = i,
        sleeps = sleeps,
        stages = stages,
        history = history,
        feelings = feelings,
        trends = Trends.compute(nights, sleeps, now, zone, feelings),
        goalWeek = SleepGoal.weekStatus(SleepGoal.nightsOf(sleeps, zone), i.sleepGoal),
        noteEffects = NoteEffect.compute(nights, i.notes, zone, NoteEffect.asleepByNight(sleeps, zone), feelings),
        habits = habitLines(sleeps, i),
        // Diagramme im Verlauf: Schlaf aus Health Connect, Weckmomente und Befinden aus dem Verlauf.
        charts = HistoryCharts.of(nights, sleeps, stages, i.notes, i.alarms, i.sleepGoal, now, zone, feelings, latencyMinutes = i.latency),
        weekly = weeklyCard(nights, sleeps, i, feelings),
    )
}

/** Wochenrückblick als Karte am Sonntag und Montag; an anderen Tagen oder ohne Daten `null`. */
private fun weeklyCard(nights: List<NightRecord>, sleeps: List<SleepSummary>, i: InsightInputs, feelings: List<HealthFeeling>): WeeklySummary? {
    val today = LocalDate.now(i.zone)
    if (!WeeklySummary.cardVisible(today)) return null
    return WeeklySummary.of(nights, sleeps, i.notes, i.zone, i.sleepGoal, WeeklySummary.weekEnd(today), feelings).takeIf { it.hasData }
}

/** Gewohnheiten: Regelmässigkeit und Serien aus der Gesundheits-App, Abendroutine aus den Haken. */
private fun habitLines(sleeps: List<SleepSummary>, i: InsightInputs): HabitLines {
    val zone = i.zone
    val today = SleepNotes.nightDate(Instant.now(), zone)
    val reached = Streak.reachedByNight(sleeps, zone, i.sleepGoal)
    return HabitLines(
        regular = Regularity.count(Regularity.bedtimesOf(sleeps, zone), zone, until = today),
        streak = Streak.current(reached, today),
        bestStreak = Streak.longest(reached, today),
        routineAverage = Routine.average(i.routineTicks, i.routineItems, today),
        routineItems = i.routineItems.size,
    )
}
