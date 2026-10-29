package com.phasewake.app.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.phasewake.app.PhaseWakeApp
import com.phasewake.app.R
import com.phasewake.app.diag.DiagnosticsScreen
import com.phasewake.app.security.DeviceAuth
import com.phasewake.app.ui.onboarding.OnboardingScreen
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.MissHint
import com.phasewake.app.core.SleepSound
import com.phasewake.app.core.SleepSounds
import com.phasewake.app.core.WindowAdvice
import com.phasewake.app.sounds.SleepSoundService
import com.phasewake.app.ui.editor.EditorScreen
import com.phasewake.app.ui.history.HistoryScreen
import com.phasewake.app.ui.history.NightDetailActions
import com.phasewake.app.ui.history.NightDetailData
import com.phasewake.app.ui.history.NightDetailScreen
import com.phasewake.app.ui.home.Check
import com.phasewake.app.ui.home.HomeScreen
import com.phasewake.app.ui.home.MAX_ALARMS
import com.phasewake.app.ui.settings.HealthState
import com.phasewake.app.ui.settings.SettingsScreen
import com.phasewake.app.ui.sounds.SoundsScreen
import com.phasewake.app.ui.theme.ThemeMode
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch

// Bildschirme der Handy-App im NavHost: Daten aus [NavState], Aktionen aus [NavActions].

/** Was die Bildschirme zeigen. */
internal class NavState(
    val data: AppData,
    val insights: Insights,
    val soundRec: SoundRecControl,
    val nightCheck: NightCheckData,
    val themeMode: ThemeMode,
)

/** Aktionen, die mehrere Bildschirme brauchen (Berechtigungen und Dialoge liegen in [PhaseWakeNav]). */
internal class NavActions(
    val nav: NavHostController,
    /** Teilen des Verlaufs: zuerst der Hinweis, was die Datei enthält. */
    val onExport: () -> Unit,
    /** Fragt nach dem Mitteilungsrecht, falls es fehlt (ab Android 13); sonst nichts. */
    val askNotificationsIfMissing: () -> Unit,
    val connectHealth: () -> Unit,
    val onFix: (Check) -> Unit,
)

