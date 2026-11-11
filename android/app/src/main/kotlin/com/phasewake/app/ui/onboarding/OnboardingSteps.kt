package com.phasewake.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.phasewake.app.ui.components.LogoMark
import com.phasewake.app.R
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Learning
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.editor.ChoiceLabel
import com.phasewake.app.ui.editor.TIME_PICKER_SCALE
import com.phasewake.app.ui.editor.CHECK_ICON_MAX_SEGMENTS
import com.phasewake.app.ui.editor.WINDOW_OPTIONS
import com.phasewake.app.ui.editor.scaled
import com.phasewake.app.ui.home.Check
import com.phasewake.app.ui.settings.HealthState
import java.time.Instant

// Schritte der Einrichtung: Inhalt der einzelnen Seiten.

/** Seite mit Überschrift (für Bildschirmleser als Überschrift ausgezeichnet). */
@Composable
internal fun Page(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
internal fun Body(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
}

@Composable
internal fun Welcome() {
    // Mond in der Schriftfarbe: auf dem hellen Grund dunkel, im dunklen Modus hell (Audit N-8).
    LogoMark(64.dp, moon = MaterialTheme.colorScheme.onSurface)
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
internal fun WatchStep(model: OnboardingModel) {
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

/**
 * Erster Wecker: Zielzeit und Fenster, Werktage; alles Weitere später im Editor. [created]: Fenster des angelegten
 * Weckers, `null` = noch keiner; «Wecker anlegen» und «Weiter» rufen beide [onCreate].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlarmStep(model: OnboardingModel, time: TimePickerState, window: Int, onWindow: (Int) -> Unit, created: String?, onCreate: () -> Unit) {
    Page(R.string.onb_alarm_title) {
        Body(R.string.onb_alarm_text)
        // Angelegt: Der Wähler ist gesperrt, eine Änderung legte sonst still nichts an (Audit N-11).
        val locked = created != null
        val lockedText = created?.let { stringResource(R.string.onb_alarm_done, it) }
        TimePicker(
            time,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .scaled(TIME_PICKER_SCALE)
                .then(if (lockedText != null) Modifier.locked(lockedText) else Modifier),
        )
        // Wie im Editor: «Genau · 10 · 20 · 30».
        val exact = window == AlarmSpec.EXACT_WINDOW
        val exactLabel = stringResource(R.string.editor_window_exact)
        Text(if (exact) exactLabel else stringResource(R.string.editor_window, window), style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Max)) {
            WINDOW_OPTIONS.forEachIndexed { index, value ->
                SegmentedButton(
                    selected = value == window,
                    onClick = { onWindow(value) },
                    enabled = !locked,
                    shape = SegmentedButtonDefaults.itemShape(index, WINDOW_OPTIONS.size),
                    modifier = Modifier.heightIn(min = 48.dp).fillMaxHeight(),
                    // Wie im Editor: bei mehr als zwei Feldern ohne Haken (Audit UI-7).
                    icon = { if (WINDOW_OPTIONS.size <= CHECK_ICON_MAX_SEGMENTS) SegmentedButtonDefaults.Icon(value == window) },
                ) { ChoiceLabel(if (value == AlarmSpec.EXACT_WINDOW) exactLabel else Format.number(value)) }
            }
        }
        if (exact) {
            Text(stringResource(R.string.editor_window_exact_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val label = created
        if (label != null) {
            Text(stringResource(R.string.onb_alarm_done, label), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        } else if (model.canCreateAlarm) {
            Button(onClick = onCreate) { Text(stringResource(R.string.onb_alarm_create)) }
        }
    }
}

/** Ob «Weiter» auf der Seite «Erster Wecker» den gezeigten Wecker anlegt: nur, wenn hier noch keiner angelegt wurde und noch keiner besteht. */
internal fun createsOnNext(createdHere: Boolean, model: OnboardingModel): Boolean =
    !createdHere && !model.hasAlarm && model.canCreateAlarm

/** Sperrt den Inhalt sichtbar (blasser) für Berührung; Bildschirmleser lesen [description] statt der Bedienelemente. */
private fun Modifier.locked(description: String): Modifier = this
    .alpha(LOCKED_ALPHA)
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
    .clearAndSetSemantics {
        contentDescription = description
        disabled()
    }

/** Deckkraft gesperrter Bedienelemente (wie Material für deaktivierte Inhalte). */
private const val LOCKED_ALPHA = 0.38f

/**
 * Lernphase: kurze Einleitung und drei Punkte (Audit U-2); die Knöpfe «Später» / «Lernphase starten» stehen unten
 * statt «Weiter». Nach dem Start «✓ Erlaubt».
 */
@Composable
internal fun LearningStep(model: OnboardingModel) {
    Page(R.string.learn_consent_title) {
        Body(R.string.learn_intro)
        listOf(
            stringResource(R.string.learn_point_nights, Learning.NIGHTS),
            stringResource(R.string.learn_point_battery),
            stringResource(R.string.learn_point_private),
        ).forEach { point ->
            Row {
                Text("•", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 10.dp).clearAndSetSemantics { })
                Text(point, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (model.learningConsent == true) {
            Text("✓ " + stringResource(R.string.onb_granted), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun TestPage(model: OnboardingModel) {
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
internal fun RightsStep(model: OnboardingModel) {
    val failed = model.check?.failed.orEmpty()
    Page(R.string.onb_rights_title) {
        listOf(
            Check.NOTIFICATIONS,
            Check.FULL_SCREEN,
            Check.EXACT_ALARMS,
        ).filter { it.inReadyList || it in failed }.forEach { check ->
            // Erlaubt: «✓ Mitteilungen erlaubt»; fehlt es: was ohne das Recht passiert, dazu «Erlauben» (Audit UI-10).
            val granted = check !in failed
            RightRow(stringResource(if (granted) check.title else check.problem), granted) { model.onFix(check) }
        }
        val health = model.health
        if (health != HealthState.UNAVAILABLE) {
            RightRow(
                stringResource(R.string.onb_optional, stringResource(R.string.health_connect)),
                granted = health == HealthState.CONNECTED,
                state = stringResource(R.string.health_connected),
                onAllow = model.onConnectHealth,
            )
        }
    }
}

/**
 * Ein Recht: Die Zeile zeigt den Zustand – erlaubt mit Haken, sonst was fehlt –, der Knopf «Erlauben» erscheint nur,
 * wenn das Recht fehlt. [state]: Zustand rechts statt Haken vorne (Gesundheitsdaten: «Verbunden»).
 */
@Composable
private fun RightRow(text: String, granted: Boolean, state: String? = null, onAllow: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (granted && state == null) "✓ $text" else text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        if (!granted) {
            FilledTonalButton(onClick = onAllow) { Text(stringResource(R.string.check_allow)) }
        } else if (state != null) {
            Text("✓ $state", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}
