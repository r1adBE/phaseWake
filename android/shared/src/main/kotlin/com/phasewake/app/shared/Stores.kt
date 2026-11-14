package com.phasewake.app.shared

import android.content.Context
import android.os.UserManager
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Einziger DataStore der App (eine Instanz pro Prozess, wie von DataStore verlangt), [PhaseWakeStore].
 *
 * Erst nach dem ersten Entsperren (Audit SP-4): Vorher ist die Datei nicht lesbar, DataStore liefert dann einen leeren
 * Stand und behält ihn im Speicher, auch nachdem sie lesbar ist (bis zum nächsten Schreiben). Darum scheitert jeder
 * Zugriff davor sofort ([IllegalStateException]), statt mit dem leeren Stand weiterzuarbeiten. Was vor dem Entsperren
 * nötig ist, liegt im geräteverschlüsselten Speicher (Zweitwecker des Handys, `DirectBoot` der Uhr).
 */
val Context.phaseWakeStore: DataStore<Preferences>
    get() = UnlockedOnly(applicationContext ?: this, PhaseWakeStore.of(this))

/**
 * Der DataStore «phasewake». Ist die Datei beschädigt (Audit SP-7), beginnt er leer statt bei jedem Lesen zu scheitern:
 * Wecker kommen mit dem nächsten Abgleich von der Gegenseite zurück (deren Stand ist neuer); Notizen, Abhaken, Befinden,
 * Zustimmungen, Feiertagswahl, Einstellungen und die Lernphase sind weg. Die kaputte Datei bleibt als Kopie
 * «phasewake.preferences_pb.broken» (die jüngste, wie `.broken` von `SettingsFile` auf iOS; nicht in der Sicherung).
 * Der leere Stand trägt [RESET_AT]: Er wird nicht als neuer Stand gestempelt und so nicht der Gegenseite aufgedrängt
 * (Lernphase, Schlafziel, Schalter der Einschlaf-Messung; diese behält ihren Stand, bis hier etwas geändert wird).
 */
object PhaseWakeStore {
    private const val TAG = "Stores"
    private const val NAME = "phasewake"

    /** Zeitpunkt (ms), zu dem eine kaputte Datei durch einen leeren Stand ersetzt wurde; sonst fehlt er. */
    val RESET_AT = longPreferencesKey("store_reset_at")

    @Volatile
    private var shared: DataStore<Preferences>? = null

    /** Die Instanz dieses Prozesses (ohne Prüfung der Sperre; dafür [phaseWakeStore]). */
    internal fun of(context: Context): DataStore<Preferences> = shared ?: synchronized(this) {
        shared ?: create(context.applicationContext.preferencesDataStoreFile(NAME)).also { shared = it }
    }

    /** DataStore auf [file] mit der Regel für eine kaputte Datei; [now]: Zeitpunkt für [RESET_AT] (Tests). */
    internal fun create(
        file: File,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        now: () -> Long = System::currentTimeMillis,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { e ->
            Log.w(TAG, "Speicher beschädigt, beginne leer (Kopie ${file.name}.broken)", e)
            try {
                file.copyTo(File(file.parentFile, file.name + ".broken"), overwrite = true)
            } catch (copy: Exception) {
                Log.w(TAG, "Kopie der beschädigten Datei nicht angelegt", copy)
            }
            emptyPreferences().toMutablePreferences().apply { this[RESET_AT] = now() }
        },
        scope = scope,
    ) { file }

    /** Ob [prefs] nach einer kaputten Datei leer begann ([RESET_AT]). */
    fun wasReset(prefs: Preferences): Boolean = prefs[RESET_AT] != null
}

/** [store] nur nach dem ersten Entsperren von [context] (siehe [phaseWakeStore]). */
internal class UnlockedOnly(private val context: Context, private val store: DataStore<Preferences>) : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow {
        checkUnlocked()
        emitAll(store.data)
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        checkUnlocked()
        return store.updateData(transform)
    }

    private fun checkUnlocked() = check(context.getSystemService(UserManager::class.java)?.isUserUnlocked != false) {
        "Speicher vor dem ersten Entsperren nicht lesbar (Audit SP-4)"
    }
}
