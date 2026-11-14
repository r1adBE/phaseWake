package com.phasewake.app.ui.onboarding

import android.os.SystemClock
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.phasewake.app.ui.editor.WEEKDAYS
import com.phasewake.app.ui.home.Check
import com.phasewake.app.ui.home.MAX_ALARMS
import com.phasewake.app.ui.home.NightCheckState
import com.phasewake.app.ui.settings.HealthState
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.launch
import com.phasewake.app.ui.theme.outlinedBorder

/** Schritte der Ersteinrichtung; danach «Fertig». */
internal const val STEPS = 6

/** Seite «Erster Wecker»: «Weiter» legt den gezeigten Wecker an, falls noch keiner angelegt wurde. */
internal const val ALARM_PAGE = 3

/** Seite der Lernphase: statt «Weiter» die Knöpfe «Später» und «Lernphase starten». */
internal const val LEARNING_PAGE = 4

/**
 * So lange nach einem Tipp auf die Knöpfe unten zählt kein weiterer (Prüfrunde 2, B-2): «Später» der Lernseite liegt
 * dort, wo eben «Weiter» war; ein Doppeltipp lehnte sonst die Lernphase ab, ohne dass die Seite zu sehen war.
 */
internal const val PAGE_TAP_LOCK_MS = 400L

/**
 * Ersteinrichtung in sechs Schritten (jederzeit überspringbar): Willkommen, Uhr verbinden, Rechte, erster Wecker,
 * Lernphase (Zustimmung, Audit F-02), Probewecker – dann «Fertig». Zustand der Uhr und Rechte kommen aus dem Nacht-Check ([NavState.nightCheck]).
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
        hasAlarm = state.data.alarms.isNotEmpty(),
        testTarget = state.data.transient?.takeIf { it.spec.id.startsWith(Transient.TEST_PREFIX) }?.plan?.target,
        onFix = actions.onFix,
        onConnectHealth = actions.connectHealth,
        onCreateAlarm = { spec -> app.afterAlarmsChanged { app.alarms.upsert(spec) } },
        onStartTest = actions.onTest,
        // Neu gelesen mit jedem Nacht-Check (nach dem Verbinden prüft er sofort neu).
        companionLinked = remember(state.nightCheck.check) { CompanionLink.isAssociated(app) },
        learningConsent = state.data.learning?.consent,
        onLearningConsent = { on -> app.appScope.launch { app.setLearningConsent(on) } },
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
    /** Ob schon ein Wecker besteht (Einrichtung wiederholt): Dann legt «Weiter» keinen weiteren an (Audit N-1). */
    val hasAlarm: Boolean = false,
    /** Zielzeit eines laufenden Probeweckers; `null` = keiner. */
    val testTarget: Instant?,
    val onFix: (Check) -> Unit,
    val onConnectHealth: () -> Unit,
    val onCreateAlarm: (AlarmSpec) -> Unit,
    val onStartTest: () -> Unit,
    /** Ob Phase Wake schon Begleit-App der Uhr ist ([Check.COMPANION]). */
    val companionLinked: Boolean = false,
    /** Zustimmung zur Lernphase: `true` an, `false` «Später»/aus, `null` noch nicht gefragt. */
    val learningConsent: Boolean? = null,
    val onLearningConsent: (Boolean) -> Unit = {},
)

/** Einrichtung ohne eigenen Seitenzustand: Seite [page] (0–[STEPS]; [STEPS] = «Fertig»), Wechsel über [onPage]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OnboardingContent(page: Int, onPage: (Int) -> Unit, model: OnboardingModel, onDone: () -> Unit) {
    // Zustand des Schritts «Erster Wecker» hier, damit «Weiter» den gezeigten Wecker anlegen kann (Audit UI-5).
    val context = LocalContext.current
    val alarmTime = rememberTimePickerState(7, 0, is24Hour = DateFormat.is24HourFormat(context))
    var alarmWindow by rememberSaveable { mutableIntStateOf(AlarmSpec.DEFAULT_WINDOW_MINUTES) }
    var alarmCreated by rememberSaveable { mutableStateOf<String?>(null) }
    val createAlarm = {
        val spec = AlarmSpec(
            id = UUID.randomUUID().toString(), hour = alarmTime.hour, minute = alarmTime.minute, days = WEEKDAYS,
            windowMinutes = alarmWindow, sound = WakeTones.DEFAULT,
        )
        model.onCreateAlarm(spec)
        alarmCreated = Format.window(context, spec)
    }
    // Knöpfe unten: nach jedem Tipp kurz gesperrt (B-2), auch «Fertig».
    var lockedUntil by remember { mutableLongStateOf(0L) }
    fun tap(action: () -> Unit) {
        val now = SystemClock.uptimeMillis()
        if (now < lockedUntil) return
        lockedUntil = now + PAGE_TAP_LOCK_MS
        action()
    }
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
        // Inhalt senkrecht mittig (Gestaltung 2); längere Schritte scrollen.
        BoxWithConstraints(Modifier.weight(1f)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        ) {
            // Ab Schritt 2 die Welle mit dem Mond, der mit jedem Schritt weiter wandert (Gestaltung 2, wie am iPhone).
            if (page > 0) OnboardingGlyph(page, STEPS)
            when (page) {
                0 -> Welcome()
                1 -> WatchStep(model)
                2 -> RightsStep(model)
                ALARM_PAGE -> AlarmStep(model, alarmTime, alarmWindow, { alarmWindow = it }, alarmCreated, createAlarm)
                LEARNING_PAGE -> LearningStep(model)
                5 -> TestPage(model)
                else -> Page(R.string.onb_done_title) { Body(R.string.onb_done_text) }
            }
        }
        }
        // Lernphase noch nicht gestartet: nur «Später» und «Lernphase starten», beide gehen weiter (Audit U-2).
        val askLearning = page == LEARNING_PAGE && model.learningConsent != true
        if (askLearning) {
            Button(
                onClick = { tap { model.onLearningConsent(true); onPage(page + 1) } },
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            ) { Text(stringResource(R.string.learn_consent_start)) }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page > 0) OutlinedButton(onClick = { tap { onPage(page - 1) } }, border = outlinedBorder()) { Text(stringResource(R.string.onb_back)) }
            Spacer(Modifier.weight(1f))
            when {
                askLearning -> TextButton(onClick = { tap { model.onLearningConsent(false); onPage(page + 1) } }) {
                    Text(stringResource(R.string.learn_consent_later))
                }
                page < STEPS -> Button(onClick = {
                    tap {
                        // «Weiter» ohne «Wecker anlegen»: den gezeigten Wecker trotzdem anlegen, sonst fehlte er still –
                        // aber nur, wenn noch keiner besteht (wiederholte Einrichtung, Audit N-1).
                        if (page == ALARM_PAGE && createsOnNext(alarmCreated != null, model)) createAlarm()
                        onPage(page + 1)
                    }
                }) { Text(stringResource(R.string.onb_next)) }
                else -> Button(onClick = { tap(onDone) }) { Text(stringResource(R.string.onb_finish)) }
            }
        }
    }
}

