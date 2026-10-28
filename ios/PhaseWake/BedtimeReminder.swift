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

    func reschedule(alarms: [AlarmSpec], doneUntil: [String: Date], goalMinutes: Int, enabled: Bool) async {
        let previous = queue
        let task = Task { @MainActor in
            await previous?.value
            await self.rescheduleNow(alarms: alarms, doneUntil: doneUntil, goalMinutes: goalMinutes, enabled: enabled)
        }
        queue = task
        await task.value
    }

    private func rescheduleNow(alarms: [AlarmSpec], doneUntil: [String: Date], goalMinutes: Int, enabled: Bool) async {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: (0..<Self.nightsAhead).map(Self.identifier))
        guard enabled else { return }
        let now = Date()
        let calendar = Calendar.current
        var from = now
        var skip = Set<String>()
        var lastNight: String?
        var index = 0
        // Je Nacht zählt nur der früheste Wecker; begrenzt, falls viele Wecker am selben Morgen stehen.
        for _ in 0..<(Self.nightsAhead * 8) {
            guard index < Self.nightsAhead,
                  let plan = AlarmSchedule.earliestPlan(alarms.filter { !skip.contains($0.id) }, now: from, doneUntil: doneUntil, calendar: calendar)
            else { break }
            from = plan.target
            // Einmaliger Wecker: nur sein nächstes Vorkommen.
            if alarms.first(where: { $0.id == plan.alarmId })?.isOneShot == true { skip.insert(plan.alarmId) }
            let night = SleepNotes.nightDate(plan.windowStart, calendar: calendar)
            guard night != lastNight else { continue }
            lastNight = night
            let at = Bedtime.reminderAt(nextAlarmTarget: plan.target, goalMinutes: goalMinutes)
            // Schon vorbei: diese Nacht ohne Erinnerung.
            guard at > now else { continue }
            let content = UNMutableNotificationContent()
            content.title = L("bedtime_title")
            content.body = L("bedtime_notification",
                             Format.time(Bedtime.bedtime(nextAlarmTarget: plan.target, goalMinutes: goalMinutes)),
                             Format.duration(minutes: goalMinutes),
                             Format.time(plan.target))
            content.sound = .default
            let parts = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: at)
            let trigger = UNCalendarNotificationTrigger(dateMatching: parts, repeats: false)
            try? await center.add(UNNotificationRequest(identifier: Self.identifier(index), content: content, trigger: trigger))
            index += 1
        }
    }
}
