package com.phasewake.app.wear

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.DiagDevice
import com.phasewake.app.core.DiagKind
import com.phasewake.app.shared.AlarmRepository
import com.phasewake.app.shared.DiagnosticsLog
import com.phasewake.app.shared.HolidayStore
import com.phasewake.app.shared.LearningStore
import com.phasewake.app.shared.LearningSync
import com.phasewake.app.shared.AutoOnsetStore
import com.phasewake.app.shared.SleepGoalStore
import com.phasewake.app.shared.TransientStore
import com.phasewake.app.shared.NightRepository
import com.phasewake.app.shared.RecordSync
import com.phasewake.app.shared.RecordingRepository
import com.phasewake.app.shared.OnsetShift
import com.phasewake.app.shared.WearSync
import com.phasewake.app.shared.phaseWakeStore
import com.phasewake.app.wear.record.LearnMarkStore
import com.phasewake.app.wear.record.RecordControl
import com.phasewake.app.wear.schedule.AlarmScheduler
import com.phasewake.app.wear.schedule.DirectBoot
import com.phasewake.app.wear.schedule.OnsetOverride
import com.phasewake.app.wear.schedule.OnsetStore
import com.phasewake.app.wear.sync.PhoneCatchUp
import com.phasewake.app.wear.wake.GoodNightState
import com.phasewake.app.wear.wake.NightProgress
import com.phasewake.app.wear.wake.Notifications
import com.phasewake.app.wear.wake.RingState
import java.io.File
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Einstieg der Uhr-App: hält die wenigen gemeinsamen Objekte (bewusst ohne DI-Bibliothek).
 *
 * Läuft auch vor dem ersten Entsperren (Direct Boot): Dann keines der Objekte mit Speicher anfassen
 * (Wecker, Nächte, DataStore), siehe [com.phasewake.app.wear.schedule.DirectBoot]. Offen nur für Robolectric-Tests,
 * die [catchUpAndReschedule] weglassen (sonst plante es neben dem geprüften Weckdienst).
 */
open class PhaseWakeWear : Application() {

