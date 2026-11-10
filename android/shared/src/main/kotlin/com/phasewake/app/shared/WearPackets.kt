package com.phasewake.app.shared

import android.util.Log
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import com.phasewake.app.core.Codec
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.RecorderSkip

/**
 * Formate der Datenpakete im Data Layer: Uhr-Status, «Verlauf löschen», Abbruch von Probewecker/Powernap und das
 * frühere Nacht-Paket, das die Uhr nur noch bei der einmaligen Umstellung ([QueueMessages.legacyItems]) liest. Aufruf
 * über [WearSync] (dessen Begleitobjekt), z. B. `WearSync.readNight(map)`.
 */
abstract class WearPackets {
    private val tag = "WearSync"

    /**
     * Status-Paket der Uhr mit dem Verbindungs-Stand ([DataLayer.PROTOCOL]) dieser Version; [battery] und [pulse]
     * fehlen, wenn unbekannt; [recorderSkip] ist leer ohne Meldung.
     */
    fun statusRequest(ready: Boolean, battery: Int?, nowMs: Long, pulse: WatchPulse? = null, recorderSkip: RecorderSkip.Reason? = null): PutDataRequest =
        PutDataMapRequest.create(DataLayer.PATH_WATCH_STATUS).apply {
            dataMap.putBoolean(DataLayer.KEY_READY, ready)
            if (battery != null) dataMap.putInt(DataLayer.KEY_BATTERY, battery)
            dataMap.putString(DataLayer.KEY_RECORDER_SKIP, recorderSkip?.code.orEmpty())
            pulse?.let { WatchPulse.put(dataMap, it) }
            dataMap.putInt(DataLayer.KEY_PROTOCOL, DataLayer.PROTOCOL)
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

    /**
     * Grund, warum die Messung vor dem Schlafen nicht startete ([RecorderSkip.Reason]), aus dem Status-Paket; `null` ohne
     * Meldung (auch ältere Uhr), bei unbekanntem Grund oder wenn das Paket älter als [maxAgeMs] ist.
     */
    fun readRecorderSkip(map: DataMap, nowMs: Long, maxAgeMs: Long): RecorderSkip.Reason? =
        map.getString(DataLayer.KEY_RECORDER_SKIP)?.takeIf { nowMs - map.getLong(DataLayer.KEY_UPDATED_AT) <= maxAgeMs }
            ?.let { code -> RecorderSkip.Reason.entries.firstOrNull { it.code == code } }

    /** Verbindungs-Stand der Uhr aus dem Status-Paket; ohne Angabe (Uhr vor dieser Angabe) 0. */
    fun readProtocol(map: DataMap): Int = if (map.containsKey(DataLayer.KEY_PROTOCOL)) map.getInt(DataLayer.KEY_PROTOCOL) else 0

    /**
     * Ob die Uhr-App älter ist als die Handy-App (Review O-04): ihr Verbindungs-Stand [watchProtocol] ist kleiner als
     * [own]. `null` (keine Uhr bekannt) warnt nicht; ein Status ohne Angabe stammt von einer älteren Uhr.
     */
    fun watchOutdated(watchProtocol: Int?, own: Int = DataLayer.PROTOCOL): Boolean = watchProtocol != null && watchProtocol < own

    /** Zeitpunkt des Status-Pakets (ms); `null` ohne Angabe. */
    fun readSeenAt(map: DataMap): Long? = map.getLong(DataLayer.KEY_UPDATED_AT).takeIf { it > 0 }

    /** Liest ein früheres Nacht-Paket (nur für die Umstellung). @return `null`, wenn es unlesbar ist. */
    fun readNight(map: DataMap): NightRecord? = runCatching {
        Codec.decodeNight(map.getString(DataLayer.KEY_TEXT).orEmpty())
    }.onFailure { Log.w(tag, "Nacht-Paket unlesbar", it) }.getOrNull()
}
