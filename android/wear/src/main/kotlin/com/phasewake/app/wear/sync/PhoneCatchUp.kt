package com.phasewake.app.wear.sync

import android.net.Uri
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.phasewake.app.shared.DataLayer
import com.phasewake.app.shared.phaseWakeStore
import com.phasewake.app.wear.PhaseWakeWear
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Holt nach, was das Handy schon abgelegt hat, falls die Uhr eine Änderung verpasst hat (Prozess neu,
 * Neustart, Update): den neuesten Weckerstand und ein «Verlauf löschen». Nur nach dem ersten Entsperren
 * aufrufen. Fehler (keine Wear-Dienste, kein Handy) werden geloggt, die Uhr arbeitet dann allein weiter.
 */
object PhoneCatchUp {
    private const val TAG = "PhoneCatchUp"

    /** Zeitpunkt (ms) des zuletzt angewendeten «Verlauf löschen» vom Handy. */
    private val CLEAR_APPLIED_AT = longPreferencesKey("clear_history_applied_at")

    /** @return ob sich die Wecker geändert haben (dann neu planen). Höchstens [TIMEOUT_MS], damit Empfänger nicht hängen. */
    suspend fun run(app: PhaseWakeWear): Boolean = withTimeoutOrNull(TIMEOUT_MS) { catchUp(app) } ?: false

    private const val TIMEOUT_MS = 5_000L

    private suspend fun catchUp(app: PhaseWakeWear): Boolean {
        var changed = false
        try {
            app.sync.fetchAlarms()?.let { changed = app.alarms.applyRemote(it) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Wecker nachholen fehlgeschlagen", e)
        }
        try {
            latestClear(app)?.let { applyClear(app, it) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "«Verlauf löschen» nachholen fehlgeschlagen", e)
        }
        return changed
    }

    /**
     * Löscht die Nächte, die vor dem «Verlauf löschen» am Handy ([clearedAt], ms) liegen; spätere bleiben.
     * Ein schon angewendetes Löschen (gleicher oder älterer Zeitpunkt) wird übersprungen.
     */
    suspend fun applyClear(app: PhaseWakeWear, clearedAt: Long) {
        val store = app.phaseWakeStore
        if (clearedAt <= (store.data.first()[CLEAR_APPLIED_AT] ?: 0L)) return
        val cut = Instant.ofEpochMilli(clearedAt)
        val nights = app.nights.load()
        val keep = nights.filter { it.wokeAt.isAfter(cut) }
        if (keep.size < nights.size) {
            app.nights.clear()
            keep.forEach { app.nights.add(it) }
        }
        store.edit { it[CLEAR_APPLIED_AT] = clearedAt }
    }

    /** Zeitpunkt des neuesten «Verlauf löschen» im Data Layer; `null`, wenn keins da ist. */
    private suspend fun latestClear(app: PhaseWakeWear): Long? {
        val client = Wearable.getDataClient(app)
        val items = client.getDataItems(Uri.Builder().scheme("wear").path(DataLayer.PATH_CLEAR_HISTORY).build()).await()
        return try {
            items.mapNotNull { DataMapItem.fromDataItem(it).dataMap.getLong(DataLayer.KEY_UPDATED_AT).takeIf { t -> t > 0 } }.maxOrNull()
        } finally {
            items.release()
        }
    }
}
