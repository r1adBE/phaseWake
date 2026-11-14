package com.phasewake.app.ring

import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.phoneApp
import com.phasewake.app.shared.AlarmStamps
import java.time.Instant

// Einmal-Wecker nach seiner Nacht ausschalten (aus BackupAlarm.kt ausgelagert, Zeilengrenze).

/**
 * Schaltet einen noch eingeschalteten Einmal-Wecker aus (über die Uhr-Synchronisation). Nur die Fassung, die
 * geklingelt hat (Fix G-1/S-1, [AlarmStamps.rang]): Mit Zielzeit [target] muss sie dessen Weckzeit dieser Nacht sein
 * (früher geweckt: gemeldete Ausnahme [onsetShift]); ohne (Ende des Schlummerns) gilt der gespeicherte Wecker. Der
 * Zeitpunkt wird der der bisherigen Fassung + 1 ms: Eine neuere Änderung auf der Uhr gewinnt beim Abgleich.
 */
internal suspend fun BackupAlarm.disableOneShot(alarmId: String?, target: Instant? = null) {
    val spec = alarms.current().alarms.firstOrNull { it.id == alarmId } ?: return
    if (!spec.isOneShot || !spec.enabled) return
    val shifted = target != null && onsetShift()?.let { it.alarmId == spec.id && it.target == target.toEpochMilli() } == true
    val rang: (AlarmSpec) -> Boolean = { target == null || AlarmStamps.rang(it, null, target, zone, shifted) }
    if (!rang(spec)) return
    context.phoneApp.afterAlarmsChanged { alarms.disableAfterRing(spec.id, rang) ?: alarms.current() }
}
