package com.phasewake.app.wear

import android.app.Application
import com.phasewake.app.shared.AlarmRepository
import com.phasewake.app.shared.NightRepository
import com.phasewake.app.shared.WearSync
import com.phasewake.app.shared.phaseWakeStore
import com.phasewake.app.wear.schedule.AlarmScheduler
import com.phasewake.app.wear.wake.Notifications
import com.phasewake.app.wear.wake.RingState
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
