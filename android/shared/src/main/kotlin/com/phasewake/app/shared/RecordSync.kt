package com.phasewake.app.shared

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import java.time.Instant
import kotlinx.coroutines.tasks.await

/**
 * «Nacht aufzeichnen» über den Data Layer: Start und Stopp vom Handy (Nachrichten), Stand der Uhr und abgeschlossene
 * Aufzeichnungen (Datenpakete, kommen auch bei kurz getrennter Verbindung an). Fehler werden geloggt, nicht weitergereicht.
 */
class RecordSync(context: Context) {
    private val app = context.applicationContext
    private val dataClient by lazy { Wearable.getDataClient(app) }

    /** Uhr: Stand ans Handy; [since] = Beginn der laufenden Aufzeichnung, `null` = keine. */
    suspend fun publishStatus(since: Instant?) = guarded("Aufzeichnungs-Stand senden") {
        dataClient.putDataItem(statusRequest(since)).await()
    }

    /** Uhr: abgeschlossene Aufzeichnung ans Handy. */
    suspend fun publishRecording(recording: NightRecording) = guarded("Aufzeichnung senden") {
        dataClient.putDataItem(recordingRequest(recording)).await()
    }

    /**
     * Handy: Stand der Uhr ([readStatus]); `null`, wenn sie keinen meldet (kennt die Aufzeichnung nicht) oder die
     * Wear-Dienste fehlen.
     */
    suspend fun fetchStatus(): RecordStatus? = try {
        val items = dataClient.getDataItems(Uri.Builder().scheme("wear").path(DataLayer.PATH_RECORD_STATUS).build()).await()
        try {
            items.firstOrNull()?.let { readStatus(DataMapItem.fromDataItem(it).dataMap) }
        } finally {
            items.release()
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null
    }

    /** Handy: abgelegte Aufzeichnungen mit ihrer Adresse (zum Löschen nach dem Speichern). */
    suspend fun fetchRecordings(): List<Pair<NightRecording, Uri>> = try {
        val items = dataClient.getDataItems(
            Uri.Builder().scheme("wear").path(DataLayer.PATH_RECORDING_PREFIX).build(),
            DataClient.FILTER_PREFIX,
        ).await()
        try {
            items.mapNotNull { item -> readRecording(DataMapItem.fromDataItem(item).dataMap)?.let { it to item.uri } }
        } finally {
            items.release()
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        emptyList()
    }

    /** Handy: alle Aufzeichnungs-Pakete entfernen («Verlauf löschen»). */
    suspend fun deleteRecordings() = guarded("Aufzeichnungs-Pakete entfernen") {
        dataClient.deleteDataItems(
            Uri.Builder().scheme("wear").path(DataLayer.PATH_RECORDING_PREFIX).build(),
            DataClient.FILTER_PREFIX,
        ).await()
    }

    /**
     * Handy: Start ([start] `true`) oder Stopp an die erreichbaren Uhren.
     * @return ob mindestens eine Uhr erreichbar war (sonst Hinweis am Handy).
     */
    suspend fun command(start: Boolean): Boolean = try {
        val nodes = Wearable.getCapabilityClient(app)
            .getCapability(DataLayer.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE).await().nodes
        val messages = Wearable.getMessageClient(app)
        for (node in nodes) messages.sendMessage(node.id, commandPath(start), ByteArray(0)).await()
        nodes.isNotEmpty()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.i(TAG, "Aufzeichnungs-Befehl nicht möglich", e)
        false
    }

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.i(TAG, "$what nicht möglich", e)
        }
    }

    /** Stand der Uhr: [since] = Beginn der laufenden Aufzeichnung, `null` = keine läuft. */
    data class RecordStatus(val since: Instant?)

    /** Befehl vom Handy an die Uhr. */
    enum class Command { START, STOP }

    companion object {
        private const val TAG = "RecordSync"

        /** Nachrichtenpfad zum Befehl. */
        fun commandPath(start: Boolean): String = if (start) DataLayer.PATH_RECORD_START else DataLayer.PATH_RECORD_STOP

        /** Befehl zu einem empfangenen Nachrichtenpfad; `null` für andere Pfade. */
        fun commandOf(path: String?): Command? = when (path) {
            DataLayer.PATH_RECORD_START -> Command.START
            DataLayer.PATH_RECORD_STOP -> Command.STOP
            else -> null
        }

        /** Stand-Paket (dringend: das Handy zeigt «läuft seit …» gleich an); ohne Zeitstempel, gleicher Stand ändert nichts. */
        fun statusRequest(since: Instant?): PutDataRequest = PutDataMapRequest.create(DataLayer.PATH_RECORD_STATUS).apply {
            dataMap.putLong(DataLayer.KEY_RECORD_SINCE, since?.toEpochMilli() ?: 0L)
        }.asPutDataRequest().setUrgent()

        /** Liest das Stand-Paket. */
        fun readStatus(map: DataMap): RecordStatus =
            RecordStatus(map.getLong(DataLayer.KEY_RECORD_SINCE).takeIf { it > 0 }?.let(Instant::ofEpochMilli))

        /** Paket einer abgeschlossenen Aufzeichnung unter «Präfix + Beginn». */
        fun recordingRequest(recording: NightRecording): PutDataRequest =
            PutDataMapRequest.create(DataLayer.PATH_RECORDING_PREFIX + recording.start.toEpochMilli()).apply {
                dataMap.putString(DataLayer.KEY_TEXT, NightRecording.encode(recording))
            }.asPutDataRequest().setUrgent()

        /** Liest ein Aufzeichnungs-Paket; `null`, wenn unlesbar oder nicht abgeschlossen. */
        fun readRecording(map: DataMap): NightRecording? =
            map.getString(DataLayer.KEY_TEXT)?.let(NightRecording::decode)?.takeIf { it.finished }
    }
}
