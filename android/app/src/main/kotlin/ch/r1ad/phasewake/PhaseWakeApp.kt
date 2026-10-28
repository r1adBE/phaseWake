package ch.r1ad.phasewake

import android.app.Application
import android.content.Context
import ch.r1ad.phasewake.ring.BackupAlarm
import ch.r1ad.phasewake.ring.RingNotifications
import ch.r1ad.phasewake.shared.AlarmRepository
import ch.r1ad.phasewake.shared.NightRepository
import ch.r1ad.phasewake.shared.WearSync
import ch.r1ad.phasewake.shared.phaseWakeStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Einstieg der Handy-App: hält die wenigen gemeinsamen Objekte (bewusst ohne DI-Bibliothek). */
class PhaseWakeApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val alarms by lazy { AlarmRepository(phaseWakeStore) }
    val nights by lazy { NightRepository(File(filesDir, "nights.v1")) }
    val settings by lazy { SettingsRepository(phaseWakeStore) }
    val sync by lazy { WearSync(this) }
    val backup by lazy { BackupAlarm(this, alarms, phaseWakeStore) }

    override fun onCreate() {
        super.onCreate()
        RingNotifications.createChannels(this)
        appScope.launch { backup.reschedule() }
    }

    /** Speichert eine Änderung an den Weckern, schickt sie an die Uhr und plant den Zweitwecker neu. */
    fun afterAlarmsChanged(change: suspend () -> ch.r1ad.phasewake.shared.AlarmSnapshot) {
        appScope.launch {
            val snapshot = change()
            sync.publishAlarms(snapshot)
            backup.reschedule()
        }
    }
}

val Context.phoneApp: PhaseWakeApp get() = applicationContext as PhaseWakeApp
