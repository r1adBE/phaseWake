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
    /// Probewecker oder Powernap, der statt des nächsten Weckers gilt; nie im Verlauf (`Transient`).
    private(set) var transient: TransientPlan?
    private static let transientKey = "transientPlan"

    private init() {
        alarms = storage.snapshot().alarms
        transient = UserDefaults.standard.string(forKey: Self.transientKey).flatMap { try? Transient.decode($0) }
        // Zeitzone oder Uhrzeit geändert (Reise): Sitzung und Rückfall hängen an festen Zeitpunkten, also neu planen.
        for name in [Notification.Name.NSSystemTimeZoneDidChange, .NSSystemClockDidChange] {
            _ = NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { _ in
                Task { @MainActor in WatchModel.shared.reschedule() }
            }
        }
    }

    /// Nächster Plan: ein vorübergehender (Probewecker, Powernap), solange er noch wecken kann; sonst der nächste
    /// Wecker. Eine heute schon erledigte Nacht zählt nicht mehr (früh geweckt, Zielzeit liegt noch vorn).
    var plan: WakePlan? {
        if let transient, now < transient.plan.target.addingTimeInterval(SmartAlarm.fallbackDelay) { return transient.plan }
        return AlarmSchedule.earliestPlan(alarms, now: now, doneUntil: storage.doneUntil, calendar: .current, holidays: storage.holidays.dates)
    }

    /// Weckart zur Kennung: gespeicherter Wecker oder der vorübergehende Plan.
    func spec(for id: String?) -> AlarmSpec? {
        alarms.first { $0.id == id } ?? (transient?.spec.id == id ? transient?.spec : nil)
    }

    func refresh() {
        now = Date()
        alarms = storage.snapshot().alarms
        // Abgelaufen (2 h nach der Zielzeit): verwerfen.
        if let transient, !Transient.isActive(transient, now: now) { setTransient(nil) }
    }

    /// Probewecker oder Powernap übernehmen (vom iPhone oder hier gestartet) und gleich planen.
    func startTransient(_ plan: TransientPlan) {
        setTransient(plan)
        DiagStore.shared.log(plan.kind == .test ? .test : .nap, "received \(Format.time(plan.plan.target))")
        reschedule()
    }

    /// Vorübergehenden Plan verwerfen (beendet, abgebrochen oder vom iPhone zurückgenommen).
    func endTransient(_ id: String? = nil) {
        guard let transient, id == nil || transient.plan.alarmId == id else { return }
        setTransient(nil)
    }

    /// Powernap von der Uhr aus: gleich planen (die App ist offen, darum klappt die Sitzung) und dem iPhone melden.
    func startNap(minutes: Int) {
        let next = AlarmSchedule.earliestPlan(alarms, now: Date(), calendar: .current)
        let nap = Transient.nap(base: alarms.first { $0.id == next?.alarmId }, now: Date(), calendar: .current,
                                minutes: minutes, uuid: UUID().uuidString.lowercased())
        startTransient(nap)
        WatchConnectivityBridge.shared.send(command: SyncKeys.nap, extra: [SyncKeys.payload: Transient.encode(nap)])
    }

    /// Powernap vorzeitig beenden (vor dem Klingeln); das iPhone verwirft seine Sicherung.
    func stopNap() {
        guard let transient, transient.kind == .nap else { return }
        endTransient()
        DiagStore.shared.log(.nap, "cancelled")
        WatchConnectivityBridge.shared.send(command: SyncKeys.transientCancel, extra: [SyncKeys.id: transient.plan.alarmId])
        reschedule()
    }

    private func setTransient(_ plan: TransientPlan?) {
        transient = plan
        UserDefaults.standard.set(plan.map(Transient.encode), forKey: Self.transientKey)
    }

    /// Feiertage vom iPhone übernehmen und neu planen, falls sie sich geändert haben.
    func applyHolidays(_ list: HolidayList) {
        guard storage.holidays != list else { return }
        storage.holidays = list
        reschedule()
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
        DiagStore.shared.log(.alarmsReceived, "\(snapshot.alarms.count) alarms")
        reschedule()
    }

    /// Plant die Smart-Alarm-Sitzung für das nächste Fenster (geht nur, solange die App aktiv ist).
    func reschedule() {
        refresh()
        SmartAlarm.shared.schedule(plan)
        let armed: Bool
        switch SmartAlarm.shared.phase {
        case .idle, .waiting: armed = false
        case .scheduled, .measuring, .ringing, .snoozed: armed = true
        }
        WatchDiagStatus.shared.update(plan: plan, armed: armed)
        // Komplikation auf dem Zifferblatt sofort aktualisieren.
        WidgetCenter.shared.reloadAllTimelines()
    }
}
