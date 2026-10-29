package com.phasewake.app

import android.app.Application
import android.content.Context
import com.phasewake.app.health.HealthSleep
import com.phasewake.app.ring.BackupAlarm
import com.phasewake.app.ring.RingNotifications
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.DiagDevice
import com.phasewake.app.core.DiagKind
import com.phasewake.app.holidays.HolidayRepository
import com.phasewake.app.shared.AlarmRepository
import com.phasewake.app.shared.DiagnosticsLog
import com.phasewake.app.shared.HolidayStore
import com.phasewake.app.shared.TestRunStore
import com.phasewake.app.shared.TransientStore
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
            diag.log(DiagKind.ERROR, e.javaClass.simpleName)
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

    /** Diagnose-Protokoll (geräteverschlüsselt, auch vor dem ersten Entsperren). */
    val diag by lazy { DiagnosticsLog.forContext(this, DiagDevice.PHONE) }

    /** Probewecker oder Powernap, der gerade gilt. */
    val transient by lazy { TransientStore(phaseWakeStore) }
    val testRuns by lazy { TestRunStore(phaseWakeStore) }
    val holidays by lazy { HolidayRepository(this, settings, HolidayStore(phaseWakeStore), sync, diag::log) }

    /** Weckart zu einer Kennung: gespeicherter Wecker oder vorübergehender Plan (Probewecker, Powernap). */
    suspend fun specFor(alarmId: String?): AlarmSpec? =
        alarms.current().alarms.firstOrNull { it.id == alarmId } ?: transient.specFor(alarmId)

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
        if (!BackupAlarm.isUnlocked(this)) {
            diag.log(DiagKind.DIRECT_BOOT, "app start locked")
            return
        }
        diag.log(DiagKind.BOOT, "app start")
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
            // Feiertage nachführen (Fenster der nächsten 400 Tage); bei einer Änderung neu planen.
            if (holidays.refresh()) backup.reschedule()
            // Schlafziel für den Einschlafpunkt auf der Uhr (unverändert: gleiches Paket, die Uhr merkt nichts).
            publishSleepGoal()
        }
        // Geräusche: Abgelaufenes beim Start löschen, danach täglich.
        appScope.launch { sounds.cleanup() }
        com.phasewake.app.sounds.SoundStore.scheduleCleanup(this)
    }

    /** Schickt das Schlafziel an die Uhr (nach einer Änderung, beim Start, nach dem Wiederherstellen). */
    suspend fun publishSleepGoal() {
        sync.publishSleepGoal(settings.sleepGoalNow(), settings.sleepGoalStamp())
    }

    /** Speichert eine Änderung an den Weckern, schickt sie an die Uhr und plant den Zweitwecker neu. */
    fun afterAlarmsChanged(change: suspend () -> com.phasewake.app.shared.AlarmSnapshot) {
        appScope.launch {
            val snapshot = change()
            diag.log(DiagKind.SYNC, "alarms sent ${snapshot.alarms.size}")
            sync.publishAlarms(snapshot)
            backup.reschedule()
            com.phasewake.app.widget.NextAlarmWidget.update(this@PhaseWakeApp)
        }
    }
}

val Context.phoneApp: PhaseWakeApp get() = applicationContext as PhaseWakeApp
