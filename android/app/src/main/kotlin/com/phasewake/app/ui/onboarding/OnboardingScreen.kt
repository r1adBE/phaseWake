package com.phasewake.app.ui.onboarding

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.phasewake.app.PhaseWakeApp
import com.phasewake.app.R
import com.phasewake.app.companion.CompanionLink
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Transient
import com.phasewake.app.ring.WakeTones
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.NavActions
import com.phasewake.app.ui.NavState
import com.phasewake.app.ui.components.LogoMark
import com.phasewake.app.ui.editor.TIME_PICKER_SCALE
import com.phasewake.app.ui.editor.WEEKDAYS
import com.phasewake.app.ui.editor.scaled
import com.phasewake.app.ui.home.Check
import com.phasewake.app.ui.home.MAX_ALARMS
import com.phasewake.app.ui.home.NightCheckState
import com.phasewake.app.ui.settings.HealthState
import com.phasewake.app.ui.startTest
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt

/** Schritte der Ersteinrichtung; danach «Fertig». */
private const val STEPS = 5

/**
 * Ersteinrichtung in fünf Schritten (jederzeit überspringbar): Willkommen, Uhr verbinden, Rechte, erster Wecker,
 * Probewecker – dann «Fertig». Zustand der Uhr und Rechte kommen aus dem Nacht-Check ([NavState.nightCheck]).
 */
@Composable
internal fun OnboardingScreen(app: PhaseWakeApp, state: NavState, actions: NavActions, onDone: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    // System-Zurück: einen Schritt zurück; auf der ersten Seite wie «Überspringen» (sonst erschiene die Einrichtung
    // beim nächsten Start wieder, ohne angelegten Wecker).
    BackHandler { if (page > 0) page-- else onDone() }
    val model = OnboardingModel(
        check = state.nightCheck.check,
        health = state.insights.health,
        canCreateAlarm = state.data.alarms.size < MAX_ALARMS,
        testTarget = state.data.transient?.takeIf { it.spec.id.startsWith(Transient.TEST_PREFIX) }?.plan?.target,
        onFix = actions.onFix,
        onConnectHealth = actions.connectHealth,
        onCreateAlarm = { spec -> app.afterAlarmsChanged { app.alarms.upsert(spec) } },
        onStartTest = { startTest(app) },
        // Neu gelesen mit jedem Nacht-Check (nach dem Verbinden prüft er sofort neu).
        companionLinked = remember(state.nightCheck.check) { CompanionLink.isAssociated(app) },
    )
    OnboardingContent(page, onPage = { page = it }, model, onDone)
}

/** Was die Schritte zeigen und auslösen: aus dem App-Zustand oder aus Beispieldaten (Bildschirmfotos). */
internal class OnboardingModel(
    /** Ergebnis des Nacht-Checks (Uhr und Rechte); `null`, solange er noch läuft. */
    val check: NightCheckState?,
    val health: HealthState,
    /** Ob noch ein Wecker angelegt werden darf ([MAX_ALARMS]). */
    val canCreateAlarm: Boolean,
    /** Zielzeit eines laufenden Probeweckers; `null` = keiner. */
    val testTarget: Instant?,
    val onFix: (Check) -> Unit,
    val onConnectHealth: () -> Unit,
    val onCreateAlarm: (AlarmSpec) -> Unit,
    val onStartTest: () -> Unit,
    /** Ob Phase Wake schon Begleit-App der Uhr ist ([Check.COMPANION]). */
    val companionLinked: Boolean = false,
)

/** Einrichtung ohne eigenen Seitenzustand: Seite [page] (0–[STEPS]; [STEPS] = «Fertig»), Wechsel über [onPage]. */
@Composable
internal fun OnboardingContent(page: Int, onPage: (Int) -> Unit, model: OnboardingModel, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (page < STEPS) stringResource(R.string.onb_step, page + 1, STEPS) else "",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (page < STEPS) TextButton(onClick = onDone) { Text(stringResource(R.string.onb_skip)) }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (page) {
                0 -> Welcome()
                1 -> WatchStep(model)
                2 -> RightsStep(model)
                3 -> AlarmStep(model)
                4 -> TestPage(model)
                else -> Page(R.string.onb_done_title) { Body(R.string.onb_done_text) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page > 0) OutlinedButton(onClick = { onPage(page - 1) }) { Text(stringResource(R.string.onb_back)) }
            Spacer(Modifier.weight(1f))
            if (page < STEPS) {
                Button(onClick = { onPage(page + 1) }) { Text(stringResource(R.string.onb_next)) }
            } else {
                Button(onClick = onDone) { Text(stringResource(R.string.onb_finish)) }
            }
        }
    }
}

