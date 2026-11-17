package com.phasewake.app.ui.home

import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Learning
import com.phasewake.app.core.SleepGoal
import com.phasewake.app.core.Transient
import com.phasewake.app.core.TransientPlan
import com.phasewake.app.shared.RecordSync
import java.time.Instant
import java.time.LocalDate

/** Neues der Startseite; Vorgaben = nichts davon, wie in den Bildschirmfotos. */
class HomeExtras(
    /** Feiertage für Wecker mit «An Feiertagen nicht wecken». */
    val holidays: Set<LocalDate> = emptySet(),
    /** Pro Wecker erledigt bis (früh geweckt); dieser Wecker zählt erst danach wieder. */
    val doneUntil: Map<String, Instant> = emptyMap(),
    /** Laufender Powernap; `null` = keiner. */
    val nap: TransientPlan? = null,
    /** Vorschlag der Dauer in Minuten. */
    val napMinutes: Int = Transient.NAP_DEFAULT_MINUTES,
    /** Powernap starten (Minuten); `null` = keine Karte. */
    val onNap: ((Int) -> Unit)? = null,
    val onStopNap: (String) -> Unit = {},
    /** «Nächstes Mal überspringen»; `null` = kein Menü (auch, solange die Uhr es nicht kennt). */
    val onSkip: ((AlarmSpec) -> Unit)? = null,
    val onUndoSkip: (AlarmSpec) -> Unit = {},
    /** «In 2 Minuten testen» auf dem Nacht-Check; `null` = kein Knopf. */
    val onTest: (() -> Unit)? = null,
    /** Zielzeit eines laufenden Probeweckers; `null` = keiner. */
    val testAt: Instant? = null,
    /** Die Uhr misst das Einschlafen automatisch (Galaxy Watch): Zeile «ab 22:30» im Kopf. */
    val autoOnset: Boolean = false,
    /** Schlafziel in Minuten, für den Beginn der automatischen Messung. */
    val sleepGoal: Int = SleepGoal.DEFAULT_MINUTES,
    /** «Nacht aufzeichnen» auf der Galaxy Watch; `null` = Uhr kennt es nicht, keine Karte. */
    val record: RecordSync.RecordStatus? = null,
    /** Aufzeichnung starten (`true`) oder stoppen. */
    val onRecord: (Boolean) -> Unit = {},
    /** Lernphase: Fortschritt oder gelernte Werte; `null` = keine Karte. */
    val learning: HomeLearning? = null,
    /** Gelernte Werte nach der Lernphase für die Schlafenszeit am Abend; `null` = keine Empfehlung. */
    val learned: Learning.Values? = null,
)
