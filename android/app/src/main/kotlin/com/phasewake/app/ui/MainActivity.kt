package com.phasewake.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import com.phasewake.app.ui.components.Intro
import com.phasewake.app.ui.components.Night
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.health.connect.client.PermissionController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.phasewake.app.R
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.MissHint
import com.phasewake.app.core.NightHistory
import com.phasewake.app.core.NoteEffect
import com.phasewake.app.core.SleepGoal
import com.phasewake.app.core.SleepSound
import com.phasewake.app.core.SleepSounds
import com.phasewake.app.sounds.SleepSoundService
import com.phasewake.app.sounds.SoundRecorderService
import com.phasewake.app.core.Regularity
import com.phasewake.app.core.Routine
import com.phasewake.app.core.SleepNotes
import com.phasewake.app.core.Streak
import com.phasewake.app.core.WeeklySummary
import com.phasewake.app.ui.history.HabitLines
import com.phasewake.app.ui.home.SoundRecState
import com.phasewake.app.ui.sounds.SoundsScreen
import com.phasewake.app.core.SleepSummary
import com.phasewake.app.core.Trends
import com.phasewake.app.core.WindowAdvice
import java.time.Instant
import java.time.ZoneId
import com.phasewake.app.phoneApp
import com.phasewake.app.shared.DataLayer
import com.phasewake.app.shared.DoNotDisturb
import com.phasewake.app.ui.editor.EditorScreen
import com.phasewake.app.ui.history.Export
import com.phasewake.app.ui.history.ExportDialog
import com.phasewake.app.ui.history.HistoryScreen
import com.phasewake.app.ui.home.Check
import com.phasewake.app.ui.home.HomeScreen
import com.phasewake.app.ui.home.MAX_ALARMS
import com.phasewake.app.ui.home.NightCheckState
import com.phasewake.app.ui.settings.HealthState
import com.phasewake.app.ui.settings.SettingsScreen
import com.phasewake.app.ui.theme.PhaseWakeTheme
import com.phasewake.app.ui.theme.ThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Einzige Activity der Handy-App; Navigation zwischen Wecker, Verlauf, Einstellungen und Editor. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = phoneApp
        // Aus dem Wochenrückblick geöffnet: gleich der Verlauf.
        val openHistory = intent?.getStringExtra(EXTRA_OPEN) == OPEN_HISTORY
        setContent {
            val mode by app.settings.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            PhaseWakeTheme(mode) { PhaseWakeNav(mode, openHistory) }
        }
    }

    companion object {
        /** Was beim Öffnen gezeigt wird (z. B. aus einer Mitteilung). */
        const val EXTRA_OPEN = "open"
        const val OPEN_HISTORY = "history"
    }
}

private object Routes {
    const val ALARMS = "alarms"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val EDITOR = "editor?id={id}"
    const val SOUNDS = "sounds"
    fun editor(id: String?) = if (id == null) "editor" else "editor?id=$id"
}

private data class Tab(val route: String, val label: Int, val icon: Int)

private val tabs = listOf(
    Tab(Routes.ALARMS, R.string.nav_alarms, R.drawable.ic_alarm),
    Tab(Routes.HISTORY, R.string.nav_history, R.drawable.ic_history),
    Tab(Routes.SETTINGS, R.string.nav_settings, R.drawable.ic_settings),
)

