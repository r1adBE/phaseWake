package com.phasewake.app.shared

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Prüft, ob «Nicht stören» Wecker ausdrücklich blockiert. Normalerweise lässt Android Wecker durch;
 * nur wenn der Nutzer das abgeschaltet hat (oder «Totale Stille» gilt), kann keine App wecken.
 */
object DoNotDisturb {

    fun blocksAlarms(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return when (manager.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_NONE -> true
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> runCatching {
                manager.notificationPolicy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS == 0
            }.getOrDefault(false)
            else -> false
        }
    }

    /** Öffnet die «Nicht stören»-Einstellungen, ersatzweise die allgemeinen Einstellungen. */
    fun openSettings(context: Context) {
        val zen = Intent(ZEN_MODE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(zen)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Öffentliche Aktion der Einstellungen-App, nicht als Konstante im SDK. */
    private const val ZEN_MODE_SETTINGS = "android.settings.ZEN_MODE_SETTINGS"
}
