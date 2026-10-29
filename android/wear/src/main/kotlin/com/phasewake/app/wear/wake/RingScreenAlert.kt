package com.phasewake.app.wear.wake

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.RingAlert
import com.phasewake.app.wear.ui.RingActivity
import com.phasewake.app.wear.wake.WakeService.Companion.TAG
import com.phasewake.app.wear.wake.WakeService.Night
import com.phasewake.app.wear.wake.WakeService.Phase
import com.phasewake.app.wear.wearApp

// Weckbildschirm nach vorn bringen, auf drei Wegen zugleich (siehe docs/ENTSCHEIDE.md, 29.10.2026):
// 1. neue Weck-Mitteilung mit Vollbild (eine Aktualisierung startet es nie),
// 2. Wecker-Alarm des Systems, der den Weckbildschirm in 1 s öffnet,
// 3. direkter Start (erlaubt, solange ein Fenster der App sichtbar ist).
// Ist der Weckbildschirm danach nicht zu sehen, alle RingAlert.REALERT_SECONDS dasselbe noch einmal.

/** Abstand der Erinnerungen in Millisekunden. */
private const val REALERT_MS = RingAlert.REALERT_SECONDS * 1000L

/** 1 s: frühester Wecker-Alarm, wie beim Fensterbeginn ([com.phasewake.app.wear.schedule.AlarmScheduler]). */
private const val ALARM_DELAY_MS = 1000L

/**
 * Beginn einer Klingel-Runde ([why] für die Diagnose, z. B. «ring» oder «restart»): Weck-Mitteilung unter
 * neuer Kennung, Weckbildschirm auf allen Wegen anstossen, Erinnerungen starten.
 */
internal fun WakeService.postRinging(n: Night, why: String) {
    goForeground(ringingNotification(n), measuring = false, id = nextRingId())
    realerts = 0
    app.diag.log(DiagKind.RING_SCREEN, "watch $why: ${bringRingScreen()}")
    handler.removeCallbacks(realert)
    handler.postDelayed(realert, REALERT_MS)
}

/** Erinnerung: Ist der Weckbildschirm nicht zu sehen, erscheint die Weck-Mitteilung neu (Vollbild, falls das Display inzwischen aus ist). */
internal fun WakeService.onRealert() {
    val n = night ?: return
    if (n.phase != Phase.RINGING) return
    if (!RingActivity.shown) {
        realerts++
        goForeground(ringingNotification(n), measuring = false, id = nextRingId())
        val probe = bringRingScreen()
        // Nur die erste Erinnerung je Runde ins Protokoll (sonst bis 30 Einträge je Runde).
        if (realerts == 1) app.diag.log(DiagKind.RING_SCREEN, "watch not visible, re-alert: $probe")
    }
    handler.postDelayed(realert, REALERT_MS)
}

/** Ende der Klingel-Runde (Schlummern, Beenden): keine Erinnerung und kein Start des Weckbildschirms mehr. */
internal fun WakeService.stopRingScreenAlert() {
    handler.removeCallbacks(realert)
    cancelRingScreenAlarm(this)
}

/** Hebt den Wecker-Alarm für den Weckbildschirm auf (gleiche Absicht wie in [scheduleRingScreenAlarm]). */
internal fun cancelRingScreenAlarm(context: Context) =
    context.getSystemService(AlarmManager::class.java).cancel(Notifications.ringScreenByAlarm(context))

private fun WakeService.nextRingId(): Int = RingAlert.nextId(notificationId, Notifications.ID_RING, Notifications.ID_RING_ALT)

/** Stösst Weg 2 und 3 an (Weg 1 ist die Mitteilung) und gibt den Zustand für die Diagnose zurück. */
private fun WakeService.bringRingScreen(): String {
    val probe = probe(this)
    scheduleRingScreenAlarm(this)
    // Ohne sichtbares Fenster verwirft das System den Start still; dann tragen Mitteilung und Alarm.
    // Aus dem Dienst nur mit neuer Aufgabe möglich (Lint «WearRecents» trifft hier nicht zu).
    @Suppress("WearRecents")
    startActivity(
        Intent(this, RingActivity::class.java)
            .putExtra(RingActivity.EXTRA_VIA, RingActivity.VIA_DIRECT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    return probe
}

/** Wecker-Alarm in [ALARM_DELAY_MS], der den Weckbildschirm selbst öffnet; ersetzt einen schon gestellten. */
private fun scheduleRingScreenAlarm(context: Context) {
    val launch = Notifications.ringScreenByAlarm(context)
    try {
        context.getSystemService(AlarmManager::class.java)
            .setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + ALARM_DELAY_MS, launch), launch)
    } catch (e: SecurityException) {
        Log.w(TAG, "Wecker-Alarm für den Weckbildschirm nicht erlaubt", e)
        context.wearApp.diag.log(DiagKind.RING_SCREEN, "watch alarm launch not allowed")
    }
}

/**
 * Erwarteter Weg ([RingAlert.route]) und was dazu führt, ohne persönliche Daten: Display an/aus
 * (Always-on zählt als aus), Sperre, Recht für Vollbild, Mitteilungen erlaubt, App sichtbar.
 */
private fun probe(context: Context): String {
    val interactive = context.getSystemService(PowerManager::class.java).isInteractive
    val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    val notifications = NotificationManagerCompat.from(context)
    val fullScreen = notifications.canUseFullScreenIntent()
    val visible = appVisible()
    // Die Uhr ist nie Begleit-App (die Zuordnung hält das Handy).
    val route = RingAlert.route(visible, companion = false, interactive = interactive, locked = locked, fullScreenAllowed = fullScreen)
    return "${route.name} screen=${if (interactive) "on" else "off"} locked=$locked fsi=$fullScreen " +
        "notifications=${notifications.areNotificationsEnabled()} app=$visible"
}

/** Ob ein Fenster der App sichtbar ist (nur dann erlaubt Android den direkten Start aus dem Dienst). */
private fun appVisible(): Boolean {
    val info = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(info)
    // Vordergrunddienst allein (IMPORTANCE_FOREGROUND_SERVICE) zählt nicht.
    return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
        info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
}
