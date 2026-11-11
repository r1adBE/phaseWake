package com.phasewake.app.shared

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Zuverlässige Zustellung an die Gegenseite ohne Ablage im Data Layer (Audit C2, Entscheid r1AD 10.11.2026):
 * Datenpakete des Data Layers sichert Google zusätzlich in der Cloud. Nächte, Aufzeichnungen, Wecker (ihre Namen sind
 * eigener Text), Lernwerte, frühere Zielzeit, Schlafziel, Feiertage, Schalter der Einschlaf-Messung und der Stand von
 * «Nacht aufzeichnen» gehen darum als Nachricht ([Transport]: MessageClient, grosse Inhalte über ChannelClient) direkt an
 * das verbundene Gerät.
 *
 * Nachrichten werden nicht nachgeliefert. Damit nichts verloren geht, wenn die Gegenseite gerade weg ist, liegt jeder
 * Eintrag lokal im Ordner [dir], bis sie den Empfang bestätigt ([DataLayer.PATH_ACK], [acked]); bis dahin sendet [flush]
 * ihn bei jedem Anlass erneut (beim Einreihen, beim Start der App, wenn die Gegenseite erreichbar wird, bei jeder
 * Nachricht von ihr). Ein Eintrag mit gleichem Schlüssel ([Entry.key], z. B. «alarms») ersetzt den älteren: Für Zustände
 * zählt nur der neueste. Nächte und Aufzeichnungen haben je einen eigenen Schlüssel. Die Bestätigung nennt die Kennung
 * des gesendeten Standes ([Entry.id]): Ein inzwischen ersetzter Eintrag bleibt. Der Empfänger verarbeitet idempotent
 * (dieselbe Nacht zweimal ändert nichts) und bestätigt erst danach ([WearSync.receive]).
 *
 * Eine Instanz je Prozess und Ordner ([forContext]), damit alle Zugriffe über dieselbe Sperre laufen.
 */
class Outbox(private val dir: File, private val transport: Transport, private val now: () -> Long = System::currentTimeMillis) {

    /** Ein wartender Eintrag: an Geräte mit [capability] unter [path], Inhalt [body] (Text). */
    data class Entry(val id: String, val key: String, val path: String, val capability: String, val body: String, val createdAt: Long)

    /** Ein Paket des früheren Weges im Data Layer ([migrateOnce]): [entry] wird vor dem Löschen eingereiht, falls da. */
    data class Legacy(val uri: Uri, val entry: Entry? = null)

    /** Weg zur Gegenseite; im Test ersetzt. */
    interface Transport {
        /** Kennungen der gerade erreichbaren Geräte mit [capability]. */
        suspend fun nodes(capability: String): List<String>

        /** Sendet [data] an [node] unter [path]; wirft bei einem Fehler. */
        suspend fun send(node: String, path: String, data: ByteArray)
    }

    private val lock = Mutex()

    /** Reiht [body] unter [key] ein (ersetzt einen älteren Eintrag mit gleichem Schlüssel) und sendet gleich. */
    suspend fun enqueue(key: String, path: String, capability: String, body: String) {
        lock.withLock { write(Entry(UUID.randomUUID().toString(), key, path, capability, body, now())) }
        flush()
    }

    /** Wartende Einträge, älteste zuerst. */
    suspend fun pending(): List<Entry> = lock.withLock { readAll() }

    /**
     * Sendet alle wartenden Einträge an die erreichbaren Geräte ihrer Fähigkeit. Ohne Gerät oder bei einem Fehler bleibt
     * der Eintrag für den nächsten Anlass.
     * @return Zahl der weiterhin wartenden Einträge.
     */
    suspend fun flush(): Int = lock.withLock {
        val entries = readAll()
        val nodes = mutableMapOf<String, List<String>>()
        for (entry in entries) {
            val targets = nodes[entry.capability] ?: (guarded { transport.nodes(entry.capability) } ?: emptyList()).also { nodes[entry.capability] = it }
            for (node in targets) guarded { transport.send(node, entry.path, wrap(entry.id, entry.body)) }
        }
        entries.size
    }

