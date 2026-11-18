package com.phasewake.app.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.phasewake.app.sounds.SleepSoundService
import com.phasewake.app.ui.history.HistoryActions
import com.phasewake.app.ui.history.HistoryScreen
import com.phasewake.app.ui.history.HistoryState
import com.phasewake.app.ui.history.NightDetailActions
import com.phasewake.app.ui.history.NightDetailData
import com.phasewake.app.ui.history.NightDetailScreen
import com.phasewake.app.ui.home.Check
import com.phasewake.app.ui.home.HomeActions
import com.phasewake.app.ui.home.HomeScreen
import com.phasewake.app.ui.home.HomeState
import com.phasewake.app.ui.home.LearnNightsScreen
import com.phasewake.app.ui.home.goodNightOffered
import com.phasewake.app.ui.settings.HealthState
import com.phasewake.app.ui.settings.LicensesScreen
import com.phasewake.app.ui.settings.SettingsActions
import com.phasewake.app.ui.settings.SettingsScreen
import com.phasewake.app.ui.settings.SettingsState
import com.phasewake.app.ui.sounds.SoundsActions
import com.phasewake.app.ui.sounds.SoundsScreen
import com.phasewake.app.ui.sounds.SoundsState
import com.phasewake.app.ui.theme.EveningDarkSetting
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
    /** «In 2 Minuten testen»; ersetzt er einen wartenden Powernap, fragt er zuerst. */
    val onTest: () -> Unit,
)

@Composable
internal fun AppNavHost(
    app: PhaseWakeApp,
    state: NavState,
    actions: NavActions,
    content: PaddingValues,
    homeList: LazyListState,
) {
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
        // Zurück immer über backFrom: Ein zweiter schneller Tipp nimmt sonst den Bildschirm darunter mit.
        composable(Routes.SOUNDS) { entry -> SoundsRoute(app, state.data, onBack = { actions.nav.backFrom(entry) }) }
        composable(ExtraRoutes.DIAGNOSTICS) { entry ->
            DiagnosticsScreen(
                onBack = { actions.nav.backFrom(entry) },
                onHide = {
                    app.appScope.launch { app.settings.setDiagnostics(false) }
                    actions.nav.backFrom(entry)
                },
            )
        }
        composable(ExtraRoutes.LICENSES) { entry -> LicensesScreen(onBack = { actions.nav.backFrom(entry) }) }
        composable(ExtraRoutes.LEARN_NIGHTS) { entry ->
            val data = state.data
            val goodNight =
                goodNightOffered(data.alarms, Instant.now(), ZoneId.systemDefault(), data.holidays, data.doneUntil)
            LearnNightsScreen(learnNightList(data), onBack = { actions.nav.backFrom(entry) }, goodNight = goodNight)
        }
        composable(ExtraRoutes.ONBOARDING) { entry ->
            OnboardingScreen(app, state, actions, onDone = {
                // Nur von der offenen Einrichtung aus (zweiter Tipp auf «Fertig» oder «Überspringen»: nichts).
                if (actions.nav.currentBackStackEntry?.id != entry.id) return@OnboardingScreen
                app.appScope.launch { app.settings.setOnboardingDone(true) }
                // Einrichtung selbst vom Stapel nehmen (nicht nur einen Tab wählen): sonst bleibt sie offen, weil der
                // Wecker-Tab darunter schon liegt. Zurück dorthin, woher sie kam (Startseite oder Einstellungen).
                if (!actions.nav.popBackStack(ExtraRoutes.ONBOARDING, inclusive = true)) {
                    actions.nav.switchTab(Routes.ALARMS)
                }
            })
        }
        composable(
            ExtraRoutes.NIGHT,
            arguments = listOf(navArgument("key") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            NightRoute(app, state, entry.arguments?.getString("key"), onBack = { actions.nav.backFrom(entry) })
        }
        composable(
            Routes.EDITOR,
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry -> EditorRoute(app, state, entry.arguments?.getString("id"), actions, entry) }
    }
}

