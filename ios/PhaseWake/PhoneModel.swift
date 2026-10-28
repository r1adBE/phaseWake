import Observation
import PhaseWakeCore
import SwiftUI

/// Zustand der iPhone-App: Wecker, Verlauf, Abgleich mit der Uhr.
@MainActor
@Observable
final class PhoneModel {
    static let shared = PhoneModel()

    let storage = PhaseWakeStorage()
    let backup = BackupAlarm()
    let health = HealthSleep()
    private(set) var alarms: [AlarmSpec] = []
    private(set) var nights: [NightRecord] = []
    /// Zeitpunkt, zu dem die Uhr zuletzt geweckt hat – der Nachttisch-Modus holt dann das Licht nach.
    private(set) var lastRing: Date?

    private init() {
        alarms = storage.snapshot().alarms
        nights = storage.nights()
    }

    func upsert(_ spec: AlarmSpec) {
        change { list in
            var list = list
            if let i = list.firstIndex(where: { $0.id == spec.id }) { list[i] = spec } else { list.append(spec) }
            return list
        }
    }

    func remove(_ id: String) { change { $0.filter { $0.id != id } } }

    func setEnabled(_ alarm: AlarmSpec, _ enabled: Bool) {
        change { list in list.map { $0.id == alarm.id ? $0.with(enabled: enabled) : $0 } }
    }

    func applyRemote(_ snapshot: AlarmSnapshot) {
        guard storage.applyRemote(snapshot) else { return }
        alarms = snapshot.alarms
        Task { await backup.reschedule(alarms) }
    }

    func add(night: NightRecord) {
        storage.add(night)
        nights = storage.nights()
    }

    func setFeeling(_ feeling: Int, for night: NightRecord) {
        storage.setFeeling(feeling, windowStart: night.windowStart)
        nights = storage.nights()
    }

    func watchRang() { lastRing = Date() }

    func clearHistory() {
        storage.clearNights()
        nights = []
        PhoneConnectivity.shared.send(command: SyncKeys.clearHistory)
    }

    private func change(_ edit: ([AlarmSpec]) -> [AlarmSpec]) {
        let snapshot = storage.mutate(edit)
        alarms = snapshot.alarms
        PhoneConnectivity.shared.publish(snapshot)
        Task { await backup.reschedule(alarms) }
    }
}
