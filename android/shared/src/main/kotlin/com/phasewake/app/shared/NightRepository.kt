package com.phasewake.app.shared

import android.util.AtomicFile
import android.util.Log
import com.phasewake.app.core.Codec
import com.phasewake.app.core.CodecException
import com.phasewake.app.core.NightHistory
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
 * Verlauf der aufgezeichneten Nächte, höchstens [NightHistory.MAX_NIGHTS], in einer Datei.
 * Schreibt atomar, damit ein Absturz nie einen halben Verlauf hinterlässt.
 */
class NightRepository(
    file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val atomic = AtomicFile(file)
    private val mutex = Mutex()
    private val state = MutableStateFlow<List<NightRecord>?>(null)

    /** Nächte, älteste zuerst; `null`, bis die Datei gelesen ist. */
    val nights: StateFlow<List<NightRecord>?> = state.asStateFlow()

    suspend fun load(): List<NightRecord> = mutex.withLock { loadLocked() }

    /** Fügt eine Nacht hinzu; eine Nacht mit gleichem Fensterbeginn wird ersetzt. */
    suspend fun add(night: NightRecord) = mutex.withLock {
        val updated = NightHistory.trim(loadLocked().filterNot { it.windowStart == night.windowStart } + night)
        write(updated)
        state.value = updated
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
