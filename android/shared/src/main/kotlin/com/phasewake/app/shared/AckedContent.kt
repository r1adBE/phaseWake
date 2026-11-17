package com.phasewake.app.shared

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Zuletzt von der Gegenseite bestätigter Inhalt je Schlüssel der Warteschlange, als Prüfsumme (SHA-256)
 * in einer Datei [file]: Beim Start eines Prozesses reiht [enqueueIfChanged] einen Zustand (Wecker, Schlafziel …) nur
 * ein, wenn er sich seit der letzten Bestätigung geändert hat oder noch ein Eintrag desselben Schlüssels wartet. So
 * wecken sich Handy und Uhr nachts nicht bei jedem Prozessstart gegenseitig; die Zusage des Abgleichs beim Prozessstart (Ging eine Nachricht
 * verloren, gleicht der Start ab) bleibt: Verloren ist nur, was nie bestätigt wurde.
 *
 * Nur Zustände ([Outbox.expires] falsch): Nächte und Aufzeichnungen haben je einen eigenen Schlüssel und würden die
 * Datei sonst ohne Ende wachsen lassen. Eine unlesbare Datei gilt als leer (dann wird wie bisher gesendet).
 */
class AckedContent(private val file: File) {
    private val lock = Mutex()

    /** Bestätigt wurde [entry] (genau dieser Stand): seine Prüfsumme für [Outbox.Entry.key] merken. */
    suspend fun remember(entry: Outbox.Entry) {
        if (Outbox.expires(entry.key)) return
        lock.withLock {
            withContext(Dispatchers.IO) {
                val all = read() + (entry.key to hash(entry.body))
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                tmp.writeText(all.entries.joinToString("") { (k, v) -> "${hex(k)}=$v\n" })
                if (!tmp.renameTo(file)) {
                    file.delete()
                    check(tmp.renameTo(file)) { "Bestätigte Stände nicht gespeichert" }
                }
            }
        }
    }

    /** Ob [body] unter [key] genau der zuletzt bestätigte Inhalt ist. */
    suspend fun matches(key: String, body: String): Boolean = lock.withLock {
        withContext(Dispatchers.IO) { read()[key] } == hash(body)
    }

    private fun read(): Map<String, String> = try {
        if (!file.exists()) emptyMap() else file.readLines().mapNotNull { line ->
            val cut = line.indexOf('=')
            if (cut <= 0) null else unhex(line.substring(0, cut))?.let { it to line.substring(cut + 1) }
        }.toMap()
    } catch (e: Exception) {
        Log.w(TAG, "Bestätigte Stände unlesbar, gelten als leer", e)
        emptyMap()
    }

    companion object {
        private const val TAG = "AckedContent"

        @Volatile
        private var shared: AckedContent? = null

        /** Die Datei dieses Prozesses (im Ordner der Warteschlange, nicht gesichert). */
        fun forContext(context: Context): AckedContent = shared ?: synchronized(this) {
            shared ?: AckedContent(File(context.applicationContext.filesDir, "outbox/acked")).also { shared = it }
        }

        /** Prüfsumme eines Inhalts (SHA-256, hexadezimal). */
        fun hash(body: String): String = MessageDigest.getInstance("SHA-256").digest(body.encodeToByteArray()).let(::hexBytes)

        private fun hex(key: String) = hexBytes(key.encodeToByteArray())
        private fun hexBytes(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        private fun unhex(text: String): String? = runCatching {
            ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }.decodeToString()
        }.getOrNull()?.takeIf { text.length % 2 == 0 }
    }
}

/**
 * Reiht [body] unter [key] ein wie [Outbox.enqueue], ausser der Inhalt ist schon bestätigt ([AckedContent.matches]) und
 * unter [key] wartet nichts (Abgleich beim Prozessstart). Wartet ein anderer Stand, wird [body] immer
 * eingereiht, sonst bliebe der wartende (z. B. nach dem Zurückändern auf den bestätigten Stand).
 * @return die Kennung des Eintrags oder `null`, wenn nichts eingereiht wurde.
 */
suspend fun Outbox.enqueueIfChanged(content: AckedContent, key: String, path: String, capability: String, body: String): String? {
    if (pending().none { it.key == key } && content.matches(key, body)) return null
    return enqueue(key, path, capability, body)
}

/**
 * Bestätigung der Gegenseite für [id]: Eintrag entfernen ([Outbox.acked]) und seinen Inhalt merken ([AckedContent]).
 * Gemerkt wird nur, wenn genau dieser Stand entfernt wurde (ein inzwischen ersetzter bleibt und zählt nicht).
 */
suspend fun Outbox.ackedAndRemember(content: AckedContent, id: String): Boolean {
    val entry = pending().firstOrNull { it.id == id }
    val removed = acked(id)
    if (removed && entry != null) content.remember(entry)
    return removed
}
