package com.phasewake.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.phasewake.app.PhaseWakeApp
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Codec
import com.phasewake.app.core.CustomNote
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
import com.phasewake.app.core.SleepSound
import com.phasewake.app.core.SleepSounds
import com.phasewake.app.core.SleepSummary
import com.phasewake.app.core.SoundClip
import com.phasewake.app.core.SoundEvents
import com.phasewake.app.core.StageSpan
import com.phasewake.app.core.TestRun
import com.phasewake.app.core.Transient
import com.phasewake.app.core.TransientPlan
import com.phasewake.app.core.Streak
import com.phasewake.app.core.Trends
import com.phasewake.app.core.WeeklySummary
import com.phasewake.app.shared.DataLayer
import com.phasewake.app.sounds.SleepSoundService
import com.phasewake.app.sounds.SoundRecorderService
import com.phasewake.app.ui.history.HabitLines
import com.phasewake.app.ui.history.HistoryCharts
import com.phasewake.app.ui.home.NightCheckState
import com.phasewake.app.ui.home.SoundRecState
import com.phasewake.app.ui.settings.HealthState
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// Zustand der Handy-App für die Navigation: gespeicherte Daten, Auswertungen, Aufnahme, Nacht-Check.

/** Gespeicherte Daten der App, wie sie die Bildschirme zeigen (aus den Repositories gesammelt). */
internal class AppData(
    val alarms: List<AlarmSpec>,
    /** Verlauf; `null`, bis er gelesen ist. */
    val nights: List<NightRecord>?,
    val notes: Map<LocalDate, List<String>>,
    val customNotes: List<CustomNote>,
    /** Schlafziel in Minuten. */
    val sleepGoal: Int,
    val bedtimeReminder: Boolean,
    /** Timer der Einschlafklänge in Minuten; `null` = ohne Timer. */
    val soundTimer: Int?,
    val sound: SleepSound,
    val soundPlaying: SleepSoundService.Playing?,
    val recording: SoundRecorderService.Recording?,
    val weeklyReview: Boolean,
    /** Aufgenommene Geräusche; `null`, bis sie gelesen sind. */
    val soundClips: List<SoundClip>?,
    val routineItems: List<RoutineItem>,
    val routineTicks: Map<LocalDate, List<String>>,
    /** Feiertage für Wecker mit «An Feiertagen nicht wecken». */
    val holidays: Set<LocalDate> = emptySet(),
    /** Gespeicherter Probewecker oder Powernap (auch abgelaufen; [Transient.isActive] prüft). */
    val transient: TransientPlan? = null,
    /** Laufender oder beendeter Probewecker für das Ergebnis; `null` = nichts anzuzeigen. */
    val testRun: TestRun? = null,
    /** Vorschlag der Powernap-Dauer in Minuten. */
    val napMinutes: Int = Transient.NAP_DEFAULT_MINUTES,
    /** Ersteinrichtung erledigt; `null`, bis gelesen. */
    val onboardingDone: Boolean? = null,
    /** Diagnose in den Einstellungen sichtbar. */
    val diagnostics: Boolean = false,
    /** Pro Wecker erledigt bis (Uhr hat die Nacht übernommen, Zweitwecker hat geweckt); siehe `BackupAlarm.doneUntilFlow`. */
    val doneUntil: Map<String, Instant> = emptyMap(),
)

