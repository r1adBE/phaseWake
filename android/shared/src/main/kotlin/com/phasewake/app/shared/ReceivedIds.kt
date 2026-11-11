package com.phasewake.app.shared

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Zuletzt verarbeitete Kennungen der Warteschlange der Gegenseite ([Outbox.Entry.id], Audit A-1): Kommt derselbe Eintrag
 * noch einmal (Bestätigung verloren, erneut gesendet), bestätigt [WearSync.receive] ihn nur erneut, ohne ihn ein zweites
 * Mal zu verarbeiten. Gespeichert in [file] (eine Kennung je Zeile, höchstens [MAX] – die neuesten), damit es auch nach
 * einem Prozessende gilt. Eine Instanz je Prozess ([forContext]).
 */
class ReceivedIds(private val file: File) {
    private val lock = Mutex()

    /** Im Speicher, sobald einmal gelesen; älteste zuerst. */
    private var ids: ArrayDeque<String>? = null

    /** Ob [id] schon verarbeitet wurde. */
    suspend fun contains(id: String): Boolean = lock.withLock { load().contains(id) }

    /** Merkt [id] als verarbeitet; die ältesten über [MAX] fallen weg. */
    suspend fun add(id: String) = lock.withLock {
        val list = load()
        if (list.contains(id)) return@withLock
        list.addLast(id)
        while (list.size > MAX) list.removeFirst()
        withContext(Dispatchers.IO) {
            try {
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                tmp.writeText(list.joinToString("\n"))
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            } catch (e: Exception) {
                // Nur ein Schutz vor doppelter Verarbeitung (die ohnehin nichts ändert): im Speicher gilt es weiter.
                Log.w(TAG, "Empfangene Kennungen nicht gespeichert", e)
            }
        }
    }

    private suspend fun load(): ArrayDeque<String> = ids ?: withContext(Dispatchers.IO) {
        val text = try {
            if (file.exists()) file.readText() else ""
        } catch (e: Exception) {
            Log.w(TAG, "Empfangene Kennungen nicht lesbar", e)
            ""
        }
        ArrayDeque(text.lines().filter { it.isNotBlank() }.takeLast(MAX))
    }.also { ids = it }

    companion object {
        private const val TAG = "ReceivedIds"

        /** So viele Kennungen bleiben gemerkt (weit mehr als gleichzeitig unterwegs sein können). */
        const val MAX = 200

        @Volatile
        private var shared: ReceivedIds? = null

        /** Die Liste dieses Prozesses (Datei «outbox-received» im privaten Speicher, nicht gesichert). */
        fun forContext(context: Context): ReceivedIds = shared ?: synchronized(this) {
            shared ?: ReceivedIds(File(context.applicationContext.filesDir, "outbox-received")).also { shared = it }
        }
    }
}
