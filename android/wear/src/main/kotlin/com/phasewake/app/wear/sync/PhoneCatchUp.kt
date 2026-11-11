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
 * Holt nach, was die Uhr verpasst haben könnte (Prozess neu, Neustart, Update): einmalig auf die Warteschlange umstellen
 * ([WearSync.migrateTransport]), wartende Nächte und Aufzeichnungen senden, das Handy um seinen Stand bitten (Wecker und
 * Einstellungen kommen über die Warteschlange, [WearSync.requestSync]) und ein verpasstes «Verlauf löschen» anwenden.
 * Nur nach dem ersten Entsperren aufrufen. Fehler (keine Wear-Dienste, kein Handy) werden geloggt, die Uhr arbeitet dann
 * allein weiter.
 */
object PhoneCatchUp {
    private const val TAG = "PhoneCatchUp"

    /** Zeitpunkt (ms) des zuletzt angewendeten «Verlauf löschen» vom Handy. */
    private val CLEAR_APPLIED_AT = longPreferencesKey("clear_history_applied_at")

    /** Höchstens [TIMEOUT_MS], damit Empfänger nicht hängen. */
    suspend fun run(app: PhaseWakeWear) {
        withTimeoutOrNull(TIMEOUT_MS) { catchUp(app) }
    }

    private const val TIMEOUT_MS = 5_000L

    private suspend fun catchUp(app: PhaseWakeWear) {
        app.sync.migrateTransport()
        // Zuerst ein verpasstes «Verlauf löschen» anwenden, dann senden (Audit D-02): Sonst gingen gelöschte Nächte aus
        // der Warteschlange noch einmal ans Handy.
        try {
            latestClear(app)?.let { applyClear(app, it) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "«Verlauf löschen» nachholen fehlgeschlagen", e)
        }
        app.sync.flush()
        app.sync.requestSync()
    }

    /**
     * Löscht die Nächte und Aufzeichnungen, die vor dem «Verlauf löschen» am Handy ([clearedAt], ms) liegen; spätere bleiben.
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
        // Aufzeichnungen ebenso: bis dahin beendete löschen, eine laufende bleibt.
        app.recordings.clear(before = cut)
        // Noch nicht ans Handy Gesendetes, das bis dahin endet, ebenso (Audit D-02).
        app.sync.dropHistory(clearedAt)
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