/** Sammelt [AppData] aus den Repositories; Verlauf und Geräusche werden einmal geladen. */
@Composable
internal fun collectAppData(app: PhaseWakeApp): AppData {
    val alarms by app.alarms.alarms.collectAsStateWithLifecycle(initialValue = emptyList())
    val nights by app.nights.nights.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { app.nights.load() }
    val notes by app.notes.notes.collectAsStateWithLifecycle(initialValue = emptyMap())
    val customNotes by app.notes.custom.collectAsStateWithLifecycle(initialValue = emptyList())
    val sleepGoal by app.settings.sleepGoal.collectAsStateWithLifecycle(initialValue = SleepGoal.DEFAULT_MINUTES)
    val bedtimeReminder by app.settings.bedtimeReminder.collectAsStateWithLifecycle(initialValue = false)
    val soundTimer by app.settings.soundTimer.collectAsStateWithLifecycle(initialValue = SleepSounds.DEFAULT_TIMER_MINUTES)
    val sound by app.settings.sound.collectAsStateWithLifecycle(initialValue = SleepSound.PINK)
    val soundPlaying by SleepSoundService.playing.collectAsStateWithLifecycle()
    val recording by SoundRecorderService.recording.collectAsStateWithLifecycle()
    val weeklyReview by app.settings.weeklyReview.collectAsStateWithLifecycle(initialValue = false)
    val soundClips by app.sounds.clips.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { app.sounds.load() }
    val routineItems by app.routine.items.collectAsStateWithLifecycle(initialValue = emptyList())
    val routineTicks by app.routine.ticks.collectAsStateWithLifecycle(initialValue = emptyMap())
    val holidays by app.holidays.store.dates.collectAsStateWithLifecycle(initialValue = emptySet())
    val transient by app.transient.stored.collectAsStateWithLifecycle(initialValue = null)
    val testRun by app.testRuns.run.collectAsStateWithLifecycle(initialValue = null)
    val napMinutes by app.settings.napMinutes.collectAsStateWithLifecycle(initialValue = Transient.NAP_DEFAULT_MINUTES)
    val onboardingFlow = remember { app.settings.onboardingDone.map<Boolean, Boolean?> { it } }
    val onboardingDone by onboardingFlow.collectAsStateWithLifecycle(initialValue = null)
    val diagnostics by app.settings.diagnostics.collectAsStateWithLifecycle(initialValue = false)
    val doneUntil by app.backup.doneUntilFlow.collectAsStateWithLifecycle(initialValue = emptyMap())
    return AppData(
        alarms, nights, notes, customNotes, sleepGoal, bedtimeReminder, soundTimer, sound,
        soundPlaying, recording, weeklyReview, soundClips, routineItems, routineTicks,
        holidays, transient, testRun, napMinutes, onboardingDone, diagnostics, doneUntil,
    )
}

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
)

/**
 * Rechnet die [Insights]. [healthCheck] zählt bei jedem Fortsetzen und nach der Berechtigung hoch:
 * Dann wird Health Connect neu geprüft und gelesen (neue Nächte).
 */
@Composable
internal fun rememberInsights(app: PhaseWakeApp, data: AppData, healthCheck: Int): Insights {
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
    // so weit zurück wie der Verlauf, mindestens bis vor die älteste eigene Nacht. Nur im Speicher.
    val sessions by produceState(emptyList<HealthSession>(), health, healthCheck, nights?.size) {
        val now = Instant.now()
        val from = listOfNotNull(
            now.minus(Duration.ofDays(HealthHistory.DAYS + 1)),
            nights?.minOfOrNull { it.windowStart }?.minus(STAGES_BEFORE),
        ).min()
        value = if (health == HealthState.CONNECTED) app.health.sessions(from, now) else emptyList()
    }
    val sleeps = remember(sessions, clearedAt) { HealthHistory.summaries(sessions, clearedAt) }
    val stages = remember(sessions, clearedAt) { HealthHistory.stages(sessions, clearedAt) }
    val history = remember(nights, sessions, clearedAt) { HealthHistory.merge(nights.orEmpty(), sessions, clearedAt, ZoneId.systemDefault()) }
    val trends = remember(nights, sleeps) { Trends.compute(nights.orEmpty(), sleeps, Instant.now(), ZoneId.systemDefault()) }
    val goalWeek = remember(sleeps, data.sleepGoal) { SleepGoal.weekStatus(SleepGoal.nightsOf(sleeps, ZoneId.systemDefault()), data.sleepGoal) }
    val noteEffects = remember(nights, data.notes, sleeps) {
        val zone = ZoneId.systemDefault()
        NoteEffect.compute(nights.orEmpty(), data.notes, zone, NoteEffect.asleepByNight(sleeps, zone))
    }
    val habits = remember(sleeps, data.sleepGoal, data.routineItems, data.routineTicks) { habitLines(sleeps, data) }
    // Diagramme im Verlauf: Schlaf aus Health Connect, Weckmomente und Befinden aus dem Verlauf.
    val charts = remember(nights, sleeps, stages, data.notes, data.alarms, data.sleepGoal) {
        HistoryCharts.of(nights.orEmpty(), sleeps, stages, data.notes, data.alarms, data.sleepGoal, Instant.now(), ZoneId.systemDefault())
    }
    val weekly = remember(nights, sleeps, data.notes, data.sleepGoal) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        if (!WeeklySummary.cardVisible(today)) null
        else WeeklySummary.of(nights.orEmpty(), sleeps, data.notes, zone, data.sleepGoal, WeeklySummary.weekEnd(today)).takeIf { it.hasData }
    }
    // Text der nächsten Wochenrückblick-Mitteilung hier vorberechnen (App offen, neue Nacht, Befinden,
    // Notizen): Im Hintergrund liest Health Connect ohne eigene Berechtigung keine Schlafdaten.
    LaunchedEffect(data.weeklyReview, nights, sleeps, data.notes, data.sleepGoal, data.customNotes) {
        val list = nights ?: return@LaunchedEffect
        if (data.weeklyReview) app.weekly.prepare(list, sleeps, data.notes, data.sleepGoal, data.customNotes)
    }
    return Insights(health, stages, trends, goalWeek, noteEffects, habits, charts, weekly, history)
}

