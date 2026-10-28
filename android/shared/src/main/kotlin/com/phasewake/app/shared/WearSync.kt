package com.phasewake.app.shared

import android.content.Context
import android.util.Log
import com.phasewake.app.core.Codec
import com.phasewake.app.core.NightRecord
import com.google.android.gms.common.api.ApiException
import android.net.Uri
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * Schickt Wecker, Nächte und Weck-Ereignisse an die Gegenseite.
 *
 * Fehler (keine Uhr gekoppelt, Wear-Dienste fehlen) werden geloggt und nicht weitergereicht:
 * Beide Geräte funktionieren auch allein.
 */
class WearSync(context: Context) {
    private val app = context.applicationContext
    private val dataClient by lazy { Wearable.getDataClient(app) }
    private val messageClient by lazy { Wearable.getMessageClient(app) }
    private val capabilityClient by lazy { Wearable.getCapabilityClient(app) }

    suspend fun publishAlarms(snapshot: AlarmSnapshot) = guarded("Wecker senden") {
        val request = PutDataMapRequest.create(DataLayer.PATH_ALARMS).apply {
            dataMap.putString(DataLayer.KEY_TEXT, Codec.encodeAlarms(snapshot.alarms))
            dataMap.putLong(DataLayer.KEY_UPDATED_AT, snapshot.updatedAt)
        }
        dataClient.putDataItem(request.asPutDataRequest().setUrgent()).await()
    }