@Composable
private fun AlarmsRoute(
    app: PhaseWakeApp,
    state: NavState,
    actions: NavActions,
    content: PaddingValues,
    homeList: LazyListState,
) {
    val data = state.data
    val recordStatus by app.recordStatus.collectAsStateWithLifecycle()
    val nights = data.nights
    val findings = rememberFindings(app, state.insights)
    HomeScreen(
        state = HomeState(
            alarms = data.alarms,
            lastNight = nights?.lastOrNull(),
            missHint = nights?.lastOrNull()
                ?.let { last -> MissHint.of(data.alarms.firstOrNull { it.id == last.alarmId }, nights.orEmpty()) },
            finding = findings.home,
            bedtimeFinding = findings.homeBedtime,
            check = state.nightCheck.check,
            notes = data.notes,
            customNotes = data.customNotes,
            soundPlaying = data.soundPlaying,
            soundRec = state.soundRec.state,
            soundClips = data.soundClips.orEmpty(),
            soundFile = { app.sounds.file(it) },
            routineItems = data.routineItems,
            routineTicks = data.routineTicks,
        ),
        actions = HomeActions(
            onHideFinding = findings.onHide,
            onFix = actions.onFix,
            onAdd = { actions.nav.navigate(Routes.editor(null)) },
            onEdit = { actions.nav.navigate(Routes.editor(it)) },
            onToggle = { alarm, enabled -> app.afterAlarmsChanged { app.alarms.setEnabled(alarm.id, enabled) } },
            onFeeling = { night, feeling ->
                app.appScope.launch { app.nights.setFeeling(night.alarmId, night.windowStart, feeling) }
            },
            onToggleNote = { date, id -> app.appScope.launch { app.notes.toggle(date, id) } },
            onSounds = { actions.nav.navigate(Routes.SOUNDS) },
            onSoundRec = state.soundRec.onSoundRec,
            onDeleteClip = { clip -> app.appScope.launch { app.sounds.delete(clip) } },
            onDeleteNightClips = { date -> app.appScope.launch { app.sounds.deleteNight(date) } },
            onToggleRoutine = { date, id -> app.appScope.launch { app.routine.toggle(date, id) } },
        ),
        listState = homeList,
        contentPadding = content,
        extras = homeExtras(
            app, data, record = recordStatus, onLearnDetails = { actions.nav.navigate(ExtraRoutes.LEARN_NIGHTS) },
            onTest = actions.onTest,
        ),
    )
}

@Composable
private fun HistoryRoute(app: PhaseWakeApp, state: NavState, actions: NavActions, content: PaddingValues) {
    val data = state.data
    val insights = state.insights
    val findings = rememberFindings(app, insights)
    val recordings by app.recordings.recordings.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { app.recordings.load() }
    HistoryScreen(
        state = HistoryState(
            nights = data.nights.orEmpty(),
            loadStages = if (insights.health == HealthState.CONNECTED) {
                { night -> insights.stages.filter {
                    it.end.isAfter(night.windowStart.minus(STAGES_BEFORE)) &&
                        it.start.isBefore(night.wokeAt.plus(STAGES_AFTER))
                } }
            } else {
                null
            },
            trends = insights.trends,
            healthConnected = insights.health == HealthState.CONNECTED,
            goalWeek = insights.goalWeek,
            notes = data.notes,
            customNotes = data.customNotes,
            noteEffects = insights.noteEffects,
            habits = insights.habits,
            weekly = insights.weekly,
            weeklyDeep = findings.weekDeep,
            findings = findings.all,
            soundClips = data.soundClips.orEmpty(),
            soundFile = { app.sounds.file(it) },
            charts = insights.charts,
            items = insights.history,
            canConnectHealth = insights.health == HealthState.AVAILABLE,
            healthFeelings = data.healthFeelings,
            recordings = recordings.orEmpty(),
            goalMinutes = data.sleepGoal, asleep = insights.asleep,
        ),
        contentPadding = content,
        actions = HistoryActions(
            onConnectHealth = actions.connectHealth,
            onToggleNote = { date, id -> app.appScope.launch { app.notes.toggle(date, id) } },
            onDeleteClip = { clip -> app.appScope.launch { app.sounds.delete(clip) } },
            onDeleteNightClips = { date -> app.appScope.launch { app.sounds.deleteNight(date) } },
            onOpenNight = { key -> actions.nav.navigate(ExtraRoutes.night(key)) },
        ),
    )
}