@Composable
private fun PhaseWakeNav(themeMode: ThemeMode, openHistory: Boolean = false) {
    val context = LocalContext.current
    val app = context.phoneApp
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
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
    val soundRecDefault by app.settings.soundRecDefault.collectAsStateWithLifecycle(initialValue = false)
    val soundRecChoice by app.settings.soundRecChoice.collectAsStateWithLifecycle(initialValue = null)
    val weeklyReview by app.settings.weeklyReview.collectAsStateWithLifecycle(initialValue = false)
    val soundClips by app.sounds.clips.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { app.sounds.load() }
    val routineItems by app.routine.items.collectAsStateWithLifecycle(initialValue = emptyList())
    val routineTicks by app.routine.ticks.collectAsStateWithLifecycle(initialValue = emptyMap())

    var check by remember { mutableStateOf<NightCheckState?>(null) }
    var checkRun by remember { mutableStateOf(0) }
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { checkRun++ }
    var health by remember { mutableStateOf(HealthState.UNAVAILABLE) }
    var healthCheck by remember { mutableStateOf(0) }
    LaunchedEffect(healthCheck) {
        health = when {
            app.health.needsUpdate -> HealthState.UPDATE_REQUIRED
            !app.health.isAvailable -> HealthState.UNAVAILABLE
            app.health.isGranted() -> HealthState.CONNECTED
            else -> HealthState.AVAILABLE
        }
    }
    // Trends: Schlafdauer aus Health Connect (falls verbunden) und Befinden aus dem Verlauf.
    // Bei jedem Öffnen neu lesen (healthCheck zählt beim Fortsetzen hoch): neue Nächte aus Health Connect.
    val sleeps by produceState(emptyList<SleepSummary>(), health, healthCheck) {
        // So weit zurück wie der Verlauf: auch für die Wirkung der Notizen (Trends nehmen nur 14 Tage).
        value = if (health == HealthState.CONNECTED) app.health.recentSleep(NightHistory.MAX_NIGHTS + 1L) else emptyList()
    }
    // Schlafphasen für den ganzen Verlauf in einer Abfrage statt einer je Nacht beim Scrollen.
    val stages by produceState(emptyList<com.phasewake.app.core.StageSpan>(), health, healthCheck, nights?.size) {
        val list = nights.orEmpty()
        value = if (health == HealthState.CONNECTED && list.isNotEmpty()) {
            app.health.stagesBetween(list.first().windowStart.minus(java.time.Duration.ofHours(14)), list.last().wokeAt.plus(java.time.Duration.ofHours(2)))
        } else {
            emptyList()
        }
    }
    val trends = remember(nights, sleeps) { Trends.compute(nights.orEmpty(), sleeps, Instant.now(), ZoneId.systemDefault()) }
    val goalWeek = remember(sleeps, sleepGoal) { SleepGoal.weekStatus(SleepGoal.nightsOf(sleeps, ZoneId.systemDefault()), sleepGoal) }
    val noteEffects = remember(nights, notes, sleeps) {
        val zone = ZoneId.systemDefault()
        NoteEffect.compute(nights.orEmpty(), notes, zone, NoteEffect.asleepByNight(sleeps, zone))
    }
    // Gewohnheiten: Regelmässigkeit und Serien aus der Gesundheits-App, Abendroutine aus den Haken.
    val habits = remember(sleeps, sleepGoal, routineItems, routineTicks) {
        val zone = ZoneId.systemDefault()
        val today = SleepNotes.nightDate(Instant.now(), zone)
        val reached = Streak.reachedByNight(sleeps, zone, sleepGoal)
        HabitLines(
            regular = Regularity.count(Regularity.bedtimesOf(sleeps, zone), zone, until = today),
            streak = Streak.current(reached, today),
            bestStreak = Streak.longest(reached, today),
            routineAverage = Routine.average(routineTicks, routineItems, today),
            routineItems = routineItems.size,
        )
    }
    // Wochenrückblick als Karte am Sonntag und Montag.
    val weekly = remember(nights, sleeps, notes, sleepGoal) {
        val zone = ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        if (!WeeklySummary.cardVisible(today)) null
        else WeeklySummary.of(nights.orEmpty(), sleeps, notes, zone, sleepGoal, WeeklySummary.weekEnd(today)).takeIf { it.hasData }
    }

    // Geräusche aufnehmen: für diese Nacht gewählt, sonst die Vorgabe aus den Einstellungen.
    fun tonight() = SleepNotes.nightDate(Instant.now(), ZoneId.systemDefault())
    val soundRecWanted = soundRecChoice?.takeIf { it.first == tonight() }?.second ?: soundRecDefault
    var micDenied by rememberSaveable { mutableStateOf(false) }
    fun hasMic() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
    fun startRecording() = SoundRecorderService.start(context)
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micDenied = !granted
        if (granted) {
            app.appScope.launch { app.settings.setSoundRecChoice(tonight(), true) }
            startRecording()
        }
    }
    // Vorgabe in den Einstellungen: nur fragen, nichts starten.
    val micPermissionDefault = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> micDenied = !granted }
    fun onSoundRec(enabled: Boolean) {
        if (enabled && !hasMic()) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        app.appScope.launch { app.settings.setSoundRecChoice(tonight(), enabled) }
        if (enabled) startRecording() else SoundRecorderService.stop(context)
    }
    // Einschlafklänge: Ende nach dem Timer, spätestens zu Beginn des nächsten Weckfensters.
    fun startSounds(chosen: SleepSound, timer: Int?) {
        val now = Instant.now()
        val endsAt = SleepSounds.stopAt(now, timer, AlarmSchedule.earliestPlan(alarms, now, ZoneId.systemDefault())?.windowStart)
        if (endsAt != null && !endsAt.isAfter(now)) return
        SleepSoundService.play(context, chosen, endsAt)
    }

    val healthPermission = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        healthCheck++
    }
    LifecycleResumeEffect(Unit) {
        Format.update(context)
        checkRun++
        healthCheck++
        onPauseOrDispose { }
    }
    // Aufnahme für diese Nacht gewünscht, aber (noch) nicht gestartet: beim Öffnen am Abend starten.
    // Nur sichtbar (Mikrofon-Dienst ab Android 14 nur aus der sichtbaren App) und mit Mikrofon-Recht.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(soundRecWanted, recording, alarms, checkRun) {
        if (!soundRecWanted || recording != null || !hasMic()) return@LaunchedEffect
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        if (SleepNotes.entryVisible(now, zone, AlarmSchedule.earliestPlan(alarms, now, zone)?.windowStart)) startRecording()
    }
    // Aus dem Wochenrückblick: einmal zum Verlauf.
    var openedHistory by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openHistory) {
        if (openHistory && !openedHistory) {
            openedHistory = true
            nav.switchTab(Routes.HISTORY)
        }
    }
    // Nacht-Check: bei jedem Öffnen und danach jede halbe Minute (die Uhr kann kommen und gehen).
    // Nur solange die App sichtbar ist – im Hintergrund nicht abfragen (Akku).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(checkRun) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                check = NightCheckState.collect(context, app.sync.isReachable(DataLayer.CAPABILITY_WATCH), app.sync.watchReady(), app.sync.watchBattery())
                delay(30_000)
            }
        }
    }

    // Klingelt es, ohne dass der Weckbildschirm erscheinen durfte (Vollbild nicht erlaubt): beim Öffnen zeigen.
    val ringing by com.phasewake.app.ring.PhoneRingService.ringState.collectAsStateWithLifecycle()
    LaunchedEffect(ringing) {
        if (ringing is com.phasewake.app.ring.PhoneRingService.State.Ringing) {
            context.startActivity(Intent(context, com.phasewake.app.ring.PhoneRingActivity::class.java))
        }
    }

    // Kopfbereich der Startseite ist immer Nachtblau: dort helle Statusleisten-Symbole.
    val view = LocalView.current
    val lightBars = themeMode == ThemeMode.LIGHT || (themeMode == ThemeMode.SYSTEM && !isSystemInDarkTheme())
    SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = lightBars && route != Routes.ALARMS
    }
    // Wer «Animationen entfernen» eingeschaltet hat, bekommt keine Start-Animation.
    var intro by rememberSaveable { mutableStateOf(animationsEnabled(context)) }
    // Teilen des Verlaufs: zuerst ein Satz dazu, was die Datei enthält.
    var askExport by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (route != Routes.EDITOR && route != Routes.SOUNDS) {
                val onNight = route == Routes.ALARMS && PhaseWakeTheme.isDark
                NavigationBar(containerColor = if (onNight) Night.Bottom.copy(alpha = 0.92f) else MaterialTheme.colorScheme.surfaceContainerLow) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.switchTab(tab.route) },
                            icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (route == Routes.ALARMS && alarms.isNotEmpty() && alarms.size < MAX_ALARMS) {
                ExtendedFloatingActionButton(
                    onClick = { nav.navigate(Routes.editor(null)) },
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(20.dp)) },
                    text = { Text(stringResource(R.string.home_add_alarm)) },
                )
            }
        },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        val content = PaddingValues(
            start = padding.calculateStartPadding(direction),
            end = padding.calculateEndPadding(direction),
            top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 12.dp,
        )
        NavHost(
            nav,
            startDestination = Routes.ALARMS,
            enterTransition = { fadeIn() + slideInHorizontally { it / 12 } },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { fadeOut() + slideOutHorizontally { it / 12 } },
        ) {
            composable(Routes.ALARMS) {
                HomeScreen(
                    alarms = alarms,
                    lastNight = nights?.lastOrNull(),
                    missHint = nights?.lastOrNull()?.let { last -> MissHint.of(alarms.firstOrNull { it.id == last.alarmId }, nights.orEmpty()) },
                    check = check,
                    onFix = { item ->
                        when (item) {
                            Check.NOTIFICATIONS -> {
                                val activity = context as? android.app.Activity
                                val canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    (!askedNotifications || activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true)
                                if (canAsk) {
                                    askedNotifications = true
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    // Dauerhaft abgelehnt: Android fragt nicht mehr, also direkt zu den Mitteilungs-Einstellungen.
                                    openSettings(
                                        context,
                                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                                    )
                                }
                            }
                            Check.FULL_SCREEN -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                openSettings(
                                    context,
                                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:${context.packageName}".toUri()),
                                )
                            }
                            Check.EXACT_ALARMS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                openSettings(
                                    context,
                                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()),
                                )
                            }
                            Check.DND, Check.DND_UNKNOWN -> DoNotDisturb.openSettings(context)
                            Check.VOLUME -> openSettings(context, Intent(Settings.ACTION_SOUND_SETTINGS))
                            Check.WATCH, Check.WATCH_SETUP, Check.WATCH_BATTERY, Check.BATTERY -> Unit
                        }
                    },
                    contentPadding = content,
                    onAdd = { nav.navigate(Routes.editor(null)) },
                    onEdit = { nav.navigate(Routes.editor(it)) },
                    onToggle = { alarm, enabled -> app.afterAlarmsChanged { app.alarms.setEnabled(alarm.id, enabled) } },
                    onFeeling = { night, feeling -> app.appScope.launch { app.nights.setFeeling(night.alarmId, night.windowStart, feeling) } },
                    notes = notes,
                    customNotes = customNotes,
                    onToggleNote = { date, id -> app.appScope.launch { app.notes.toggle(date, id) } },
                    soundPlaying = soundPlaying,
                    onSounds = { nav.navigate(Routes.SOUNDS) },
                    soundRec = SoundRecState(soundRecWanted && (recording != null || hasMic()), recording != null, micDenied),
                    onSoundRec = ::onSoundRec,
                    soundClips = soundClips.orEmpty(),
                    soundFile = { app.sounds.file(it) },
                    onDeleteClip = { clip -> app.appScope.launch { app.sounds.delete(clip) } },
                    onDeleteNightClips = { date -> app.appScope.launch { app.sounds.deleteNight(date) } },
                    routineItems = routineItems,
                    routineTicks = routineTicks,
                    onToggleRoutine = { date, id -> app.appScope.launch { app.routine.toggle(date, id) } },
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    nights.orEmpty(),
                    content,
                    loadStages = if (health == HealthState.CONNECTED) {
                        { night -> stages.filter { it.end.isAfter(night.windowStart.minus(java.time.Duration.ofHours(14))) && it.start.isBefore(night.wokeAt.plus(java.time.Duration.ofHours(2))) } }
                    } else {
                        null
                    },
                    onExport = { askExport = true },
                    trends = trends,
                    healthConnected = health == HealthState.CONNECTED,
                    onConnectHealth = { healthPermission.launch(app.health.permissions) },
                    goalWeek = goalWeek,
                    notes = notes,
                    customNotes = customNotes,
                    onToggleNote = { date, id -> app.appScope.launch { app.notes.toggle(date, id) } },
                    noteEffects = noteEffects,
                    habits = habits,
                    weekly = weekly,
                    soundClips = soundClips.orEmpty(),
                    soundFile = { app.sounds.file(it) },
                    onDeleteClip = { clip -> app.appScope.launch { app.sounds.delete(clip) } },
                    onDeleteNightClips = { date -> app.appScope.launch { app.sounds.deleteNight(date) } },
                )
            }
            composable(Routes.SETTINGS) {
                val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
                SettingsScreen(
                    themeMode = themeMode,
                    version = version,
                    contentPadding = content,
                    onThemeMode = { app.appScope.launch { app.settings.setThemeMode(it) } },
                    onExport = { askExport = true },
                    canExport = !nights.isNullOrEmpty(),
                    onClearHistory = {
                        app.appScope.launch {
                            app.nights.clear()
                            app.notes.clearNotes()
                            app.routine.clearTicks()
                            app.sounds.clear()
                            app.sync.clearHistoryEverywhere()
                        }
                    },
                    health = health,
                    onConnectHealth = { healthPermission.launch(app.health.permissions) },
                    goalMinutes = sleepGoal,
                    onGoal = { minutes ->
                        app.appScope.launch {
                            app.settings.setSleepGoal(minutes)
                            app.bedtime.reschedule()
                        }
                    },
                    bedtimeReminder = bedtimeReminder,
                    onBedtimeReminder = { enabled ->
                        // Ohne Mitteilungen keine Erinnerung: beim Einschalten einmal fragen.
                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        app.appScope.launch {
                            app.settings.setBedtimeReminder(enabled)
                            app.bedtime.reschedule()
                        }
                    },
                    customNotes = customNotes,
                    onAddCustomNote = { name -> app.appScope.launch { app.notes.addCustom(name) } },
                    onRenameCustomNote = { id, name -> app.appScope.launch { app.notes.renameCustom(id, name) } },
                    onDeleteCustomNote = { id -> app.appScope.launch { app.notes.deleteCustom(id) } },
                    weeklyReview = weeklyReview,
                    onWeeklyReview = { enabled ->
                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        app.appScope.launch {
                            app.settings.setWeeklyReview(enabled)
                            app.weekly.reschedule()
                        }
                    },
                    soundRecDefault = soundRecDefault,
                    onSoundRecDefault = { enabled ->
                        if (enabled && !hasMic()) micPermissionDefault.launch(Manifest.permission.RECORD_AUDIO)
                        app.appScope.launch { app.settings.setSoundRecDefault(enabled) }
                    },
                    routineItems = routineItems,
                    onAddRoutineItem = { text -> app.appScope.launch { app.routine.add(text) } },
                    onRemoveRoutineItem = { id -> app.appScope.launch { app.routine.remove(id) } },
                )
            }
            composable(Routes.SOUNDS) {
                val now = Instant.now()
                val windowStart = AlarmSchedule.earliestPlan(alarms, now, ZoneId.systemDefault())?.windowStart
                SoundsScreen(
                    playing = soundPlaying,
                    selected = sound,
                    timer = soundTimer,
                    canPlay = windowStart == null || windowStart.isAfter(now),
                    onBack = { nav.popBackStack() },
                    onSelect = { chosen ->
                        app.appScope.launch { app.settings.setSound(chosen) }
                        // Läuft schon ein Klang: gleich wechseln.
                        if (soundPlaying != null) startSounds(chosen, soundTimer)
                    },
                    onTimer = { minutes ->
                        app.appScope.launch { app.settings.setSoundTimer(minutes) }
                        // Läuft schon ein Klang: Timer ab jetzt neu.
                        soundPlaying?.let { startSounds(it.sound, minutes) }
                    },
                    onPlay = { startSounds(sound, soundTimer) },
                    onStop = { SleepSoundService.stop(context) },
                )
            }
            composable(
                Routes.EDITOR,
                arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                val id = entry.arguments?.getString("id")
                val existing = alarms.firstOrNull { it.id == id }
                EditorScreen(
                    existing = existing,
                    advice = existing?.let { WindowAdvice.suggest(it, nights.orEmpty()) },
                    onBack = { nav.popBackStack() },
                    onSave = { spec ->
                        // Ein neuer Wecker über dem Limit (z. B. gleichzeitig auf der Uhr angelegt) wird nicht gespeichert.
                        if (existing != null || alarms.size < MAX_ALARMS) app.afterAlarmsChanged { app.alarms.upsert(spec) }
                        nav.popBackStack()
                    },
                    onDelete = { spec ->
                        app.afterAlarmsChanged { app.alarms.remove(spec.id) }
                        nav.popBackStack()
                    },
                )
            }
        }
    }
    if (askExport) {
        ExportDialog(
            onShare = {
                askExport = false
                Export.share(context, nights.orEmpty(), notes)
            },
            onDismiss = { askExport = false },
        )
    }
    if (intro) Intro(onFinished = { intro = false })
    }
}

private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(Routes.ALARMS) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

private fun animationsEnabled(context: android.content.Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

/** Öffnet eine Einstellungsseite; fehlt sie auf dem Gerät, die Seite der App, ersatzweise die Einstellungen. */
private fun openSettings(context: android.content.Context, intent: Intent) {
    val fallbacks = listOf(
        intent,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (candidate in fallbacks) {
        try {
            context.startActivity(candidate)
            return
        } catch (_: android.content.ActivityNotFoundException) {
            // Nächste Seite versuchen.
        }
    }
}
