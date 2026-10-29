import Foundation
import PhaseWakeCore
import UIKit
import UserNotifications

/// Wochenrückblick: Mitteilung am Sonntag um 18 Uhr über die letzten 7 Nächte (Standard aus).
/// iOS rechnet zur Zeit der Mitteilung nichts mehr aus, darum steht der Text schon vorher fest: Er wird
/// bei jedem Öffnen der App, jeder neuen Nacht und jedem Befinden mit dem aktuellen Stand neu geplant.
/// Ein Tipp öffnet den Verlauf.
@MainActor
final class WeeklyReview {
    nonisolated static let identifier = "weekly-review"

    /// Neuplanungen laufen nacheinander, nie verschränkt.
    private var queue: Task<Void, Never>?

    func reschedule(model: PhoneModel) async {
        let previous = queue
        let task = Task { @MainActor in
            await previous?.value
            await self.rescheduleNow(model: model)
        }
        queue = task
        await task.value
    }

    private func rescheduleNow(model: PhoneModel) async {
        let center = UNUserNotificationCenter.current()
        guard model.weeklyReview else {
            center.removePendingNotificationRequests(withIdentifiers: [Self.identifier])
            return
        }
        // Gesperrt sind die Gesundheitsdaten nicht lesbar: den bisher geplanten Text lieber stehen lassen.
        guard UIApplication.shared.isProtectedDataAvailable else { return }
        let calendar = Calendar.current
        guard let at = WeeklySummary.nextNotification(after: Date(), calendar: calendar) else { return }
        let sleeps = await model.health.recentSleep(days: Double(NightHistory.maxNights + 1))
        let summary = WeeklySummary.of(
            nights: model.nights, sleeps: sleeps, notes: model.notes, calendar: calendar,
            goalMinutes: model.sleepGoal, weekEnd: WeeklySummary.weekEnd(today: NightDate.today(at, calendar: calendar))
        )
        center.removePendingNotificationRequests(withIdentifiers: [Self.identifier])
        // Nichts zu berichten: keine Mitteilung.
        guard summary.hasData else { return }
        let content = UNMutableNotificationContent()
        content.title = L("weekly_notification_title")
        content.body = ([weeklyText(summary)] + [weeklyNoteLine(summary, model: model)].compactMap { $0 }).joined(separator: "\n")
        content.sound = .default
        let parts = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: at)
        let trigger = UNCalendarNotificationTrigger(dateMatching: parts, repeats: false)
        try? await center.add(UNNotificationRequest(identifier: Self.identifier, content: content, trigger: trigger))
    }
}

/// Tipp auf eine Mitteilung: Der Wochenrückblick öffnet den Verlauf; die übrigen öffnen die App wie bisher.
final class NotificationRouter: NSObject, UNUserNotificationCenterDelegate, @unchecked Sendable {
    static let shared = NotificationRouter()

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let id = response.notification.request.identifier
        // Zweitwecker vor iOS 26 (Kennung = UUID, siehe `BackupAlarm`): Der Probewecker zählt das Klingeln.
        if UUID(uuidString: id) != nil { await MainActor.run { PhoneModel.shared.transient.phoneAlerted(id) } }
        guard id == WeeklyReview.identifier else { return }
        await MainActor.run { PhoneModel.shared.selectedTab = .history }
    }

    /// Bei offener App: Der Zweitwecker (Kennung = UUID) erscheint und klingelt trotzdem – ein Wecker muss wecken.
    /// Alle anderen Mitteilungen bleiben wie bisher stumm, solange die App offen ist.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async
        -> UNNotificationPresentationOptions {
        let id = notification.request.identifier
        guard UUID(uuidString: id) != nil else { return [] }
        await MainActor.run { PhoneModel.shared.transient.phoneAlerted(id) }
        return [.banner, .list, .sound]
    }
}

/// Platzhalter für einen fehlenden Wert (wie Android).
private let noValue = "\u{2013}"

/// Der Rückblick in einer Zeile; ohne Gesundheitsdaten nur das Befinden. Wie `weeklyText` (Android).
func weeklyText(_ summary: WeeklySummary) -> String {
    let feeling = summary.feelingAverage.map(decimalText) ?? noValue
    guard summary.hasSleep else { return L("weekly_notification_feeling", feeling) }
    let asleep = summary.asleepAverage.map { Format.duration(minutes: $0) } ?? noValue
    return L("weekly_notification_text", asleep, summary.goalReached, summary.regular, feeling)
}

/// Die deutlichste Notiz der Woche, falls eine (gelöschte eigene Notizen zeigen nichts).
@MainActor
func weeklyNoteLine(_ summary: WeeklySummary, model: PhoneModel) -> String? {
    guard let effect = summary.strongestNote, let name = model.noteName(effect.noteId) else { return nil }
    return noteEffectText(effect, name: name)
}

/// «Nach ‹Alkohol› Befinden −0,8 (3 vs. 7 Nächte)», wie im Verlauf.
func noteEffectText(_ effect: NoteEffect, name: String) -> String {
    let value = abs(effect.delta).formatted(.number.precision(.fractionLength(1)))
    let signed = (effect.delta < 0 ? "\u{2212}" : "+") + value
    return LP("notes_effect_line", effect.nightsWithout, name, signed, effect.nightsWith, effect.nightsWithout)
}

/// Eine Nachkommastelle nach Sprache (wie in den Trends).
func decimalText(_ value: Double) -> String { value.formatted(.number.precision(.fractionLength(1))) }