/** Gewohnheiten: Regelmässigkeit und Serien aus der Gesundheits-App, Abendroutine aus den Haken. */
private fun habitLines(sleeps: List<SleepSummary>, data: AppData): HabitLines {
    val zone = ZoneId.systemDefault()
    val today = SleepNotes.nightDate(Instant.now(), zone)
    val reached = Streak.reachedByNight(sleeps, zone, data.sleepGoal)
    return HabitLines(
        regular = Regularity.count(Regularity.bedtimesOf(sleeps, zone), zone, until = today),
        streak = Streak.current(reached, today),
        bestStreak = Streak.longest(reached, today),
        routineAverage = Routine.average(data.routineTicks, data.routineItems, today),
        routineItems = data.routineItems.size,
    )
}

/** Schlafphasen einer Nacht: so weit vor dem Fensterbeginn und nach dem Wecken (ganze Nacht samt Aufwachen). */
internal val STAGES_BEFORE: Duration = Duration.ofHours(14)
internal val STAGES_AFTER: Duration = Duration.ofHours(2)

/** «Geräusche aufnehmen»: Zustand für die Startseite, Schalter für diese Nacht und für die Vorgabe. */
internal class SoundRecControl(
    val state: SoundRecState,
    /** Vorgabe «für neue Nächte» aus den Einstellungen. */
    val default: Boolean,
    val onSoundRec: (Boolean) -> Unit,
    val onDefault: (Boolean) -> Unit,
)

/**
 * Geräusche aufnehmen: für diese Nacht gewählt, sonst die Vorgabe aus den Einstellungen – diese aber
 * erst ab der Bettzeit der kommenden Nacht (ohne Wecker ab 21 Uhr); davor nur von Hand. Bei jedem
 * Fortsetzen ([checkRun]) neu beurteilt.
 */
