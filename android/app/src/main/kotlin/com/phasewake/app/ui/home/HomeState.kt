package com.phasewake.app.ui.home

import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.CustomNote
import com.phasewake.app.core.Finding
import com.phasewake.app.core.FindingKind
import com.phasewake.app.core.MissHint
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.RoutineItem
import com.phasewake.app.core.SoundClip
import com.phasewake.app.sounds.SleepSoundService
import java.io.File
import java.time.LocalDate

/**
 * Was die Startseite ([HomeScreen]) zeigt.
 *
 * @property finding Die wichtigste Auffälligkeit der letzten 7 Nächte ([com.phasewake.app.core.Findings.home]);
 *   `null`: keine Karte.
 * @property bedtimeFinding Auffälligkeit, solange die Bettzeit-Karte steht: ohne Bettzeit-Tipp (Findings.home mit
 *   `bedtimeAdvice`).
 * @property notes Schlafnotizen je Nachtdatum; [customNotes] die eigenen Notizen.
 * @property soundPlaying Laufende Einschlafklänge.
 * @property soundRec «Geräusche aufnehmen» für diese Nacht; `null`: kein Schalter (Bildschirmfotos).
 * @property soundClips Aufgenommene Geräusche (alle Nächte) für den Morgenbericht; [soundFile] liefert die Datei.
 * @property routineItems Abendroutine: Punkte; [routineTicks] die Haken je Nachtdatum.
 */
data class HomeState(
    val alarms: List<AlarmSpec>,
    val lastNight: NightRecord?,
    val check: NightCheckState?,
    val missHint: MissHint? = null,
    val finding: Finding? = null,
    val bedtimeFinding: Finding? = finding,
    val notes: Map<LocalDate, List<String>> = emptyMap(),
    val customNotes: List<CustomNote> = emptyList(),
    val soundPlaying: SleepSoundService.Playing? = null,
    val soundRec: SoundRecState? = null,
    val soundClips: List<SoundClip> = emptyList(),
    val soundFile: (SoundClip) -> File? = { null },
    val routineItems: List<RoutineItem> = emptyList(),
    val routineTicks: Map<LocalDate, List<String>> = emptyMap(),
)

/**
 * Aktionen der Startseite ([HomeScreen]).
 *
 * @property onSounds Einstieg zu den Einschlafklängen; `null`: kein Einstieg (Bildschirmfotos).
 */
data class HomeActions(
    val onFix: (Check) -> Unit,
    val onAdd: () -> Unit,
    val onEdit: (String) -> Unit,
    val onToggle: (AlarmSpec, Boolean) -> Unit,
    val onFeeling: (NightRecord, Int) -> Unit = { _, _ -> },
    val onHideFinding: (FindingKind) -> Unit = {},
    val onToggleNote: (LocalDate, String) -> Unit = { _, _ -> },
    val onSounds: (() -> Unit)? = null,
    val onSoundRec: (Boolean) -> Unit = {},
    val onDeleteClip: (SoundClip) -> Unit = {},
    val onDeleteNightClips: (LocalDate) -> Unit = {},
    val onToggleRoutine: (LocalDate, String) -> Unit = { _, _ -> },
)
