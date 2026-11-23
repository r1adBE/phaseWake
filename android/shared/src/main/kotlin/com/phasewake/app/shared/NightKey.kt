package com.phasewake.app.shared

import com.phasewake.app.core.FeelingAsk
import com.phasewake.app.core.NightKeep
import com.phasewake.app.core.NightRecord
import java.time.Instant

/**
 * Eine Nacht ist eindeutig über Wecker und Fensterbeginn: Zwei Wecker mit genau gleichem Fenster
 * sind zwei Nächte. Bestehende Verläufe tragen die Kennung schon und bleiben so lesbar.
 */
internal fun NightRecord.isNight(alarmId: String, windowStart: Instant): Boolean =
    this.alarmId == alarmId && this.windowStart == windowStart

/**
 * Ersetzt dieselbe Nacht oder hängt sie an; ein schon gespeicherter Akkustand (z. B. der des Handys) und die
 * Einschlafzeit samt Beginn bleiben, wenn die neue keine hat (die Uhr schickt dieselbe Nacht noch einmal, eine ältere
 * Uhr-App ohne Einschlafzeit). Ein schon angegebenes Befinden bleibt immer; das der neuen gilt nur ohne gespeichertes
 * ([FeelingAsk.merged]: die Wahl auf der Uhr nach dem Wecken überschreibt keine Eingabe am Handy). Danach gekürzt nach
 * [keep] (Handy oder Uhr).
 */
internal fun mergeNight(
    current: List<NightRecord>,
    night: NightRecord,
    keep: NightKeep,
    now: Instant,
): List<NightRecord> {
    val same: (NightRecord) -> Boolean = { it.isNight(night.alarmId, night.windowStart) }
    val old = current.firstOrNull(same)
    val merged = night.copy(
        feeling = FeelingAsk.merged(stored = old?.feeling, incoming = night.feeling),
        watchBattery = night.watchBattery ?: old?.watchBattery,
        phoneBattery = night.phoneBattery ?: old?.phoneBattery,
        // Beginn und Einschlafzeit nur zusammen übernehmen (gehören zur selben Messung).
        bedAt = if (night.asleepAt != null) night.bedAt else old?.bedAt,
        asleepAt = night.asleepAt ?: old?.asleepAt,
    )
    return keep.trim(current.filterNot(same) + merged, now)
}
