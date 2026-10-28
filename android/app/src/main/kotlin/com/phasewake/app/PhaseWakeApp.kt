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
    val notes by lazy { com.phasewake.app.notes.NotesRepository(phaseWakeStore) }
    val bedtime by lazy { com.phasewake.app.reminder.BedtimeReminder(this, alarms, settings) }
    val weekly by lazy { com.phasewake.app.reminder.WeeklyReminder(this) }
    val sounds by lazy { com.phasewake.app.sounds.SoundStore(this) }
    val routine by lazy { com.phasewake.app.habits.RoutineRepository(phaseWakeStore) }

    override fun onCreate() {
        super.onCreate()
        RingNotifications.createChannels(this)
        com.phasewake.app.ui.Format.update(this)
        com.phasewake.app.ring.SunriseLight.createChannel(this)
        com.phasewake.app.reminder.BedtimeReminder.createChannel(this)
        com.phasewake.app.sounds.SleepSoundService.createChannel(this)
        com.phasewake.app.sounds.SoundRecorderService.createChannel(this)
        // Vor dem ersten Entsperren nach einem Neustart (nur der Zweitwecker läuft) ist der Speicher
        // nicht lesbar; neu geplant wird beim Entsperren (BOOT_COMPLETED).
        if (!BackupAlarm.isUnlocked(this)) return
        // Datensparsamkeit: ein früher geteilter Export bleibt nicht im Cache liegen.
        java.io.File(cacheDir, "export").deleteRecursively()
        appScope.launch {
            // Was die Uhr schon hat, abholen: Wecker und Nächte (z. B. Uhr-App vor der Handy-App
            // installiert). Die Nacht-Pakete werden danach gelöscht, wie beim normalen Empfang.
            sync.fetchAlarms()?.let { alarms.applyRemote(it) }
            for ((night, uri) in sync.fetchNights()) {
                nights.add(night)
                sync.delete(uri)
            }
            backup.reschedule()
        }
        // Geräusche: Abgelaufenes beim Start löschen, danach täglich.
        appScope.launch { sounds.cleanup() }
        com.phasewake.app.sounds.SoundStore.scheduleCleanup(this)
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