@Composable
internal fun AppNavHost(app: PhaseWakeApp, state: NavState, actions: NavActions, content: PaddingValues, homeList: LazyListState) {
    NavHost(
        actions.nav,
        startDestination = Routes.ALARMS,
        enterTransition = { fadeIn() + slideInHorizontally { it / 12 } },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { fadeOut() + slideOutHorizontally { it / 12 } },
    ) {
        composable(Routes.ALARMS) { AlarmsRoute(app, state, actions, content, homeList) }
        composable(Routes.HISTORY) { HistoryRoute(app, state, actions, content) }
        composable(Routes.SETTINGS) { SettingsRoute(app, state, actions, content) }
        composable(Routes.SOUNDS) { SoundsRoute(app, state.data, actions) }
        composable(ExtraRoutes.DIAGNOSTICS) {
            DiagnosticsScreen(
                onBack = { actions.nav.popBackStack() },
                onHide = {
                    app.appScope.launch { app.settings.setDiagnostics(false) }
                    actions.nav.popBackStack()
                },
            )
        }
        composable(ExtraRoutes.ONBOARDING) {
            OnboardingScreen(app, state, actions, onDone = {
                app.appScope.launch { app.settings.setOnboardingDone(true) }
                // Einrichtung selbst vom Stapel nehmen (nicht nur einen Tab wählen): sonst bleibt sie offen, weil der
                // Wecker-Tab darunter schon liegt. Zurück dorthin, woher sie kam (Startseite oder Einstellungen).
                if (!actions.nav.popBackStack(ExtraRoutes.ONBOARDING, inclusive = true)) actions.nav.switchTab(Routes.ALARMS)
            })
        }
        composable(
            ExtraRoutes.NIGHT,
            arguments = listOf(navArgument("key") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry -> NightRoute(app, state, entry.arguments?.getString("key"), actions) }
        composable(
            Routes.EDITOR,
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry -> EditorRoute(app, state, entry.arguments?.getString("id"), actions) }
    }
}

@Composable
private fun AlarmsRoute(app: PhaseWakeApp, state: NavState, actions: NavActions, content: PaddingValues, homeList: LazyListState) {
    val data = state.data
    val nights = data.nights
    HomeScreen(
        listState = homeList,
        alarms = data.alarms,
        lastNight = nights?.lastOrNull(),
        missHint = nights?.lastOrNull()?.let { last -> MissHint.of(data.alarms.firstOrNull { it.id == last.alarmId }, nights.orEmpty()) },
        check = state.nightCheck.check,
        onFix = actions.onFix,
        contentPadding = content,
        onAdd = { actions.nav.navigate(Routes.editor(null)) },
        onEdit = { actions.nav.navigate(Routes.editor(it)) },
        onToggle = { alarm, enabled -> app.afterAlarmsChanged { app.alarms.setEnabled(alarm.id, enabled) } },
        onFeeling = { night, feeling -> app.appScope.launch { app.nights.setFeeling(night.alarmId, night.windowStart, feeling) } },
        notes = data.notes,
        customNotes = data.customNotes,
        onToggleNote = { date, id -> app.appScope.launch { app.notes.toggle(date, id) } },
        soundPlaying = data.soundPlaying,
        onSounds = { actions.nav.navigate(Routes.SOUNDS) },
        soundRec = state.soundRec.state,
        onSoundRec = state.soundRec.onSoundRec,
        soundClips = data.soundClips.orEmpty(),
        soundFile = { app.sounds.file(it) },
        onDeleteClip = { clip -> app.appScope.launch { app.sounds.delete(clip) } },
        onDeleteNightClips = { date -> app.appScope.launch { app.sounds.deleteNight(date) } },
        routineItems = data.routineItems,
        routineTicks = data.routineTicks,
        onToggleRoutine = { date, id -> app.appScope.launch { app.routine.toggle(date, id) } },
        extras = homeExtras(app, data, skipAllowed = state.nightCheck.watchKnowsSkip),
    )
}

@Composable
private fun HistoryRoute(app: PhaseWakeApp, state: NavState, actions: NavActions, content: PaddingValues) {
    val data = state.data
    val insights = state.insights
    HistoryScreen(
        data.nights.orEmpty(),
        content,
        loadStages = if (insights.health == HealthState.CONNECTED) {
            { night -> insights.stages.filter { it.end.isAfter(night.windowStart.minus(STAGES_BEFORE)) && it.start.isBefore(night.wokeAt.plus(STAGES_AFTER)) } }
        } else {
            null
        },
        onExport = actions.onExport,
        trends = insights.trends,
        healthConnected = insights.health == HealthState.CONNECTED,
        onConnectHealth = actions.connectHealth,
        goalWeek = insights.goalWeek,
        notes = data.notes,
        customNotes = data.customNotes,
        onToggleNote = { date, id -> app.appScope.launch { app.notes.toggle(date, id) } },
        noteEffects = insights.noteEffects,
        habits = insights.habits,
        weekly = insights.weekly,
        soundClips = data.soundClips.orEmpty(),
        soundFile = { app.sounds.file(it) },
        onDeleteClip = { clip -> app.appScope.launch { app.sounds.delete(clip) } },
        onDeleteNightClips = { date -> app.appScope.launch { app.sounds.deleteNight(date) } },
        charts = insights.charts,
        items = insights.history,
        canConnectHealth = insights.health == HealthState.AVAILABLE,
        healthFeelings = data.healthFeelings,
        onOpenNight = { key -> actions.nav.navigate(ExtraRoutes.night(key)) },
    )
}

/** Detailseite einer Nacht mit Wischen zu den Nächten davor und danach (Verlauf, Phasen, Notizen, Geräusche). */
@Composable
private fun NightRoute(app: PhaseWakeApp, state: NavState, key: String?, actions: NavActions) {
    val data = state.data
    NightDetailScreen(
        NightDetailData(
            items = state.insights.history,
            stages = state.insights.stages,
            notes = data.notes,
            customNotes = data.customNotes,
            healthFeelings = data.healthFeelings,
            soundClips = data.soundClips.orEmpty(),
        ),
        startKey = key,
        actions = NightDetailActions(
            onBack = { actions.nav.popBackStack() },
            onToggleNote = { date, id -> app.appScope.launch { app.notes.toggle(date, id) } },
            onHealthFeeling = { date, feeling -> app.appScope.launch { app.notes.setHealthFeeling(date, feeling) } },
            soundFile = { app.sounds.file(it) },
            onDeleteClip = { clip -> app.appScope.launch { app.sounds.delete(clip) } },
            onDeleteNightClips = { date -> app.appScope.launch { app.sounds.deleteNight(date) } },
        ),
    )
}

@Composable
private fun SettingsRoute(app: PhaseWakeApp, state: NavState, actions: NavActions, content: PaddingValues) {
    val context = LocalContext.current
    val res = LocalResources.current
    val data = state.data
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    SettingsScreen(
        themeMode = state.themeMode,
        version = version,
        contentPadding = content,
        onThemeMode = { app.appScope.launch { app.settings.setThemeMode(it) } },
        onExport = actions.onExport,
        canExport = !data.nights.isNullOrEmpty(),
        onClearHistory = {
            // Nach der Rückfrage noch die Gerätesperre (falls eingerichtet); Abbruch löscht nichts.
            DeviceAuth.confirm(context, res.getString(R.string.auth_clear_history)) {
                app.appScope.launch {
                    app.nights.clear()
                    // Nächte aus Health Connect lassen sich nicht löschen: bis jetzt ausblenden.
                    app.settings.setHistoryClearedAt(Instant.now())
                    app.notes.clearNotes()
                    app.routine.clearTicks()
                    app.sounds.clear()
                    app.sync.clearHistoryEverywhere()
                }
            }
        },
        health = state.insights.health,
        onConnectHealth = actions.connectHealth,
        goalMinutes = data.sleepGoal,
        onGoal = { minutes ->
            app.appScope.launch {
                app.settings.setSleepGoal(minutes)
                app.publishSleepGoal()
                app.bedtime.reschedule()
            }
        },
        bedtimeReminder = data.bedtimeReminder,
        onBedtimeReminder = { enabled ->
            // Ohne Mitteilungen keine Erinnerung: beim Einschalten einmal fragen.
            if (enabled) actions.askNotificationsIfMissing()
            app.appScope.launch {
                app.settings.setBedtimeReminder(enabled)
                app.bedtime.reschedule()
            }
        },
        customNotes = data.customNotes,
        onAddCustomNote = { name -> app.appScope.launch { app.notes.addCustom(name) } },
        onRenameCustomNote = { id, name -> app.appScope.launch { app.notes.renameCustom(id, name) } },
        onDeleteCustomNote = { id -> app.appScope.launch { app.notes.deleteCustom(id) } },
        weeklyReview = data.weeklyReview,
        onWeeklyReview = { enabled ->
            if (enabled) actions.askNotificationsIfMissing()
            app.appScope.launch {
                app.settings.setWeeklyReview(enabled)
                app.weekly.reschedule()
            }
        },
        soundRecDefault = state.soundRec.default,
        onSoundRecDefault = state.soundRec.onDefault,
        routineItems = data.routineItems,
        onAddRoutineItem = { text -> app.appScope.launch { app.routine.add(text) } },
        onRemoveRoutineItem = { id -> app.appScope.launch { app.routine.remove(id) } },
        extras = settingsExtras(app, data, actions),
    )
}

@Composable
private fun SoundsRoute(app: PhaseWakeApp, data: AppData, actions: NavActions) {
    val context = LocalContext.current
    // Einschlafklänge: Ende nach dem Timer, spätestens zu Beginn des nächsten Weckfensters.
    fun startSounds(chosen: SleepSound, timer: Int?) {
        val now = Instant.now()
        val endsAt = SleepSounds.stopAt(now, timer, AlarmSchedule.earliestPlan(data.alarms, now, ZoneId.systemDefault(), data.holidays)?.windowStart)
        if (endsAt != null && !endsAt.isAfter(now)) return
        SleepSoundService.play(context, chosen, endsAt)
    }
    val now = Instant.now()
    val windowStart = AlarmSchedule.earliestPlan(data.alarms, now, ZoneId.systemDefault(), data.holidays)?.windowStart
    SoundsScreen(
        playing = data.soundPlaying,
        selected = data.sound,
        timer = data.soundTimer,
        canPlay = windowStart == null || windowStart.isAfter(now),
        onBack = { actions.nav.popBackStack() },
        onSelect = { chosen ->
            app.appScope.launch { app.settings.setSound(chosen) }
            // Läuft schon ein Klang: gleich wechseln.
            if (data.soundPlaying != null) startSounds(chosen, data.soundTimer)
        },
        onTimer = { minutes ->
            app.appScope.launch { app.settings.setSoundTimer(minutes) }
            // Läuft schon ein Klang: Timer ab jetzt neu.
            data.soundPlaying?.let { startSounds(it.sound, minutes) }
        },
        onPlay = { startSounds(data.sound, data.soundTimer) },
        onStop = { SleepSoundService.stop(context) },
    )
}

@Composable
private fun EditorRoute(app: PhaseWakeApp, state: NavState, id: String?, actions: NavActions) {
    val data = state.data
    val existing = data.alarms.firstOrNull { it.id == id }
    EditorScreen(
        existing = existing,
        holidaysAllowed = state.nightCheck.watchKnowsSkip,
        advice = existing?.let { WindowAdvice.suggest(it, data.nights.orEmpty()) },
        onBack = { actions.nav.popBackStack() },
        onSave = { spec ->
            // Ein neuer Wecker über dem Limit (z. B. gleichzeitig auf der Uhr angelegt) wird nicht gespeichert.
            if (existing != null || data.alarms.size < MAX_ALARMS) app.afterAlarmsChanged { app.alarms.upsert(spec) }
            actions.nav.popBackStack()
        },
        onDelete = { spec ->
            app.afterAlarmsChanged { app.alarms.remove(spec.id) }
            actions.nav.popBackStack()
        },
    )
}
