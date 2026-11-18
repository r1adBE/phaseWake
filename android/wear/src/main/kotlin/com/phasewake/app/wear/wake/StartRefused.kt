package com.phasewake.app.wear.wake

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.WakePlan
import com.phasewake.app.core.WakeReason
import com.phasewake.app.wear.R
import com.phasewake.app.wear.schedule.AlarmReceiver
import com.phasewake.app.wear.system.StartLock
import com.phasewake.app.wear.ui.RingActivity
import com.phasewake.app.wear.wearApp

/**
 * Start des [WakeService] im Vordergrund, abgesichert: Verweigert das System den Start
 * (ForegroundServiceStartNotAllowedException, IllegalStateException), steht eine Zeile in der Diagnose. Sollte der
 * Start wecken (Zielzeit, Ende des Schlummerns), erscheint die Weck-Mitteilung direkt: Ihr Vollbild öffnet den
 * Weckbildschirm, und der holt den Dienst aus dem Vordergrund nach ([RingActivity]). Vorher schaltet die Uhr das
 * Display ein ([RingScreenWake]); ist der Weckbildschirm nach [RingScreenWake.RETRY_MS] nicht zu sehen, einmal dasselbe
 * als neue Mitteilung.
 */
internal object StartRefused {
    /** Aufträge, die wecken: Nur bei ihnen erscheint die Weck-Mitteilung ohne Dienst. */
    private val RINGING = setOf(WakeService.ACTION_DEADLINE, WakeService.ACTION_SNOOZE_END)

    /** Request-Code der Absicht zum Weckbildschirm (getrennt von denen in [Notifications]). */
    private const val REQUEST_RESCUE = 7

    /** Auftrag an den Weckbildschirm: Dienst mit dieser Aktion und dem Plan im Intent nachholen. */
    const val EXTRA_ACTION = "rescueAction"

    private val screenWake = RingScreenWake("phasewake:rescue")
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    /** Kennung der Wiederholung im Handler. */
    private val RETRY_TOKEN = Any()

    /** Ob der Display-Wakelock gehalten wird; für Tests. */
    internal val wakeHeld: Boolean get() = screenWake.held

    /**
     * Startet [intent] als Vordergrunddienst; `false`, wenn das System den Start verweigert hat (dann schon behandelt).
     */
    fun start(context: Context, intent: Intent, plan: WakePlan?): Boolean = try {
        ContextCompat.startForegroundService(context, intent)
        true
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException (ab Android 12) ist eine IllegalStateException.
        onRefused(context, intent.action, plan, e)
        // Kein Dienst, der ihn lösen würde: Start-Wakelock gleich lösen (nach der Weck-Mitteilung).
        StartLock.release()
        false
    }

    /** Protokolliert den verweigerten Start und zeigt bei einem Weck-Auftrag die Weck-Mitteilung direkt. */
    fun onRefused(context: Context, action: String?, plan: WakePlan?, error: Exception) {
        Log.e(WakeService.TAG, "Vordergrund-Start verweigert: $action", error)
        val ringing = if (plan != null && action != null && action in RINGING) action to plan else null
        runCatching {
            context.wearApp.diag.log(
                DiagKind.ERROR,
                "watch start refused ${action?.substringAfterLast('.')} ${error.javaClass.simpleName}" +
                    if (ringing != null) ", ring notification" else "",
            )
        }
        if (ringing != null) {
            val app = context.applicationContext
            postRescue(app, ringing.first, ringing.second)
            handler.removeCallbacksAndMessages(RETRY_TOKEN)
            handler.postDelayed({ retry(app, ringing.first, ringing.second) }, RETRY_TOKEN, RingScreenWake.RETRY_MS)
        }
    }

    /** Display ein, dann die Weck-Mitteilung (neu, damit das Vollbild startet). */
    private fun postRescue(context: Context, action: String, plan: WakePlan) {
        screenWake.wake(context)
        try {
            NotificationManagerCompat.from(context).notify(Notifications.ID_RING, notification(context, action, plan))
        } catch (e: SecurityException) {
            // Mitteilungen nicht erlaubt: Der Zweitwecker am Handy bleibt.
            Log.e(WakeService.TAG, "Weck-Mitteilung nicht erlaubt", e)
        }
    }

    /** Weckbildschirm nicht zu sehen und kein Dienst: Mitteilung einmal neu (Aktualisierung startet kein Vollbild). */
    private fun retry(context: Context, action: String, plan: WakePlan) {
        if (RingActivity.shown || WakeService.running) return
        NotificationManagerCompat.from(context).cancel(Notifications.ID_RING)
        postRescue(context, action, plan)
        runCatching {
            context.wearApp.diag.log(
                DiagKind.RING_SCREEN,
                "watch rescue not visible, re-alert: screen=${RingScreenWake.screen(context)}",
            )
        }
    }

    /**
     * Weck-Mitteilung wie beim Klingeln ([Notifications.ringing]), ihr Vollbild trägt Aktion und Plan zum Nachholen.
     */
    internal fun notification(context: Context, action: String, plan: WakePlan): Notification {
        val screen = AlarmReceiver.putPlan(Intent(context, RingActivity::class.java), plan)
            .putExtra(RingActivity.EXTRA_VIA, RingActivity.VIA_RESCUE)
            .putExtra(EXTRA_ACTION, action)
        val open = PendingIntent.getActivity(
            context,
            REQUEST_RESCUE,
            screen,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, Notifications.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle(context.getString(R.string.notification_ring_title))
            .setContentText(context.getString(ringReasonRes(WakeReason.DEADLINE)))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .build()
    }

    /**
     * Vom Weckbildschirm aus [RingActivity.onCreate]: Kam er über die Weck-Mitteilung ohne laufenden Dienst, startet
     * er den Dienst jetzt (sichtbar, also erlaubt). `true`, wenn nachgeholt wird.
     */
    fun resume(context: Context, intent: Intent?): Boolean {
        if (intent?.getStringExtra(RingActivity.EXTRA_VIA) != RingActivity.VIA_RESCUE || WakeService.running) {
            return false
        }
        val plan = AlarmReceiver.planFrom(intent) ?: return false
        val action = intent.getStringExtra(EXTRA_ACTION)?.takeIf { it in RINGING } ?: return false
        // Der Weckbildschirm ist offen und hält das Display selbst an; der Dienst übernimmt das Wecken.
        handler.removeCallbacksAndMessages(RETRY_TOKEN)
        screenWake.release()
        runCatching {
            context.wearApp.diag.log(DiagKind.RING_SCREEN, "watch rescue start ${action.substringAfterLast('.')}")
        }
        WakeService.command(context, action, plan)
        return true
    }
}
