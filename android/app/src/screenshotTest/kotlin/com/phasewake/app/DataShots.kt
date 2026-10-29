package com.phasewake.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.phasewake.app.core.DiagDevice
import com.phasewake.app.core.DiagEntry
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.PhoneAlert
import com.phasewake.app.core.TestRun
import com.phasewake.app.core.TestStep
import com.phasewake.app.diag.DiagnosticsActions
import com.phasewake.app.diag.DiagnosticsContent
import com.phasewake.app.diag.StatusLine
import com.phasewake.app.ui.home.Check
import com.phasewake.app.ui.home.NightCheckState
import com.phasewake.app.ui.home.TestResultDialog
import com.phasewake.app.ui.settings.NewPasswordDialog
import com.phasewake.app.ui.settings.RestoreConfirmDialog
import com.phasewake.app.ui.theme.ThemeMode
import java.time.Duration

/** Bildschirmfotos von Sicherung, Diagnose und Ergebnis des Probeweckers. Beispieldaten, keine echten. */

@PreviewTest @Preview(locale = "de-rCH", name = "Sicherung Passwort hell", device = PHONE) @Composable
fun BackupCreateLight() = Themed(ThemeMode.LIGHT) {
    // Zwölf Zeichen, beide gleich: «Sichern» ist bereit, darunter der Hinweis zum Passwort.
    NewPasswordDialog(onSave = {}, onDismiss = {}, initial = "nachtfalter7", initialRepeat = "nachtfalter7")
}

@PreviewTest @Preview(locale = "de-rCH", name = "Sicherung Passwort dunkel", device = PHONE) @Composable
fun BackupCreateDark() = Themed(ThemeMode.DARK) {
    // Wiederholung weicht ab: Fehlermeldung in Rot und als Text.
    NewPasswordDialog(onSave = {}, onDismiss = {}, initial = "nachtfalter7", initialRepeat = "nachtfalter")
}

@PreviewTest @Preview(locale = "de-rCH", name = "Wiederherstellen Vorschau hell", device = PHONE) @Composable
fun RestorePreviewLight() = Themed(ThemeMode.LIGHT) {
    RestoreConfirmDialog(alarms = 3, nights = 42, noteDays = 17, onConfirm = {}, onDismiss = {})
}

@PreviewTest @Preview(locale = "de-rCH", name = "Wiederherstellen Vorschau dunkel", device = PHONE) @Composable
fun RestorePreviewDark() = Themed(ThemeMode.DARK) {
    RestoreConfirmDialog(alarms = 3, nights = 42, noteDays = 17, onConfirm = {}, onDismiss = {})
}

/** Ereignisse der letzten Nacht von Handy und Uhr, minutengenau vor der festen Testzeit. */
private fun diagEntry(minutesAgo: Long, device: DiagDevice, kind: DiagKind, detail: String) =
    DiagEntry(FIXED_NOW.minus(Duration.ofMinutes(minutesAgo)), device, kind, detail)

private val diagEntries = listOf(
    diagEntry(2, DiagDevice.PHONE, DiagKind.STOP, "from watch"),
    diagEntry(9, DiagDevice.WATCH, DiagKind.RING, "light sleep"),
    diagEntry(9, DiagDevice.PHONE, DiagKind.RING, "phone with watch"),
    diagEntry(10, DiagDevice.WATCH, DiagKind.PHASE, "light"),
    diagEntry(50, DiagDevice.WATCH, DiagKind.WINDOW, "open"),
    diagEntry(50, DiagDevice.WATCH, DiagKind.MEASURE_START, ""),
    diagEntry(560, DiagDevice.PHONE, DiagKind.SCHEDULED, "phone backup"),
    diagEntry(561, DiagDevice.PHONE, DiagKind.SYNC, "alarms sent 2"),
    diagEntry(561, DiagDevice.WATCH, DiagKind.ALARMS_RECEIVED, "from phone"),
    diagEntry(600, DiagDevice.WATCH, DiagKind.BATTERY, "64 %"),
)

/** Zustand wie auf dem Gerät: Bezeichnungen aus den Texten der App, Werte als Beispiel. */
@Composable
private fun diagStatus(): List<StatusLine> {
    val yes = stringResource(R.string.diag_yes)
    val no = stringResource(R.string.diag_no)
    return listOf(
        StatusLine(stringResource(R.string.diag_next_phone), "29.10.26, 07:05"),
        StatusLine(stringResource(R.string.diag_next_watch), "29.10.26, 06:30 – 07:00"),
        StatusLine(stringResource(R.string.check_exact_alarms), yes),
        StatusLine(stringResource(R.string.diag_battery_opt), no),
        StatusLine(stringResource(R.string.check_notifications), yes),
        StatusLine(stringResource(R.string.check_watch), yes),
        StatusLine(stringResource(R.string.diag_watch_battery), "64 %"),
        StatusLine(stringResource(R.string.diag_watch_seen), stringResource(R.string.diag_unknown)),
        StatusLine(stringResource(R.string.diag_versions), stringResource(R.string.diag_phone) + " 1.0 · " + stringResource(R.string.diag_watch) + " 1.0"),
    )
}

private val noDiagActions = DiagnosticsActions(onBack = {}, onHide = {}, onShare = {}, onFetchWatch = {}, onClear = {})

@PreviewTest @Preview(locale = "de-rCH", name = "Diagnose hell", device = PHONE_TALL) @Composable
fun DiagnosticsLight() = Themed(ThemeMode.LIGHT) { DiagnosticsContent(diagStatus(), diagEntries, noAnswer = false, actions = noDiagActions) }

@PreviewTest @Preview(locale = "de-rCH", name = "Diagnose dunkel", device = PHONE_TALL) @Composable
fun DiagnosticsDark() = Themed(ThemeMode.DARK) { DiagnosticsContent(diagStatus(), diagEntries, noAnswer = true, actions = noDiagActions) }

private val testTarget = FIXED_NOW.minus(Duration.ofMinutes(1))

/** Handy-Wecker aus (nicht geprüft), Stopp kam nicht an: ✓, ✓, –, ✗. */
private val testMixed = TestRun(
    "test-a", testTarget, PhoneAlert.OFF,
    mapOf(TestStep.RECEIVED to testTarget.minusSeconds(118), TestStep.WATCH_RANG to testTarget),
)

/** Handy hat nicht mitgeklingelt («Nicht stören» blockiert): ✓, ✓, ✗ mit Knopf, ✓. */
private val testPhoneFailed = TestRun(
    "test-b", testTarget, PhoneAlert.SOUND,
    mapOf(TestStep.RECEIVED to testTarget.minusSeconds(118), TestStep.WATCH_RANG to testTarget, TestStep.STOP_ARRIVED to testTarget.plusSeconds(20)),
)

@PreviewTest @Preview(locale = "de-rCH", name = "Probewecker Ergebnis gemischt", device = PHONE) @Composable
fun TestResultMixed() = Themed(ThemeMode.LIGHT) { TestResultDialog(testMixed, NightCheckState(emptyList()), onFix = {}, onClose = {}) }

@PreviewTest @Preview(locale = "de-rCH", name = "Probewecker Ergebnis Handy", device = PHONE) @Composable
fun TestResultPhone() = Themed(ThemeMode.DARK) { TestResultDialog(testPhoneFailed, NightCheckState(listOf(Check.DND)), onFix = {}, onClose = {}) }
