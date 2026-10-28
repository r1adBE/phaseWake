package com.phasewake.app.wear

import android.app.Application
import com.phasewake.app.shared.AlarmRepository
import com.phasewake.app.shared.NightRepository
import com.phasewake.app.shared.WearSync
import com.phasewake.app.shared.phaseWakeStore
import com.phasewake.app.wear.schedule.AlarmScheduler
import com.phasewake.app.wear.schedule.OnsetOverride
import com.phasewake.app.wear.wake.Notifications
import com.phasewake.app.wear.wake.RingState
import java.io.File
import java.time.Instant
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow

/** Einstieg der Uhr-App: hält die wenigen gemeinsamen Objekte (bewusst ohne DI-Bibliothek). */
class PhaseWakeWear : Application() {

    /** Lebt so lange wie der Prozess; für Arbeit, die eine Activity oder einen Empfänger überdauert. */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            // Ein Fehler in einer Hintergrundarbeit (Speicher, Sync) darf den Prozess – und damit
            // eine laufende Weck-Nacht – nicht beenden.
            Log.e("PhaseWakeWear", "Hintergrundarbeit fehlgeschlagen", e)
        },
    )

    val alarms by lazy { AlarmRepository(phaseWakeStore) }
    val nights by lazy { NightRepository(File(filesDir, "nights.v1")) }
    val sync by lazy { WearSync(this) }
    val onsetOverride by lazy { OnsetOverride(phaseWakeStore) }
    val scheduler by lazy { AlarmScheduler(this, alarms, onsetOverride, phaseWakeStore, sync) }
    val ringState = RingState()

    /** Seit wann nach «Gute Nacht» auf das Einschlafen gewartet wird; `null`, wenn nicht. */
    val goodNight = MutableStateFlow<Instant?>(null)

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}

/** Kurzer Zugriff auf die App aus Activities, Diensten und Empfängern. */
val android.content.Context.wearApp: PhaseWakeWear get() = applicationContext as PhaseWakeWear
