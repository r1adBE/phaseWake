import Observation
import PhaseWakeCore
import SwiftUI

/// Zustand der Uhr-App: Wecker, nächster Plan, Verlauf.
@MainActor
@Observable
final class WatchModel {
    static let shared = WatchModel()

    let storage = PhaseWakeStorage()
    private(set) var alarms: [AlarmSpec] = []
    private(set) var now = Date()

    private init() {
        alarms = storage.snapshot().alarms
    }

    /// Nächster Plan; eine heute schon erledigte Nacht zählt nicht mehr (früh geweckt, Zielzeit liegt noch vorn).
    var plan: WakePlan? { AlarmSchedule.earliestPlan(alarms, now: max(now, storage.doneUntil), calendar: .current) }

    func refresh() {
        now = Date()
        alarms = storage.snapshot().alarms
    }

    /// Ein/Aus auf der Uhr; Änderung geht ans iPhone und plant neu.
    func setEnabled(_ alarm: AlarmSpec, _ enabled: Bool) {
        let snapshot = storage.mutate { list in list.map { $0.id == alarm.id ? $0.with(enabled: enabled) : $0 } }
        alarms = snapshot.alarms
        WatchConnectivityBridge.shared.publish(snapshot)
        reschedule()
    }

    /// Vom iPhone empfangene Wecker übernehmen, falls neuer.
    func applyRemote(_ snapshot: AlarmSnapshot) {
        guard storage.applyRemote(snapshot) else { return }
        alarms = snapshot.alarms
        reschedule()
    }

    /// Plant die Smart-Alarm-Sitzung für das nächste Fenster (geht nur, solange die App aktiv ist).
    func reschedule() {
        refresh()
        SmartAlarm.shared.schedule(plan)
    }
}