/** Seite mit Überschrift (für Bildschirmleser als Überschrift ausgezeichnet). */
@Composable
private fun Page(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun Body(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun Welcome() {
    LogoMark(64.dp)
    Page(R.string.onb_welcome_title) {
        Body(R.string.onb_welcome_text)
        Text(stringResource(R.string.onb_privacy), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Uhr verbunden, Phase Wake fehlt dort (Knopf zum Installieren) oder keine Uhr gekoppelt. Mit verbundener Uhr dazu
 * die Begleit-App: Knopf zum Verbinden oder «✓ Als Begleit-App verbunden».
 */
@Composable
private fun WatchStep(model: OnboardingModel) {
    val failed = model.check?.failed.orEmpty()
    Page(R.string.onb_watch_title) {
        when {
            Check.WATCH_APP in failed -> {
                Body(R.string.check_watch_app_problem)
                FilledTonalButton(onClick = { model.onFix(Check.WATCH_APP) }) { Text(stringResource(R.string.check_watch_app_action)) }
            }
            Check.WATCH in failed -> Body(R.string.check_watch_problem)
            model.check != null -> Body(R.string.onb_watch_ok)
        }
        when {
            Check.COMPANION in failed -> {
                Body(R.string.companion_why)
                FilledTonalButton(onClick = { model.onFix(Check.COMPANION) }) { Text(stringResource(R.string.companion_connect)) }
            }
            model.companionLinked -> Text(
                "✓ " + stringResource(R.string.companion_connected),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Erster Wecker: Zielzeit und Fenster, Werktage; alles Weitere später im Editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmStep(model: OnboardingModel) {
    val context = LocalContext.current
    val time = rememberTimePickerState(7, 0, is24Hour = DateFormat.is24HourFormat(context))
    var window by rememberSaveable { mutableFloatStateOf(AlarmSpec.DEFAULT_WINDOW_MINUTES.toFloat()) }
    var created by rememberSaveable { mutableStateOf<String?>(null) }
    Page(R.string.onb_alarm_title) {
        Body(R.string.onb_alarm_text)
        TimePicker(time, modifier = Modifier.align(Alignment.CenterHorizontally).scaled(TIME_PICKER_SCALE))
        Text(stringResource(R.string.editor_window, window.roundToInt()), style = MaterialTheme.typography.titleSmall)
        Slider(
            value = window,
            onValueChange = { window = it },
            valueRange = AlarmSpec.MIN_WINDOW_MINUTES.toFloat()..AlarmSpec.MAX_WINDOW_MINUTES.toFloat(),
            steps = (AlarmSpec.MAX_WINDOW_MINUTES - AlarmSpec.MIN_WINDOW_MINUTES) / WINDOW_STEP - 1,
        )
        val label = created
        if (label != null) {
            Text(stringResource(R.string.onb_alarm_done, label), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        } else if (model.canCreateAlarm) {
            Button(onClick = {
                val spec = AlarmSpec(
                    id = UUID.randomUUID().toString(), hour = time.hour, minute = time.minute, days = WEEKDAYS,
                    windowMinutes = window.roundToInt(), sound = WakeTones.DEFAULT,
                )
                model.onCreateAlarm(spec)
                created = Format.window(spec)
            }) { Text(stringResource(R.string.onb_alarm_create)) }
        }
    }
}

/** Schritt des Weckfensters in Minuten (wie im Editor). */
private const val WINDOW_STEP = 5

@Composable
private fun TestPage(model: OnboardingModel) {
    val running = model.testTarget
    Page(R.string.test_title) {
        Body(R.string.test_hint)
        if (running != null && running.isAfter(Instant.now())) {
            Text(stringResource(R.string.test_running, Format.time(running)), style = MaterialTheme.typography.titleSmall)
        } else {
            FilledTonalButton(onClick = model.onStartTest) { Text(stringResource(R.string.test_button)) }
        }
    }
}

/** Rechte: Mitteilungen, Vollbild und exakte Alarme (wo nötig) mit Knopf; Gesundheitsdaten freiwillig. */
@Composable
private fun RightsStep(model: OnboardingModel) {
    val failed = model.check?.failed.orEmpty()
    Page(R.string.onb_rights_title) {
        listOf(
            Check.NOTIFICATIONS,
            Check.FULL_SCREEN,
            Check.EXACT_ALARMS,
        ).filter { it.inReadyList || it in failed }.forEach { check ->
            RightRow(stringResource(check.title), granted = check !in failed) { model.onFix(check) }
        }
        val health = model.health
        if (health != HealthState.UNAVAILABLE) {
            RightRow(
                stringResource(R.string.onb_optional, stringResource(R.string.health_connect)),
                granted = health == HealthState.CONNECTED,
                onAllow = model.onConnectHealth,
            )
        }
    }
}

/** Ein Recht: Name, dann «Erlaubt» (mit Haken) oder ein Knopf «Erlauben». */
@Composable
private fun RightRow(name: String, granted: Boolean, onAllow: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 12.dp))
        if (granted) {
            Text("✓ " + stringResource(R.string.onb_granted), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        } else {
            FilledTonalButton(onClick = onAllow) { Text(stringResource(R.string.check_allow)) }
        }
    }
}
