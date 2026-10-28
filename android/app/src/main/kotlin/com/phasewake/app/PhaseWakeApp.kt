package com.phasewake.app

import android.app.Application
import android.content.Context
import com.phasewake.app.health.HealthSleep
import com.phasewake.app.ring.BackupAlarm
import com.phasewake.app.ring.RingNotifications
import com.phasewake.app.shared.AlarmRepository
import com.phasewake.app.shared.NightRepository
import com.phasewake.app.shared.WearSync
import com.phasewake.app.shared.phaseWakeStore
import java.io.File
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Einstieg der Handy-App: hält die wenigen gemeinsamen Objekte (bewusst ohne DI-Bibliothek). */
class PhaseWakeApp : Application() {

    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            // Ein Fehler in einer Hintergrundarbeit darf den Prozess (und einen klingelnden Wecker) nicht beenden.
            Log.e("PhaseWakeApp", "Hintergrundarbeit fehlgeschlagen", e)
        },
    )

    val alarms by lazy { AlarmRepository(phaseWakeStore) }
    val nights by lazy { NightRepository(File(filesDir, "nights.v1")) }
    val settings by lazy { SettingsRepository(phaseWakeStore) }
    val sync by lazy { WearSync(this) }
    val health by lazy { HealthSleep(this) }
    val backup by lazy { BackupAlarm(this, alarms, phaseWakeStore, sync) }

    override fun onCreate() {
        super.onCreate()
        RingNotifications.createChannels(this)
        com.phasewake.app.ui.Format.update(this)
        com.phasewake.app.ring.SunriseLight.createChannel(this)
        // Vor dem ersten Entsperren nach einem Neustart (nur der Zweitwecker läuft) ist der Speicher
        // nicht lesbar; neu geplant wird beim Entsperren (BOOT_COMPLETED).
        if (!BackupAlarm.isUnlocked(this)) return
        // Datensparsamkeit: ein früher geteilter Export bleibt nicht im Cache liegen.
        java.io.File(cacheDir, "export").deleteRecursively()
        appScope.launch { backup.reschedule() }
    }

    /** Speichert eine Änderung an den Weckern, schickt sie an die Uhr und plant den Zweitwecker neu. */
    fun afterAlarmsChanged(change: suspend () -> com.phasewake.app.shared.AlarmSnapshot) {
        appScope.launch {
            val snapshot = change()
            sync.publishAlarms(snapshot)
            backup.reschedule()
        }
    }
}

val Context.phoneApp: PhaseWakeApp get() = applicationContext as PhaseWakeApp
