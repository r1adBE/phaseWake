package com.phasewake.app.shared

import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.phasewake.app.core.Codec
import com.phasewake.app.core.HolidayList
import com.phasewake.app.core.Holidays
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.TimeBounds
import java.util.UUID
import kotlinx.coroutines.tasks.await

/**
 * Schlüssel und Inhalte der Warteschlange [Outbox] zwischen Handy und Uhr (Audit C2): Textformate der Nachrichten
 * (`DataLayer.PATH_MSG_…`) und die eigenen Pakete der früheren Wege für die einmalige Umstellung.
 */
object QueueMessages {
    private const val TAG = "QueueMessages"

    /** Schlüssel der Warteschlange ([Outbox.Entry.key]); Nächte und Aufzeichnungen mit eigenem Zusatz. */
    const val KEY_ALARMS = "alarms"
    const val KEY_NIGHT = "night/"
    const val KEY_RECORDING = "recording/"
    const val KEY_RECORD_STATUS = "record-status"
    const val KEY_ONSET_SHIFT = "onset-shift"
    const val KEY_LEARNING = "learning"

    /** Meldung der Lernphase je Nacht ([DataLayer.PATH_MSG_LEARN_SKIP]), mit der Zielzeit in ms als Zusatz. */
    const val KEY_LEARN_SKIP = "learn-skip/"
    const val KEY_SLEEP_GOAL = "sleep-goal"
    const val KEY_AUTO_ONSET = "auto-onset"
    const val KEY_HOLIDAYS = "holidays"

    /** Schlüssel einer Nacht: Fensterbeginn und Kennung (zwei Wecker mit gleichem Fensterbeginn bleiben getrennt). */
    fun nightKey(night: NightRecord): String = KEY_NIGHT + night.windowStart.toEpochMilli() + "-" + Uri.encode(night.alarmId)

    /**
     * Inhalt der Nachricht [DataLayer.PATH_MSG_ALARMS]: «updatedAt», Zeilenumbruch, Wecker, danach (Audit A-4) die
     * Zeitpunkte je Wecker und die gelöschten Wecker: Zeile [STAMPS_HEADER], dann je eine Zeile «t=kennung=ms» bzw.
     * «d=kennung=ms» (Kennung URL-kodiert). Ältere Versionen lesen diese Zeilen als unlesbare Wecker-Zeilen und
     * überspringen sie (zu wenige Felder).
     */
    fun encodeAlarms(snapshot: AlarmSnapshot): String = buildString {
        append(snapshot.updatedAt).append('\n').append(Codec.encodeAlarms(snapshot.alarms))
        if (snapshot.perAlarm) {
            append(STAMPS_HEADER).append('\n')
            snapshot.stamps.forEach { (id, at) -> append(STAMP).append(AlarmStamps.escape(id)).append('=').append(at).append('\n') }
            snapshot.deleted.forEach { (id, at) -> append(DELETED).append(AlarmStamps.escape(id)).append('=').append(at).append('\n') }
        }
    }

    /**
     * Liest [encodeAlarms]; `null`, wenn unlesbar. Ohne Zeitpunkte je Wecker (ältere Version): [AlarmSnapshot.perAlarm]
     * `false`, dann gilt beim Übernehmen weiter «neuere Liste gewinnt».
     */
    fun decodeAlarms(body: String): AlarmSnapshot? = runCatching {
        val cut = body.indexOf('\n')
        val updatedAt = body.substring(0, if (cut < 0) body.length else cut).toLong()
        val rest = if (cut < 0) "" else body.substring(cut + 1)
        val marker = rest.lines().indexOf(STAMPS_HEADER)
        val lines = rest.lines()
        val alarmsText = if (marker < 0) rest else lines.take(marker).joinToString("\n")
        val stamps = mutableMapOf<String, Long>()
        val deleted = mutableMapOf<String, Long>()
        if (marker >= 0) {
            for (line in lines.drop(marker + 1)) {
                val target = when {
                    line.startsWith(STAMP) -> stamps
                    line.startsWith(DELETED) -> deleted
                    else -> continue
                }
                val text = line.substring(if (target === stamps) STAMP.length else DELETED.length)
                val eq = text.lastIndexOf('=')
                if (eq <= 0) continue
                val at = text.substring(eq + 1).toLongOrNull() ?: continue
                target[AlarmStamps.unescape(text.substring(0, eq)) ?: continue] = at
            }
        }
        // Zeitpunkte nach [TimeBounds] geprüft (Audit KN-4): unplausibler Listen-Zeitpunkt → Nachricht verworfen.
        AlarmStamps.checked(AlarmSnapshot(Codec.decodeAlarms(alarmsText), updatedAt, stamps, deleted, perAlarm = marker >= 0))
    }.onFailure { Log.w(TAG, "Wecker-Nachricht unlesbar", it) }.getOrNull()

    /** Beginn der Zeitpunkte je Wecker in [encodeAlarms]. */
    private const val STAMPS_HEADER = "#stamps"
    private const val STAMP = "t="
    private const val DELETED = "d="

