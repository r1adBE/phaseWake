package com.phasewake.app.shared

import android.content.Context
import android.util.Log
import com.phasewake.app.core.DiagDevice
import com.phasewake.app.core.DiagEntry
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.DiagLog
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Diagnose-Protokoll in einer Textdatei ([DiagLog]-Zeilen), nur lokal. Schreiben geschieht auf einem eigenen
 * Thread (nie auf dem Hauptthread); ab [DiagLog.MAX_ENTRIES] plus [SLACK] Zeilen wird die Datei gekürzt.
 *
 * Liegt im geräteverschlüsselten Speicher ([forContext]), damit auch Ereignisse vor dem ersten Entsperren
 * (Direct Boot) ankommen. Darum stehen darin nur Zeiten und Arten, keine Namen und keine Messwerte.
 */
class DiagnosticsLog(
    private val file: File,
    private val device: DiagDevice,
    private val clock: () -> Instant = Instant::now,
    private val executor: Executor = Executors.newSingleThreadExecutor(),
) {
    private var lines = -1
    private val version = MutableStateFlow(0)

    /** Zählt bei jeder Änderung hoch; die Diagnose-Seite liest danach neu. */
    val changes: StateFlow<Int> = version.asStateFlow()

    /** Hält ein Ereignis fest (kehrt sofort zurück). */
    fun log(kind: DiagKind, detail: String = "") {
        val entry = DiagEntry(clock(), device, kind, DiagLog.clean(detail))
        executor.execute { append(entry) }
    }

    /** Alle Einträge der letzten Tage, älteste zuerst. Liest die Datei: nicht auf dem Hauptthread aufrufen. */
    @Synchronized
    fun entries(): List<DiagEntry> = DiagLog.trim(read(), clock())

    /** Löscht das Protokoll. */
    @Synchronized
    fun clear() {
        if (file.exists() && !file.delete()) Log.w(TAG, "Protokoll nicht löschbar")
        lines = 0
        version.value++
    }

    @Synchronized
    private fun append(entry: DiagEntry) {
        try {
            if (lines < 0) lines = read().size
            file.parentFile?.mkdirs()
            file.appendText(DiagLog.encode(entry) + "\n")
            lines++
            if (lines > DiagLog.MAX_ENTRIES + SLACK) compact()
            version.value++
        } catch (e: IOException) {
            // Kein Speicher frei oder nicht beschreibbar: das Protokoll ist nur eine Hilfe, der Wecker läuft weiter.
            Log.w(TAG, "Protokoll nicht schreibbar", e)
        }
    }

    private fun compact() {
        val kept = DiagLog.trim(read(), clock())
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(kept.joinToString("") { DiagLog.encode(it) + "\n" })
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
        lines = kept.size
    }

    private fun read(): List<DiagEntry> = try {
        if (file.exists()) file.readLines().mapNotNull(DiagLog::decode) else emptyList()
    } catch (e: IOException) {
        Log.w(TAG, "Protokoll nicht lesbar", e)
        emptyList()
    }

    /** Zugriff für die ganze App (Dienste, Empfänger, Bildschirme), eine Datei je Prozess. */
    companion object {
        private const val TAG = "DiagnosticsLog"

        /** So viele Zeilen über der Höchstzahl, bevor gekürzt wird (seltener umschreiben). */
        const val SLACK: Int = 100

        @Volatile
        private var instance: DiagnosticsLog? = null

        /** Das Protokoll dieses Geräts im geräteverschlüsselten Speicher (auch vor dem ersten Entsperren lesbar). */
        fun forContext(context: Context, device: DiagDevice): DiagnosticsLog = instance ?: synchronized(this) {
            instance ?: DiagnosticsLog(File(context.applicationContext.createDeviceProtectedStorageContext().filesDir, "diag.log"), device)
                .also { instance = it }
        }
    }
}
