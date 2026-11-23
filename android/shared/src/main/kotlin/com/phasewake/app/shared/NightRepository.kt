package com.phasewake.app.shared

import android.util.AtomicFile
import android.util.Log
import com.phasewake.app.core.Codec
import com.phasewake.app.core.CodecException
import com.phasewake.app.core.HistoryPrune
import com.phasewake.app.core.NightKeep
import com.phasewake.app.core.NightRecord
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Verlauf der aufgezeichneten Nächte in einer Datei, gekürzt nach [keep] (Handy: 2 Jahre, Uhr: 90 Nächte).
 * Schreibt atomar, damit ein Absturz nie einen halben Verlauf hinterlässt.
 */
class NightRepository(
    file: File,
    private val keep: NightKeep,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Jetzt, für die Altersgrenze von [keep] (Tests geben eine feste Zeit). */
    private val now: () -> java.time.Instant = java.time.Instant::now,
) {
    private val atomic = AtomicFile(file)
    private val mutex = Mutex()
    private val state = MutableStateFlow<List<NightRecord>?>(null)

    /** Nächte, älteste zuerst; `null`, bis die Datei gelesen ist. */
    val nights: StateFlow<List<NightRecord>?> = state.asStateFlow()

    suspend fun load(): List<NightRecord> = mutex.withLock { loadLocked() }

    /**
     * Fügt eine Nacht hinzu; dieselbe Nacht (Wecker und Fensterbeginn, siehe [mergeNight]) wird ersetzt.
     * Ein schon angegebenes Befinden bleibt erhalten, auch wenn die Uhr dieselbe Nacht noch einmal schickt, selbst mit
     * ihrer Wahl nach dem Wecken ([com.phasewake.app.core.FeelingAsk.merged]).
     */
    suspend fun add(night: NightRecord) = mutex.withLock {
        val updated = mergeNight(loadLocked(), night, keep, now())
        write(updated)
        state.value = updated
    }

    /**
     * Speichert das Befinden (1–5) zur Nacht dieses Weckers mit diesem Fensterbeginn.
     * @return die Nacht mit dem neuen Befinden (die Uhr schickt sie so ans Handy); `null`, wenn es sie nicht gibt.
     */
    suspend fun setFeeling(alarmId: String, windowStart: java.time.Instant, feeling: Int): NightRecord? =
        mutex.withLock {
            val updated = loadLocked().map { if (it.isNight(alarmId, windowStart)) it.copy(feeling = feeling) else it }
            write(updated)
            state.value = updated
            updated.firstOrNull { it.isNight(alarmId, windowStart) }
        }

    /**
     * Ersetzt den ganzen Verlauf (Wiederherstellen einer Sicherung), gekürzt nach [keep].
     */
    suspend fun replaceAll(nights: List<NightRecord>) = mutex.withLock {
        val trimmed = keep.trim(nights, now())
        write(trimmed)
        state.value = trimmed
    }

    /** «Älter als 90 Tage löschen»: Nächte, die bis [cutoff] geweckt haben, weg ([HistoryPrune.nights]). */
    suspend fun clearBefore(cutoff: java.time.Instant) = mutex.withLock {
        val kept = HistoryPrune.nights(loadLocked(), cutoff)
        write(kept)
        state.value = kept
    }

    suspend fun clear() = mutex.withLock {
        write(emptyList())
        state.value = emptyList()
    }

    private suspend fun loadLocked(): List<NightRecord> {
        state.value?.let { return it }
        val loaded = withContext(io) { read() }
        state.value = loaded
        return loaded
    }

    private fun read(): List<NightRecord> {
        val text = try {
            atomic.readFully().decodeToString()
        } catch (_: FileNotFoundException) {
            return emptyList()
        }
        return text.split(SEPARATOR).filter { it.isNotBlank() }.mapNotNull { chunk ->
            try {
                Codec.decodeNight(chunk.trim('\n') + "\n")
            } catch (e: CodecException) {
                Log.w(TAG, "Nacht im Verlauf nicht lesbar, übersprungen", e)
                null
            }
        }
    }

    private suspend fun write(nights: List<NightRecord>) = withContext(io) {
        val stream = atomic.startWrite()
        try {
            stream.write(nights.joinToString(SEPARATOR) { Codec.encodeNight(it) }.encodeToByteArray())
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

    private companion object {
        const val TAG = "NightRepository"
        const val SEPARATOR = "\n%%\n"
    }
}
