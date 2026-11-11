package com.phasewake.app.shared

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
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
 * ihn bei einem späteren Anlass erneut (Start der App, Gegenseite erreichbar, Nachricht von ihr), aber frühestens
 * [RESEND_AFTER_MS] nach dem letzten erfolgreichen Senden (Audit A-1: sonst kam derselbe Eintrag mehrfach an, weil jede
 * Nachricht der Gegenseite alles Unbestätigte erneut schickte). Übergeht [flush] deshalb einen Eintrag, sendet es ihn
 * von selbst, sobald die Wartezeit um ist (Audit AN-3, ein einziger Zeitgeber für alle). [enqueue] speichert nur lokal
 * (schnell) und sendet den neuen Eintrag danach im Hintergrund ([scope]). Ein Eintrag mit gleichem Schlüssel
 * ([Entry.key], z. B. «alarms») ersetzt den älteren: Für Zustände zählt nur der neueste. Nächte und Aufzeichnungen
 * haben je einen eigenen Schlüssel. Die Bestätigung nennt die Kennung des gesendeten Standes ([Entry.id]): Ein
 * inzwischen ersetzter Eintrag bleibt. Der Empfänger verarbeitet idempotent (dieselbe Nacht zweimal ändert nichts),
 * merkt sich die zuletzt verarbeiteten Kennungen ([ReceivedIds]) und bestätigt erst danach ([WearSync.receive]).
 *
 * Die Sperre gilt nur für den Ordner und die gemerkten Zeiten, nie während des Sendens (Audit AN-4): Ein hängender
 * Schritt zur Gegenseite hält weder [enqueue] noch [acked] auf.
 *
 * Begrenzt (Audit A-8, AN-2): Nur Nächte und Aufzeichnungen ([expires]) fallen nach [MAX_AGE_MS] weg, und je Art
 * ([kindOf]) bleiben höchstens [MAX_PER_KIND], die neuesten. Zustände (Wecker, Einstellungen, Lernphase …) haben je
 * einen Schlüssel, also höchstens einen Eintrag, und verfallen nie: Sonst ginge eine Änderung nach zwei Wochen ohne
 * Gegenseite verloren.
 *
 * Eine Instanz je Prozess und Ordner ([forContext]), damit alle Zugriffe über dieselbe Sperre laufen.
 *
 * @param scope Bereich für das Senden nach [enqueue] und das verzögerte Nachsenden; lebt so lange wie der Prozess.
 * @param io Dateizugriffe; im Test der Takt des Tests.
 */
