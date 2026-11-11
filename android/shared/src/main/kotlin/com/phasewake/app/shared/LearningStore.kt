package com.phasewake.app.shared

import android.content.Context
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
import kotlinx.coroutines.flow.first

/**
 * Stand der Lernphase vom Handy für die Uhr ([DataLayer.PATH_MSG_LEARNING]): läuft sie ([active]: die Uhr zeichnet nachts
 * automatisch auf) und die gelernten Werte für den Detektor, je `null` ohne Wert. [updatedAt]: Zeitpunkt am Handy (ms).
 */
data class LearningSnapshot(
    val active: Boolean,
    val floor: Double?,
    val restingHeartRate: Double?,
    val updatedAt: Long,
) {
    /**
     * [config] mit den gelernten Werten (ohne Werte unverändert). Ungültige Werte (NaN, negativ, absurd; z. B. aus einem
     * älteren gespeicherten Stand) gelten als keine ([DetectorConfig.withLearned]), statt den Start des Fensters abzubrechen.
     */
    fun applyTo(config: DetectorConfig): DetectorConfig = config.withLearned(floor, restingHeartRate)

    /** Kurz fürs Diagnose-Protokoll: «active=1 floor=0.0200 hr=set» (kein Pulswert). */
    fun diagLine(): String =
        "active=${if (active) 1 else 0} floor=${NoiseFloor.format(floor)} hr=${if (restingHeartRate != null) "set" else "-"}"

    companion object {
        /** Aus den gelernten Werten [values] (fertig, `null` = noch keine) und ob die Lernphase läuft. */
        fun of(active: Boolean, values: Learning.Values?, updatedAt: Long) =
            LearningSnapshot(active, values?.floor, values?.restingHeartRate, updatedAt)

        /** Inhalt der Nachricht [DataLayer.PATH_MSG_LEARNING]: «aktiv|grundrauschen|ruhepuls|updatedAt» (leer = kein Wert). */
        fun encode(snapshot: LearningSnapshot): String =
            listOf(if (snapshot.active) "1" else "0", snapshot.floor?.toString().orEmpty(), snapshot.restingHeartRate?.toString().orEmpty(), snapshot.updatedAt.toString()).joinToString("|")

        /**
         * Liest [encode]; `null`, wenn unlesbar. Ungültige Werte ([Learning.validFloor], [Learning.validRestingHeartRate])
         * gelten als keine, wie `LearningSnapshot(context:)` (Swift).
         */
        fun decode(body: String): LearningSnapshot? {
            val f = body.split("|").takeIf { it.size == 4 } ?: return null
            val updatedAt = f[3].toLongOrNull() ?: return null
            return LearningSnapshot(
                f[0] == "1",
                Learning.validFloor(f[1].toDoubleOrNull()),
                Learning.validRestingHeartRate(f[2].toDoubleOrNull()),
                updatedAt,
            )
        }
    }
}

/**
 * Lernphase vom Handy an die Uhr: über die Warteschlange [Outbox] (erreicht auch eine gerade getrennte Uhr, sobald sie
 * wieder da ist; Ruhepuls und Grundrauschen nicht als Datenpaket in der Cloud, Audit C2). Fehler (keine Uhr, keine
 * Wear-Dienste) werden geloggt, nicht weitergereicht.
 */
class LearningSync(context: Context) {
    private val app = context.applicationContext
    private val outbox by lazy { Outbox.forContext(app) }

    /** Handy: Stand an die Uhren. */
    suspend fun publish(snapshot: LearningSnapshot) {
        try {
            outbox.enqueue(QueueMessages.KEY_LEARNING, DataLayer.PATH_MSG_LEARNING, DataLayer.CAPABILITY_WATCH, LearningSnapshot.encode(snapshot))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.i(TAG, "Lernphase senden nicht möglich", e)
        }
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
