package com.phasewake.app.shared

import com.google.android.gms.wearable.DataMap

/**
 * Pulsrechte der Uhr im Status-Paket ([DataLayer.PATH_WATCH_STATUS]): [heartRate] (Puls bei offener App) und
 * [background] (Puls im Hintergrund, nötig für die Messung in der Nacht). Ältere Uhren melden sie nicht.
 */
data class WatchPulse(val heartRate: Boolean, val background: Boolean) {
    companion object {
        /** Legt [pulse] in [map] ab ([DataLayer.KEY_PULSE], [DataLayer.KEY_PULSE_BACKGROUND]). */
        fun put(map: DataMap, pulse: WatchPulse) {
            map.putBoolean(DataLayer.KEY_PULSE, pulse.heartRate)
            map.putBoolean(DataLayer.KEY_PULSE_BACKGROUND, pulse.background)
        }

        /** Pulsrechte aus dem Status-Paket; `null` ohne Angabe (ältere Uhr), dann warnt das Handy nicht. */
        fun read(map: DataMap): WatchPulse? =
            if (map.containsKey(DataLayer.KEY_PULSE) && map.containsKey(DataLayer.KEY_PULSE_BACKGROUND)) {
                WatchPulse(map.getBoolean(DataLayer.KEY_PULSE), map.getBoolean(DataLayer.KEY_PULSE_BACKGROUND))
            } else {
                null
            }

        /**
         * Ob der Nacht-Check warnen soll: Die Uhr hat gemeldet, dass sie den Puls im Hintergrund nicht messen darf.
         * Ohne Meldung ([pulse] `null`, ältere Uhr) keine Warnung.
         */
        fun warns(pulse: WatchPulse?): Boolean = pulse?.background == false
    }
}

/** Handy: zuletzt gemeldete Pulsrechte der Uhr; `null`, wenn unbekannt (ältere Uhr, kein Status, keine Wear-Dienste). */
suspend fun WearSync.watchPulse(): WatchPulse? = readStatus { WatchPulse.read(it) }
