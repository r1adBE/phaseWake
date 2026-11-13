package com.phasewake.app.shared

import android.content.Context
import android.util.Log
import com.phasewake.app.core.Codec
import com.phasewake.app.core.HolidayList
import com.phasewake.app.core.Holidays
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.RecorderSkip
import com.google.android.gms.common.api.ApiException
import android.net.Uri
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Schickt Wecker, Nächte und Weck-Ereignisse an die Gegenseite ([peer]: auf der Uhr das Handy, sonst die Uhr).
 *
 * Inhalte mit persönlichen Daten gehen über die Warteschlange [Outbox] als Nachricht direkt an das verbundene Gerät
 * (bestätigt, bis dahin wiederholt), nicht als Datenpaket, das Google in der Cloud sichert (Audit C2). Als Datenpaket
 * bleiben nur der Uhr-Status, «Verlauf löschen» und der Abbruch von Probewecker oder Powernap (siehe [DataLayer]); eine
 * Uhr-App vor der Warteschlange erhält nichts mehr, der Nacht-Check meldet sie als veraltet.
 *
 * Fehler (keine Uhr gekoppelt, Wear-Dienste fehlen) werden geloggt und nicht weitergereicht:
 * Beide Geräte funktionieren auch allein.
 */
class WearSync(context: Context, private val peer: String = Outbox.peer(context)) {
    private val app = context.applicationContext
    private val dataClient by lazy { Wearable.getDataClient(app) }
    private val messageClient by lazy { Wearable.getMessageClient(app) }
    private val capabilityClient by lazy { Wearable.getCapabilityClient(app) }
    private val outbox by lazy { Outbox.forContext(app) }
    private val received by lazy { ReceivedIds.forContext(app) }

    /** Wecker an die Gegenseite (Warteschlange). */
    suspend fun publishAlarms(snapshot: AlarmSnapshot) = guarded("Wecker senden") {
        outbox.enqueue(QueueMessages.KEY_ALARMS, DataLayer.PATH_MSG_ALARMS, peer, QueueMessages.encodeAlarms(snapshot))
    }

    /** Sendet die wartenden Einträge der Warteschlange erneut (Gegenseite erreichbar, App-Start). */
    suspend fun flush() = guarded("Warteschlange senden") { outbox.flush() }

    /** Bittet die Gegenseite, ihren aktuellen Stand einzureihen ([DataLayer.PATH_SYNC_REQUEST]). */
    suspend fun requestSync() = message(peer, DataLayer.PATH_SYNC_REQUEST)

    /**
     * Empfangene Nachricht der Warteschlange von [node]: [handle] verarbeitet den Text; gelingt das (kein Fehler),
     * wird die Kennung gemerkt ([ReceivedIds]) und der Empfang bestätigt ([DataLayer.PATH_ACK]). Derselbe Eintrag noch
     * einmal (Audit A-1): nur erneut bestätigen, nicht noch einmal verarbeiten. Ohne Kennung (unlesbar) nichts.
     * Eigene wartende Einträge sendet der Aufrufer danach ausserhalb des Empfangs ([flush] im App-Bereich, Audit A-8).
     * @return ob der Eintrag gültig war (verarbeitet oder schon verarbeitet); dann lohnt sich ein [flush].
     */
    suspend fun receive(node: String, data: ByteArray?, handle: suspend (String) -> Unit): Boolean {
        // Über der Grenze (Audit S-2): nicht entschlüsseln und nicht bestätigen.
        if (!PeerLimit.fits(data)) {
            Log.w(TAG, "Empfangener Eintrag zu gross (${data?.size} Bytes), verworfen")
            return false
        }
        val (id, body) = Outbox.open(data) ?: return false
        if (received.contains(id)) {
            Log.i(TAG, "Eintrag schon verarbeitet, nur bestätigen")
        } else {
            try {
                handle(body)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Empfangener Eintrag nicht verarbeitet", e)
                return false
            }
            received.add(id)
        }
        guarded("Empfang bestätigen") { messageClient.sendMessage(node, DataLayer.PATH_ACK, id.encodeToByteArray()).await() }
        return true
    }

