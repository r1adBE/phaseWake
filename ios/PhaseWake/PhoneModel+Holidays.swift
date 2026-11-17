import PhaseWakeCore
import SwiftUI

// MARK: - Feiertage und «Nächstes Mal überspringen»

extension PhoneModel {
    /// Feiertage geändert (Wahl, neuer Tag): merken, an die Uhr, Sicherung und Erinnerung neu planen.
    func holidaysChanged() {
        storage.holidays = holidays.list
        publishToWatch()
        Task { await backup.reschedule(alarms) }
        Task { await replanBedtime() }
    }

    /// «Nächstes Mal überspringen» bzw. rückgängig; geht an die Uhr wie jede Änderung. Nach früh geweckter Nacht
    /// (Zielzeit noch vorn) trifft es den nächsten Termin, nicht den erledigten heutigen.
    func setSkipped(_ alarm: AlarmSpec, _ skipped: Bool) {
        let updated = skipped
            ? AlarmSchedule.skipNext(alarm, now: Date(), doneUntil: doneUntil[alarm.id], calendar: .gregorianLocal, holidays: holidays.dates)
            : AlarmSchedule.undoSkip(alarm)
        upsert(updated)
    }
}
