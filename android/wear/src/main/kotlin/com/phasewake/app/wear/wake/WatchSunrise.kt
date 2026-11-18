package com.phasewake.app.wear.wake

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.Sunrise
import com.phasewake.app.wear.ui.SunriseActivity
import com.phasewake.app.wear.wearApp
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sonnenaufgang ab Weckfenster auf der Uhr ([com.phasewake.app.core.SunriseDevice.WATCH] oder BOTH):
 * Der [WakeService] startet ihn mit dem Fenster (höchstens 30 Min vor der Zielzeit), [SunriseActivity] zeigt ihn.
 * Endet mit dem Klingeln (der Weckbildschirm übernimmt das Licht), mit der Nacht oder durch Tippen; die Messung läuft
 * weiter. Zum Öffnen schaltet ein kurzer Display-Wakelock das Display gedimmt ein ([RingScreenWake], 18.11.2026):
 * Bei dunklem Display führt Wear OS das Vollbild nicht aus. Danach bleibt der Bildschirm nur über die Activity an.
 */
object WatchSunrise {
    /** Ein laufendes Licht von [start] ([Sunrise.lightStart]) bis [end] (Zielzeit). */
    data class Active(val alarmId: String, val start: Instant, val end: Instant) {
        fun progress(now: Instant = Instant.now()): Float = Sunrise.progress(start, end, now)
    }

    private val state = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = state.asStateFlow()

    private val screenWake = RingScreenWake("phasewake:sunrise", bright = false)
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    /** Ob [SunriseActivity] gerade zu sehen ist; sonst kommt die Mitteilung nach [RingScreenWake.RETRY_MS] neu. */
    @Volatile
    var shown: Boolean = false

    /** Ob der Display-Wakelock gehalten wird; für Tests. */
    internal val wakeHeld: Boolean get() = screenWake.held

    /**
     * Startet das Licht: Display ein, Vollbild-Mitteilung und, wenn erlaubt, gleich die Activity. Ist sie nach
     * [RingScreenWake.RETRY_MS] nicht zu sehen, einmal dasselbe als neue Mitteilung (eine Aktualisierung startet das
     * Vollbild nie).
     */
    fun start(context: Context, active: Active, targetText: String) {
        if (state.value == null) context.wearApp.diag.log(DiagKind.RING, "watch sunrise start")
        state.value = active
        val app = context.applicationContext
        handler.removeCallbacksAndMessages(RETRY_TOKEN)
        show(app, targetText)
        handler.postDelayed({ retry(app, active, targetText) }, RETRY_TOKEN, RingScreenWake.RETRY_MS)
    }

    private fun retry(context: Context, active: Active, targetText: String) {
        if (state.value != active || shown) return
        context.getSystemService(NotificationManager::class.java).cancel(Notifications.ID_SUNRISE)
        show(context, targetText)
        context.wearApp.diag.log(
            DiagKind.RING,
            "watch sunrise not visible, re-alert: screen=${RingScreenWake.screen(context)}",
        )
    }

    private fun show(context: Context, targetText: String) {
        screenWake.wake(context)
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

    /** Beendet das Licht (getippt, geklingelt oder Nacht vorbei); ein laufendes ins Diagnose-Protokoll. */
    fun stop(context: Context) {
        if (state.value != null) context.wearApp.diag.log(DiagKind.STOP, "watch sunrise end")
        state.value = null
        handler.removeCallbacksAndMessages(RETRY_TOKEN)
        screenWake.release()
        context.getSystemService(NotificationManager::class.java).cancel(Notifications.ID_SUNRISE)
    }

    /** Kennung der Wiederholung im Handler. */
    private val RETRY_TOKEN = Any()
}
