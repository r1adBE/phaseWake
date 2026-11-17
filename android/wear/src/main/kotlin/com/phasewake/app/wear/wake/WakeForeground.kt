package com.phasewake.app.wear.wake

import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.phasewake.app.wear.wake.WakeService.Companion.TAG

// Vordergrund des Weckdienstes, ausgelagert aus WakeService.kt.

/**
 * Vordergrund mit dem passenden Typ: «health» zum Messen (braucht Sensorrechte), sonst
 * «systemExempted» (Wecker-Apps). Schlägt «health» fehl, läuft der Dienst ohne Messung weiter.
 * [id]: Kennung der Mitteilung; eine neue Kennung ersetzt die bisherige (das System entfernt die alte,
 * hier zur Sicherheit auch selbst). Nur so startet eine Weck-Mitteilung ihr Vollbild ([postRinging]).
 * @return `true`, wenn gemessen werden darf.
 */
// Die Diensttypen (API 34) setzt ServiceCompat auf älteren Versionen nicht; das Einbetten ist unbedenklich.
@android.annotation.SuppressLint("InlinedApi")
internal fun WakeService.goForeground(
    shown: android.app.Notification,
    measuring: Boolean,
    id: Int = Notifications.ID_SERVICE,
): Boolean {
    notification = shown
    val previous = notificationId
    notificationId = id
    val measured = measuring && foregroundForHealth(shown, id)
    if (!measured) {
        try {
            ServiceCompat.startForeground(this, id, shown, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Vordergrund nicht möglich", e)
        }
    }
    if (previous != id) NotificationManagerCompat.from(this).cancel(previous)
    return measured
}

@android.annotation.SuppressLint("InlinedApi")
private fun WakeService.foregroundForHealth(shown: android.app.Notification, id: Int): Boolean = try {
    ServiceCompat.startForeground(this, id, shown, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
    healthRefusal = null
    true
} catch (e: RuntimeException) {
    // SecurityException (Rechte fehlen) oder ForegroundServiceStartNotAllowedException. Grund und Rechte auch ins
    // Diagnose-Protokoll (schreiben die Aufrufer), nicht nur ins Systemprotokoll.
    Log.w(TAG, "Messen nicht erlaubt, nur Wecken zur Zielzeit", e)
    healthRefusal = SensorAccess.refusal(e, SensorAccess.of(this))
    false
}