    /**
     * Ob «Verlauf löschen» um [cutMs] den wartenden Eintrag [entry] verwirft (Audit D-02): eine Nacht oder Aufzeichnung,
     * die bis dahin endet oder vorher eingereiht wurde; andere Einträge nie.
     */
    fun droppedByClear(entry: Outbox.Entry, cutMs: Long): Boolean = when {
        entry.key.startsWith(KEY_NIGHT) -> entry.createdAt < cutMs || nightEndsBy(entry.body, cutMs)
        entry.key.startsWith(KEY_RECORDING) -> entry.createdAt < cutMs || recordingEndsBy(entry.body, cutMs)
        else -> false
    }

    /** Ob der Eintrag einer Nacht ([Codec.encodeNight]) bis [cutMs] endet (geweckt); unlesbar: nein (Audit D-02). */
    fun nightEndsBy(body: String, cutMs: Long): Boolean =
        runCatching { Codec.decodeNight(body) }.getOrNull()?.let { it.wokeAt.toEpochMilli() <= cutMs } ?: false

    /** Ob der Eintrag einer Aufzeichnung ([NightRecording.encode]) bis [cutMs] beendet war; laufend oder unlesbar: nein. */
    fun recordingEndsBy(body: String, cutMs: Long): Boolean =
        NightRecording.decode(body)?.end?.let { it.toEpochMilli() <= cutMs } ?: false

    /**
     * Liest den Inhalt von [DataLayer.PATH_MSG_HOLIDAYS]: Liste und Zeitpunkt; `null`, wenn unlesbar oder der Zeitpunkt
     * unplausibel ist ([TimeBounds], Audit AN-5: sonst gewänne ein Stand «aus der Zukunft» für immer).
     */
    fun decodeHolidays(body: String): Pair<HolidayList, Long>? {
        val cut = body.indexOf('\n').takeIf { it > 0 } ?: return null
        val at = TimeBounds.checked(body.substring(0, cut).toLongOrNull()) ?: return null
        return Holidays.decode(body.substring(cut + 1)) to at
    }

    /** Liest «wert|updatedAt» (Schlafziel, Schalter); `null`, wenn unlesbar oder der Zeitpunkt unplausibel ([TimeBounds]). */
    fun decodeStamped(body: String): Pair<Int, Long>? {
        val f = body.split("|").takeIf { it.size == 2 } ?: return null
        return (f[0].toIntOrNull() ?: return null) to (TimeBounds.checked(f[1].toLongOrNull()) ?: return null)
    }

    /**
     * Eigene Pakete der früheren Wege im Data Layer ([local]: Kennung dieses Geräts) für die einmalige Umstellung
     * ([Outbox.migrateOnce]): auf der Uhr ([peer] = Handy) Nächte, Aufzeichnungen, frühere Zielzeit und
     * Aufzeichnungs-Stand mit ihrem Inhalt als Eintrag, dazu die Wecker; auf dem Handy Wecker, Lernphase, Feiertage,
     * Schlafziel und Schalter (ohne Inhalt: die Uhr bekommt den Stand über [WearSync.requestSync]).
     */
    suspend fun legacyItems(dataClient: DataClient, local: String, peer: String): List<Outbox.Legacy> {
        val now = System.currentTimeMillis()
        fun own(path: String) = Uri.Builder().scheme("wear").authority(local).path(path).build()
        val result = mutableListOf<Outbox.Legacy>()
        suspend fun collect(path: String, prefix: Boolean, entry: (DataMap) -> Outbox.Entry?) {
            val items = if (prefix) dataClient.getDataItems(own(path), DataClient.FILTER_PREFIX).await() else dataClient.getDataItems(own(path)).await()
            try {
                items.forEach { result += Outbox.Legacy(it.uri, entry(DataMapItem.fromDataItem(it).dataMap)) }
            } finally {
                items.release()
            }
        }
        fun make(key: String, path: String, body: String) = Outbox.Entry(UUID.randomUUID().toString(), key, path, peer, body, now)
        collect(DataLayer.PATH_ALARMS, prefix = false) { null }
        if (peer == DataLayer.CAPABILITY_PHONE) {
            collect(DataLayer.PATH_NIGHT_PREFIX, prefix = true) { map ->
                WearSync.readNight(map)?.let { make(nightKey(it), DataLayer.PATH_MSG_NIGHT, Codec.encodeNight(it)) }
            }
            collect(DataLayer.PATH_RECORDING_PREFIX, prefix = true) { map ->
                RecordSync.readRecording(map)?.let { make(KEY_RECORDING + it.start.toEpochMilli(), DataLayer.PATH_MSG_RECORDING, NightRecording.encode(it)) }
            }
            collect(DataLayer.PATH_ONSET_SHIFT, prefix = false) { map -> OnsetShift.read(map)?.let { make(KEY_ONSET_SHIFT, DataLayer.PATH_MSG_ONSET_SHIFT, it.encode()) } }
            collect(DataLayer.PATH_RECORD_STATUS, prefix = false) { map ->
                make(KEY_RECORD_STATUS, DataLayer.PATH_MSG_RECORD_STATUS, map.getLong(DataLayer.KEY_RECORD_SINCE).toString())
            }
        } else {
            for (path in listOf(DataLayer.PATH_LEARNING, DataLayer.PATH_HOLIDAYS, DataLayer.PATH_SLEEP_GOAL, DataLayer.PATH_AUTO_ONSET)) {
                collect(path, prefix = false) { null }
            }
        }
        return result
    }
}
