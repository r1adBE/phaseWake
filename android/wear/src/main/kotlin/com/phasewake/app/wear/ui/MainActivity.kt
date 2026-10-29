package com.phasewake.app.wear.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.compose.material3.AppScaffold
import androidx.compose.runtime.remember
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Transient
import com.phasewake.app.wear.diag.WatchDiag
import com.phasewake.app.wear.sync.TransientInbox
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.phasewake.app.wear.ui.theme.PhaseWakeTheme
import com.phasewake.app.wear.wearApp
import kotlinx.coroutines.launch

/** Uhr-App: Startbildschirm (nächstes Weckfenster, Wecker, Berechtigungen) und einfacher Wecker-Editor. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PhaseWakeTheme { WatchApp() } }
    }
}

private const val ROUTE_HOME = "home"
private const val ROUTE_EDITOR = "editor"
private const val ROUTE_TIME = "time"
private const val ROUTE_NAP = "nap"
private const val ROUTE_DIAG = "diag"

/**
 * Bildschirme der Uhr; Wischen nach rechts geht zurück (im Editor ohne Speichern). Die Uhr bearbeitet nur
 * Zeit, Fenster, Tage und Ein/Aus; der Rest bleibt, wie er am Handy eingestellt ist (siehe [mergeEdit]).
 */
@Composable
private fun WatchApp() {
    val app = LocalContext.current.wearApp
    val nav = rememberSwipeDismissableNavController()
    val alarms by app.alarms.alarms.collectAsStateWithLifecycle(initialValue = emptyList())
    var draft by rememberSaveable(stateSaver = draftSaver) { mutableStateOf<AlarmSpec?>(null) }
    val holidays by app.holidays.dates.collectAsStateWithLifecycle(initialValue = emptySet())
    // Nur vom eigenen Bildschirm aus zurück: Ein Doppeltipp darf nicht auch den Start schliessen.
    fun back(from: String) {
        if (nav.currentDestination?.route == from) nav.popBackStack()
    }
    fun open(spec: AlarmSpec) {
        if (nav.currentDestination?.route != ROUTE_HOME) return
        draft = spec
        nav.navigate(ROUTE_EDITOR)
    }
    AppScaffold {
        SwipeDismissableNavHost(navController = nav, startDestination = ROUTE_HOME) {
            composable(ROUTE_HOME) {
                HomeScreen(
                    onAdd = { open(newAlarm()) },
                    onEdit = { open(it) },
                    onNap = { if (nav.currentDestination?.route == ROUTE_HOME) nav.navigate(ROUTE_NAP) },
                    onDiagnostics = { if (nav.currentDestination?.route == ROUTE_HOME) nav.navigate(ROUTE_DIAG) },
                )
            }
            composable(ROUTE_NAP) {
                NapScreen(Transient.NAP_DEFAULT_MINUTES) { minutes ->
                    app.appScope.launch { TransientInbox.startNap(app, minutes) }
                    back(ROUTE_NAP)
                }
            }
            composable(ROUTE_DIAG) { DiagRoute(onHide = { back(ROUTE_DIAG) }) }
            composable(ROUTE_EDITOR) {
                val current = draft
                if (current == null) {
                    // Kein Entwurf (sollte nicht vorkommen): zurück zum Start, nie unter den Start hinaus.
                    LaunchedEffect(Unit) { nav.popBackStack(ROUTE_HOME, inclusive = false) }
                } else {
                    EditorContent(
                        draft = current,
                        isNew = alarms.none { it.id == current.id },
                        onPickTime = { if (nav.currentDestination?.route == ROUTE_EDITOR) nav.navigate(ROUTE_TIME) },
                        onChange = { draft = it },
                        onSave = {
                            // Im Prozess-Bereich: Speichern läuft weiter, auch wenn der Bildschirm schon zu ist.
                            app.appScope.launch {
                                val list = app.alarms.current().alarms
                                val stored = list.firstOrNull { it.id == current.id }
                                // Höchstens MAX_ALARMS Wecker, wie am Handy.
                                if (stored != null || list.size < MAX_ALARMS) {
                                    app.sync.publishAlarms(app.alarms.upsert(mergeEdit(stored, current)))
                                    app.scheduler.reschedule()
                                }
                            }
                            back(ROUTE_EDITOR)
                        },
                        skipped = AlarmSchedule.skippedDate(current, Instant.now(), ZoneId.systemDefault())?.let { shortDay(it) },
                        onSkip = if (current.isOneShot || alarms.none { it.id == current.id }) null else { on ->
                            draft = if (on) {
                                AlarmSchedule.skipNext(current.copy(skipUntil = 0L), Instant.now(), ZoneId.systemDefault(), holidays)
                            } else {
                                AlarmSchedule.undoSkip(current)
                            }
                        },
                        onDelete = {
                            app.appScope.launch {
                                app.sync.publishAlarms(app.alarms.remove(current.id))
                                app.scheduler.reschedule()
                            }
                            back(ROUTE_EDITOR)
                        },
                    )
                }
            }
            composable(ROUTE_TIME) {
                val current = draft
                if (current == null) {
                    // Kein Entwurf (sollte nicht vorkommen): zurück zum Start, nie unter den Start hinaus.
                    LaunchedEffect(Unit) { nav.popBackStack(ROUTE_HOME, inclusive = false) }
                } else {
                    EditorTime(current) { time ->
                        draft = current.copy(hour = time.hour, minute = time.minute)
                        back(ROUTE_TIME)
                    }
                }
            }
        }
    }
}

/** Diagnose der Uhr mit Zustand: nächster Plan, Version, Protokoll. */
@Composable
private fun DiagRoute(onHide: () -> Unit) {
    val context = LocalContext.current
    val app = context.wearApp
    val changes by app.diag.changes.collectAsStateWithLifecycle()
    var entries by remember { mutableStateOf(emptyList<com.phasewake.app.core.DiagEntry>()) }
    var next by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(changes) {
        entries = withContext(Dispatchers.IO) { app.diag.entries() }
        next = app.scheduler.nextPlan()?.let { shortDay(it.target.atZone(ZoneId.systemDefault()).toLocalDate()) + " " + shortTime(it.target) }
    }
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    DiagScreen(
        next = next,
        version = version,
        entries = entries,
        onClear = { app.appScope.launch(Dispatchers.IO) { app.diag.clear() } },
        onHide = {
            app.appScope.launch { WatchDiag.setShown(context, false) }
            onHide()
        },
    )
}

/** Kurzes Datum mit Wochentag (z. B. «Do., 30.10.»). */
private fun shortDay(date: java.time.LocalDate): String {
    val locale = java.util.Locale.getDefault()
    return java.time.format.DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdM"), locale).format(date)
}

private fun shortTime(at: Instant): String =
    java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).format(at.atZone(ZoneId.systemDefault()))
