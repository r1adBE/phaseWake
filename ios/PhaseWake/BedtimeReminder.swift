import Foundation
import PhaseWakeCore
import UserNotifications

/// Bettzeit-Erinnerung: gewöhnliche Mitteilung (nicht zeitkritisch) 30 Minuten vor der Bettzeit,
/// Bettzeit = Zielzeit des nächsten Weckers minus Schlafziel. iOS weckt die App nach der Mitteilung
/// nicht auf, darum stehen die Erinnerungen für die nächsten Nächte im Voraus.
@MainActor
final class BedtimeReminder {
    /// So viele Nächte im Voraus (wie der Zweitwecker).
    private static let nightsAhead = 3
    private static func identifier(_ index: Int) -> String { "bedtime-\(index)" }

    /// Neuplanungen laufen nacheinander, nie verschränkt.
    private var queue: Task<Void, Never>?

    func requestPermission() async {
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
    }

    func reschedule(alarms: [AlarmSpec], goalMinutes: Int, enabled: Bool, holidays: Set<String>) async {
        let previous = queue
        let task = Task { @MainActor in
            await previous?.value
            await self.rescheduleNow(alarms: alarms, goalMinutes: goalMinutes, enabled: enabled, holidays: holidays)
        }
        queue = task
        await task.value
    }

    /// Je Nacht zählt nur der früheste Wecker; eine schon verpasste Erinnerung entfällt (Regel im Kern, `Bedtime.next`).
    private func rescheduleNow(alarms: [AlarmSpec], goalMinutes: Int, enabled: Bool, holidays: Set<String>) async {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: (0..<Self.nightsAhead).map(Self.identifier))
        guard enabled else { return }
        let calendar = Calendar.current
        var from = Date()
        for index in 0..<Self.nightsAhead {
            guard let reminder = Bedtime.next(alarms, from: from, calendar: calendar, goalMinutes: goalMinutes, holidays: holidays) else { break }
            // Die nächste Erinnerung liegt echt nach dieser: so folgt die nächste Nacht.
            from = reminder.at
            let content = UNMutableNotificationContent()
            content.title = L("bedtime_title")
            content.body = L("bedtime_notification",
                             Format.time(reminder.bedtime),
                             Format.duration(minutes: goalMinutes),
                             Format.time(reminder.target))
            content.sound = .default
            let parts = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: reminder.at)
            let trigger = UNCalendarNotificationTrigger(dateMatching: parts, repeats: false)
            try? await center.add(UNNotificationRequest(identifier: Self.identifier(index), content: content, trigger: trigger))
        }
    }
}
