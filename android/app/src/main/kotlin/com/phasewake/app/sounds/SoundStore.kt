package com.phasewake.app.sounds

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.phasewake.app.core.SoundClip
import com.phasewake.app.core.SoundEvents
import com.phasewake.app.phoneApp
import com.phasewake.app.ring.BackupAlarm
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Aufgenommene Geräusche: Ausschnitte als M4A im App-Ordner «sounds/», dazu eine Liste
 * ([SoundEvents.encode]). Nie in Sicherungen (siehe Sicherungsregeln), nie im Export, nie übertragen.
 * Älter als [SoundEvents.RETENTION_DAYS] Tage: gelöscht beim Start der App und täglich.
 */
class SoundStore(context: Context) {
    private val dir = File(context.filesDir, DIR)
    private val index = File(dir, INDEX)
    private val mutex = Mutex()
    private val state = MutableStateFlow<List<SoundClip>?>(null)

    /** `null`, bis [load] gelesen hat. */
    val clips: StateFlow<List<SoundClip>?> = state.asStateFlow()

    /** Datei eines Ausschnitts; `null` bei einem ungültigen Namen. */
    fun file(clip: SoundClip): File? = if (SoundEvents.isSafeFile(clip.file)) File(dir, clip.file) else null

    /** Neue Datei für einen Ausschnitt (noch nicht in der Liste). */
    fun newFile(name: String): File {
        dir.mkdirs()
        return File(dir, name)
    }

    suspend fun load(): List<SoundClip> = mutex.withLock { loadLocked() }

    /** Fügt einen Ausschnitt hinzu; über [SoundEvents.MAX_CLIPS_PER_NIGHT] je Nacht fallen die leisesten weg. */
    suspend fun add(clip: SoundClip) = mutex.withLock {
        val added = SoundEvents.add(loadLocked(), clip)
        save(added.kept)
        added.dropped.forEach { deleteFile(it) }
    }

    suspend fun delete(clip: SoundClip) = mutex.withLock {
        save(loadLocked().filterNot { it.file == clip.file })
        deleteFile(clip)
    }

    /** Alle Ausschnitte einer Nacht. */
    suspend fun deleteNight(night: LocalDate) = mutex.withLock {
        val (gone, kept) = loadLocked().partition { it.night == night }
        save(kept)
        gone.forEach { deleteFile(it) }
    }

    /** «Verlauf löschen»: alle Ausschnitte. */
    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) { dir.deleteRecursively() }
        state.value = emptyList()
    }

    /**
     * Löscht abgelaufene Ausschnitte und Dateien, die in keiner Liste stehen (z. B. nach einem Absturz);
     * solche erst nach einer Stunde, damit ein gerade entstehender Ausschnitt bleibt.
     */
    suspend fun cleanup(now: Instant = Instant.now()) = mutex.withLock {
        val (kept, expired) = SoundEvents.expire(loadLocked(), now)
        if (expired.isNotEmpty()) save(kept)
        expired.forEach { deleteFile(it) }
        val known = kept.map { it.file }.toSet() + INDEX
        withContext(Dispatchers.IO) {
            val before = now.toEpochMilli() - ORPHAN_AGE_MS
            dir.listFiles().orEmpty().filter { it.isFile && it.name !in known && it.lastModified() < before }.forEach { it.delete() }
        }
    }

    private suspend fun loadLocked(): List<SoundClip> {
        state.value?.let { return it }
        val list = withContext(Dispatchers.IO) {
            try {
                if (index.exists()) SoundEvents.decode(index.readText()) else emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "Geräusch-Liste nicht lesbar", e)
                emptyList()
            }
        }
        state.value = list
        return list
    }

    private suspend fun save(list: List<SoundClip>) {
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            // Erst vollständig schreiben, dann ersetzen: Ein Abbruch hinterlässt keine halbe Liste.
            val temp = File(dir, INDEX + TEMP)
            temp.writeText(SoundEvents.encode(list))
            if (!temp.renameTo(index)) {
                index.delete()
                temp.renameTo(index)
            }
        }
        state.value = list
    }

    private suspend fun deleteFile(clip: SoundClip) {
        val file = file(clip) ?: return
        withContext(Dispatchers.IO) { file.delete() }
    }

    /** Tägliches Aufräumen, auch wenn die App nicht geöffnet wird. Nicht exportiert: nur der eigene Alarm. */
    class CleanupReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_CLEANUP || !BackupAlarm.isUnlocked(context)) return
            val pending = goAsync()
            val app = context.phoneApp
            app.appScope.launch {
                try {
                    app.sounds.cleanup()
                } finally {
                    pending.finish()
                }
            }
        }
    }

    companion object {
        private const val TAG = "SoundStore"
        private const val DIR = "sounds"
        private const val INDEX = "clips.txt"
        private const val TEMP = ".tmp"
        private const val ACTION_CLEANUP = "com.phasewake.app.action.SOUND_CLEANUP"
        private const val REQUEST_CODE = 8
        private const val ORPHAN_AGE_MS = 3_600_000L

        /** Plant das tägliche Aufräumen (ungenau, ohne Wecken); ein erneuter Aufruf ersetzt den Plan. */
        fun scheduleCleanup(context: Context) {
            val intent = Intent(context, CleanupReceiver::class.java).setAction(ACTION_CLEANUP)
            val pending = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            context.getSystemService(AlarmManager::class.java).setInexactRepeating(
                AlarmManager.RTC,
                System.currentTimeMillis() + AlarmManager.INTERVAL_DAY,
                AlarmManager.INTERVAL_DAY,
                pending,
            )
        }
    }
}
