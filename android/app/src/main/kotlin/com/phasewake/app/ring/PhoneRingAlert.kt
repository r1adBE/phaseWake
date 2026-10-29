package com.phasewake.app.ring

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.RingAlert
import com.phasewake.app.phoneApp

/**
 * Bringt den Weckbildschirm des Handys nach vorn, auf drei Wegen zugleich (docs/ENTSCHEIDE.md, 29.10.2026):
 * neue Weck-Mitteilung mit Vollbild (eine Aktualisierung startet es nie), Wecker-Alarm des Systems, der
 * [PhoneRingActivity] in 1 s öffnet, und direkter Start (erlaubt, solange ein Fenster der App sichtbar ist).
 * Ist der Weckbildschirm danach nicht zu sehen, alle [RingAlert.REALERT_SECONDS] dasselbe noch einmal.
 * Bei Display an und entsperrt zeigt Android eine Vollbild-Mitteilung nur oben an; die Erinnerung bringt
 * dann das Vollbild, sobald das Display aus oder gesperrt ist.
 */
internal class PhoneRingAlert(private val service: Service, private val handler: Handler) {
    /** Kennung der aktuellen Weck-Mitteilung; `null` = noch keine in diesem Dienst. */
    private var id: Int? = null
    private var realerts = 0
    private val realert = Runnable { onRealert() }

    /**
     * Weck-Mitteilung als Vordergrund-Meldung. [newRound]: neue Klingel-Runde (auch nach dem Schlummern) oder
     * Erinnerung, dann unter neuer Kennung; sonst (zweite Weckmeldung derselben Runde) dieselbe aktualisieren.
     */
    fun post(newRound: Boolean) {
        val current = id
        val next = if (!newRound && current != null) current else RingAlert.nextId(current, RingNotifications.ID_RING, RingNotifications.ID_RING_ALT)
        ServiceCompat.startForeground(
            service, next, RingNotifications.ringing(service),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0,
        )
        // Das System entfernt die alte Vordergrund-Meldung beim Wechsel der Kennung; zur Sicherheit auch hier.
        if (current != null && current != next) NotificationManagerCompat.from(service).cancel(current)
        id = next
    }

    /** Beginn einer Klingel-Runde ([why] für die Diagnose): alle Wege anstossen, Erinnerungen starten. */
    fun show(why: String) {
        realerts = 0
        service.phoneApp.diag.log(DiagKind.RING_SCREEN, "phone $why: ${bring()}")
        handler.removeCallbacks(realert)
        handler.postDelayed(realert, REALERT_MS)
    }

    /** Ende der Klingel-Runde (Schlummern, Stopp): keine Erinnerung und kein Start des Weckbildschirms mehr. */
    fun stop() {
        handler.removeCallbacks(realert)
        service.getSystemService(AlarmManager::class.java).cancel(RingNotifications.ringScreenByAlarm(service))
    }

    private fun onRealert() {
        if (PhoneRingService.ringState.value !is PhoneRingService.State.Ringing) return
        if (!PhoneRingActivity.shown) {
            realerts++
            post(newRound = true)
            val probe = bring()
            // Nur die erste Erinnerung je Runde ins Protokoll (sonst bis 30 Einträge je Runde).
            if (realerts == 1) service.phoneApp.diag.log(DiagKind.RING_SCREEN, "phone not visible, re-alert: $probe")
        }
        handler.postDelayed(realert, REALERT_MS)
    }

    /** Wecker-Alarm und direkter Start; gibt den Zustand für die Diagnose zurück. */
    private fun bring(): String {
        val probe = probe()
        scheduleAlarm()
        // Ohne sichtbares Fenster verwirft Android den Start still; dann tragen Mitteilung und Alarm.
        service.startActivity(
            Intent(service, PhoneRingActivity::class.java)
                .putExtra(PhoneRingActivity.EXTRA_VIA, PhoneRingActivity.VIA_DIRECT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return probe
    }

    /** Wecker-Alarm in [ALARM_DELAY_MS], der den Weckbildschirm öffnet; ersetzt einen schon gestellten. */
    private fun scheduleAlarm() {
        if (!BackupAlarm.canScheduleExact(service)) {
            service.phoneApp.diag.log(DiagKind.RING_SCREEN, "phone alarm launch not allowed")
            return
        }
        val launch = RingNotifications.ringScreenByAlarm(service)
        try {
            service.getSystemService(AlarmManager::class.java)
                .setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + ALARM_DELAY_MS, launch), launch)
        } catch (e: SecurityException) {
            Log.w(TAG, "Wecker-Alarm für den Weckbildschirm nicht erlaubt", e)
            service.phoneApp.diag.log(DiagKind.RING_SCREEN, "phone alarm launch not allowed")
        }
    }

    /** Erwarteter Weg ([RingAlert.route]) und was dazu führt, ohne persönliche Daten. */
    private fun probe(): String {
        val interactive = service.getSystemService(PowerManager::class.java).isInteractive
        val locked = service.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val notifications = NotificationManagerCompat.from(service)
        val fullScreen = notifications.canUseFullScreenIntent()
        val info = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
        // Vordergrunddienst allein (IMPORTANCE_FOREGROUND_SERVICE) zählt nicht als sichtbar.
        val visible = info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        val route = RingAlert.route(visible, interactive, locked, fullScreen)
        return "${route.name} screen=${if (interactive) "on" else "off"} locked=$locked fsi=$fullScreen " +
            "notifications=${notifications.areNotificationsEnabled()} app=$visible"
    }

    private companion object {
        const val TAG = "PhoneRingAlert"
        const val REALERT_MS = RingAlert.REALERT_SECONDS * 1000L

        /** 1 s: frühester Wecker-Alarm, wie bei der Weiterleitung der Weckmeldung ([BackupAlarm.FORWARD_DELAY]). */
        const val ALARM_DELAY_MS = 1000L
    }
}
