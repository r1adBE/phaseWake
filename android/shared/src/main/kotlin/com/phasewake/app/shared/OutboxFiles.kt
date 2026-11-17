package com.phasewake.app.shared

import android.util.Log
import java.io.File

/**
 * Ablage der [Outbox] im Ordner [dir]: je Schlüssel eine Datei (Name aus dem Schlüssel, Inhalt [VERSION] und die
 * Felder des Eintrags je Zeile), in einem Schritt ersetzt; dazu die Marke der einmaligen Umstellung. Nur unter der
 * Sperre der [Outbox] und auf ihrem Dispatcher für Dateizugriffe aufrufen.
 */
internal class OutboxFiles(private val dir: File) {

    /** Speichert [entry]; ersetzt den Eintrag mit gleichem Schlüssel. */
    fun write(entry: Outbox.Entry) {
        dir.mkdirs()
        val file = File(dir, name(entry.key))
        val tmp = File(dir, file.name + ".tmp")
        val text = listOf(
            VERSION,
            entry.id,
            entry.key,
            entry.path,
            entry.capability,
            entry.createdAt.toString(),
            entry.body,
        ).joinToString("\n")
        // Erst auf der Platte (fsync), dann in einem Schritt ersetzen: Scheitert das Schreiben, bleibt der vorige
        // Stand.
        java.io.FileOutputStream(tmp).use { out -> out.write(text.encodeToByteArray()); out.fd.sync() }
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Eintrag nicht gespeichert" }
        }
    }

    /** Alle lesbaren Einträge, älteste zuerst. */
    fun readAll(): List<Outbox.Entry> = files().mapNotNull(::read).sortedBy { it.createdAt }

    /** Dateien der Einträge (ohne «.tmp»). */
    fun files(): List<File> =
        dir.listFiles().orEmpty().filter { it.isFile && it.name.startsWith(PREFIX) && !it.name.endsWith(".tmp") }

    /** Eintrag aus [file]; `null`, wenn unlesbar (geloggt). */
    fun read(file: File): Outbox.Entry? =
        try { parse(file.readText()) } catch (e: Exception) { Log.w(TAG, "Eintrag unlesbar", e); null }

    /**
     * Grenzen der Warteschlange, nur für Nächte und Aufzeichnungen ([Outbox.expires]): Einträge älter als
     * [Outbox.MAX_AGE_MS] bezogen auf [current] (oder aus der «Zukunft», Uhr verstellt, bleiben) entfernen, je Art
     * ([Outbox.kindOf]) nur die neuesten [Outbox.MAX_PER_KIND] behalten. Zustände bleiben. Dazu Reste: «.tmp» (unter
     * der Sperre schreibt keiner, also liegengeblieben) und gelesene, aber unlesbare Einträge (leer oder halb nach
     * einem Stromausfall; scheitert schon das Lesen, bleibt die Datei).
     */
    fun prune(current: Long) {
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(PREFIX) && (it.name.endsWith(".tmp") || broken(it)) }
            .forEach { Log.w(TAG, "Rest ${it.name} entfernt"); it.delete() }
        val all = files().mapNotNull { file -> read(file)?.let { file to it } }.filter { Outbox.expires(it.second.key) }
        val drop = all.filter { (_, e) -> current - e.createdAt > Outbox.MAX_AGE_MS }.toMutableList()
        all.filterNot { it in drop }.groupBy { Outbox.kindOf(it.second.key) }.values.forEach { group ->
            if (group.size > Outbox.MAX_PER_KIND) {
                drop += group.sortedByDescending { it.second.createdAt }.drop(Outbox.MAX_PER_KIND)
            }
        }
        for ((file, entry) in drop) {
            Log.w(TAG, "Eintrag ${entry.key} verworfen (zu alt oder zu viele)")
            file.delete()
        }
    }

    /** Ob die einmalige Umstellung ([Outbox.migrateOnce]) schon lief (Marke im Ordner). */
    fun migrated(): Boolean = File(dir, MIGRATED).exists()

    /** Setzt die Marke der Umstellung. */
    fun markMigrated() {
        dir.mkdirs()
        File(dir, MIGRATED).writeText("2")
    }

    private fun broken(file: File): Boolean =
        runCatching { file.readText() }.getOrNull()?.let { parse(it) == null } == true

    private fun parse(text: String): Outbox.Entry? {
        val p = text.split("\n", limit = 7)
        if (p.size < 7 || p[0] != VERSION) return null
        return p[5].toLongOrNull()?.let { Outbox.Entry(p[1], p[2], p[3], p[4], p[6], it) }
    }

    private companion object {
        /** Wie in [Outbox], damit die Meldungen gleich heissen. */
        const val TAG = "Outbox"
        const val VERSION = "v1"
        const val PREFIX = "e-"
        const val MIGRATED = "migrated"

        /** Dateiname zum Schlüssel (hexadezimal, ohne Pfadzeichen). */
        fun name(key: String): String =
            PREFIX + key.encodeToByteArray().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
