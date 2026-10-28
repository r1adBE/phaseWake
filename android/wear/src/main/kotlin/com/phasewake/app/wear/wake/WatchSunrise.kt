package com.phasewake.app.wear.wake

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import com.phasewake.app.core.Sunrise
import com.phasewake.app.wear.ui.SunriseActivity
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sonnenaufgang ab Weckfenster auf der Uhr ([com.phasewake.app.core.SunriseDevice.WATCH] oder BOTH):
 * Der [WakeService] startet ihn mit dem Fenster, [SunriseActivity] zeigt ihn. Endet mit dem Klingeln
 * (der Weckbildschirm übernimmt das Licht), mit der Nacht oder durch Tippen; die Messung läuft weiter.
 * Kein eigener Wake-Lock: Der Bildschirm bleibt nur über die Activity an.
 */
object WatchSunrise {
    /** Ein laufendes Licht von [start] (Fensterbeginn) bis [end] (Zielzeit). */
    data class Active(val alarmId: String, val start: Instant, val end: Instant) {
        fun progress(now: Instant = Instant.now()): Float = Sunrise.progress(start, end, now)
    }

    private val state = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = state.asStateFlow()

    /** Startet das Licht: Vollbild-Mitteilung (auch bei dunklem Display) und, wenn erlaubt, gleich die Activity. */
    fun start(context: Context, active: Active, targetText: String) {
        state.value = active
        context.getSystemService(NotificationManager::class.java)
            .notify(Notifications.ID_SUNRISE, Notifications.sunrise(context, targetText))
        try {
            // Aus dem Dienst nur mit neuer Aufgabe möglich (Lint «WearRecents» trifft hier nicht zu).
            @Suppress("WearRecents")
            context.startActivity(Intent(context, SunriseActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            // Start aus dem Hintergrund verweigert: Die Vollbild-Mitteilung öffnet den Bildschirm.
            Log.w("WatchSunrise", "Sonnenaufgang nicht direkt gestartet", e)
        }
    }

    /** Beendet das Licht (getippt, geklingelt oder Nacht vorbei). */
    fun stop(context: Context) {
        state.value = null
        context.getSystemService(NotificationManager::class.java).cancel(Notifications.ID_SUNRISE)
    }
}
