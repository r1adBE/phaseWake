import Observation
import PhaseWakeCore
import SwiftUI
import WidgetKit

/// Zustand der Uhr-App: Wecker, nächster Plan, Verlauf.
@MainActor
@Observable
final class WatchModel {
    static let shared = WatchModel()

    let storage = PhaseWakeStorage.watchShared
    private(set) var alarms: [AlarmSpec] = []
    private(set) var now = Date()

    private init() {
        alarms = storage.snapshot().alarms
        // Zeitzone oder Uhrzeit geändert (Reise): Sitzung und Rückfall hängen an festen Zeitpunkten, also neu planen.
        for name in [Notification.Name.NSSystemTimeZoneDidChange, .NSSystemClockDidChange] {
            _ = NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { _ in
                Task { @MainActor in WatchModel.shared.reschedule() }
            }
        }
    }

    /// Nächster Plan; eine heute schon erledigte Nacht zählt nicht mehr (früh geweckt, Zielzeit liegt noch vorn).
    var plan: WakePlan? { AlarmSchedule.earliestPlan(alarms, now: now, doneUntil: storage.doneUntil, calendar: .current) }

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

    /// Höchstens so viele Wecker wie am iPhone.
    static let maxAlarms = 8
    var canAddAlarm: Bool { alarms.count < Self.maxAlarms }

    /// Wecker auf der Uhr anlegen oder ändern; geht ans iPhone und plant neu (zuletzt gespeichert gewinnt).
    /// Ein bestehender Wecker behält alles, was die Uhr nicht bearbeitet (siehe `AlarmSpec.editedOnWatch`).
    func save(_ draft: AlarmSpec) {
        let snapshot = storage.mutate { list in
            var list = list
            if let i = list.firstIndex(where: { $0.id == draft.id }) {
                list[i] = list[i].editedOnWatch(from: draft)
            } else if list.count < Self.maxAlarms {
                list.append(draft)
            }
            return list
        }
        alarms = snapshot.alarms
        WatchConnectivityBridge.shared.publish(snapshot)
        reschedule()
    }

    /// Wecker auf der Uhr löschen; geht ans iPhone und plant neu.
    func remove(_ id: String) {
        let snapshot = storage.mutate { list in list.filter { $0.id != id } }
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
        // Komplikation auf dem Zifferblatt sofort aktualisieren.
        WidgetCenter.shared.reloadAllTimelines()
    }
}
