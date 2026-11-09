package com.phasewake.app.shared

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.phasewake.app.core.DetectorConfig
import com.phasewake.app.core.Learning
import com.phasewake.app.core.NoiseFloor
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

/**
 * Stand der Lernphase vom Handy für die Uhr ([DataLayer.PATH_LEARNING]): läuft sie ([active]: die Uhr zeichnet nachts
 * automatisch auf) und die gelernten Werte für den Detektor, je `null` ohne Wert. [updatedAt]: Zeitpunkt am Handy (ms).
 */
data class LearningSnapshot(
    val active: Boolean,
    val floor: Double?,
    val restingHeartRate: Double?,
    val updatedAt: Long,
) {
    /** [config] mit den gelernten Werten (ohne Werte unverändert). */
    fun applyTo(config: DetectorConfig): DetectorConfig = config.copy(personalFloor = floor, restingHeartRate = restingHeartRate)

    /** Kurz fürs Diagnose-Protokoll: «active=1 floor=0.0200 hr=set» (kein Pulswert). */
    fun diagLine(): String =
        "active=${if (active) 1 else 0} floor=${NoiseFloor.format(floor)} hr=${if (restingHeartRate != null) "set" else "-"}"

    companion object {
        /** Aus den gelernten Werten [values] (fertig, `null` = noch keine) und ob die Lernphase läuft. */
        fun of(active: Boolean, values: Learning.Values?, updatedAt: Long) =
            LearningSnapshot(active, values?.floor, values?.restingHeartRate, updatedAt)

        /** Paket für die Uhren (dringend wie das Schlafziel); fehlende Werte fehlen auch im Paket. */
        fun request(snapshot: LearningSnapshot): PutDataRequest = PutDataMapRequest.create(DataLayer.PATH_LEARNING).apply {
            dataMap.putBoolean(DataLayer.KEY_LEARN_ACTIVE, snapshot.active)
            snapshot.floor?.let { dataMap.putDouble(DataLayer.KEY_LEARN_FLOOR, it) }
            snapshot.restingHeartRate?.let { dataMap.putDouble(DataLayer.KEY_LEARN_RESTING_HR, it) }
            dataMap.putLong(DataLayer.KEY_UPDATED_AT, snapshot.updatedAt)
        }.asPutDataRequest().setUrgent()

        /** Liest ein Paket; ohne Angaben (ältere Handys): keine Lernphase, keine Werte. */
        fun read(map: DataMap): LearningSnapshot = LearningSnapshot(
            active = map.getBoolean(DataLayer.KEY_LEARN_ACTIVE, false),
            floor = if (map.containsKey(DataLayer.KEY_LEARN_FLOOR)) map.getDouble(DataLayer.KEY_LEARN_FLOOR) else null,
            restingHeartRate = if (map.containsKey(DataLayer.KEY_LEARN_RESTING_HR)) map.getDouble(DataLayer.KEY_LEARN_RESTING_HR) else null,
            updatedAt = map.getLong(DataLayer.KEY_UPDATED_AT),
        )
    }
}

/**
 * Lernphase über den Data Layer ([DataLayer.PATH_LEARNING]): Das Handy legt den Stand als Paket ab (erreicht auch eine
 * gerade getrennte Uhr), die Uhr holt ihn nach. Fehler (keine Uhr, keine Wear-Dienste) werden geloggt, nicht weitergereicht.
 */
class LearningSync(context: Context) {
    private val app = context.applicationContext
    private val dataClient by lazy { Wearable.getDataClient(app) }

    /** Handy: Stand an die Uhren. */
    suspend fun publish(snapshot: LearningSnapshot) {
        try {
            dataClient.putDataItem(LearningSnapshot.request(snapshot)).await()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.i(TAG, "Lernphase senden nicht möglich", e)
        }
    }

    /** Uhr: zuletzt abgelegter Stand; `null`, wenn nichts da ist oder die Wear-Dienste fehlen. */
    suspend fun fetch(): LearningSnapshot? = try {
        val items = dataClient.getDataItems(Uri.Builder().scheme("wear").path(DataLayer.PATH_LEARNING).build()).await()
        try {
            items.map { LearningSnapshot.read(DataMapItem.fromDataItem(it).dataMap) }.maxByOrNull { it.updatedAt }
        } finally {
            items.release()
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null
    }

    private companion object {
        const val TAG = "LearningSync"
    }
}

/**
 * Lernphase auf der Uhr, vom Handy; übernimmt nur einen neueren Stand (wie [SleepGoalStore]). Privat im DataStore der Uhr,
 * den keine Sicherung erfasst. Ohne Stand vom Handy: keine Lernphase, keine gelernten Werte.
 */
class LearningStore(private val store: DataStore<Preferences>) {

    /** Aktueller Stand; ohne Daten inaktiv und ohne Werte. */
    suspend fun current(): LearningSnapshot {
        val prefs = store.data.first()
        return LearningSnapshot(prefs[ACTIVE] ?: false, prefs[FLOOR], prefs[RESTING_HR], prefs[UPDATED_AT] ?: 0L)
    }

    /**
     * Speichert [snapshot], nur wenn sein Zeitpunkt neuer ist als der gespeicherte.
     * @return ob gespeichert wurde und sich dabei etwas geändert hat.
     */
    suspend fun setIfNewer(snapshot: LearningSnapshot): Boolean {
        var changed = false
        store.edit { prefs ->
            if (snapshot.updatedAt <= (prefs[UPDATED_AT] ?: 0L)) return@edit
            val before = LearningSnapshot(prefs[ACTIVE] ?: false, prefs[FLOOR], prefs[RESTING_HR], snapshot.updatedAt)
            changed = before != snapshot
            prefs[ACTIVE] = snapshot.active
            val floor = snapshot.floor
            if (floor != null) prefs[FLOOR] = floor else prefs.remove(FLOOR)
            val pulse = snapshot.restingHeartRate
            if (pulse != null) prefs[RESTING_HR] = pulse else prefs.remove(RESTING_HR)
            prefs[UPDATED_AT] = snapshot.updatedAt
        }
        return changed
    }

    private companion object {
        val ACTIVE = booleanPreferencesKey("learn_active")
        val FLOOR = doublePreferencesKey("learn_floor")
        val RESTING_HR = doublePreferencesKey("learn_resting_hr")
        val UPDATED_AT = longPreferencesKey("learn_updated_at")
    }
}
