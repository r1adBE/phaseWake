package com.phasewake.app.shared

import android.content.Context
import java.io.File

/**
 * Anfrage nach dem Stand der Gegenseite beim Prozessstart ([WearSync.requestSyncOnStart]) höchstens alle
 * [MIN_INTERVAL_MS]: Startet ein Prozess nachts mehrmals neu, fragt er nicht jedes Mal. Der Zeitpunkt
 * der letzten gesendeten Anfrage liegt in [file] (übersteht das Prozessende). Was sich ändert, kommt ohnehin über die
 * Warteschlange; die Anfrage holt nur einen verpassten Stand nach.
 */
class SyncGate(private val file: File, private val now: () -> Long = System::currentTimeMillis) {

    /** Ob jetzt wieder angefragt wird ([due]). */
    fun due(): Boolean = due(last(), now())

    /** Anfrage gesendet: Zeitpunkt merken. Ein Fehler beim Schreiben lässt die nächste Anfrage einfach zu. */
    fun mark() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(now().toString())
        }
    }

    private fun last(): Long? = runCatching { if (file.exists()) file.readText().trim().toLongOrNull() else null }.getOrNull()

    companion object {
        /** Mindestabstand zweier Anfragen beim Start: 30 Min. */
        const val MIN_INTERVAL_MS = 30 * 60_000L

        /**
         * Ob nach einer Anfrage um [last] (ms, `null`: noch nie oder unlesbar) jetzt ([now]) wieder angefragt wird.
         * Eine zurückgestellte Uhr ([now] vor [last]) fragt gleich.
         */
        fun due(last: Long?, now: Long): Boolean = last == null || now < last || now - last >= MIN_INTERVAL_MS

        /** Die Datei dieses Geräts (im Ordner der Warteschlange, nicht gesichert). */
        fun forContext(context: Context): SyncGate = SyncGate(File(context.applicationContext.filesDir, "outbox/sync-requested"))
    }
}
