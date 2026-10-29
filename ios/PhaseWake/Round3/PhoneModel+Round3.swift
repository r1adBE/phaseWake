import PhaseWakeCore
import SwiftUI
import UIKit

// MARK: - Runde 3: Feiertage, Probewecker, Powernap, Start

extension PhoneModel {
    /// Weckart zur Kennung aus «klingeln»: gespeicherter Wecker oder der laufende Probewecker/Powernap.
    func ringSpec(_ alarmId: String?) -> AlarmSpec? {
        alarms.first { $0.id == alarmId } ?? transient.spec(for: alarmId)
    }

    /// Feiertage geändert (Wahl, neuer Tag): merken, an die Uhr, Sicherung und Erinnerung neu planen.
    func holidaysChanged() {
        storage.holidays = holidays.list
        publishToWatch()
        Task { await backup.reschedule(alarms) }
        Task { await replanBedtime() }
    }

    /// Wecker, Feiertage und Schlafziel an die Uhr; der Anwendungskontext ersetzt jeweils den ganzen Stand.
    func publishToWatch(_ snapshot: AlarmSnapshot? = nil) {
        PhoneConnectivity.shared.publish(snapshot ?? storage.snapshot(), holidays: holidays.list, sleepGoal: sleepGoal)
    }

    /// «Nächstes Mal überspringen» bzw. rückgängig; geht an die Uhr wie jede Änderung. Nach früh geweckter Nacht
    /// (Zielzeit noch vorn) trifft es den nächsten Termin, nicht den erledigten heutigen.
    func setSkipped(_ alarm: AlarmSpec, _ skipped: Bool) {
        let updated = skipped
            ? AlarmSchedule.skipNext(alarm, now: Date(), doneUntil: doneUntil[alarm.id], calendar: .current, holidays: holidays.dates)
            : AlarmSchedule.undoSkip(alarm)
        upsert(updated)
    }

    /// Beim Start: Ereignis fürs Protokoll, Feiertage laden, dann alles planen.
    func startUp() async {
        let device = UIDevice.current
        device.isBatteryMonitoringEnabled = true
        DiagStore.shared.log(.boot, "app start")
        if device.batteryLevel >= 0 { DiagStore.shared.log(.battery, "\(Int((device.batteryLevel * 100).rounded())) %") }
        if await holidays.load() {
            holidaysChanged()
        } else {
            await backup.reschedule(alarms)
            await replanBedtime()
        }
        await replanWeekly()
        await transient.restore()
    }

    /// Beim Öffnen: neuer Tag → Feiertage neu auflösen.
    func becameActive() {
        if holidays.refresh() { holidaysChanged() }
    }
}
