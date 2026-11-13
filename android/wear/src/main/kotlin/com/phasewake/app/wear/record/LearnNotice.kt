package com.phasewake.app.wear.record

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.LearnNights
import com.phasewake.app.core.LearnSkip
import com.phasewake.app.core.Learning
import com.phasewake.app.core.ShortNight
import com.phasewake.app.core.WakePlan
import com.phasewake.app.wear.PhaseWakeWear
import com.phasewake.app.wear.R
import com.phasewake.app.wear.schedule.AlarmReceiver
import com.phasewake.app.wear.ui.MainActivity
import com.phasewake.app.wear.wearApp
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first

/**
 * Hinweise der Lernphase auf der Galaxy Watch (Lern-Übersicht, Entscheid r1AD 13.11.2026), damit niemand nach 7 Tagen
 * ohne gezählte Nacht dasteht, ohne den Grund zu kennen:
 * - Warnung am Abend: [LearnNights.WARN_LEAD] vor dem Beginn der Aufzeichnung (Beginn der Einschlaf-Messung) prüft ein
 *   ungenauer Alarm den Akku; liegt er unter [Learning.MIN_BATTERY] und lädt die Uhr nicht, kommt «Lade die Uhr …».
 * - Nacht nicht aufgezeichnet ([skipped]): Meldung ans Handy über die Warteschlange ([LearnSkip], dort in der
 *   Lern-Übersicht) und bei Akku oder fehlenden Sensoren eine Mitteilung mit dem Grund.
 * Ohne Mitteilungsrecht nur die Meldung ans Handy und das Diagnose-Protokoll.
 */
object LearnNotice {
    /** Kanal der Hinweise (normale Wichtigkeit: vibriert einmal, kein Vollbild). */
    const val CHANNEL = "learning"

    /** Aktion des Alarms der Warnung an [AlarmReceiver]. */
    const val ACTION_WARN = "com.phasewake.app.action.LEARN_WARN"

    private const val REQUEST_WARN = 20
    private const val REQUEST_OPEN = 21
    private const val ID_NOTICE = 8
    private const val TAG = "LearnNotice"

    /** Legt den Kanal an (beim Start der App, mit den übrigen Kanälen). */
    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.watch_learn_title), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /**
     * Beginn der Aufzeichnung der Lernphase für den nächsten echten Wecker [plan]: Beginn der automatischen
     * Einschlaf-Messung ([ShortNight.autoOnsetStart]). `null`, wenn die Lernphase nicht läuft, «Einschlafen automatisch
     * erkennen» aus ist (dann startet sie nur mit «Gute Nacht», ohne festen Zeitpunkt) oder der Wecker fehlt.
     */
    internal suspend fun recordStart(app: PhaseWakeWear, plan: WakePlan?): Instant? {
        if (plan == null || !app.learning.current().active || !app.autoOnset.enabled.first()) return null
        val spec = app.alarms.current().alarms.firstOrNull { it.id == plan.alarmId } ?: return null
        return ShortNight.autoOnsetStart(plan.target, app.sleepGoal.minutes.first(), spec.sleepDurationMinutes)
    }

    /** Neuplanen ([com.phasewake.app.wear.schedule.AlarmScheduler]): Warnung für [plan] stellen oder aufheben. */
    suspend fun update(context: Context, plan: WakePlan?, now: Instant) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val at = recordStart(context.wearApp, plan)?.let(LearnNights::warnAt)?.takeIf { it.isAfter(now) }
        if (at == null) {
            alarms.cancel(pending(context))
            return
        }
        // Ungenau genügt (darf etwas früher kommen, LearnNights.WARN_EARLY); kein Recht für exakte Alarme nötig.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pending(context))
    }

    /** Alarm der Warnung: Akku prüfen ([LearnNights.shouldWarn]) und gegebenenfalls warnen. */
    suspend fun onWarn(context: Context) {
        val app = context.wearApp
        val start = recordStart(app, app.scheduler.nextAlarmPlan()) ?: return
        val battery = RecordControl.battery(context)
        val charging = context.getSystemService(BatteryManager::class.java)?.isCharging == true
        if (!LearnNights.shouldWarn(true, battery, charging, Instant.now(), start)) return
        app.diag.log(DiagKind.BATTERY, "learn warn battery $battery%")
        show(context, context.getString(R.string.watch_learn_warn, battery ?: 0, Learning.MIN_BATTERY))
    }

    /**
     * Die Lernphase zeichnet die Nacht nicht auf ([skip]): Meldung ans Handy (Warteschlange) und bei Akku oder fehlenden
     * Sensoren eine Mitteilung mit dem Grund. Eine laufende Aufzeichnung von Hand zählt selbst, dazu keine Mitteilung.
     */
    suspend fun skipped(app: PhaseWakeWear, skip: LearnSkip) {
        app.recordSync.publishLearnSkip(skip)
        val text = when (skip.reason) {
            LearnSkip.Reason.BATTERY -> app.getString(R.string.watch_learn_skip_battery, skip.battery ?: 0, Learning.MIN_BATTERY)
            LearnSkip.Reason.SENSORS -> app.getString(R.string.watch_learn_skip_sensors)
            LearnSkip.Reason.MANUAL, LearnSkip.Reason.LATE -> return
        }
        show(app, text)
    }

    /**
     * Einschlaf-Messung des Weckers [alarmId] ohne Sensoren nicht gestartet: Läuft die Lernphase, zeichnet sie diese
     * Nacht nicht auf ([skipped] mit [LearnSkip.Reason.SENSORS], Zielzeit = nächste des Weckers).
     */
    suspend fun sensorsRefused(app: PhaseWakeWear, alarmId: String) {
        if (!app.learning.current().active) return
        val spec = app.alarms.current().alarms.firstOrNull { it.id == alarmId } ?: return
        val target = AlarmSchedule.nextTarget(spec, Instant.now(), ZoneId.systemDefault()) ?: return
        skipped(app, LearnSkip(target, LearnSkip.Reason.SENSORS))
    }

    private fun show(context: Context, text: String) {
        val open = PendingIntent.getActivity(
            context, REQUEST_OPEN, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle(context.getString(R.string.watch_learn_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_NOTICE, notification)
        } catch (e: SecurityException) {
            // Ohne Mitteilungsrecht: Die Lern-Übersicht am Handy zeigt den Grund trotzdem.
            Log.i(TAG, "Hinweis der Lernphase ohne Mitteilungsrecht", e)
        }
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_WARN, Intent(context, AlarmReceiver::class.java).setAction(ACTION_WARN),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
