import Foundation
import PhaseWakeCore
import UserNotifications
#if canImport(AlarmKit)
import AlarmKit
#endif

/// Zweitwecker auf dem iPhone.
///
/// Ab iOS 26 über AlarmKit: echter Wecker, der auch bei Lautlos und Fokus klingelt. Er klingelt,
/// wenn die Uhr den Weckmoment meldet, und als Sicherung 2 Minuten nach der Zielzeit, falls sich
/// die Uhr nicht meldet. Vor iOS 26 bleibt nur eine zeitkritische Mitteilung (weniger zuverlässig).
@MainActor
final class BackupAlarm {
    private static let grace: TimeInterval = 120
    private static let suppressedKey = "backupSuppressedUntil"
    private static let backupIdKey = "backupAlarmId"
    private var ringingId: UUID?

    /// Plant die Sicherung für den nächsten Wecker mit Zweitwecker.
    func reschedule(_ alarms: [AlarmSpec]) async {
        await cancelBackup()
        let suppressed = Date(timeIntervalSince1970: UserDefaults.standard.double(forKey: Self.suppressedKey))
        let from = max(Date(), suppressed)
        guard let plan = AlarmSchedule.earliestPlan(alarms.filter(\.phoneBackup), now: from, calendar: .current) else { return }
        let id = UUID()
        UserDefaults.standard.set(id.uuidString, forKey: Self.backupIdKey)
        await schedule(id: id, at: plan.target.addingTimeInterval(Self.grace))
    }

    /// Die Uhr weckt gerade: sofort mitklingeln (falls gewünscht) und die Sicherung dieser Nacht aussetzen.
    func ringNow(alarm: AlarmSpec?) async {
        let until = Date().addingTimeInterval(Double(AlarmSpec.maxWindowMinutes * 60) + Self.grace)
        UserDefaults.standard.set(until.timeIntervalSince1970, forKey: Self.suppressedKey)
        if alarm?.phone != .off {
            let id = UUID()
            ringingId = id
            await schedule(id: id, at: Date().addingTimeInterval(1))
        }
        await reschedule(PhoneModel.shared.alarms)
    }

    func stopRinging() async {
        guard let id = ringingId else { return }
        ringingId = nil
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) {
            try? AlarmManager.shared.stop(id: id)
            return
        }
        #endif
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [id.uuidString])
    }

    // MARK: Plattform

    private func schedule(id: UUID, at date: Date) async {
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) {
            await scheduleAlarmKit(id: id, at: date)
            return
        }
        #endif
        await scheduleNotification(id: id, at: date)
    }

    private func cancelBackup() async {
        guard let text = UserDefaults.standard.string(forKey: Self.backupIdKey), let id = UUID(uuidString: text) else { return }
        UserDefaults.standard.removeObject(forKey: Self.backupIdKey)
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) {
            try? AlarmManager.shared.cancel(id: id)
            return
        }
        #endif
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [id.uuidString])
    }

    #if canImport(AlarmKit)
    @available(iOS 26.0, *)
    private func scheduleAlarmKit(id: UUID, at date: Date) async {
        do {
            if AlarmManager.shared.authorizationState != .authorized {
                _ = try await AlarmManager.shared.requestAuthorization()
            }
            let stop = AlarmButton(text: LocalizedStringResource(stringLiteral: L("ring_stop")), textColor: .white, systemImageName: "stop.fill")
            let alert = AlarmPresentation.Alert(title: LocalizedStringResource(stringLiteral: L("ring_greeting")), stopButton: stop)
            let attributes = AlarmAttributes<PhaseWakeAlarm>(presentation: AlarmPresentation(alert: alert), tintColor: Brand.accentLight)
            let configuration = AlarmManager.AlarmConfiguration<PhaseWakeAlarm>.alarm(schedule: .fixed(date), attributes: attributes)
            _ = try await AlarmManager.shared.schedule(id: id, configuration: configuration)
        } catch {
            await scheduleNotification(id: id, at: date)
        }
    }
    #endif

    private func scheduleNotification(id: UUID, at date: Date) async {
        let center = UNUserNotificationCenter.current()
        _ = try? await center.requestAuthorization(options: [.alert, .sound, .timeSensitive])
        let content = UNMutableNotificationContent()
        content.title = L("ring_greeting")
        content.body = L("ring_notification_text")
        content.sound = .default
        content.interruptionLevel = .timeSensitive
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(1, date.timeIntervalSinceNow), repeats: false)
        try? await center.add(UNNotificationRequest(identifier: id.uuidString, content: content, trigger: trigger))
    }
}

#if canImport(AlarmKit)
/// Zusatzdaten des Weckers für AlarmKit (keine nötig).
@available(iOS 26.0, *)
struct PhaseWakeAlarm: AlarmMetadata {}
#endif
