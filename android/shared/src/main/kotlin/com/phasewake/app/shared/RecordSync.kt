package com.phasewake.app.shared

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.Wearable
import java.time.Instant
import kotlinx.coroutines.tasks.await

/**
 * «Nacht aufzeichnen» zwischen Uhr und Handy: Start und Stopp vom Handy (Nachrichten), Stand der Uhr und abgeschlossene
 * Aufzeichnungen über die Warteschlange [Outbox] (bestätigt, bis dahin wiederholt; grosse Aufzeichnungen über einen
 * Kanal), nicht als Datenpaket (Audit C2). Fehler werden geloggt, nicht weitergereicht.
 */
class RecordSync(context: Context) {
    private val app = context.applicationContext
    private val outbox by lazy { Outbox.forContext(app) }

    /** Uhr: Stand ans Handy; [since] = Beginn der laufenden Aufzeichnung, `null` = keine. */
    suspend fun publishStatus(since: Instant?) = guarded("Aufzeichnungs-Stand senden") {
        outbox.enqueue(QueueMessages.KEY_RECORD_STATUS, DataLayer.PATH_MSG_RECORD_STATUS, DataLayer.CAPABILITY_PHONE, (since?.toEpochMilli() ?: 0L).toString())
    }

    /** Uhr: abgeschlossene Aufzeichnung ans Handy (eigener Schlüssel je Beginn). */
    suspend fun publishRecording(recording: NightRecording) = guarded("Aufzeichnung senden") {
        outbox.enqueue(QueueMessages.KEY_RECORDING + recording.start.toEpochMilli(), DataLayer.PATH_MSG_RECORDING, DataLayer.CAPABILITY_PHONE, NightRecording.encode(recording))
    }

    /**
     * Handy: Start ([start] `true`) oder Stopp an die erreichbaren Uhren. [continuous]: Diagnose am Handy freigeschaltet,
     * der Start misst dann durchgehend statt in Stichproben (Audit F-01; ältere Uhren lesen den Inhalt nicht).
     * @return ob mindestens eine Uhr erreichbar war (sonst Hinweis am Handy).
     */
    suspend fun command(start: Boolean, continuous: Boolean = false): Boolean = try {
        val nodes = Wearable.getCapabilityClient(app)
            .getCapability(DataLayer.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE).await().nodes
        val messages = Wearable.getMessageClient(app)
        for (node in nodes) messages.sendMessage(node.id, commandPath(start), commandBody(start, continuous)).await()
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

        /** Inhalt des Start-Befehls, wenn durchgehend gemessen werden soll. */
        private const val CONTINUOUS = "continuous"

        /** Inhalt des Befehls: leer, beim Start mit [continuous] «continuous». */
        fun commandBody(start: Boolean, continuous: Boolean): ByteArray =
            if (start && continuous) CONTINUOUS.encodeToByteArray() else ByteArray(0)

        /** Uhr: ob der Start-Befehl [body] durchgehend messen lässt; leer (auch ältere Handys) heisst Stichproben. */
        fun continuousOf(body: ByteArray?): Boolean = body?.decodeToString()?.trim() == CONTINUOUS

        /** Befehl zu einem empfangenen Nachrichtenpfad; `null` für andere Pfade. */
        fun commandOf(path: String?): Command? = when (path) {
            DataLayer.PATH_RECORD_START -> Command.START
            DataLayer.PATH_RECORD_STOP -> Command.STOP
            else -> null
        }

        /** Liest den Inhalt von [DataLayer.PATH_MSG_RECORD_STATUS] (Beginn in ms, 0 = keine); `null`, wenn unlesbar. */
        fun decodeStatus(body: String): RecordStatus? =
            body.trim().toLongOrNull()?.let { ms -> RecordStatus(ms.takeIf { it > 0 }?.let(Instant::ofEpochMilli)) }

        /** Liest ein früheres Aufzeichnungs-Paket (nur für die Umstellung); `null`, wenn unlesbar oder nicht abgeschlossen. */
        fun readRecording(map: DataMap): NightRecording? =
            map.getString(DataLayer.KEY_TEXT)?.let(NightRecording::decode)?.takeIf { it.finished }
    }
}