    /**
     * Kanal der Gegenseite (grosser Eintrag der Warteschlange) ganz lesen und schliessen; `null`, wenn das scheitert
     * oder der Inhalt grösser als [PeerLimit.MAX_BYTES] ist (Audit S-2: nie ohne Grenze in den Speicher).
     */
    suspend fun readChannel(channel: ChannelClient.Channel): ByteArray? {
        val client = Wearable.getChannelClient(app)
        return try {
            client.getInputStream(channel).await().use { PeerLimit.read(it) }
                .also { if (it == null) Log.w(TAG, "Kanal grösser als ${PeerLimit.MAX_BYTES} Bytes, abgebrochen") }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.i(TAG, "Kanal nicht lesbar", e)
            null
        } finally {
            client.close(channel)
        }
    }

    /** Bestätigung der Gegenseite ([DataLayer.PATH_ACK]): Eintrag aus der Warteschlange entfernen. */
    suspend fun acked(data: ByteArray?) {
        val id = DataLayer.text(data).takeIf { it.isNotEmpty() } ?: return
        outbox.acked(id)
    }

    /**
     * Uhr: Einträge der Warteschlange von «Verlauf löschen» ([before], ms) verwerfen: Nächte und Aufzeichnungen, die bis
     * dahin enden (Audit D-02, nach ihrem Inhalt, nicht nach dem Zeitpunkt des Einreihens), dazu wie bisher alles, was
     * vorher eingereiht wurde.
     */
    suspend fun dropHistory(before: Long) {
        outbox.dropIf(QueueMessages.KEY_NIGHT) { QueueMessages.droppedByClear(it, before) }
        outbox.dropIf(QueueMessages.KEY_RECORDING) { QueueMessages.droppedByClear(it, before) }
    }

    /**
     * Einmalige Umstellung auf die Warteschlange: eigene Pakete der früheren Wege aus dem Data Layer löschen; Nächte,
     * Aufzeichnungen, frühere Zielzeit und Aufzeichnungs-Stand der Uhr vorher einreihen, damit nichts verloren geht.
     */
    suspend fun migrateTransport() = guarded("Umstellung auf Nachrichten") {
        val local = Wearable.getNodeClient(app).localNode.await().id
        if (outbox.migrateOnce({ QueueMessages.legacyItems(dataClient, local, peer) }, { dataClient.deleteDataItems(it).await() })) Log.i(TAG, "Umstellung auf Nachrichten erledigt")
    }

    /** Uhr: eine Nacht ans Handy (Warteschlange, Schlüssel mit Fensterbeginn und Kennung). */
    suspend fun publishNight(night: NightRecord) = guarded("Nacht senden") {
        outbox.enqueue(QueueMessages.nightKey(night), DataLayer.PATH_MSG_NIGHT, peer, Codec.encodeNight(night))
    }

    /**
     * Uhr: meldet, ob sie für die Nacht bereit ist (Sensoren, Mitteilungen und Weckbildschirm erlaubt), mit Pulsrechten
     * und dem Grund einer nicht gestarteten Messung vor dem Schlafen dieser Nacht ([recorderSkip], Audit F-06).
     * @return ob das Paket abgelegt wurde (der Data Layer stellt es dann zu).
     */
    suspend fun publishWatchReady(ready: Boolean, battery: Int? = null, pulse: WatchPulse? = null, recorderSkip: RecorderSkip.Reason? = null): Boolean {
        var stored = false
        guarded("Status senden") {
            dataClient.putDataItem(statusRequest(ready, battery, System.currentTimeMillis(), pulse, recorderSkip)).await()
            stored = true
        }
        return stored
    }

    /** Uhr: frühere Zielzeit aus dem Einschlaf-Modus ans Handy ([OnsetShift], Review K-07), über die Warteschlange. */
    suspend fun publishOnsetShift(shift: OnsetShift) = guarded("Einschlaf-Zielzeit senden") {
        outbox.enqueue(QueueMessages.KEY_ONSET_SHIFT, DataLayer.PATH_MSG_ONSET_SHIFT, peer, shift.encode())
    }

