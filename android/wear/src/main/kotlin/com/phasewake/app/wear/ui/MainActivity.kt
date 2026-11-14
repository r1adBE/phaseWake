package com.phasewake.app.wear.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.currentBackStackEntryAsState
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Transient
import com.phasewake.app.core.TransientConflict
import com.phasewake.app.wear.R
import com.phasewake.app.wear.diag.WatchDiag
import com.phasewake.app.wear.sync.TransientInbox
import com.phasewake.app.wear.ui.theme.PhaseWakeTheme
import com.phasewake.app.wear.wearApp
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Uhr-App: Startbildschirm (nächstes Weckfenster, Wecker, Berechtigungen) und einfacher Wecker-Editor. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Zeiten nach der 12/24-h-Einstellung der Uhr (Audit N-10).
        WearClock.update(this)
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
    // `null`, bis die Wecker gelesen sind: Erst dann zählt ein fehlender Wecker als anderswo gelöscht (B-3).
    val stored by app.alarms.alarms.collectAsStateWithLifecycle(initialValue = null)
    val alarms = stored.orEmpty()
    var draft by rememberSaveable(stateSaver = draftSaver) { mutableStateOf<AlarmSpec?>(null) }
    // Ob der Entwurf beim Öffnen ein gespeicherter Wecker war (sonst ein neuer).
    var editingExisting by rememberSaveable { mutableStateOf(false) }
    val top by nav.currentBackStackEntryAsState()
    // Schalter «Nächstes Mal überspringen» in diesem Bearbeiten: null = nicht angefasst (AlarmSchedule.editedSkip).
    var skipRequest by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val holidays by app.holidays.dates.collectAsStateWithLifecycle(initialValue = emptySet())
    // Erledigte Nächte (früh geweckt): «Nächstes Mal überspringen» trifft dann den nächsten Termin.
    var done by remember { mutableStateOf(emptyMap<String, Instant>()) }
    LaunchedEffect(alarms) { done = app.scheduler.doneUntil() }
    // Nur vom eigenen Bildschirm aus zurück: Ein Doppeltipp darf nicht auch den Start schliessen.
    fun back(from: String) {
        if (nav.currentDestination?.route == from) nav.popBackStack()
    }
    fun open(spec: AlarmSpec) {
        if (nav.currentDestination?.route != ROUTE_HOME) return
        draft = spec
        editingExisting = alarms.any { it.id == spec.id }
        skipRequest = null
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
                    app.appScope.launch {
                        // Abgelehnt (Wecker läuft oder beginnt vorher): Hinweis, nichts geändert.
                        val refused = TransientInbox.startNap(app, minutes) ?: return@launch
                        withContext(Dispatchers.Main) { Toast.makeText(app, conflictText(refused), Toast.LENGTH_LONG).show() }
                    }
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
                    // Anderswo gelöscht (Handy), während der Editor offen ist: Hinweis und zu, statt ihn neu anzulegen (B-3).
                    val gone = stored?.let { editorSave(editingExisting, it, current.id) } == EditorSave.GONE
                    LaunchedEffect(gone, top?.destination?.route) {
                        if (gone && top?.destination?.route == ROUTE_EDITOR) {
                            Toast.makeText(app, R.string.editor_alarm_gone, Toast.LENGTH_LONG).show()
                            back(ROUTE_EDITOR)
                        }
                    }
                    EditorContent(
                        draft = current,
                        isNew = !editingExisting,
                        onPickTime = { if (nav.currentDestination?.route == ROUTE_EDITOR) nav.navigate(ROUTE_TIME) },
                        onChange = { draft = it },
                        onSave = save@{
                            if (nav.currentDestination?.route != ROUTE_EDITOR) return@save
                            // Höchstens MAX_ALARMS Wecker, wie am Handy: dann Hinweis, der Editor bleibt (B-11).
                            when (editorSave(editingExisting, alarms, current.id)) {
                                EditorSave.FULL -> {
                                    Toast.makeText(app, app.getString(R.string.home_max_alarms, MAX_ALARMS), Toast.LENGTH_LONG).show()
                                    return@save
                                }
                                EditorSave.GONE -> {
                                    Toast.makeText(app, R.string.editor_alarm_gone, Toast.LENGTH_LONG).show()
                                    back(ROUTE_EDITOR)
                                    return@save
                                }
                                EditorSave.SAVE -> Unit
                            }
                            val request = skipRequest
                            val existed = editingExisting
                            // Im Prozess-Bereich: Speichern läuft weiter, auch wenn der Bildschirm schon zu ist.
                            app.appScope.launch {
                                val list = app.alarms.current().alarms
                                // Gespeicherter Stand zählt (Handy gleichzeitig): nichts wieder anlegen, keinen neunten.
                                if (editorSave(existed, list, current.id) == EditorSave.SAVE) {
                                    val zone = ZoneId.systemDefault()
                                    val doneAt = app.scheduler.doneUntil()[current.id]
                                    val holidays = app.holidays.current()
                                    val merged = savedEdit(list.firstOrNull { it.id == current.id }, current, request, Instant.now(), zone, holidays, doneAt)
                                    app.sync.publishAlarms(app.alarms.upsert(merged))
                                    app.scheduler.reschedule()
                                }
                            }
                            back(ROUTE_EDITOR)
                        },
                        // Anzeige aus dem Stand, der gespeichert würde (gleiche Kernregel wie beim Speichern).
                        skipped = AlarmSchedule.skippedDate(
                            savedEdit(alarms.firstOrNull { it.id == current.id }, current, skipRequest, Instant.now(), ZoneId.systemDefault(), holidays, done[current.id]),
                            Instant.now(),
                            ZoneId.systemDefault(),
                        )?.let { shortDay(it) },
                        onSkip = if (current.isOneShot || alarms.none { it.id == current.id }) null else { on -> skipRequest = on },
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
    // Mit Build-Nummer der Testversion, z. B. «16.2.2 (96)» (r1AD 14.11.2026).
    val version = remember { com.phasewake.app.shared.BuildNumber.ownLabel(context, watch = true) }
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

/** Hinweis, warum ein Powernap nicht startet. */
private fun conflictText(conflict: TransientConflict): Int = when (conflict) {
    TransientConflict.ALARM_RUNNING -> R.string.transient_alarm_running
    TransientConflict.ALARM_SOON -> R.string.transient_alarm_soon
}

/** Kurzes Datum mit Wochentag (z. B. «Do., 30.10.»). */
private fun shortDay(date: java.time.LocalDate): String {
    val locale = java.util.Locale.getDefault()
    return localizedDigits(java.time.format.DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdM"), locale), locale).format(date)
}

private fun shortTime(at: Instant): String =
    timeFormatter().format(at.atZone(ZoneId.systemDefault()))