class Outbox(
    private val dir: File,
    private val transport: Transport,
    private val now: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = processScope(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

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

    /** Je Kennung ([Entry.id]): Zeitpunkt (ms) des letzten erfolgreichen Sendens in diesem Prozess. Nur unter [lock]. */
    private val sentAt = mutableMapOf<String, Long>()

    /** Kennungen, die gerade gesendet werden (ausserhalb der Sperre); kein zweites [flush] sendet sie gleichzeitig. */
    private val sending = mutableSetOf<String>()

    /** Verzögertes Nachsenden ([scheduleRetry]): höchstens eines, zum frühesten fälligen Zeitpunkt [retryAt]. */
    private val retryLock = Any()
    private var retry: Job? = null
    private var retryAt = Long.MAX_VALUE

    /**
     * Reiht [body] unter [key] ein (ersetzt einen älteren Eintrag mit gleichem Schlüssel) und kehrt zurück, sobald er
     * lokal gespeichert ist (Audit AN-1: schnell, z. B. vor dem Neuplanen). Gesendet wird nur dieser Eintrag (Audit A-1)
     * im Hintergrund ([scope]). @return seine Kennung.
     */
    suspend fun enqueue(key: String, path: String, capability: String, body: String): String {
        val entry = Entry(UUID.randomUUID().toString(), key, path, capability, body, now())
        lock.withLock {
            write(entry)
            prune()
        }
        scope.launch { flush(only = entry.id) }
        return entry.id
    }

    /** Wartende Einträge, älteste zuerst. */
    suspend fun pending(): List<Entry> = lock.withLock { readAll() }

    /**
     * Sendet die wartenden Einträge an die erreichbaren Geräte ihrer Fähigkeit; einen schon gesendeten erst wieder
     * [RESEND_AFTER_MS] danach (Audit A-1), dann von selbst ([scheduleRetry], Audit AN-3). Ohne Gerät oder bei einem
     * Fehler bleibt der Eintrag für den nächsten Anlass. Gesendet wird ausserhalb der Sperre (Audit AN-4): Unter der
     * Sperre nur auswählen und danach die Zeiten nachführen. [only]: nur den Eintrag mit dieser Kennung ([enqueue]).
     * @return Zahl der wartenden Einträge beim Auswählen.
     */
    suspend fun flush(only: String? = null): Int {
        val current = now()
        var waiting = 0
        var nextDue: Long? = null
        val due = lock.withLock {
            prune()
            val entries = readAll()
            waiting = entries.size
            // Gemerkte Zeiten ersetzter oder bestätigter Einträge vergessen.
            sentAt.keys.retainAll(entries.map { it.id }.toSet())
            val candidates = entries.filter { (only == null || it.id == only) && it.id !in sending }
            val (ready, later) = candidates.partition { resendDue(sentAt[it.id], current) }
            nextDue = later.mapNotNull { sentAt[it.id] }.minOrNull()?.plus(RESEND_AFTER_MS)
            sending += ready.map { it.id }
            ready
        }
        nextDue?.let(::scheduleRetry)
        if (due.isEmpty()) return waiting
        val sent = mutableSetOf<String>()
        try {
            val nodes = mutableMapOf<String, List<String>>()
            for (entry in due) {
                val targets = nodes.getOrPut(entry.capability) { guarded { transport.nodes(entry.capability) } ?: emptyList() }
                for (node in targets) if (guarded { transport.send(node, entry.path, wrap(entry.id, entry.body)) } != null) sent += entry.id
            }
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    sending -= due.map { it.id }.toSet()
                    sent.forEach { sentAt[it] = current }
                }
            }
        }
        return waiting
    }

    /**
     * Ein Nachsenden um [at] (ms) planen, falls nicht schon eines früher oder gleichzeitig ansteht (zusammengefasst:
     * höchstens ein Zeitgeber). Ein späteres ersetzt sich durch das frühere.
     */
    private fun scheduleRetry(at: Long) = synchronized(retryLock) {
        if (retry?.isActive == true && retryAt <= at) return@synchronized
        retry?.cancel()
        retryAt = at
        retry = scope.launch {
            delay((at - now()).coerceAtLeast(0L))
            // Ab hier zählt dieser Zeitgeber nicht mehr: [flush] darf ein neues Nachsenden planen, ohne ihn abzubrechen.
            synchronized(retryLock) {
                if (retry === coroutineContext.job) {
                    retry = null
                    retryAt = Long.MAX_VALUE
                }
            }
            flush()
        }
    }

    /** Die Gegenseite hat [id] empfangen: Eintrag entfernen (nur genau diesen Stand). */
    suspend fun acked(id: String): Boolean = lock.withLock {
        val file = files().firstOrNull { read(it)?.id == id } ?: return@withLock false
        sentAt.remove(id)
        withContext(io) { file.delete() }
    }

    /** Entfernt wartende Einträge, deren Schlüssel mit [prefix] beginnt und die vor [before] (ms) eingereiht wurden. */
    suspend fun drop(prefix: String, before: Long = Long.MAX_VALUE) = dropIf(prefix) { it.createdAt < before }

    /** Entfernt wartende Einträge, deren Schlüssel mit [prefix] beginnt und für die [matches] gilt. */
    suspend fun dropIf(prefix: String, matches: (Entry) -> Boolean) = lock.withLock {
        withContext(io) {
            files().filter { read(it)?.let { e -> e.key.startsWith(prefix) && matches(e) } == true }.forEach { it.delete() }
        }
    }

    /**
     * Grenzen der Warteschlange (Audit A-8, AN-2), nur für Nächte und Aufzeichnungen ([expires]): Einträge älter als
     * [MAX_AGE_MS] (oder aus der «Zukunft», Uhr verstellt, bleiben) entfernen, je Art ([kindOf]) nur die neuesten
     * [MAX_PER_KIND] behalten. Zustände bleiben. Nur unter [lock].
     */
    private suspend fun prune() = withContext(io) {
        val current = now()
        val all = files().mapNotNull { file -> read(file)?.let { file to it } }.filter { expires(it.second.key) }
        val drop = all.filter { (_, e) -> current - e.createdAt > MAX_AGE_MS }.toMutableList()
        all.filterNot { it in drop }.groupBy { kindOf(it.second.key) }.values.forEach { group ->
            if (group.size > MAX_PER_KIND) drop += group.sortedByDescending { it.second.createdAt }.drop(MAX_PER_KIND)
        }
        for ((file, entry) in drop) {
            Log.w(TAG, "Eintrag ${entry.key} verworfen (zu alt oder zu viele)")
            file.delete()
        }
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
        withContext(io) {
            dir.mkdirs()
            marker.writeText("2")
        }
        flush()
        return true
    }

    private suspend fun write(entry: Entry) = withContext(io) {
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

    private suspend fun readAll(): List<Entry> = withContext(io) { files().mapNotNull(::read).sortedBy { it.createdAt } }

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

        /** Ein gesendeter, noch nicht bestätigter Eintrag geht frühestens so lange danach erneut (Audit A-1). */
        const val RESEND_AFTER_MS = 45_000L

        /** Älter als 14 Tage: verworfen (Audit A-8; die Uhr behält ihren eigenen Verlauf). */
        const val MAX_AGE_MS = 14 * 24 * 3_600_000L

        /** Höchstens so viele wartende Einträge je Art ([kindOf]); ältere fallen weg. */
        const val MAX_PER_KIND = 60

        /** Art eines Schlüssels: bei Nächten und Aufzeichnungen der Teil bis «/» (z. B. «night/»), sonst der Schlüssel. */
        fun kindOf(key: String): String = key.indexOf('/').let { if (it < 0) key else key.substring(0, it + 1) }

        /**
         * Ob Einträge unter [key] verfallen dürfen ([MAX_AGE_MS], [MAX_PER_KIND]): nur Nächte und Aufzeichnungen
         * (Audit AN-2). Zustände (Wecker, Einstellungen, Lernphase …) verfallen nie.
         */
        fun expires(key: String): Boolean = key.startsWith(QueueMessages.KEY_NIGHT) || key.startsWith(QueueMessages.KEY_RECORDING)

        /** Bereich für das Senden im Hintergrund: lebt so lange wie der Prozess; ein Fehler wird geloggt, nie weitergereicht. */
        private fun processScope() = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.w(TAG, "Senden im Hintergrund fehlgeschlagen", e) },
        )

        /**
         * Ob ein Eintrag, zuletzt um [lastSentAt] gesendet (`null`: noch nie in diesem Prozess), jetzt ([now]) wieder
         * gesendet wird. Eine zurückgestellte Uhr ([now] vor [lastSentAt]) sendet gleich.
         */
        fun resendDue(lastSentAt: Long?, now: Long): Boolean =
            lastSentAt == null || now < lastSentAt || now - lastSentAt >= RESEND_AFTER_MS

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
