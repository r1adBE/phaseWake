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
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phasewake.app.core.RecorderSkip
import com.phasewake.app.shared.DataLayer
import com.phasewake.app.shared.WearSync
import com.phasewake.app.wear.wake.SensorAccess
import java.time.Clock
import java.time.Duration
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Uhr-Status fürs Handy (bereit für die Nacht, Akkustand, Pulsrechte, nicht gestartete Messung vor dem Schlafen), wie
 * ihn der Nacht-Check am Handy zeigt. Gesendet nur bei Bedarf, damit nicht jedes Neuplanen ein dringendes Datenpaket auslöst.
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
     * Sendet den Status, aber nur bei Bedarf: Bereitschaft, Pulsrechte oder Meldung [noteSkip] geändert, Akkustand um
     * mindestens [BATTERY_STEP] Punkte geändert, letzte Meldung älter als [MAX_AGE] oder noch ohne den Verbindungs-Stand
     * dieser Version (nach einem Update gleich melden, sonst bliebe am Handy bis zu einer Stunde «veraltet»).
     * Jede Meldung ist ein dringendes Datenpaket und lässt das Handy neu planen.
     */
    suspend fun publish(): Unit = lock.withLock {
        val ready = isReady()
        val battery = batteryPercent()
        // Lädt die Uhr, misst sie trotz wenig Akku (F-6); das Handy warnt dann nicht.
        val charging = context.getSystemService(BatteryManager::class.java)?.isCharging == true
        val pulse = SensorAccess.of(context).pulse()
        val instant = clock().instant()
        val now = instant.toEpochMilli()
        val prefs = store.data.first()
        // Nur, solange ihre Nacht nicht vorbei ist; ans Handy nur der Grund (ohne Uhrzeit).
        val skip = RecorderSkip.decode(prefs[SKIP])?.takeIf { it.appliesAt(instant) }?.reason
        val skipCode = skip?.code.orEmpty()
        val lastAt = prefs[SENT_AT]
        val lastBattery = prefs[SENT_BATTERY]
        val due = prefs[SENT_READY] != ready || prefs[SENT_PULSE] != pulse.heartRate || prefs[SENT_PULSE_BACKGROUND] != pulse.background ||
            prefs[SENT_PROTOCOL] != DataLayer.PROTOCOL || prefs[SENT_SKIP] != skipCode || (prefs[SENT_CHARGING] ?: false) != charging ||
            (battery != null && (lastBattery == null || abs(battery - lastBattery) >= BATTERY_STEP)) ||
            lastAt == null || now - lastAt >= MAX_AGE.toMillis() || now < lastAt
        if (!due) return@withLock
        // Mit Akkustand der Uhr: Der Nacht-Check am Handy warnt, wenn er für die Nacht knapp ist. Das Paket trägt auch den
        // Verbindungs-Stand, damit das Handy eine ältere Uhr-App erkennt (Review O-04), und die Pulsrechte: Ohne Puls im
        // Hintergrund misst die Uhr nachts nicht, der Nacht-Check warnt dann.
        if (!sync.publishWatchReady(ready, battery, pulse, skip, charging)) return@withLock
        store.edit {
            it[SENT_READY] = ready
            it[SENT_CHARGING] = charging
            it[SENT_PULSE] = pulse.heartRate
            it[SENT_PULSE_BACKGROUND] = pulse.background
            it[SENT_AT] = now
            it[SENT_PROTOCOL] = DataLayer.PROTOCOL
            it[SENT_SKIP] = skipCode
            if (battery != null) it[SENT_BATTERY] = battery else it.remove(SENT_BATTERY)
        }
    }

    /**
     * Messung vor dem Schlafen für die Nacht von [skip] nicht gestartet (Audit F-06): merken und gleich melden; der
     * Nacht-Check am Handy zeigt einen Hinweis, bis die Uhr nach ihrer Zielzeit wieder meldet (beim Neuplanen nach dem
     * Wecken).
     */
    suspend fun noteSkip(skip: RecorderSkip) {
        store.edit { it[SKIP] = skip.encode() }
        publish()
    }

    /** Die Messung vor dem Schlafen der Nacht mit Zielzeit [target] läuft doch: eine Meldung dafür aufheben. */
    suspend fun clearSkip(target: java.time.Instant) {
        if (RecorderSkip.decode(store.data.first()[SKIP])?.target != target) return
        store.edit { it.remove(SKIP) }
        publish()
    }

    /** Gemerkte Meldung [noteSkip]; intern für Tests. */
    internal suspend fun skip(): RecorderSkip? = RecorderSkip.decode(store.data.first()[SKIP])

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
        private val SENT_CHARGING = booleanPreferencesKey("status_sent_charging")
        private val SENT_PULSE = booleanPreferencesKey("status_sent_pulse")
        private val SENT_PULSE_BACKGROUND = booleanPreferencesKey("status_sent_pulse_background")
        private val SENT_AT = longPreferencesKey("status_sent_at")
        private val SENT_PROTOCOL = intPreferencesKey("status_sent_protocol")
        private val SENT_SKIP = stringPreferencesKey("status_sent_recorder_skip")

        /** Gemerkte Meldung [noteSkip] (`RecorderSkip.encode`). */
        private val SKIP = stringPreferencesKey("status_recorder_skip")
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
