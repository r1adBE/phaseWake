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
import com.phasewake.app.shared.SleepGoalStore
import com.phasewake.app.shared.TransientStore
import com.phasewake.app.shared.NightRepository
import com.phasewake.app.shared.WearSync
import com.phasewake.app.shared.phaseWakeStore
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
 * (Wecker, Nächte, DataStore), siehe [com.phasewake.app.wear.schedule.DirectBoot].
 */
class PhaseWakeWear : Application() {

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
    val sync by lazy { WearSync(this) }
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

    /** Probewecker oder Powernap, der gerade gilt. */
    val transient by lazy { TransientStore(phaseWakeStore) }

    /** Weckart zu einer Kennung: gespeicherter Wecker oder vorübergehender Plan. */
    suspend fun specFor(alarmId: String?): AlarmSpec? =
        alarms.current().alarms.firstOrNull { it.id == alarmId } ?: transient.specFor(alarmId)

    /** Einschlafzeit der kommenden Nacht (automatische Messung oder «Gute Nacht») für die Untergrenze kurzer Nächte. */
    val onsets by lazy { OnsetStore(phaseWakeStore) }

    /** «Gute Nacht» (gespeichert, überlebt das Ende des Prozesses). */
    val goodNight by lazy { GoodNightState(phaseWakeStore) }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        diag.log(if (DirectBoot.isUnlocked(this)) DiagKind.BOOT else DiagKind.DIRECT_BOOT, "app start battery ${batteryPercent()}")
        // Nachholen, was das Handy schon abgelegt hat (Wecker, «Verlauf löschen»), falls eine Änderung
        // verpasst wurde; nur nach dem ersten Entsperren, vorher ist der Speicher nicht lesbar.
        if (DirectBoot.isUnlocked(this)) {
            appScope.launch { if (PhoneCatchUp.run(this@PhaseWakeWear)) scheduler.reschedule() }
        }
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
}

/** Akkustand in Prozent für das Protokoll; -1, wenn unbekannt. */
private fun Context.batteryPercent(): Int =
    getSystemService(android.os.BatteryManager::class.java)?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

/** Kurzer Zugriff auf die App aus Activities, Diensten und Empfängern. */
val android.content.Context.wearApp: PhaseWakeWear get() = applicationContext as PhaseWakeWear
