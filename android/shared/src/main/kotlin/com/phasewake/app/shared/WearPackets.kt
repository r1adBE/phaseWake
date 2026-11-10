package com.phasewake.app.shared

import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import com.phasewake.app.core.Codec
import com.phasewake.app.core.HolidayList
import com.phasewake.app.core.Holidays
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.SleepGoal

/**
 * Formate der Datenpakete im Data Layer: Uhr-Status, «Verlauf löschen», Abbruch von Probewecker/Powernap und die
 * früheren Pakete (Wecker, Nächte, Feiertage, Schlafziel, Schalter), die das Handy von älteren Uhren liest bzw. für
 * sie noch schreibt. Aufruf über [WearSync] (dessen Begleitobjekt), z. B. `WearSync.readNight(map)`.
 */
abstract class WearPackets {
    private val tag = "WearSync"


    /** Liest ein empfangenes Wecker-Paket. @return `null`, wenn es unlesbar ist. */
    fun readAlarms(map: DataMap): AlarmSnapshot? = runCatching {
        AlarmSnapshot(Codec.decodeAlarms(map.getString(DataLayer.KEY_TEXT).orEmpty()), map.getLong(DataLayer.KEY_UPDATED_AT))
    }.onFailure { Log.w(tag, "Wecker-Paket unlesbar", it) }.getOrNull()

    /** Paket mit allen Weckern (dringend: die Uhr soll gleich neu planen). */
    fun alarmsRequest(snapshot: AlarmSnapshot): PutDataRequest = PutDataMapRequest.create(DataLayer.PATH_ALARMS).apply {
        dataMap.putString(DataLayer.KEY_TEXT, Codec.encodeAlarms(snapshot.alarms))
        dataMap.putLong(DataLayer.KEY_UPDATED_AT, snapshot.updatedAt)
    }.asPutDataRequest().setUrgent()

    /** Paket einer Nacht unter «Präfix + Fensterbeginn-Kennung» (siehe [publishNight]). */
    fun nightRequest(night: NightRecord): PutDataRequest =
        PutDataMapRequest.create(DataLayer.PATH_NIGHT_PREFIX + night.windowStart.toEpochMilli() + "-" + Uri.encode(night.alarmId)).apply {
            dataMap.putString(DataLayer.KEY_TEXT, Codec.encodeNight(night))
        }.asPutDataRequest()

    /**
     * Status-Paket der Uhr mit den Feldern je Weckerzeile und dem Verbindungs-Stand ([DataLayer.PROTOCOL]) dieser
     * Version; [battery] und [pulse] fehlen, wenn unbekannt.
     */
    fun statusRequest(ready: Boolean, battery: Int?, nowMs: Long, pulse: WatchPulse? = null): PutDataRequest = PutDataMapRequest.create(DataLayer.PATH_WATCH_STATUS).apply {
        dataMap.putBoolean(DataLayer.KEY_READY, ready)
        if (battery != null) dataMap.putInt(DataLayer.KEY_BATTERY, battery)
        pulse?.let { WatchPulse.put(dataMap, it) }
        dataMap.putInt(DataLayer.KEY_PROTOCOL, DataLayer.PROTOCOL)
        dataMap.putInt(DataLayer.KEY_ALARM_FIELDS, Codec.ALARM_FIELDS)
        dataMap.putLong(DataLayer.KEY_UPDATED_AT, nowMs)
    }.asPutDataRequest().setUrgent()

    /** Paket «Probewecker oder Powernap abgebrochen», siehe [DataLayer.PATH_TRANSIENT_CANCELLED]. */
    fun transientCancelRequest(alarmId: String, nowMs: Long): PutDataRequest =
        PutDataMapRequest.create(DataLayer.PATH_TRANSIENT_CANCELLED).apply {
            dataMap.putString(DataLayer.KEY_TEXT, alarmId)
            dataMap.putLong(DataLayer.KEY_UPDATED_AT, nowMs)
        }.asPutDataRequest().setUrgent()

    /** Kennung aus dem Abbruch-Paket; `null`, wenn sie fehlt oder leer ist. */
    fun readTransientCancel(map: DataMap): String? = map.getString(DataLayer.KEY_TEXT)?.takeIf { it.isNotEmpty() }

    /** Feiertags-Paket für die Uhren. */
    fun holidaysRequest(list: HolidayList, updatedAt: Long): PutDataRequest = PutDataMapRequest.create(DataLayer.PATH_HOLIDAYS).apply {
        dataMap.putString(DataLayer.KEY_TEXT, Holidays.encode(list))
        dataMap.putLong(DataLayer.KEY_UPDATED_AT, updatedAt)
    }.asPutDataRequest().setUrgent()