    /** Lebt so lange wie der Prozess; für Arbeit, die eine Activity oder einen Empfänger überdauert. */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            // Ein Fehler in einer Hintergrundarbeit (Speicher, Sync) darf den Prozess – und damit
            // eine laufende Weck-Nacht – nicht beenden.
            Log.e("PhaseWakeWear", "Hintergrundarbeit fehlgeschlagen", e)
            diag.log(DiagKind.ERROR, e.javaClass.simpleName)
        },
    )

    val alarms by lazy { AlarmRepository(phaseWakeStore) }
    val nights by lazy { NightRepository(File(filesDir, "nights.v1")) }
    val sync by lazy { WearSync(this, com.phasewake.app.shared.DataLayer.CAPABILITY_PHONE) }

    /** «Nacht aufzeichnen»: Aufzeichnungen (privat, nicht gesichert) und ihr Austausch mit dem Handy. */
    val recordings by lazy { RecordingRepository(File(filesDir, "recordings")) }
    val recordSync by lazy { RecordSync(this) }
    val onsetOverride by lazy { OnsetOverride(phaseWakeStore) }
    val nightProgress by lazy { NightProgress(phaseWakeStore) }
    val scheduler by lazy { AlarmScheduler(this, alarms, onsetOverride, phaseWakeStore, sync, holidays, transient) }
    val ringState = RingState()

    /** Diagnose-Protokoll (geräteverschlüsselt: auch vor dem ersten Entsperren). */
    val diag by lazy { DiagnosticsLog.forContext(this, DiagDevice.WATCH) }

    /** Feiertage vom Handy für Wecker mit «An Feiertagen nicht wecken». */
    val holidays by lazy { HolidayStore(phaseWakeStore) }

    /** Schlafziel vom Handy: Einschlafpunkt auf dem Ring, automatische Einschlaf-Messung; ohne Handy der Standard. */
    val sleepGoal by lazy { SleepGoalStore(phaseWakeStore) }

    /** Schalter «Einschlafen automatisch erkennen» vom Handy; ohne Handy an. */
    val autoOnset by lazy { AutoOnsetStore(phaseWakeStore) }

    /** Probewecker oder Powernap, der gerade gilt. */
    val transient by lazy { TransientStore(phaseWakeStore) }

    /** Lernphase vom Handy: automatische Aufzeichnung und gelernte Werte; ohne Handy keine. */
    val learning by lazy { LearningStore(phaseWakeStore) }

    /** Lernphase vom Handy nachholen ([com.phasewake.app.wear.sync.PhoneCatchUp]). */
    val learningSync by lazy { LearningSync(this) }

    /** Welche Aufzeichnung die Lernphase begann und bis wann sie läuft. */
    val learnMark by lazy { LearnMarkStore(phaseWakeStore) }

    /** Weckart zu einer Kennung: gespeicherter Wecker oder vorübergehender Plan. */
    suspend fun specFor(alarmId: String?): AlarmSpec? =
        alarms.current().alarms.firstOrNull { it.id == alarmId } ?: transient.specFor(alarmId)

    /** Einschlafzeit der kommenden Nacht (automatische Messung oder «Gute Nacht») für die Untergrenze kurzer Nächte. */
    val onsets by lazy { OnsetStore(phaseWakeStore) }

    /** «Gute Nacht» (gespeichert, überlebt das Ende des Prozesses). */
    val goodNight by lazy { GoodNightState(phaseWakeStore) }

    /**
     * Frühere Zielzeit aus dem Einschlaf-Modus ans Handy ([OnsetShift], als Paket über [sync]). Offen nur für
     * Robolectric-Tests, die die Meldung prüfen (dort gibt es keinen Data Layer).
     */
    internal open suspend fun publishOnsetShift(shift: OnsetShift) = sync.publishOnsetShift(shift)

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        diag.log(if (DirectBoot.isUnlocked(this)) DiagKind.BOOT else DiagKind.DIRECT_BOOT, "app start battery ${batteryPercent()}")
        // Nachholen, was das Handy schon abgelegt hat (Wecker, «Verlauf löschen»), falls eine Änderung
        // verpasst wurde; nur nach dem ersten Entsperren, vorher ist der Speicher nicht lesbar. Danach immer neu
        // planen: Nach «Beenden erzwingen» oder einem Prozessende sind die Alarme weg, und nicht jeder Prozessstart
        // kommt über die Startseite (Kachel, Komplikation, Data Layer). Eine laufende Nacht bleibt dabei unberührt.
        if (DirectBoot.isUnlocked(this)) catchUpAndReschedule()
        // Ladegerät an- oder abgesteckt: Akkustand ans Handy, falls er sich deutlich geändert hat. Nur
        // solange der Prozess lebt (im Manifest kommen diese Meldungen nicht an); sonst beim Neuplanen.
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    // Vor dem ersten Entsperren ist der Speicher nicht lesbar.
                    if (DirectBoot.isUnlocked(context)) appScope.launch { scheduler.publishStatus() }
                }
            },
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Beim Start nach dem Entsperren: vom Handy nachholen, danach immer neu planen (im Hintergrund). */
    internal open fun catchUpAndReschedule() {
        appScope.launch {
            try {
                PhoneCatchUp.run(this@PhaseWakeWear)
            } finally {
                scheduler.reschedule()
                // Aufzeichnung: Stand ans Handy, eine liegengebliebene wieder aufnehmen.
                RecordControl.onAppStart(this@PhaseWakeWear)
            }
        }
    }
}

/** Akkustand in Prozent für das Protokoll; -1, wenn unbekannt. */
private fun Context.batteryPercent(): Int =
    getSystemService(android.os.BatteryManager::class.java)?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

/** Kurzer Zugriff auf die App aus Activities, Diensten und Empfängern. */
val android.content.Context.wearApp: PhaseWakeWear get() = applicationContext as PhaseWakeWear
