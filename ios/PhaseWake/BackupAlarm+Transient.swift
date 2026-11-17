import Foundation
import PhaseWakeCore

/// Sicherung des Probeweckers bzw. Powernaps (aus `BackupAlarm.swift` hierher, Grenze von 400 Zeilen).
extension BackupAlarm {
    // MARK: Probewecker und Powernap

    /// Sicherung für einen Probewecker oder Powernap: klingelt kurz nach dessen Zielzeit, falls sich die Uhr
    /// nicht meldet (wie die Sicherung einer Nacht). Ohne iPhone-Wecker («aus») keine.
    /// - Returns: Kennung der Sicherung, oder `nil` ohne Sicherung.
    @discardableResult
    func scheduleTransient(_ transient: TransientPlan) async -> UUID? {
        await cancelTransient()
        guard transient.spec.phoneBackup else { return nil }
        let id = UUID()
        settings.set(id.uuidString, forKey: Self.transientKey)
        setNight(RingNight(alarmId: transient.plan.alarmId, target: transient.plan.target), for: id)
        let snoozes = Transient.allowsSnooze(transient.plan.alarmId) ? Snooze.maxCount : 0
        await schedule(id: id, at: transient.plan.target.addingTimeInterval(currentGrace), snoozesLeft: snoozes,
                       tone: WakeTone(rawValue: transient.spec.sound))
        DiagStore.shared.log(.scheduled,
                             "phone backup \(transient.kind.rawValue) \(Format.logTime(transient.plan.target))")
        return id
    }

    /// Sicherung des Probeweckers bzw. Powernaps verwerfen (die Uhr hat geklingelt, gestoppt oder abgebrochen).
    func cancelTransient() async {
        guard let id = storedId(Self.transientKey) else { return }
        settings.removeObject(forKey: Self.transientKey)
        cancelAlarm(id)
    }
}
