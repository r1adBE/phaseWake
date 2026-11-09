package com.phasewake.app.shared

import android.util.AtomicFile
import android.util.Log
import com.phasewake.app.core.Epoch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Aufgezeichnete Nächte ([NightRecording]) im privaten Verzeichnis [dir], eine Datei je Aufzeichnung («rec-<beginnMs>»),
 * von keiner Sicherung erfasst (Sicherungsregeln der Apps). Höchstens [NightRecording.MAX_KEPT] abgeschlossene bleiben.
 *
 * Die Uhr schreibt fortlaufend: [begin] legt die Datei an, [append] hängt Epochen an (alle paar Minuten, mit `sync`),
 * [finish] die Schlusszeile. Ein Absturz verliert so höchstens die Epochen seit dem letzten Anhängen; eine halbe letzte
 * Zeile fällt beim Lesen weg ([NightRecording.decode]). Das Handy speichert ganze Aufzeichnungen atomar ([put]).
 */
class RecordingRepository(
    private val dir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val state = MutableStateFlow<List<NightRecording>?>(null)

    /** Abgeschlossene Aufzeichnungen, älteste zuerst; `null`, bis gelesen ([load]). */
    val recordings: StateFlow<List<NightRecording>?> = state.asStateFlow()

    /** Abgeschlossene Aufzeichnungen, älteste zuerst. */
    suspend fun load(): List<NightRecording> = mutex.withLock { finishedLocked() }

    /** Laufende (nicht abgeschlossene) Aufzeichnung, die jüngste; `null`, wenn keine. */
    suspend fun active(): NightRecording? = mutex.withLock {
        withContext(io) { readAll().filter { !it.finished }.maxByOrNull { it.start } }
    }

    /** Eine Aufzeichnung (laufend oder abgeschlossen) zum Beginn [start]; `null`, wenn keine. */
    suspend fun get(start: Instant): NightRecording? = mutex.withLock { withContext(io) { read(fileOf(start)) } }

    /** Legt die Datei einer neuen Aufzeichnung ab [start] an. */
    suspend fun begin(start: Instant) = mutex.withLock {
        withContext(io) {
            dir.mkdirs()
            write(fileOf(start), NightRecording.header(start))
        }
    }

    /** Hängt [epochs] an die laufende Aufzeichnung [start] an; schneidet vorher eine halbe letzte Zeile ab. */
    suspend fun append(start: Instant, epochs: List<Epoch>) {
        if (epochs.isEmpty()) return
        mutex.withLock { withContext(io) { appendText(fileOf(start), NightRecording.epochLines(epochs)) } }
    }

    /**
     * Lernphase: Beginn der Einschlafdauer [bedAt] und Einschlafzeit [asleepAt] an die laufende Aufzeichnung [start]
     * hängen ([NightRecording.onsetLine]); ohne Datei oder schon abgeschlossen nichts.
     */
    suspend fun appendOnset(start: Instant, bedAt: Instant, asleepAt: Instant) = mutex.withLock {
        withContext(io) {
            val file = fileOf(start)
            if (read(file)?.finished == false) appendText(file, NightRecording.onsetLine(bedAt, asleepAt))
        }
    }

    /** Schliesst die Aufzeichnung [start] mit [end] und [reason] ab und löscht die ältesten über [NightRecording.MAX_KEPT]. */
    suspend fun finish(start: Instant, end: Instant, reason: RecordEnd) = mutex.withLock {
        withContext(io) {
            val file = fileOf(start)
            if (file.exists()) appendText(file, NightRecording.endLine(end, reason))
        }
        trimLocked()
    }

    /** Speichert eine ganze (abgeschlossene) Aufzeichnung, z. B. von der Uhr empfangen; gleicher Beginn wird ersetzt. */
    suspend fun put(recording: NightRecording) = mutex.withLock {
        withContext(io) {
            dir.mkdirs()
            val atomic = AtomicFile(fileOf(recording.start))
            val stream = atomic.startWrite()
            try {
                stream.write(NightRecording.encode(recording).encodeToByteArray())
                atomic.finishWrite(stream)
            } catch (e: IOException) {
                atomic.failWrite(stream)
                throw e
            }
        }
        trimLocked()
    }

    /**
     * Löscht die abgeschlossenen Aufzeichnungen («Verlauf löschen»), mit [before] nur die bis dahin beendeten. Eine
     * laufende bleibt (sie misst weiter und wird erst mit ihrem Ende gespeichert); unlesbare Dateien fallen ganz weg.
     */
    suspend fun clear(before: Instant? = null) = mutex.withLock {
        withContext(io) {
            for (file in files()) {
                val recording = read(file)
                val end = recording?.end
                if (recording == null || (end != null && (before == null || !end.isAfter(before)))) file.delete()
            }
        }
        state.value = null
        finishedLocked()
    }

    private suspend fun finishedLocked(): List<NightRecording> {
        state.value?.let { return it }
        val loaded = withContext(io) { readAll().filter { it.finished }.sortedBy { it.start } }
        state.value = loaded
        return loaded
    }

    /** Älteste abgeschlossene über [NightRecording.MAX_KEPT] löschen und den Stand neu lesen. */
    private suspend fun trimLocked() {
        val finished = withContext(io) {
            val all = files().mapNotNull { f -> read(f)?.let { f to it } }.filter { it.second.finished }.sortedBy { it.second.start }
            all.dropLast(NightRecording.MAX_KEPT).forEach { it.first.delete() }
            all.takeLast(NightRecording.MAX_KEPT).map { it.second }
        }
        state.value = finished
    }

    private fun fileOf(start: Instant) = File(dir, PREFIX + start.toEpochMilli())

    private fun files(): List<File> = dir.listFiles().orEmpty().filter { it.isFile && it.name.startsWith(PREFIX) && !it.name.endsWith(".new") && !it.name.endsWith(".bak") }

    private fun readAll(): List<NightRecording> = files().mapNotNull(::read)

    private fun read(file: File): NightRecording? {
        val text = try {
            AtomicFile(file).readFully().decodeToString()
        } catch (_: IOException) {
            return null
        }
        return NightRecording.decode(text) ?: null.also { Log.w(TAG, "Aufzeichnung nicht lesbar: ${file.name}") }
    }

    private fun write(file: File, text: String) {
        FileOutputStream(file, false).use {
            it.write(text.encodeToByteArray())
            it.fd.sync()
        }
    }

    /** Anhängen mit `sync`; endet die Datei nicht mit einem Zeilenende (Absturz beim Schreiben), wird erst gekürzt. */
    private fun appendText(file: File, text: String) {
        if (!file.exists()) return
        val content = file.readBytes()
        if (content.isNotEmpty() && content.last() != '\n'.code.toByte()) {
            val cut = content.lastIndexOf('\n'.code.toByte()) + 1
            write(file, content.copyOf(cut).decodeToString())
        }
        FileOutputStream(file, true).use {
            it.write(text.encodeToByteArray())
            it.fd.sync()
        }
    }

    private companion object {
        const val TAG = "RecordingRepository"
        const val PREFIX = "rec-"
    }
}