    /** Die Gegenseite hat [id] empfangen: Eintrag entfernen (nur genau diesen Stand). */
    suspend fun acked(id: String): Boolean = lock.withLock {
        val file = files().firstOrNull { read(it)?.id == id } ?: return@withLock false
        withContext(Dispatchers.IO) { file.delete() }
    }

    /** Entfernt wartende Einträge, deren Schlüssel mit [prefix] beginnt und die vor [before] (ms) eingereiht wurden. */
    suspend fun drop(prefix: String, before: Long = Long.MAX_VALUE) = lock.withLock {
        files().filter { read(it)?.let { e -> e.key.startsWith(prefix) && e.createdAt < before } == true }.forEach { it.delete() }
    }

    /**
     * Einmalige Umstellung vom früheren Weg (Pakete im Data Layer): Pakete von [legacy] mit Inhalt werden eingereiht,
     * dann alle mit [delete] entfernt; danach eine Marke im Ordner. Scheitert ein Schritt, beim nächsten Start nochmals.
     * @return ob die Umstellung jetzt (vollständig) lief.
     */
    suspend fun migrateOnce(legacy: suspend () -> List<Legacy>, delete: suspend (Uri) -> Unit): Boolean {
        val marker = File(dir, MIGRATED)
        if (marker.exists()) return false
        val items = try {
            legacy()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.i(TAG, "Alte Pakete nicht lesbar", e)
            return false
        }
        lock.withLock { items.mapNotNull { it.entry }.forEach { write(it) } }
        for (item in items) {
            try {
                delete(item.uri)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.i(TAG, "Altes Paket nicht entfernt", e)
                return false
            }
        }
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            marker.writeText("2")
        }
        flush()
        return true
    }

    private suspend fun write(entry: Entry) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val file = File(dir, name(entry.key))
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(listOf(VERSION, entry.id, entry.key, entry.path, entry.capability, entry.createdAt.toString(), entry.body).joinToString("\n"))
        // Ersetzen in einem Schritt: Scheitert das Schreiben, bleibt der vorige Stand.
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Eintrag nicht gespeichert" }
        }
    }

    private suspend fun readAll(): List<Entry> = withContext(Dispatchers.IO) { files().mapNotNull(::read).sortedBy { it.createdAt } }

    private fun files(): List<File> = dir.listFiles().orEmpty().filter { it.isFile && it.name.startsWith(PREFIX) && !it.name.endsWith(".tmp") }

    private fun read(file: File): Entry? = try {
        val parts = file.readText().split("\n", limit = 7)
        if (parts.size < 7 || parts[0] != VERSION) null else Entry(parts[1], parts[2], parts[3], parts[4], parts[6], parts[5].toLong())
    } catch (e: Exception) {
        Log.w(TAG, "Eintrag unlesbar", e)
        null
    }

    /** Ein Schritt zur Gegenseite, höchstens [STEP_TIMEOUT_MS]: Hängt er, bleibt der Eintrag, die Sperre wird frei. */
    private suspend fun <T> guarded(block: suspend () -> T): T? = try {
        withTimeoutOrNull(STEP_TIMEOUT_MS) { block() }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.i(TAG, "Senden nicht möglich (keine Verbindung oder keine Wear-Dienste)", e)
        null
    }

    /** Wear-Dienste: Nachricht bis [MAX_MESSAGE_BYTES], sonst ein Kanal (ChannelClient), der nach dem Schreiben schliesst. */
    class WearTransport(context: Context) : Transport {
        private val app = context.applicationContext

        override suspend fun nodes(capability: String): List<String> =
            Wearable.getCapabilityClient(app).getCapability(capability, CapabilityClient.FILTER_REACHABLE).await().nodes.map { it.id }

        override suspend fun send(node: String, path: String, data: ByteArray) {
            if (fitsMessage(data)) {
                Wearable.getMessageClient(app).sendMessage(node, path, data).await()
                return
            }
            val channels = Wearable.getChannelClient(app)
            val channel = channels.openChannel(node, path).await()
            try {
                // Schliessen der Ausgabe beendet den Datenstrom; der Empfänger liest bis zum Ende und schliesst den Kanal.
                withContext(Dispatchers.IO) { channels.getOutputStream(channel).await().use { it.write(data) } }
            } catch (e: Exception) {
                channels.close(channel)
                throw e
            }
        }
    }

    companion object {
        private const val TAG = "Outbox"
        private const val VERSION = "v1"
        private const val PREFIX = "e-"
        private const val MIGRATED = "migrated"

        /** Grösste Nachricht über MessageClient (Grenze der Wear-Dienste 100 KB, mit Reserve); grössere über einen Kanal. */
        const val MAX_MESSAGE_BYTES = 90 * 1024

        /** Höchstdauer eines Schritts zur Gegenseite (Geräte suchen, senden). */
        private const val STEP_TIMEOUT_MS = 15_000L

        /** Ob [data] als Nachricht geht ([WearTransport]); sonst über einen Kanal. */
        fun fitsMessage(data: ByteArray): Boolean = data.size <= MAX_MESSAGE_BYTES

        /**
         * Inhalt ohne Warteschlange (einmalig, nicht bestätigt, z. B. der Diagnose-Bericht) über [transport] an alle
         * erreichbaren Geräte mit [capability]: wie die Warteschlange bis [MAX_MESSAGE_BYTES] als Nachricht, sonst über
         * einen Kanal (der Empfänger liest ihn mit [WearSync.readChannel], höchstens [PeerLimit.MAX_BYTES]). Je Schritt
         * höchstens [STEP_TIMEOUT_MS]; Fehler werden geloggt.
         * @return Zahl der Geräte, die den Inhalt erhalten haben (0: keines erreichbar oder Senden gescheitert).
         */
        suspend fun sendOnce(transport: Transport, capability: String, path: String, data: ByteArray): Int {
            val nodes = step("Geräte suchen ($path)") { transport.nodes(capability) } ?: return 0
            return nodes.count { node -> step("Senden ($path, ${data.size} Bytes)") { transport.send(node, path, data) } != null }
        }

        /** Ein Schritt von [sendOnce], höchstens [STEP_TIMEOUT_MS]; `null` bei Zeitüberschreitung oder Fehler (geloggt). */
        private suspend fun <T> step(what: String, block: suspend () -> T): T? = try {
            withTimeoutOrNull(STEP_TIMEOUT_MS) { block() }.also { if (it == null) Log.i(TAG, "$what: keine Antwort") }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.i(TAG, "$what nicht möglich (keine Verbindung oder keine Wear-Dienste)", e)
            null
        }

        @Volatile
        private var shared: Outbox? = null

        /** Die Warteschlange dieses Prozesses (Ordner «outbox» im privaten Speicher, nicht gesichert). */
        fun forContext(context: Context): Outbox = shared ?: synchronized(this) {
            shared ?: Outbox(File(context.applicationContext.filesDir, "outbox"), WearTransport(context)).also { shared = it }
        }

        /** Fähigkeit der Gegenseite: auf der Uhr das Handy, sonst die Uhr. */
        fun peer(context: Context): String =
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)) DataLayer.CAPABILITY_PHONE else DataLayer.CAPABILITY_WATCH

        /** Inhalt einer Nachricht: Kennung, Zeilenumbruch, Text. */
        fun wrap(id: String, body: String): ByteArray = "$id\n$body".encodeToByteArray()

        /** Liest [wrap]: Kennung und Text; `null`, wenn die Kennung fehlt. */
        fun open(data: ByteArray?): Pair<String, String>? {
            val text = data?.decodeToString() ?: return null
            val cut = text.indexOf('\n')
            if (cut <= 0) return null
            return text.substring(0, cut) to text.substring(cut + 1)
        }

        /** Dateiname zum Schlüssel (hexadezimal, ohne Pfadzeichen). */
        private fun name(key: String): String = PREFIX + key.encodeToByteArray().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
