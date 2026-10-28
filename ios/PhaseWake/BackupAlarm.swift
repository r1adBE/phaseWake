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
    private static let backupIdsKey = "backupAlarmIds"
    /// So viele Nächte im Voraus steht die Sicherung – auch wenn die App tagelang nicht geöffnet wird.
    private static let nightsAhead = 3
    private var ringingId: UUID?
    /// Neuplanungen laufen nacheinander, nie verschränkt (sonst blieben Alarme ohne gemerkte Kennung).
    private var queue: Task<Void, Never>?

    /// Plant die Sicherung für die nächsten Nächte mit Zweitwecker.
    func reschedule(_ alarms: [AlarmSpec]) async {
        let previous = queue
        let task = Task { @MainActor in
            await previous?.value
            await self.rescheduleNow(alarms)
        }
        queue = task
        await task.value
    }

    private func rescheduleNow(_ alarms: [AlarmSpec]) async {
        await cancelBackups()
        let suppressed = Date(timeIntervalSince1970: UserDefaults.standard.double(forKey: Self.suppressedKey))
        var from = max(Date(), suppressed)
        var ids: [String] = []
        for _ in 0..<Self.nightsAhead {
            guard let plan = AlarmSchedule.earliestPlan(alarms.filter(\.phoneBackup), now: from, calendar: .current) else { break }
            let id = UUID()
            ids.append(id.uuidString)
            await schedule(id: id, at: plan.target.addingTimeInterval(Self.grace))
            from = plan.target
        }
        UserDefaults.standard.set(ids, forKey: Self.backupIdsKey)
    }

    /// Die Uhr klingelt gerade: sofort mitklingeln (falls gewünscht) und die Sicherung dieser Nacht aussetzen.
    func ringNow(alarm: AlarmSpec?) async {
        if alarm?.phone != .off {
            let id = UUID()
            ringingId = id
            await schedule(id: id, at: Date().addingTimeInterval(2))
        }
        await watchHandled(alarmId: alarm?.id, target: nil)
    }

    /// Die Uhr hat diese Nacht übernommen (klingelt, gestoppt oder Nacht gemeldet): Sicherung bis kurz
    /// nach der Zielzeit dieser Nacht aussetzen – nicht länger, sonst fiele ein zweiter Wecker weg.
    func watchHandled(alarmId: String?, target: Date?) async {
        let now = Date()
        let alarms = PhoneModel.shared.alarms
        let spec = alarms.first { $0.id == alarmId }
        let window = TimeInterval(AlarmSpec.maxWindowMinutes * 60)
        let nightTarget = target
            ?? spec.flatMap { AlarmSchedule.nextTarget($0, now: now.addingTimeInterval(-window), calendar: .current) }
            ?? now.addingTimeInterval(window)
        let until = nightTarget.addingTimeInterval(Self.grace)
        if until.timeIntervalSince1970 > UserDefaults.standard.double(forKey: Self.suppressedKey) {
            UserDefaults.standard.set(until.timeIntervalSince1970, forKey: Self.suppressedKey)
        }
        await reschedule(alarms)
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

    private func cancelBackups() async {
        let ids = (UserDefaults.standard.stringArray(forKey: Self.backupIdsKey) ?? []).compactMap(UUID.init(uuidString:))
        UserDefaults.standard.removeObject(forKey: Self.backupIdsKey)
        for id in ids {
            #if canImport(AlarmKit)
            if #available(iOS 26.0, *) {
                try? AlarmManager.shared.cancel(id: id)
                continue
            }
            #endif
            UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [id.uuidString])
        }
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
