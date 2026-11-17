import PhaseWakeCore
import SwiftUI

// MARK: - Wochenrückblick

extension PhoneModel {
    func setWeeklyReview(_ enabled: Bool) {
        weeklyReview = enabled
        SettingsFile.phone.set(enabled, forKey: Self.weeklyKey)
        Task {
            if enabled { await bedtime.requestPermission() }
            await replanWeekly()
        }
    }

    /// Plant die Mitteilung zum nächsten Sonntag 18 Uhr mit dem aktuellen Stand neu.
    func replanWeekly() async {
        guard stateLoaded || catchUpState() else { return }
        await weekly.reschedule(model: self)
    }
}
