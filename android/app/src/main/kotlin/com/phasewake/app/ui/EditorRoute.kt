package com.phasewake.app.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.phasewake.app.PhaseWakeApp
import com.phasewake.app.R
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.WindowAdvice
import com.phasewake.app.ui.editor.EditorScreen
import com.phasewake.app.ui.home.MAX_ALARMS

/**
 * Zurück, nur solange [entry] oben auf dem Stapel liegt (Prüfrunde 2, B-1): Ein zweiter schneller Tipp auf «Speichern»
 * oder den Zurück-Pfeil nähme sonst auch die Startseite vom Stapel (leerer Bildschirm). Wie `back(from)` auf der Uhr.
 */
internal fun NavHostController.backFrom(entry: NavBackStackEntry) {
    if (isTop(entry)) popBackStack()
}

/** Ob [entry] der oberste Bildschirm ist. */
internal fun NavHostController.isTop(entry: NavBackStackEntry): Boolean = currentBackStackEntry?.id == entry.id

/**
 * Editor eines Weckers ([id] `null`: neuer Wecker). Wird der Wecker anderswo gelöscht (Uhr), schliesst der Editor mit
 * einem Hinweis, statt still einen neuen anzulegen (B-3). Kam beim Anlegen anderswo der letzte freie Platz weg, bleibt
 * er mit dem Hinweis auf die Höchstzahl offen, statt still zu verwerfen (B-11).
 */
@Composable
internal fun EditorRoute(app: PhaseWakeApp, state: NavState, id: String?, actions: NavActions, entry: NavBackStackEntry) {
    val context = LocalContext.current
    val maxAlarmsText = stringResource(R.string.home_max_alarms, MAX_ALARMS)
    val data = state.data
    // Erst zeigen, wenn die Wecker gelesen sind (nach dem Neuaufbau fehlen sie kurz): sonst wird ein bestehender
    // Wecker als neuer bearbeitet und beim Speichern doppelt angelegt.
    if (!data.alarmsLoaded) return
    val existing = data.alarms.firstOrNull { it.id == id }
    // Zuletzt gezeigter Wecker: Nach dem eigenen «Löschen» bleibt er stehen, bis der Bildschirm ausgeblendet ist.
    val last = remember(id) { mutableStateOf<AlarmSpec?>(null) }
    SideEffect { if (existing != null) last.value = existing }
    val gone = id != null && existing == null
    LaunchedEffect(gone) {
        if (gone && actions.nav.isTop(entry)) {
            Toast.makeText(context, R.string.editor_alarm_gone, Toast.LENGTH_LONG).show()
            actions.nav.backFrom(entry)
        }
    }
    val shown = existing ?: last.value
    if (id != null && shown == null) return
    EditorScreen(
        existing = shown,
        advice = shown?.let { WindowAdvice.suggest(it, data.nights.orEmpty()) },
        onBack = { actions.nav.backFrom(entry) },
        onSave = save@{ spec ->
            if (!actions.nav.isTop(entry) || gone) return@save
            if (existing == null && data.alarms.size >= MAX_ALARMS) {
                Toast.makeText(context, maxAlarmsText, Toast.LENGTH_LONG).show()
                return@save
            }
            app.afterAlarmsChanged {
                // Gespeicherter Stand zählt (Uhr gleichzeitig): einen gelöschten Wecker nicht wieder anlegen, keinen neunten.
                val current = app.alarms.current()
                val stored = current.alarms.any { it.id == spec.id }
                if (stored == (existing != null) && (stored || current.alarms.size < MAX_ALARMS)) app.alarms.upsert(spec) else current
            }
            actions.nav.backFrom(entry)
        },
        onDelete = delete@{ spec ->
            if (!actions.nav.isTop(entry)) return@delete
            app.afterAlarmsChanged { app.alarms.remove(spec.id) }
            actions.nav.backFrom(entry)
        },
        draft = entry.savedStateHandle,
    )
}
