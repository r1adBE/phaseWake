package ch.r1ad.phasewake.wear

import android.app.Application
import ch.r1ad.phasewake.shared.AlarmRepository
import ch.r1ad.phasewake.shared.NightRepository
import ch.r1ad.phasewake.shared.WearSync
import ch.r1ad.phasewake.shared.phaseWakeStore
import ch.r1ad.phasewake.wear.schedule.AlarmScheduler
import ch.r1ad.phasewake.wear.wake.Notifications
import ch.r1ad.phasewake.wear.wake.RingState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/** Einstieg der Uhr-App: hält die wenigen gemeinsamen Objekte (bewusst ohne DI-Bibliothek). */
class PhaseWakeWear : Application() {

    /** Lebt so lange wie der Prozess; für Arbeit, die eine Activity oder einen Empfänger überdauert. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val alarms by lazy { AlarmRepository(phaseWakeStore) }
    val nights by lazy { NightRepository(File(filesDir, "nights.v1")) }
    val sync by lazy { WearSync(this) }
    val scheduler by lazy { AlarmScheduler(this, alarms) }
    val ringState = RingState()

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}

/** Kurzer Zugriff auf die App aus Activities, Diensten und Empfängern. */
val android.content.Context.wearApp: PhaseWakeWear get() = applicationContext as PhaseWakeWear
