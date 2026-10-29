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
import com.phasewake.app.core.AlarmSpec
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
                HomeScreen(onAdd = { open(newAlarm()) }, onEdit = { open(it) })
            }
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