@Composable
internal fun rememberSoundRec(app: PhaseWakeApp, data: AppData, checkRun: Int): SoundRecControl {
    val context = LocalContext.current
    val soundRecDefault by app.settings.soundRecDefault.collectAsStateWithLifecycle(initialValue = false)
    val soundRecChoice by app.settings.soundRecChoice.collectAsStateWithLifecycle(initialValue = null)
    fun tonight() = SleepNotes.nightDate(Instant.now(), ZoneId.systemDefault())
    val soundRecChosen = soundRecChoice?.takeIf { it.first == tonight() }?.second
    val soundRecAuto = remember(soundRecDefault, data.alarms, data.sleepGoal, data.holidays, checkRun) {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        soundRecDefault && AlarmSchedule.earliestPlan(data.alarms, now, zone, data.holidays).let { plan -> SoundEvents.mayAutoStart(now, plan?.target, data.sleepGoal, zone, plan?.windowStart) }
    }
    val soundRecWanted = soundRecChosen ?: soundRecAuto
    var micDenied by rememberSaveable { mutableStateOf(false) }
    fun hasMic() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun startRecording() = SoundRecorderService.start(context)
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micDenied = !granted
        if (granted) {
            app.appScope.launch { app.settings.setSoundRecChoice(tonight(), true) }
            startRecording()
        }
    }
    // Vorgabe in den Einstellungen: nur fragen, nichts starten. Eingeschaltet bleibt sie nur mit Mikrofon (wie iPhone).
    val micPermissionDefault = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micDenied = !granted
        if (granted) app.appScope.launch { app.settings.setSoundRecDefault(true) }
    }
    fun onSoundRec(enabled: Boolean) {
        if (enabled && !hasMic()) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        app.appScope.launch { app.settings.setSoundRecChoice(tonight(), enabled) }
        if (enabled) startRecording() else SoundRecorderService.stop(context)
    }
    // Aufnahme für diese Nacht gewünscht, aber (noch) nicht gestartet: beim Öffnen starten – selbst
    // eingeschaltet am Abend (bis zum Weckfenster), nur die Vorgabe ab der Bettzeit ([soundRecAuto]).
    // Nur sichtbar (Mikrofon-Dienst ab Android 14 nur aus der sichtbaren App) und mit Mikrofon-Recht.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(soundRecWanted, data.recording, data.alarms, checkRun) {
        if (!soundRecWanted || data.recording != null || !hasMic()) return@LaunchedEffect
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        if (soundRecChosen != true || SleepNotes.entryVisible(now, zone, AlarmSchedule.earliestPlan(data.alarms, now, zone, data.holidays)?.windowStart)) startRecording()
    }
    return SoundRecControl(
        state = SoundRecState(soundRecWanted && (data.recording != null || hasMic()), data.recording != null, micDenied),
        default = soundRecDefault,
        onSoundRec = ::onSoundRec,
        onDefault = { enabled ->
            // Ohne Mikrofon erst fragen; gespeichert wird «an» erst nach der Zusage.
            if (enabled && !hasMic()) {
                micPermissionDefault.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                app.appScope.launch { app.settings.setSoundRecDefault(enabled) }
            }
        },
    )
}

/**
 * Nacht-Check und die Uhren ohne Phase Wake (für den Knopf «Auf der Uhr installieren»). [watchKnowsSkip]: Die Uhr
 * kennt «Überspringen» und «Feiertage» (oder es ist keine bekannt); sonst blendet das Handy beides aus.
 */
internal class NightCheckData(val check: NightCheckState?, val missingOn: List<String>, val watchKnowsSkip: Boolean = true)

/**
 * Nacht-Check: bei jedem Öffnen ([checkRun]) und danach jede halbe Minute (die Uhr kann kommen und
 * gehen). Nur solange die App sichtbar ist – im Hintergrund nicht abfragen (Akku).
 */
@Composable
internal fun rememberNightCheck(app: PhaseWakeApp, checkRun: Int): NightCheckData {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var check by remember { mutableStateOf<NightCheckState?>(null) }
    var missingOn by remember { mutableStateOf(emptyList<String>()) }
    var knowsSkip by remember { mutableStateOf(true) }
    LaunchedEffect(checkRun) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                missingOn = app.sync.nodesWithout(DataLayer.CAPABILITY_WATCH)
                knowsSkip = Codec.watchKnowsSkip(app.sync.watchAlarmFields())
                check = NightCheckState.collect(
                    context,
                    app.sync.isReachable(DataLayer.CAPABILITY_WATCH),
                    app.sync.watchReady(),
                    app.sync.watchBattery(),
                    watchAppMissing = missingOn.isNotEmpty(),
                    watchKnowsSkip = knowsSkip,
                )
                check?.let { com.phasewake.app.widget.NextAlarmWidget.saveCheck(context, it.failed) }
                delay(NIGHT_CHECK_INTERVAL_MS)
            }
        }
    }
    return NightCheckData(check, missingOn, knowsSkip)
}

/** Abstand der Nacht-Checks, solange die App sichtbar ist. */
private const val NIGHT_CHECK_INTERVAL_MS = 30_000L
