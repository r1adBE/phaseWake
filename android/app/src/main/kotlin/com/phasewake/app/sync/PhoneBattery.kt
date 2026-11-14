package com.phasewake.app.sync

import android.content.Context
import com.phasewake.app.PhaseWakeApp
import com.phasewake.app.core.BatteryTrack
import com.phasewake.app.core.NightRecord
import com.phasewake.app.shared.BatteryNight
import com.phasewake.app.shared.RingMessage
import java.time.Instant

/**
 * Akku je Nacht am Handy (r1AD 14.11.2026). Das Handy läuft nachts oft nicht; es misst darum bei Ereignissen, die es ohnehin
 * bekommt: Beginn, wenn die Uhr eine Nacht zu messen beginnt ([com.phasewake.app.shared.DataLayer.PATH_NIGHT_BEGIN]), Ende
 * beim endgültigen «stop» der Uhr oder, falls das fehlt, wenn ihre Nacht gleich nach dem Ende ankommt. Kommt eine Meldung
 * verspätet an, gilt «jetzt» nicht als Beginn bzw. Ende: dann kein Wert (statt eines falschen). Ladebeginn und -ende hält
 * [BatteryNight] fest.
 */
internal object PhoneBattery {
    /** Die Uhr beginnt eine Nacht: ab jetzt messen, nur wenn die Meldung frisch ankam ([RingMessage.isStale]). */
    fun nightBegun(context: Context, message: RingMessage, nowMs: Long = System.currentTimeMillis()) {
        if (!message.isCurrent() || message.isStale(nowMs)) {
            BatteryNight.log("night battery begin ignored (late)")
            return
        }
        BatteryNight.begin(context)
    }

    /**
     * Endgültiges «stop» der Uhr (frisch angekommen): Ende der Nacht am Handy. Ist ihre Nacht schon gespeichert, kommt der
     * Akku gleich dazu, sonst wartet er auf sie ([withPhone]).
     */
    suspend fun stopped(app: PhaseWakeApp) {
        val track = BatteryNight.end(app) ?: return
        val night = app.nights.load().lastOrNull { it.phoneBattery == null && BatteryTrack.belongsTo(track, it) }
        if (night != null) app.nights.add(night.copy(phoneBattery = track)) else BatteryNight.keepDone(app, track)
    }

    /**
     * Nacht der Uhr kommt an: Akku des Handys dazu, wenn er zu ihr gehört ([BatteryTrack.belongsTo]): der beim «stop»
     * gemerkte, sonst – nur wenn sie gleich nach ihrem Ende ankommt ([BatteryTrack.arrivedFresh]) – jetzt beendet.
     */
    fun withPhone(context: Context, night: NightRecord, now: Instant = Instant.now()): NightRecord {
        if (night.phoneBattery != null) return night
        val track = BatteryNight.takeDone(context) { BatteryTrack.belongsTo(it, night) }
            ?: if (BatteryTrack.arrivedFresh(night, now)) BatteryNight.end(context)?.takeIf { BatteryTrack.belongsTo(it, night) } else null
        return if (track != null) night.copy(phoneBattery = track) else night
    }
}
