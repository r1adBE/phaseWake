import Foundation
import UserNotifications

/// Auslöser für Weck-Mitteilungen (Zweitwecker vor iOS 26, Rückfall der Apple Watch).
enum LocalTrigger {
    /// Näher als das: Zeitintervall statt Kalender (ein Kalender-Auslöser in der Vergangenheit käme nie).
    private static let calendarFrom: TimeInterval = 60

    /// Auslöser für `date`. Weiter voraus als eine Minute: Kalender-Komponenten ohne Zeitzone. Sie gelten in der
    /// jeweiligen Ortszeit, die Mitteilung folgt also einem Zeitzonenwechsel, auch wenn die App danach nicht läuft
    /// (07:02 bleibt 07:02 Ortszeit). Sonst ein Zeitintervall von mindestens 1 s.
    static func at(_ date: Date, now: Date = Date()) -> UNNotificationTrigger {
        let interval = date.timeIntervalSince(now)
        guard interval > calendarFrom else {
            return UNTimeIntervalNotificationTrigger(timeInterval: max(1, interval), repeats: false)
        }
        // Nur diese Komponenten: `dateComponents(_:from:)` setzt weder `timeZone` noch `calendar`.
        let parts = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        return UNCalendarNotificationTrigger(dateMatching: parts, repeats: false)
    }
}