    /** Schlafziel-Paket für die Uhren (dringend wie die Feiertage: ändert sich selten, soll aber gleich ankommen). */
    fun sleepGoalRequest(minutes: Int, updatedAt: Long): PutDataRequest = PutDataMapRequest.create(DataLayer.PATH_SLEEP_GOAL).apply {
        dataMap.putInt(DataLayer.KEY_SLEEP_GOAL, minutes)
        dataMap.putLong(DataLayer.KEY_UPDATED_AT, updatedAt)
    }.asPutDataRequest().setUrgent()

    /** Liest ein Schlafziel-Paket: Minuten (mit [SleepGoal.clamp]; ohne Angabe der Standard) und Zeitpunkt. */
    fun readSleepGoal(map: DataMap): Pair<Int, Long> =
        SleepGoal.clamp(map.getInt(DataLayer.KEY_SLEEP_GOAL, SleepGoal.DEFAULT_MINUTES)) to map.getLong(DataLayer.KEY_UPDATED_AT)

    /** Paket des Schalters der Einschlaf-Messung für die Uhren (dringend wie das Schlafziel). */
    fun autoOnsetRequest(enabled: Boolean, updatedAt: Long): PutDataRequest = PutDataMapRequest.create(DataLayer.PATH_AUTO_ONSET).apply {
        dataMap.putBoolean(DataLayer.KEY_AUTO_ONSET, enabled)
        dataMap.putLong(DataLayer.KEY_UPDATED_AT, updatedAt)
    }.asPutDataRequest().setUrgent()

    /** Liest ein Paket des Schalters: an oder aus (ohne Angabe «an») und Zeitpunkt. */
    fun readAutoOnset(map: DataMap): Pair<Boolean, Long> =
        map.getBoolean(DataLayer.KEY_AUTO_ONSET, true) to map.getLong(DataLayer.KEY_UPDATED_AT)

    /** Paket «Verlauf löschen» mit Zeitpunkt. */
    fun clearRequest(nowMs: Long): PutDataRequest = PutDataMapRequest.create(DataLayer.PATH_CLEAR_HISTORY).apply {
        dataMap.putLong(DataLayer.KEY_UPDATED_AT, nowMs)
    }.asPutDataRequest().setUrgent()

    /** Bereit-Meldung aus dem Status-Paket. */
    fun readReady(map: DataMap): Boolean = map.getBoolean(DataLayer.KEY_READY)

    /** Akkustand aus dem Status-Paket; `null` ohne Angabe oder älter als [maxAgeMs]. */
    fun readBattery(map: DataMap, nowMs: Long, maxAgeMs: Long): Int? =
        map.takeIf { it.containsKey(DataLayer.KEY_BATTERY) && nowMs - it.getLong(DataLayer.KEY_UPDATED_AT) <= maxAgeMs }
            ?.getInt(DataLayer.KEY_BATTERY)

    /** Felder je Weckerzeile aus dem Status-Paket; ohne Angabe (ältere Uhr) 0. */
    fun readAlarmFields(map: DataMap): Int = if (map.containsKey(DataLayer.KEY_ALARM_FIELDS)) map.getInt(DataLayer.KEY_ALARM_FIELDS) else 0

    /** Verbindungs-Stand der Uhr aus dem Status-Paket; ohne Angabe (Uhr vor dieser Angabe) 0. */
    fun readProtocol(map: DataMap): Int = if (map.containsKey(DataLayer.KEY_PROTOCOL)) map.getInt(DataLayer.KEY_PROTOCOL) else 0

    /**
     * Ob die Uhr-App älter ist als die Handy-App (Review O-04): ihr Verbindungs-Stand [watchProtocol] ist kleiner als
     * [own]. `null` (keine Uhr bekannt) warnt nicht; ein Status ohne Angabe stammt von einer älteren Uhr.
     */
    fun watchOutdated(watchProtocol: Int?, own: Int = DataLayer.PROTOCOL): Boolean = watchProtocol != null && watchProtocol < own

    /** Zeitpunkt des Status-Pakets (ms); `null` ohne Angabe. */
    fun readSeenAt(map: DataMap): Long? = map.getLong(DataLayer.KEY_UPDATED_AT).takeIf { it > 0 }

    /** Liest ein Feiertags-Paket: Liste (tolerant, siehe [Holidays.decode]) und Zeitpunkt. */
    fun readHolidays(map: DataMap): Pair<HolidayList, Long> =
        Holidays.decode(map.getString(DataLayer.KEY_TEXT)) to map.getLong(DataLayer.KEY_UPDATED_AT)

    /** Liest ein empfangenes Nacht-Paket. @return `null`, wenn es unlesbar ist. */
    fun readNight(map: DataMap): NightRecord? = runCatching {
        Codec.decodeNight(map.getString(DataLayer.KEY_TEXT).orEmpty())
    }.onFailure { Log.w(tag, "Nacht-Paket unlesbar", it) }.getOrNull()
}
