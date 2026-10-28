package ch.r1ad.phasewake.shared

import android.content.Context
import android.util.Log
import ch.r1ad.phasewake.core.Codec
import ch.r1ad.phasewake.core.NightRecord
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.CapabilityClient
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

    suspend fun publishNight(night: NightRecord) = guarded("Nacht senden") {
        val request = PutDataMapRequest.create(DataLayer.PATH_NIGHT_PREFIX + night.windowStart.toEpochMilli()).apply {
            dataMap.putString(DataLayer.KEY_TEXT, Codec.encodeNight(night))
        }
        dataClient.putDataItem(request.asPutDataRequest()).await()
    }

    /** Sendet eine Nachricht an alle erreichbaren Geräte mit der Fähigkeit [capability]. */
    suspend fun message(capability: String, path: String, payload: ByteArray = ByteArray(0)) = guarded("Nachricht $path") {
        val nodes = capabilityClient.getCapability(capability, CapabilityClient.FILTER_REACHABLE).await().nodes
        for (node in nodes) messageClient.sendMessage(node.id, path, payload).await()
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
