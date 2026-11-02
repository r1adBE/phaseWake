package com.phasewake.app.shared

import android.content.Context
import android.util.Log
import com.phasewake.app.core.Codec
import com.phasewake.app.core.HolidayList
import com.phasewake.app.core.Holidays
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.SleepGoal
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

    /** Uhr: frühere Zielzeit aus dem Einschlaf-Modus ans Handy, als Paket ([OnsetShift], Review K-07). */
    suspend fun publishOnsetShift(shift: OnsetShift) = guarded("Einschlaf-Zielzeit senden") {
        dataClient.putDataItem(OnsetShift.request(shift)).await()
    }

    /** Handy: neueste abgelegte frühere Zielzeit der Uhr (Review N-1, auch nach einem Prozessende); `null`, wenn keine da ist. */
    suspend fun fetchOnsetShift(): OnsetShift? = try {
        val items = dataClient.getDataItems(Uri.Builder().scheme("wear").path(DataLayer.PATH_ONSET_SHIFT).build()).await()
        try {
            items.mapNotNull { OnsetShift.read(DataMapItem.fromDataItem(it).dataMap) }.maxByOrNull { it.at }
        } finally {
            items.release()
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null
    }

    /** Handy: entfernt die frühere Zielzeit nach ihrer Nacht aus dem Data Layer (Datensparsamkeit). */
    suspend fun deleteOnsetShift() = delete(Uri.Builder().scheme("wear").path(DataLayer.PATH_ONSET_SHIFT).build())

    /** Handy: zuletzt gemeldeter Status der Uhr; `null`, wenn sie noch nie etwas gemeldet hat. */
    suspend fun watchReady(): Boolean? = readStatus { readReady(it) }

    /** Handy: zuletzt gemeldeter Akkustand der Uhr in Prozent; `null`, wenn unbekannt oder älter als [maxAgeMs]. */
    suspend fun watchBattery(maxAgeMs: Long = 6 * 3_600_000L): Int? =
        readStatus { readBattery(it, System.currentTimeMillis(), maxAgeMs) }

    /** Handy: zuletzt gemeldeter Zeitpunkt des Uhr-Status (ms), also «zuletzt gesehen»; `null`, wenn unbekannt. */
    suspend fun watchSeenAt(): Long? = readStatus { readSeenAt(it) }

    /**
     * Handy: Felder je Weckerzeile, die die Uhr kennt ([readAlarmFields]); `null`, wenn sie noch nie einen Status
     * gemeldet hat oder die Wear-Dienste fehlen.
     */
    suspend fun watchAlarmFields(): Int? = readStatus { readAlarmFields(it) }

    /**
     * Handy: Verbindungs-Stand der Uhr-App ([readProtocol]); `null`, wenn sie noch nie einen Status gemeldet hat oder die
     * Wear-Dienste fehlen.
     */
    suspend fun watchProtocol(): Int? = readStatus { readProtocol(it) }

    /** Kennungen der Geräte, die ein Status-Paket ([DataLayer.PATH_WATCH_STATUS]) geschrieben haben. */
    private suspend fun statusNodes(): Set<String> {
        val items = dataClient.getDataItems(Uri.Builder().scheme("wear").path(DataLayer.PATH_WATCH_STATUS).build()).await()
        return try {
            items.mapNotNull { it.uri.host }.toSet()
        } finally {
            items.release()
        }
    }

    /** Liest das Status-Paket der Uhr mit [read]; `null`, wenn keines da ist oder die Wear-Dienste fehlen. */
    private suspend fun <T> readStatus(read: (DataMap) -> T?): T? = try {
        val items = dataClient.getDataItems(Uri.Builder().scheme("wear").path(DataLayer.PATH_WATCH_STATUS).build()).await()
        try {
            items.firstOrNull()?.let { read(DataMapItem.fromDataItem(it).dataMap) }
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

    /** Handy: Schlafziel ([minutes]) mit Zeitpunkt der Änderung an die Uhren, als Paket wie die Feiertage. */
    suspend fun publishSleepGoal(minutes: Int, updatedAt: Long) = guarded("Schlafziel senden") {
        dataClient.putDataItem(sleepGoalRequest(minutes, updatedAt)).await()
    }

    /** Uhr: zuletzt abgelegtes Schlafziel mit Zeitpunkt; `null`, wenn nichts da ist oder die Wear-Dienste fehlen. */
    suspend fun fetchSleepGoal(): Pair<Int, Long>? = try {
        val items = dataClient.getDataItems(Uri.Builder().scheme("wear").path(DataLayer.PATH_SLEEP_GOAL).build()).await()
        try {
            items.map { readSleepGoal(DataMapItem.fromDataItem(it).dataMap) }.maxByOrNull { it.second }
        } finally {
            items.release()
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null
    }

    /** Handy: Schalter der Einschlaf-Messung am Abend mit Zeitpunkt der Änderung an die Uhren, als Paket wie das Schlafziel. */
    suspend fun publishAutoOnset(enabled: Boolean, updatedAt: Long) = guarded("Einschlaf-Messung senden") {
        dataClient.putDataItem(autoOnsetRequest(enabled, updatedAt)).await()
    }

    /** Uhr: zuletzt abgelegter Schalter der Einschlaf-Messung mit Zeitpunkt; `null`, wenn nichts da ist oder die Wear-Dienste fehlen. */
    suspend fun fetchAutoOnset(): Pair<Boolean, Long>? = try {
        val items = dataClient.getDataItems(Uri.Builder().scheme("wear").path(DataLayer.PATH_AUTO_ONSET).build()).await()
        try {
            items.map { readAutoOnset(DataMapItem.fromDataItem(it).dataMap) }.maxByOrNull { it.second }
        } finally {
            items.release()
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null
    }

    /**
     * Probewecker oder Powernap [alarmId] abgebrochen: als Nachricht (sofort) und als Datenpaket (auch für ein gerade
     * nicht erreichbares Gerät, das sonst zur alten Zielzeit klingelte). [capability]: die Gegenseite.
     */
    suspend fun publishTransientCancel(capability: String, alarmId: String) {
        message(capability, DataLayer.PATH_TRANSIENT_CANCEL, alarmId.encodeToByteArray())
        guarded("Abbruch ablegen") { dataClient.putDataItem(transientCancelRequest(alarmId, System.currentTimeMillis())).await() }
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
        // Zweiter Beleg: Eine Uhr, die schon einen Status gemeldet hat, hat die App sicher – auch wenn ihre
        // Fähigkeit nach einer Neuinstallation (z. B. per adb) noch nicht bekannt gegeben ist.
        val reporting = statusNodes()
        connected.filterNot { it in withApp || it in reporting }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        emptyList()
    }

    /**
     * Angezeigte Namen der verbundenen Geräte (z. B. «Galaxy Watch8 Classic (1A2B)»), um die gekoppelte Uhr für die
     * Begleit-App-Zuordnung zu finden. Leer ohne verbundene Uhr oder ohne Wear-Dienste.
     */
    suspend fun connectedNames(): List<String> = try {
        Wearable.getNodeClient(app).connectedNodes.await().map { it.displayName }.filter { it.isNotBlank() }
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

        /**
         * Status-Paket der Uhr mit den Feldern je Weckerzeile und dem Verbindungs-Stand ([DataLayer.PROTOCOL]) dieser
         * Version; [battery] fehlt, wenn unbekannt.
         */
        fun statusRequest(ready: Boolean, battery: Int?, nowMs: Long): PutDataRequest = PutDataMapRequest.create(DataLayer.PATH_WATCH_STATUS).apply {
            dataMap.putBoolean(DataLayer.KEY_READY, ready)
            if (battery != null) dataMap.putInt(DataLayer.KEY_BATTERY, battery)
            dataMap.putInt(DataLayer.KEY_PROTOCOL, DataLayer.PROTOCOL)
            dataMap.putInt(DataLayer.KEY_ALARM_FIELDS, Codec.ALARM_FIELDS)
            dataMap.putLong(DataLayer.KEY_UPDATED_AT, nowMs)
        }.asPutDataRequest().setUrgent()

        /** Paket «Probewecker oder Powernap abgebrochen», siehe [DataLayer.PATH_TRANSIENT_CANCELLED]. */
        fun transientCancelRequest(alarmId: String, nowMs: Long): PutDataRequest =
            PutDataMapRequest.create(DataLayer.PATH_TRANSIENT_CANCELLED).apply {
                dataMap.putString(DataLayer.KEY_TEXT, alarmId)
                dataMap.putLong(DataLayer.KEY_UPDATED_AT, nowMs)
            }.asPutDataRequest().setUrgent()

        /** Kennung aus dem Abbruch-Paket; `null`, wenn sie fehlt oder leer ist. */
        fun readTransientCancel(map: DataMap): String? = map.getString(DataLayer.KEY_TEXT)?.takeIf { it.isNotEmpty() }

        /** Feiertags-Paket für die Uhren. */
        fun holidaysRequest(list: HolidayList, updatedAt: Long): PutDataRequest = PutDataMapRequest.create(DataLayer.PATH_HOLIDAYS).apply {
            dataMap.putString(DataLayer.KEY_TEXT, Holidays.encode(list))
            dataMap.putLong(DataLayer.KEY_UPDATED_AT, updatedAt)
        }.asPutDataRequest().setUrgent()

        /** Schlafziel-Paket für die Uhren (dringend wie die Feiertage: ändert sich selten, soll aber gleich ankommen). */
        fun sleepGoalRequest(minutes: Int, updatedAt: Long): PutDataRequest = PutDataMapRequest.create(DataLayer.PATH_SLEEP_GOAL).apply {
            dataMap.putInt(DataLayer.KEY_SLEEP_GOAL, minutes)
            dataMap.putLong(DataLayer.KEY_UPDATED_AT, updatedAt)
        }.asPutDataRequest().setUrgent()

        /** Liest ein Schlafziel-Paket: Minuten (mit [SleepGoal.clamp]; ohne Angabe der Standard) und Zeitpunkt. */
        fun readSleepGoal(map: DataMap): Pair<Int, Long> =
            SleepGoal.clamp(map.getInt(DataLayer.KEY_SLEEP_GOAL, SleepGoal.DEFAULT_MINUTES)) to map.getLong(DataLayer.KEY_UPDATED_AT)

        /** Paket des Schalters der Einschlaf-Messung für die Uhren (dringend wie das Schlafziel). */
        fun autoOnsetRequest(enabled: Boolean, updatedAt: Long): PutDataRequest = PutDataMapRequest.create(DataLayer.PATH_AUTO_ONSET).apply {
            dataMap.putBoolean(DataLayer.KEY_AUTO_ONSET, enabled)
            dataMap.putLong(DataLayer.KEY_UPDATED_AT, updatedAt)
        }.asPutDataRequest().setUrgent()

        /** Liest ein Paket des Schalters: an oder aus (ohne Angabe «an») und Zeitpunkt. */
        fun readAutoOnset(map: DataMap): Pair<Boolean, Long> =
            map.getBoolean(DataLayer.KEY_AUTO_ONSET, true) to map.getLong(DataLayer.KEY_UPDATED_AT)

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

        /** Felder je Weckerzeile aus dem Status-Paket; ohne Angabe (ältere Uhr) 0. */
        fun readAlarmFields(map: DataMap): Int = if (map.containsKey(DataLayer.KEY_ALARM_FIELDS)) map.getInt(DataLayer.KEY_ALARM_FIELDS) else 0

        /** Verbindungs-Stand der Uhr aus dem Status-Paket; ohne Angabe (Uhr vor dieser Angabe) 0. */
        fun readProtocol(map: DataMap): Int = if (map.containsKey(DataLayer.KEY_PROTOCOL)) map.getInt(DataLayer.KEY_PROTOCOL) else 0

        /**
         * Ob die Uhr-App älter ist als die Handy-App (Review O-04): ihr Verbindungs-Stand [watchProtocol] ist kleiner als
         * [own]. `null` (keine Uhr bekannt) warnt nicht; ein Status ohne Angabe stammt von einer älteren Uhr.
         */
        fun watchOutdated(watchProtocol: Int?, own: Int = DataLayer.PROTOCOL): Boolean = watchProtocol != null && watchProtocol < own

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
