package com.phasewake.app.wear

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import com.android.tools.screenshot.PreviewTest
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.DiagDevice
import com.phasewake.app.core.DiagEntry
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.Transient
import com.phasewake.app.wear.ui.DiagScreen
import com.phasewake.app.wear.ui.EditorContent
import com.phasewake.app.wear.ui.HomeContent
import com.phasewake.app.wear.ui.NapScreen
import com.phasewake.app.wear.ui.WatchHomeExtras
import java.time.Duration

/** Bildschirmfotos der Uhr aus Runde 3: Powernap, Diagnose und «Nächstes Mal überspringen». Beispieldaten, keine echten. */

/** Startbildschirm mit dem Chip «Powernap» (zentriert unter dem Weckfenster). */
@Composable
private fun NapHome(extras: WatchHomeExtras) = Swiss {
    AppScaffold {
        HomeContent(
            alarms = alarms,
            plan = AlarmSchedule.earliestPlan(alarms, now, zone),
            zone = zone,
            goodNightActive = false,
            permissionsMissing = false,
            dndBlocks = false,
            onGoodNight = {},
            onRequestPermissions = {},
            onOpenDnd = {},
            onToggle = { _, _ -> },
            extras = extras,
        )
    }
}

@PreviewTest @Preview(locale = "de-rCH", name = "Uhr Powernap", device = WATCH, showBackground = true, backgroundColor = 0xFF000000) @Composable
fun WatchNap() = NapHome(WatchHomeExtras(onNap = {}))

/** Powernap vor 5 Minuten gestartet, 20 Minuten lang: Chip «Beenden» mit der Weckzeit. */
@PreviewTest @Preview(locale = "de-rCH", name = "Uhr Powernap läuft", device = WATCH, showBackground = true, backgroundColor = 0xFF000000) @Composable
fun WatchNapRunning() = NapHome(
    WatchHomeExtras(nap = Transient.nap(alarms.first(), now.minus(Duration.ofMinutes(5)), zone, 20, "preview"), onNap = {}),
)

@PreviewTest @Preview(locale = "de-rCH", name = "Uhr Powernap Dauer", device = WATCH, showBackground = true, backgroundColor = 0xFF000000) @Composable
fun WatchNapPicker() = Swiss {
    AppScaffold { NapScreen(Transient.NAP_DEFAULT_MINUTES, onStart = {}) }
}

/** Ereignisse der Uhr, minutengenau vor der festen Testzeit. */
private fun watchEntry(minutesAgo: Long, kind: DiagKind, detail: String) =
    DiagEntry(now.minus(Duration.ofMinutes(minutesAgo)), DiagDevice.WATCH, kind, detail)

private val watchEntries = listOf(
    watchEntry(1, DiagKind.SCHEDULED, "watch 06:30 – 07:00"),
    watchEntry(2, DiagKind.ALARMS_RECEIVED, "from phone"),
    watchEntry(30, DiagKind.BATTERY, "64 %"),
    watchEntry(95, DiagKind.BOOT, "watch BOOT_COMPLETED"),
)

@PreviewTest @Preview(locale = "de-rCH", name = "Uhr Diagnose", device = WATCH, showBackground = true, backgroundColor = 0xFF000000) @Composable
fun WatchDiagnostics() = Swiss {
    AppScaffold { DiagScreen(next = "Mi., 28.10., 06:30 – 07:00", version = "1.0", entries = watchEntries, onClear = {}, onHide = {}) }
}

/**
 * Editor, gescrollt bis zum Schalter «Nächstes Mal überspringen» (eingeschaltet, mit Datum). Eintrag 14: Titel, Zeit,
 * Fenster (2), Tage-Titel und -Text, sieben Wochentage, «Ein» – dann der Schalter.
 */
@PreviewTest @Preview(locale = "de-rCH", name = "Uhr Wecker überspringen", device = WATCH, showBackground = true, backgroundColor = 0xFF000000) @Composable
fun WatchEditorSkip() = Swiss {
    AppScaffold {
        EditorContent(
            draft = alarms.first(),
            isNew = false,
            onPickTime = {},
            onChange = {},
            onSave = {},
            onDelete = {},
            skipped = "Mi., 28.10.",
            onSkip = {},
            listState = rememberScalingLazyListState(initialCenterItemIndex = SKIP_ITEM),
        )
    }
}

/** Index des Schalters «Überspringen» in der Liste des Editors. */
private const val SKIP_ITEM = 14
