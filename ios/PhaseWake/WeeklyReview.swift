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
        let calendar = Calendar.gregorianLocal
        guard let at = WeeklySummary.nextNotification(after: Date(), calendar: calendar) else { return }
        // Wie die Kennzahlen im Verlauf nur die letzten 90 Tage bzw. Nächte (wie `WeeklyReminder` auf Android).
        let health = await model.health.sessions(days: NightHistory.insightNights)
        let nights = NightHistory.latest(model.nights)
        let sleeps = HealthHistory.summaries(health.sessions, clearedAt: model.historyClearedAt, calendar: calendar)
        // Befinden zu Nächten aus anderen Apps, die im Verlauf stehen.
        let items = HealthHistory.merge(nights: nights, sessions: health.sessions,
                                        clearedAt: model.historyClearedAt, calendar: calendar)
        let summary = WeeklySummary.of(
            nights: nights, sleeps: sleeps, notes: model.notes, calendar: calendar,
            goalMinutes: model.sleepGoal,
            weekEnd: WeeklySummary.weekEnd(today: NightDate.today(at, calendar: calendar)),
            health: HealthFeelings.of(items, feelings: model.healthFeelings)
        )
        center.removePendingNotificationRequests(withIdentifiers: [Self.identifier])
        // Nichts zu berichten: keine Mitteilung.
        guard summary.hasData else { return }
        let content = UNMutableNotificationContent()
        content.title = L("weekly_notification_title")
        content.body = ([weeklyText(summary)] + [weeklyNoteLine(summary, model: model)].compactMap { $0 })
            .joined(separator: "\n")
        content.sound = .default
        // Auslöser im Kalender des Geräts: Er liest die Komponenten in diesem (ohne Angabe des Kalenders).
        let parts = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: at)
        let trigger = UNCalendarNotificationTrigger(dateMatching: parts, repeats: false)
        let request = UNNotificationRequest(identifier: Self.identifier, content: content, trigger: trigger)
        await NotificationLog.add(request, "weekly")
    }
}

/// Tipp auf eine Mitteilung: Der Wochenrückblick öffnet den Verlauf; die übrigen öffnen die App wie bisher.
final class NotificationRouter: NSObject, UNUserNotificationCenterDelegate, @unchecked Sendable {
    static let shared = NotificationRouter()

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let raw = response.notification.request.identifier
        // Zweitwecker vor iOS 26 (Kennung = UUID samt Kette «.1» … «.3», siehe `BackupAlarm`): Der Probewecker zählt
        // das Klingeln. Ein Tipp heisst wach: Die noch folgenden Mitteilungen der Kette fallen weg.
        let id = NotificationChain.baseId(raw)
        if UUID(uuidString: id) != nil {
            center.removePendingNotificationRequests(withIdentifiers: NotificationChain.followingIds(id))
            await MainActor.run { PhoneModel.shared.transient.phoneAlerted(id) }
        }
        guard id == WeeklyReview.identifier else { return }
        await MainActor.run { PhoneModel.shared.selectedTab = .history }
    }

    /// Bei offener App: Der Zweitwecker (Kennung = UUID) erscheint und klingelt trotzdem – ein Wecker muss wecken.
    /// Alle anderen Mitteilungen bleiben wie bisher stumm, solange die App offen ist.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async
        -> UNNotificationPresentationOptions {
        let id = NotificationChain.baseId(notification.request.identifier)
        guard UUID(uuidString: id) != nil else { return [] }
        await MainActor.run { PhoneModel.shared.transient.phoneAlerted(id) }
        return [.banner, .list, .sound]
    }
}

/// Platzhalter für einen fehlenden Wert (wie Android).
private let noValue = "\u{2013}"

/// Der Rückblick in einer Zeile; ohne Gesundheitsdaten nur das Befinden. Wie `weeklyText` (Android).
func weeklyText(_ summary: WeeklySummary) -> String {
    let feeling = summary.feelingAverage.map { decimalText($0) } ?? noValue
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
    LP("notes_effect_line", effect.nightsWithout, name, decimalText(effect.delta, signed: true), effect.nightsWith,
       effect.nightsWithout)
}

/// Eine Nachkommastelle mit dem Dezimalzeichen der Sprache: «1,5» auf Deutsch – auch in der Schweiz, wo die Region
/// sonst den Punkt vorgibt –, «1.5» auf Englisch. Ziffern nach Sprache und Region; schreibt die Sprache allein andere
/// Ziffern (Arabisch), bleibt das Zeichen der Region. `signed`: mit Vorzeichen und echtem Minus («+0,5», «−0,8»).
/// Gerundet wie Android (halb auf). Wie `Format.decimal` (Android).
func decimalText(_ value: Double, signed: Bool = false, locale: Locale = .current) -> String {
    func formatter(_ locale: Locale, style: NumberFormatter.Style) -> NumberFormatter {
        let formatter = NumberFormatter()
        formatter.locale = locale
        formatter.numberStyle = style
        return formatter
    }
    let format = formatter(locale, style: .decimal)
    format.minimumFractionDigits = 1
    format.maximumFractionDigits = 1
    format.roundingMode = .halfUp
    format.usesGroupingSeparator = false
    if let code = locale.language.languageCode?.identifier {
        let language = Locale(identifier: code)
        // Gleiche Ziffern (Ganzzahl ohne Zeichen): dann das Dezimalzeichen der Sprache.
        let five = NSNumber(value: 5)
        if formatter(language, style: .none).string(from: five) == formatter(locale, style: .none).string(from: five) {
            format.decimalSeparator = formatter(language, style: .decimal).decimalSeparator
        }
    }
    if signed {
        format.positivePrefix = "+"
        format.negativePrefix = "\u{2212}"
    }
    return format.string(from: NSNumber(value: value)) ?? String(value)
}
