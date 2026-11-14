package com.phasewake.app.shared

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.phasewake.app.core.DetectorConfig
import com.phasewake.app.core.Learning
import com.phasewake.app.core.NoiseFloor
import com.phasewake.app.core.TimeBounds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Stand der Lernphase vom Handy für die Uhr ([DataLayer.PATH_MSG_LEARNING]): läuft sie ([active]: die Uhr zeichnet nachts
 * automatisch auf) und die gelernten Werte für den Detektor, je `null` ohne Wert. [updatedAt]: Zeitpunkt am Handy (ms).
 */
data class LearningSnapshot(
    val active: Boolean,
    val floor: Double?,
    val restingHeartRate: Double?,
    val updatedAt: Long,
    /**
     * Gelernte Einschlafdauer in Minuten (0 bis [Learning.MAX_LATENCY_MINUTES]) für die Bettzeit auf dem Ring
     * (`Bedtime.onDial`, Entscheid O-03); `null` = nicht gelernt oder Handy einer früheren Version.
     */
    val latencyMinutes: Int? = null,
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
            LearningSnapshot(active, values?.floor, values?.restingHeartRate, updatedAt, values?.latencyMinutes)

        /**
         * Inhalt der Nachricht [DataLayer.PATH_MSG_LEARNING]: «aktiv|grundrauschen|ruhepuls|updatedAt» (leer = kein Wert),
         * immer genau 4 Felder: Uhren früherer Versionen lesen nur so viele und verwürfen sonst den ganzen Stand (Audit
         * F-4/SP-6). Die Einschlafdauer geht getrennt ([encodeLatency], [DataLayer.PATH_MSG_LEARN_LATENCY]).
         */
        fun encode(snapshot: LearningSnapshot): String =
            listOf(if (snapshot.active) "1" else "0", snapshot.floor?.toString().orEmpty(), snapshot.restingHeartRate?.toString().orEmpty(), snapshot.updatedAt.toString())
                .joinToString("|")

        /** Inhalt der Nachricht [DataLayer.PATH_MSG_LEARN_LATENCY]: «einschlafdauer|updatedAt», ohne Dauer «-1|updatedAt». */
        fun encodeLatency(snapshot: LearningSnapshot): String = "${snapshot.latencyMinutes ?: NO_LATENCY}|${snapshot.updatedAt}"

        /**
         * Liest [encodeLatency]: (Einschlafdauer oder `null` = keine bzw. unplausibel, Zeitpunkt); `null`, wenn unlesbar
         * (auch ein unplausibler Zeitpunkt, [QueueMessages.decodeStamped]).
         */
        fun decodeLatency(body: String): Pair<Int?, Long>? =
            QueueMessages.decodeStamped(body)?.let { (minutes, at) -> validLatency(minutes) to at }

        /** «Keine Einschlafdauer» in [encodeLatency]. */
        private const val NO_LATENCY = -1

        /** Einschlafdauer in Minuten, wenn plausibel (0 bis [Learning.MAX_LATENCY_MINUTES]); sonst `null`. */
        fun validLatency(value: Int?): Int? = value?.takeIf { it in 0..Learning.MAX_LATENCY_MINUTES }

        /**
         * Liest [encode]; `null`, wenn unlesbar. Ungültige Werte ([Learning.validFloor], [Learning.validRestingHeartRate])
         * gelten als keine, wie `LearningSnapshot(context:)` (Swift).
         */
        fun decode(body: String): LearningSnapshot? {
            // 4 Felder; 5 (mit Einschlafdauer) schickten nur Zwischenstände vor Audit F-4 – weiter lesbar, die Uhr nimmt die
            // Dauer aber nur aus [DataLayer.PATH_MSG_LEARN_LATENCY] ([LearningStore.setIfNewer]).
            val f = body.split("|").takeIf { it.size == 4 || it.size == 5 } ?: return null
            // Unplausibler Zeitpunkt (Audit AN-5/KN-4): unlesbar, sonst gewänne er gegen jeden späteren Stand.
            val updatedAt = TimeBounds.checked(f[3].toLongOrNull()) ?: return null
            return LearningSnapshot(
                f[0] == "1",
                Learning.validFloor(f[1].toDoubleOrNull()),
                Learning.validRestingHeartRate(f[2].toDoubleOrNull()),
                updatedAt,
                validLatency(f.getOrNull(4)?.toIntOrNull()),
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

    /** Handy: Stand an die Uhren, die Einschlafdauer als eigene Nachricht (Audit F-4/SP-6). */
    suspend fun publish(snapshot: LearningSnapshot) {
        try {
            outbox.enqueue(QueueMessages.KEY_LEARNING, DataLayer.PATH_MSG_LEARNING, DataLayer.CAPABILITY_WATCH, LearningSnapshot.encode(snapshot))
            outbox.enqueue(
                QueueMessages.KEY_LEARN_LATENCY, DataLayer.PATH_MSG_LEARN_LATENCY, DataLayer.CAPABILITY_WATCH, LearningSnapshot.encodeLatency(snapshot),
            )
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
        return LearningSnapshot(prefs[ACTIVE] ?: false, prefs[FLOOR], prefs[RESTING_HR], prefs[UPDATED_AT] ?: 0L, LearningSnapshot.validLatency(prefs[LATENCY]))
    }

    /** Gelernte Einschlafdauer vom Handy (O-03, Bettzeit auf dem Ring); `null` = keine. */
    val latencyMinutes: Flow<Int?> = store.data.map { LearningSnapshot.validLatency(it[LATENCY]) }.distinctUntilChanged()

    /**
     * Speichert [snapshot] ohne die Einschlafdauer (die kommt getrennt, [setLatencyIfNewer]), nur wenn sein Zeitpunkt
     * neuer ist als der gespeicherte.
     * @return ob gespeichert wurde und sich dabei etwas geändert hat.
     */
    suspend fun setIfNewer(snapshot: LearningSnapshot): Boolean {
        var changed = false
        store.edit { prefs ->
            if (snapshot.updatedAt <= (prefs[UPDATED_AT] ?: 0L)) return@edit
            val before = LearningSnapshot(prefs[ACTIVE] ?: false, prefs[FLOOR], prefs[RESTING_HR], snapshot.updatedAt)
            changed = before != snapshot.copy(latencyMinutes = null)
            prefs[ACTIVE] = snapshot.active
            val floor = snapshot.floor
            if (floor != null) prefs[FLOOR] = floor else prefs.remove(FLOOR)
            val pulse = snapshot.restingHeartRate
            if (pulse != null) prefs[RESTING_HR] = pulse else prefs.remove(RESTING_HR)
            prefs[UPDATED_AT] = snapshot.updatedAt
        }
        return changed
    }

    /**
     * Speichert die Einschlafdauer [minutes] (`null` = keine) vom Handy ([DataLayer.PATH_MSG_LEARN_LATENCY]), nur wenn
     * [updatedAt] neuer ist als der Zeitpunkt der gespeicherten Dauer (eigener Zeitpunkt, unabhängig vom übrigen Stand).
     * @return ob gespeichert wurde und sich die Dauer dabei geändert hat.
     */
    suspend fun setLatencyIfNewer(minutes: Int?, updatedAt: Long): Boolean {
        var changed = false
        store.edit { prefs ->
            if (updatedAt <= (prefs[LATENCY_AT] ?: 0L)) return@edit
            val latency = LearningSnapshot.validLatency(minutes)
            changed = LearningSnapshot.validLatency(prefs[LATENCY]) != latency
            if (latency != null) prefs[LATENCY] = latency else prefs.remove(LATENCY)
            prefs[LATENCY_AT] = updatedAt
        }
        return changed
    }

    private companion object {
        val ACTIVE = booleanPreferencesKey("learn_active")
        val FLOOR = doublePreferencesKey("learn_floor")
        val RESTING_HR = doublePreferencesKey("learn_resting_hr")
        val UPDATED_AT = longPreferencesKey("learn_updated_at")
        val LATENCY = intPreferencesKey("learn_latency_minutes")
        val LATENCY_AT = longPreferencesKey("learn_latency_updated_at")
    }
}