/** Detailseite einer Nacht mit Wischen zu den Nächten davor und danach (Verlauf, Phasen, Notizen, Geräusche). */
@Composable
private fun NightRoute(app: PhaseWakeApp, state: NavState, key: String?, onBack: () -> Unit) {
    val data = state.data
    val recordings by app.recordings.recordings.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { app.recordings.load() }
    NightDetailScreen(
        NightDetailData(
            items = state.insights.history,
            stages = state.insights.stages,
            notes = data.notes,
            customNotes = data.customNotes,
            healthFeelings = data.healthFeelings,
            soundClips = data.soundClips.orEmpty(),
            goalMinutes = data.sleepGoal,
            recordings = recordings.orEmpty(),
            sessions = state.insights.sessions,
        ),
        startKey = key,
        actions = NightDetailActions(
            onBack = onBack,
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
    // Mit Build-Nummer der Testversion, z. B. «16.2.2 (96)».
    val version = remember { com.phasewake.app.shared.BuildNumber.ownLabel(context, watch = false) }
    val nextAlarmNotice by app.settings.nextAlarmNotice.collectAsStateWithLifecycle(initialValue = true)
    val eveningDark by app.settings.eveningDark.collectAsStateWithLifecycle(initialValue = EveningDarkSetting())
    // Export nur hier (Verlauf ohne Knopf): aktiv mit Nächten oder abgeschlossenen Aufzeichnungen.
    val recordings by app.recordings.recordings.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { app.recordings.load() }
    SettingsScreen(
        state = SettingsState(
            themeMode = state.themeMode,
            version = version,
            eveningDark = eveningDark,
            canExport = !data.nights.isNullOrEmpty() || !recordings.isNullOrEmpty(),
            health = state.insights.health,
            goalMinutes = data.sleepGoal,
            bedtimeReminder = data.bedtimeReminder,
            customNotes = data.customNotes,
            weeklyReview = data.weeklyReview,
            nextAlarmNotice = nextAlarmNotice,
            autoOnset = data.autoOnset,
            soundRecDefault = state.soundRec.default,
            routineItems = data.routineItems,
        ),
        actions = SettingsActions(
            onThemeMode = { app.appScope.launch { app.settings.setThemeMode(it) } },
            onEveningDark = { app.appScope.launch { app.settings.setEveningDark(it) } },
            onEveningDarkFrom = { app.appScope.launch { app.settings.setEveningDarkFrom(it) } },
            onEveningDarkUntil = { app.appScope.launch { app.settings.setEveningDarkUntil(it) } },
            onExport = actions.onExport,
            onClearHistory = {
                // Nach der Rückfrage noch die Gerätesperre (falls eingerichtet); Abbruch löscht nichts.
                DeviceAuth.confirm(context, res.getString(R.string.auth_clear_history)) {
                    app.appScope.launch { app.clearHistory() }
                }
            },
            onConnectHealth = actions.connectHealth,
            onGoal = { minutes ->
                app.appScope.launch {
                    app.settings.setSleepGoal(minutes)
                    app.publishSleepGoal()
                    app.bedtime.reschedule()
                    app.nextAlarm.reschedule()
                }
            },
            onBedtimeReminder = { enabled ->
                // Ohne Mitteilungen keine Erinnerung: beim Einschalten einmal fragen.
                if (enabled) actions.askNotificationsIfMissing()
                app.appScope.launch {
                    app.settings.setBedtimeReminder(enabled)
                    app.bedtime.reschedule()
                    app.nextAlarm.reschedule()
                }
            },
            onAddCustomNote = { name -> app.appScope.launch { app.notes.addCustom(name) } },
            onRenameCustomNote = { id, name -> app.appScope.launch { app.notes.renameCustom(id, name) } },
            onDeleteCustomNote = { id -> app.appScope.launch { app.notes.deleteCustom(id) } },
            onWeeklyReview = { enabled ->
                if (enabled) actions.askNotificationsIfMissing()
                app.appScope.launch {
                    app.settings.setWeeklyReview(enabled)
                    app.weekly.reschedule()
                }
            },
            onNextAlarmNotice = { enabled ->
                if (enabled) actions.askNotificationsIfMissing()
                app.appScope.launch {
                    app.settings.setNextAlarmNotice(enabled)
                    app.nextAlarm.reschedule()
                }
            },
            onAutoOnset = { enabled ->
                app.appScope.launch {
                    app.settings.setAutoOnset(enabled)
                    app.publishAutoOnset()
                }
            },
            onSoundRecDefault = state.soundRec.onDefault,
            onAddRoutineItem = { text -> app.appScope.launch { app.routine.add(text) } },
            onRemoveRoutineItem = { id -> app.appScope.launch { app.routine.remove(id) } },
        ),
        contentPadding = content,
        extras = settingsExtras(app, data, actions),
    )
}

@Composable
private fun SoundsRoute(app: PhaseWakeApp, data: AppData, onBack: () -> Unit) {
    val context = LocalContext.current
    // Einschlafklänge: Ende nach dem Timer, spätestens zu Beginn des nächsten Weckfensters.
    fun startSounds(chosen: SleepSound, timer: Int?) {
        val now = Instant.now()
        val endsAt = SleepSounds.stopAt(
            now, timer,
            AlarmSchedule.earliestPlan(data.alarms, now, ZoneId.systemDefault(), data.holidays)?.windowStart,
        )
        if (endsAt != null && !endsAt.isAfter(now)) return
        SleepSoundService.play(context, chosen, endsAt)
    }
    val now = Instant.now()
    val windowStart = AlarmSchedule.earliestPlan(data.alarms, now, ZoneId.systemDefault(), data.holidays)?.windowStart
    SoundsScreen(
        SoundsState(data.soundPlaying, data.sound, data.soundTimer, canPlay = windowStart?.isAfter(now) ?: true),
        SoundsActions(
            onBack = onBack,
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
        ),
    )
}