    /** Handy: zuletzt gemeldeter Status der Uhr; `null`, wenn sie noch nie etwas gemeldet hat. */
    suspend fun watchReady(): Boolean? = readStatus { readReady(it) }

    /** Handy: zuletzt gemeldeter Akkustand der Uhr in Prozent; `null`, wenn unbekannt oder älter als [maxAgeMs]. */
    suspend fun watchBattery(maxAgeMs: Long = 6 * 3_600_000L): Int? =
        readStatus { readBattery(it, System.currentTimeMillis(), maxAgeMs) }

    /**
     * Handy: Grund, warum die Messung vor dem Schlafen dieser Nacht nicht startete ([readRecorderSkip]); `null` ohne
     * Meldung oder wenn die Uhr seit über 16 Std. nichts gemeldet hat (die Nacht ist dann sicher vorbei).
     */
    suspend fun watchRecorderSkip(maxAgeMs: Long = 16 * 3_600_000L): RecorderSkip.Reason? =
        readStatus { readRecorderSkip(it, System.currentTimeMillis(), maxAgeMs) }

    /** Handy: zuletzt gemeldeter Zeitpunkt des Uhr-Status (ms), also «zuletzt gesehen»; `null`, wenn unbekannt. */
    suspend fun watchSeenAt(): Long? = readStatus { readSeenAt(it) }

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
    internal suspend fun <T> readStatus(read: (DataMap) -> T?): T? = try {
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

    /** Handy: Feiertage an die Uhren (Warteschlange). */
    suspend fun publishHolidays(list: HolidayList, updatedAt: Long) = guarded("Feiertage senden") {
        outbox.enqueue(QueueMessages.KEY_HOLIDAYS, DataLayer.PATH_MSG_HOLIDAYS, peer, "$updatedAt\n${Holidays.encode(list)}")
    }

    /** Handy: Schlafziel ([minutes]) mit Zeitpunkt der Änderung an die Uhren, wie die Feiertage. */
    suspend fun publishSleepGoal(minutes: Int, updatedAt: Long) = guarded("Schlafziel senden") {
        outbox.enqueue(QueueMessages.KEY_SLEEP_GOAL, DataLayer.PATH_MSG_SLEEP_GOAL, peer, "$minutes|$updatedAt")
    }

    /** Handy: Schalter der Einschlaf-Messung am Abend mit Zeitpunkt der Änderung an die Uhren, wie das Schlafziel. */
    suspend fun publishAutoOnset(enabled: Boolean, updatedAt: Long) = guarded("Einschlaf-Messung senden") {
        outbox.enqueue(QueueMessages.KEY_AUTO_ONSET, DataLayer.PATH_MSG_AUTO_ONSET, peer, "${if (enabled) 1 else 0}|$updatedAt")
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
    }

    /**
     * Sendet eine Nachricht an alle erreichbaren Geräte mit der Fähigkeit [capability]. Die Suche nach den Geräten hat eine
     * Frist ([CAPABILITY_TIMEOUT_MS], Befund 13.11.2026): Antworten die Wear-Dienste nicht, wird nicht gesendet und
     * protokolliert, statt dass der Aufrufer (z. B. «klingeln» ans Handy) unbegrenzt wartet.
     */
    suspend fun message(capability: String, path: String, payload: ByteArray = ByteArray(0)) = guarded("Nachricht $path") {
        val nodes = withTimeoutOrNull(CAPABILITY_TIMEOUT_MS) {
            capabilityClient.getCapability(capability, CapabilityClient.FILTER_REACHABLE).await().nodes
        }
        if (nodes == null) {
            Log.w(TAG, "Nachricht $path nicht gesendet: keine Geräte innert $CAPABILITY_TIMEOUT_MS ms")
            return@guarded
        }
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

    /** Formate der Datenpakete (Uhr-Status, «Verlauf löschen», Abbruch; frühere Pakete für die Umstellung), siehe [WearPackets]. */
    companion object : WearPackets() {
        private const val TAG = "WearSync"

        /** Frist für die Suche nach erreichbaren Geräten in [message]. */
        const val CAPABILITY_TIMEOUT_MS = 5_000L
    }
}
