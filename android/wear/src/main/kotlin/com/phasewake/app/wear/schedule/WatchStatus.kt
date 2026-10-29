package com.phasewake.app.wear.schedule

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.phasewake.app.shared.WearSync
import java.time.Clock
import java.time.Duration
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Uhr-Status fürs Handy (bereit für die Nacht, Akkustand), wie ihn der Nacht-Check am Handy zeigt.
 * Gesendet nur bei Bedarf, damit nicht jedes Neuplanen ein dringendes Datenpaket auslöst.
 */
internal class WatchStatus(
    private val context: Context,
    private val store: DataStore<Preferences>,
    private val sync: WearSync,
    private val clock: () -> Clock,
) {
    /** Hält das Senden ([publish]) auseinander. */
    private val lock = Mutex()

    /**
     * Sendet den Status, aber nur bei Bedarf: Bereitschaft geändert, Akkustand um mindestens
     * [BATTERY_STEP] Punkte geändert oder letzte Meldung älter als [MAX_AGE]. Jede Meldung ist ein
     * dringendes Datenpaket und lässt das Handy neu planen.
     */
    suspend fun publish(): Unit = lock.withLock {
        val ready = isReady()
        val battery = batteryPercent()
        val now = clock().instant().toEpochMilli()
        val prefs = store.data.first()
        val lastAt = prefs[SENT_AT]
        val lastBattery = prefs[SENT_BATTERY]
        val due = prefs[SENT_READY] != ready ||
            (battery != null && (lastBattery == null || abs(battery - lastBattery) >= BATTERY_STEP)) ||
            lastAt == null || now - lastAt >= MAX_AGE.toMillis() || now < lastAt
        if (!due) return@withLock
        // Mit Akkustand der Uhr: Der Nacht-Check am Handy warnt, wenn er für die Nacht knapp ist.
        if (!sync.publishWatchReady(ready, battery)) return@withLock
        store.edit {
            it[SENT_READY] = ready
            it[SENT_AT] = now
            if (battery != null) it[SENT_BATTERY] = battery else it.remove(SENT_BATTERY)
        }
    }

    /** Sind die Rechte erteilt, die Messen und Wecken brauchen? */
    private fun isReady(): Boolean {
        val sensors = SENSOR_PERMISSIONS.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        val notifications = NotificationManagerCompat.from(context)
        // Ohne Vollbild-Mitteilung erscheint der Weckbildschirm nicht von selbst. Das Hintergrundrecht
        // für den Puls ist nicht nötig: Ohne Puls entscheidet die Bewegung allein.
        return sensors && notifications.areNotificationsEnabled() && notifications.canUseFullScreenIntent()
    }

    /** Akkustand der Uhr in Prozent; `null`, wenn das System ihn nicht nennt. */
    private fun batteryPercent(): Int? =
        context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }

    companion object {
        /** Zuletzt gesendeter Uhr-Status, siehe [publish]. */
        private val SENT_READY = booleanPreferencesKey("status_sent_ready")
        private val SENT_BATTERY = intPreferencesKey("status_sent_battery")
        private val SENT_AT = longPreferencesKey("status_sent_at")
        private const val BATTERY_STEP = 5
        private val MAX_AGE: Duration = Duration.ofHours(1)

        /** Eine davon genügt für den Messdienst (Typ «health»). */
        private val SENSOR_PERMISSIONS: List<String> = buildList {
            if (Build.VERSION.SDK_INT >= 36) add("android.permission.health.READ_HEART_RATE")
            add(Manifest.permission.BODY_SENSORS)
            add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }
}
