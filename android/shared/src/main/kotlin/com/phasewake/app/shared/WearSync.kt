package com.phasewake.app.shared

import android.content.Context
import android.util.Log
import com.phasewake.app.core.Codec
import com.phasewake.app.core.HolidayList
import com.phasewake.app.core.Holidays
import com.phasewake.app.core.NightRecord
import com.google.android.gms.common.api.ApiException
import android.net.Uri
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
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
        dataClient.putDataItem(alarmsRequest(snapshot)).await()
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
        dataClient.putDataItem(nightRequest(night)).await()
    }

    /**
     * Uhr: meldet, ob sie für die Nacht bereit ist (Sensoren, Mitteilungen und Weckbildschirm erlaubt).
     * @return ob das Paket abgelegt wurde (der Data Layer stellt es dann zu).
     */
    suspend fun publishWatchReady(ready: Boolean, battery: Int? = null): Boolean {
        var stored = false
        guarded("Status senden") {
            dataClient.putDataItem(statusRequest(ready, battery, System.currentTimeMillis())).await()
            stored = true
        }
        return stored
    }

    /** Handy: zuletzt gemeldeter Status der Uhr; `null`, wenn sie noch nie etwas gemeldet hat. */
    suspend fun watchReady(): Boolean? = try {
        val items = dataClient.getDataItems(Uri.Builder().scheme("wear").path(DataLayer.PATH_WATCH_STATUS).build()).await()
        try {
            items.firstOrNull()?.let { readReady(DataMapItem.fromDataItem(it).dataMap) }
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
            items.firstOrNull()?.let { readBattery(DataMapItem.fromDataItem(it).dataMap, System.currentTimeMillis(), maxAgeMs) }
        } finally {
            items.release()
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null
    }

    /** Handy: zuletzt gemeldeter Zeitpunkt des Uhr-Status (ms), also «zuletzt gesehen»; `null`, wenn unbekannt. */
    suspend fun watchSeenAt(): Long? = try {
        val items = dataClient.getDataItems(Uri.Builder().scheme("wear").path(DataLayer.PATH_WATCH_STATUS).build()).await()
        try {
            items.firstOrNull()?.let { readSeenAt(DataMapItem.fromDataItem(it).dataMap) }
        } finally {
            items.release()
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null
    }

    /** Handy: Feiertage an die Uhren (als Paket, damit auch eine gerade getrennte Uhr sie bekommt). */
    suspend fun publishHolidays(list: HolidayList, updatedAt: Long) = guarded("Feiertage senden") {
        dataClient.putDataItem(holidaysRequest(list, updatedAt)).await()
    }

    /** Uhr: zuletzt abgelegte Feiertage mit Zeitpunkt; `null`, wenn nichts da ist oder die Wear-Dienste fehlen. */
    suspend fun fetchHolidays(): Pair<HolidayList, Long>? = try {
        val items = dataClient.getDataItems(Uri.Builder().scheme("wear").path(DataLayer.PATH_HOLIDAYS).build()).await()
        try {
            items.map { readHolidays(DataMapItem.fromDataItem(it).dataMap) }.maxByOrNull { it.second }
        } finally {
            items.release()
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null
    }

    /** Handy: Verlauf überall löschen – auch auf einer Uhr, die gerade nicht verbunden ist. */
    suspend fun clearHistoryEverywhere() = guarded("Verlauf löschen") {
        dataClient.putDataItem(clearRequest(System.currentTimeMillis())).await()
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

    /**
     * Handy: Nächte, die die Uhr schon abgelegt hat, mit ihrer Adresse zum Löschen nach dem Speichern.
     * Nötig, wenn die Handy-App erst nach der Uhr-App installiert wurde: Bestehende Pakete werden
     * sonst nicht gemeldet. Leer, wenn nichts da ist oder die Wear-Dienste fehlen.
     */
    suspend fun fetchNights(): List<Pair<NightRecord, Uri>> = try {
        val items = dataClient.getDataItems(
            Uri.Builder().scheme("wear").path(DataLayer.PATH_NIGHT_PREFIX).build(),
            DataClient.FILTER_PREFIX,
        ).await()
        try {
            items.mapNotNull { item -> readNight(DataMapItem.fromDataItem(item).dataMap)?.let { it to item.uri } }
        } finally {
            items.release()
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        emptyList()
    }

    /** Sendet eine Nachricht an alle erreichbaren Geräte mit der Fähigkeit [capability]. */
    suspend fun message(capability: String, path: String, payload: ByteArray = ByteArray(0)) = guarded("Nachricht $path") {
        val nodes = capabilityClient.getCapability(capability, CapabilityClient.FILTER_REACHABLE).await().nodes
        for (node in nodes) messageClient.sendMessage(node.id, path, payload).await()
    }

    /**
     * Gekoppelte Uhren, auf denen Phase Wake fehlt: verbunden, melden die Fähigkeit aber nicht.
     * Leer, wenn keine Uhr verbunden ist oder überall die App läuft.
     */
    suspend fun nodesWithout(capability: String): List<String> = try {
        val connected = Wearable.getNodeClient(app).connectedNodes.await().map { it.id }
        val withApp = capabilityClient.getCapability(capability, CapabilityClient.FILTER_ALL).await().nodes.map { it.id }.toSet()
        connected.filterNot { it in withApp }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        emptyList()
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

        /** Paket mit allen Weckern (dringend: die Uhr soll gleich neu planen). */
        fun alarmsRequest(snapshot: AlarmSnapshot): PutDataRequest = PutDataMapRequest.create(DataLayer.PATH_ALARMS).apply {
            dataMap.putString(DataLayer.KEY_TEXT, Codec.encodeAlarms(snapshot.alarms))
            dataMap.putLong(DataLayer.KEY_UPDATED_AT, snapshot.updatedAt)
        }.asPutDataRequest().setUrgent()

        /** Paket einer Nacht unter «Präfix + Fensterbeginn-Kennung» (siehe [publishNight]). */
        fun nightRequest(night: NightRecord): PutDataRequest =
            PutDataMapRequest.create(DataLayer.PATH_NIGHT_PREFIX + night.windowStart.toEpochMilli() + "-" + Uri.encode(night.alarmId)).apply {
                dataMap.putString(DataLayer.KEY_TEXT, Codec.encodeNight(night))
            }.asPutDataRequest()

        /** Status-Paket der Uhr; [battery] fehlt, wenn unbekannt. */
        fun statusRequest(ready: Boolean, battery: Int?, nowMs: Long): PutDataRequest = PutDataMapRequest.create(DataLayer.PATH_WATCH_STATUS).apply {
            dataMap.putBoolean(DataLayer.KEY_READY, ready)
            if (battery != null) dataMap.putInt(DataLayer.KEY_BATTERY, battery)
            dataMap.putLong(DataLayer.KEY_UPDATED_AT, nowMs)
        }.asPutDataRequest().setUrgent()

        /** Feiertags-Paket für die Uhren. */
        fun holidaysRequest(list: HolidayList, updatedAt: Long): PutDataRequest = PutDataMapRequest.create(DataLayer.PATH_HOLIDAYS).apply {
            dataMap.putString(DataLayer.KEY_TEXT, Holidays.encode(list))
            dataMap.putLong(DataLayer.KEY_UPDATED_AT, updatedAt)
        }.asPutDataRequest().setUrgent()

        /** Paket «Verlauf löschen» mit Zeitpunkt. */
        fun clearRequest(nowMs: Long): PutDataRequest = PutDataMapRequest.create(DataLayer.PATH_CLEAR_HISTORY).apply {
            dataMap.putLong(DataLayer.KEY_UPDATED_AT, nowMs)
        }.asPutDataRequest().setUrgent()

        /** Bereit-Meldung aus dem Status-Paket. */
        fun readReady(map: DataMap): Boolean = map.getBoolean(DataLayer.KEY_READY)

        /** Akkustand aus dem Status-Paket; `null` ohne Angabe oder älter als [maxAgeMs]. */
        fun readBattery(map: DataMap, nowMs: Long, maxAgeMs: Long): Int? =
            map.takeIf { it.containsKey(DataLayer.KEY_BATTERY) && nowMs - it.getLong(DataLayer.KEY_UPDATED_AT) <= maxAgeMs }
                ?.getInt(DataLayer.KEY_BATTERY)

        /** Zeitpunkt des Status-Pakets (ms); `null` ohne Angabe. */
        fun readSeenAt(map: DataMap): Long? = map.getLong(DataLayer.KEY_UPDATED_AT).takeIf { it > 0 }

        /** Liest ein Feiertags-Paket: Liste (tolerant, siehe [Holidays.decode]) und Zeitpunkt. */
        fun readHolidays(map: DataMap): Pair<HolidayList, Long> =
            Holidays.decode(map.getString(DataLayer.KEY_TEXT)) to map.getLong(DataLayer.KEY_UPDATED_AT)

        /** Liest ein empfangenes Nacht-Paket. @return `null`, wenn es unlesbar ist. */
        fun readNight(map: DataMap): NightRecord? = runCatching {
            Codec.decodeNight(map.getString(DataLayer.KEY_TEXT).orEmpty())
        }.onFailure { Log.w(TAG, "Nacht-Paket unlesbar", it) }.getOrNull()
    }
}
