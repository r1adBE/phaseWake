package com.phasewake.app.shared

import com.phasewake.app.core.NightHistory
import com.phasewake.app.core.NightRecord
import java.time.Instant

/**
 * Eine Nacht ist eindeutig über Wecker und Fensterbeginn: Zwei Wecker mit genau gleichem Fenster
 * sind zwei Nächte. Bestehende Verläufe tragen die Kennung schon und bleiben so lesbar.
 */
internal fun NightRecord.isNight(alarmId: String, windowStart: Instant): Boolean =
    this.alarmId == alarmId && this.windowStart == windowStart

/** Ersetzt dieselbe Nacht oder hängt sie an; ein schon angegebenes Befinden bleibt, wenn die neue keines hat. */
internal fun mergeNight(current: List<NightRecord>, night: NightRecord): List<NightRecord> {
    val same: (NightRecord) -> Boolean = { it.isNight(night.alarmId, night.windowStart) }
    val feeling = night.feeling ?: current.firstOrNull(same)?.feeling
    return NightHistory.trim(current.filterNot(same) + night.copy(feeling = feeling))
}