    /**
     * Holt den neuesten Weckerstand, der im Data Layer liegt (beide Geräte). Nötig, weil Änderungen nur
     * bei einer Änderung gemeldet werden: Eine frisch installierte Uhr bekäme bestehende Wecker sonst nie.
     * `null`, wenn nichts da ist oder die Wear-Dienste fehlen.
     */
    suspend fun fetchAlarms(): AlarmSnapshot? = try {
        val items = dataClient.getDataItems(Uri.Builder().scheme("wear").path(DataLayer.PATH_ALARMS).build()).await()
        try {
            items.mapNotNull { readAlarms(DataMapItem.fromDataItem(it).dataMap) }.maxByOrNull { it.updatedAt }
        } finally {
            items.release()
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null
    }

    /**
     * Pfad mit Fensterbeginn und Kennung: Zwei Wecker mit gleichem Fensterbeginn überschreiben sich
     * nicht. Das Handy liest nach Präfix, auch ältere Pfade nur mit Fensterbeginn.
     */
    suspend fun publishNight(night: NightRecord) = guarded("Nacht senden") {
        val path = DataLayer.PATH_NIGHT_PREFIX + night.windowStart.toEpochMilli() + "-" + Uri.encode(night.alarmId)
        val request = PutDataMapRequest.create(path).apply {
            dataMap.putString(DataLayer.KEY_TEXT, Codec.encodeNight(night))
        }
        dataClient.putDataItem(request.asPutDataRequest()).await()
    }

    /**
     * Uhr: meldet, ob sie für die Nacht bereit ist (Sensoren, Mitteilungen und Weckbildschirm erlaubt).
     * @return ob das Paket abgelegt wurde (der Data Layer stellt es dann zu).
     */
    suspend fun publishWatchReady(ready: Boolean, battery: Int? = null): Boolean {
        var stored = false
        guarded("Status senden") {
            val request = PutDataMapRequest.create(DataLayer.PATH_WATCH_STATUS).apply {
                dataMap.putBoolean(DataLayer.KEY_READY, ready)
                if (battery != null) dataMap.putInt(DataLayer.KEY_BATTERY, battery)
                dataMap.putLong(DataLayer.KEY_UPDATED_AT, System.currentTimeMillis())
            }
            dataClient.putDataItem(request.asPutDataRequest().setUrgent()).await()
            stored = true
        }
        return stored
    }

    /** Handy: zuletzt gemeldeter Status der Uhr; `null`, wenn sie noch nie etwas gemeldet hat. */
    suspend fun watchReady(): Boolean? = try {
        val items = dataClient.getDataItems(Uri.Builder().scheme("wear").path(DataLayer.PATH_WATCH_STATUS).build()).await()
        try {
            items.firstOrNull()?.let { DataMapItem.fromDataItem(it).dataMap.getBoolean(DataLayer.KEY_READY) }
        } finally {
            items.release()
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null
    }

    /** Handy: zuletzt gemeldeter Akkustand der Uhr in Prozent; `null`, wenn unbekannt oder älter als [maxAgeMs]. */
    suspend fun watchBattery(maxAgeMs: Long = 6 * 3_600_000L): Int? = try {
        val items = dataClient.getDataItems(Uri.Builder().scheme("wear").path(DataLayer.PATH_WATCH_STATUS).build()).await()
        try {
            items.firstOrNull()?.let { DataMapItem.fromDataItem(it).dataMap }
                ?.takeIf { it.containsKey(DataLayer.KEY_BATTERY) && System.currentTimeMillis() - it.getLong(DataLayer.KEY_UPDATED_AT) <= maxAgeMs }
                ?.getInt(DataLayer.KEY_BATTERY)
        } finally {
            items.release()
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null
    }

    /** Handy: Verlauf überall löschen – auch auf einer Uhr, die gerade nicht verbunden ist. */
    suspend fun clearHistoryEverywhere() = guarded("Verlauf löschen") {
        val request = PutDataMapRequest.create(DataLayer.PATH_CLEAR_HISTORY).apply {
            dataMap.putLong(DataLayer.KEY_UPDATED_AT, System.currentTimeMillis())
        }
        dataClient.putDataItem(request.asPutDataRequest().setUrgent()).await()
        deleteNights()
    }

    /** Entfernt alle Nacht-Pakete aus dem Data Layer (Datensparsamkeit: lokal liegt der Verlauf ohnehin). */
    suspend fun deleteNights() = guarded("Nacht-Pakete entfernen") {
        dataClient.deleteDataItems(
            Uri.Builder().scheme("wear").path(DataLayer.PATH_NIGHT_PREFIX).build(),
            DataClient.FILTER_PREFIX,
        ).await()
    }

    /** Entfernt ein empfangenes Paket, sobald es lokal gespeichert ist. */
    suspend fun delete(uri: Uri) = guarded("Paket entfernen") { dataClient.deleteDataItems(uri).await() }

    /** Sendet eine Nachricht an alle erreichbaren Geräte mit der Fähigkeit [capability]. */
    suspend fun message(capability: String, path: String, payload: ByteArray = ByteArray(0)) = guarded("Nachricht $path") {
        val nodes = capabilityClient.getCapability(capability, CapabilityClient.FILTER_REACHABLE).await().nodes
        for (node in nodes) messageClient.sendMessage(node.id, path, payload).await()
    }

    /** Ob gerade ein Gerät mit [capability] erreichbar ist; `false` auch ohne Wear-Dienste. */
    suspend fun isReachable(capability: String): Boolean = try {
        capabilityClient.getCapability(capability, CapabilityClient.FILTER_REACHABLE).await().nodes.isNotEmpty()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        false
    }

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: ApiException) {
            Log.i(TAG, "$what nicht möglich (keine Verbindung oder keine Wear-Dienste): ${e.statusCode}")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "$what fehlgeschlagen", e)
        }
    }

    companion object {
        private const val TAG = "WearSync"

        /** Liest ein empfangenes Wecker-Paket. @return `null`, wenn es unlesbar ist. */
        fun readAlarms(map: DataMap): AlarmSnapshot? = runCatching {
            AlarmSnapshot(Codec.decodeAlarms(map.getString(DataLayer.KEY_TEXT).orEmpty()), map.getLong(DataLayer.KEY_UPDATED_AT))
        }.onFailure { Log.w(TAG, "Wecker-Paket unlesbar", it) }.getOrNull()

        /** Liest ein empfangenes Nacht-Paket. @return `null`, wenn es unlesbar ist. */
        fun readNight(map: DataMap): NightRecord? = runCatching {
            Codec.decodeNight(map.getString(DataLayer.KEY_TEXT).orEmpty())
        }.onFailure { Log.w(TAG, "Nacht-Paket unlesbar", it) }.getOrNull()
    }
}
