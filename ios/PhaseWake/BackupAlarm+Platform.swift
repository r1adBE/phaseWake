import Foundation
import PhaseWakeCore
import UserNotifications
#if canImport(AlarmKit)
import ActivityKit
import AlarmKit
import AppIntents
#endif

extension BackupAlarm {
    // MARK: Plattform

    /// Stellt einen iPhone-Alarm: ab iOS 26 über AlarmKit, sonst (oder wenn AlarmKit scheitert) als zeitkritische
    /// Mitteilung. `tone` = eigener Weckton; `nil` = Standardton des Systems. `snoozesLeft` > 0: Knopf «Schlummern»
    /// (beim Mitklingeln geht er an die Uhr, sonst schlummert das iPhone selbst, nur mit AlarmKit).
    func schedule(id: UUID, at date: Date, fromWatch: Bool = false, snoozesLeft: Int = 0, tone: WakeTone? = nil) async {
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) {
            await scheduleAlarmKit(id: id, at: date, fromWatch: fromWatch, snoozesLeft: snoozesLeft, tone: tone)
            return
        }
        #endif
        await scheduleNotification(id: id, at: date, tone: tone)
    }

    /// Verwirft einen gestellten iPhone-Alarm (AlarmKit und Mitteilung, je nachdem, was gestellt wurde).
    func cancelAlarm(_ id: UUID) {
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) {
            stoppedByApp.insert(id)
            try? AlarmManager.shared.cancel(id: id)
        }
        #endif
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [id.uuidString])
    }

    #if canImport(AlarmKit)
    @available(iOS 26.0, *)
    private func scheduleAlarmKit(id: UUID, at date: Date, fromWatch: Bool, snoozesLeft: Int, tone: WakeTone?) async {
        do {
            if AlarmManager.shared.authorizationState != .authorized {
                _ = try await AlarmManager.shared.requestAuthorization()
            }
            let stop = AlarmButton(text: LocalizedStringResource(stringLiteral: L("ring_stop")), textColor: .white, systemImageName: "stop.fill")
            // Schlummern nur, solange Runden übrig sind (beim Mitklingeln die der Uhr).
            let snooze = snoozesLeft > 0
                ? AlarmButton(text: LocalizedStringResource(stringLiteral: LP("ring_snooze", snoozesLeft, Int64(Snooze.minutes), Int64(snoozesLeft))),
                              textColor: .white, systemImageName: "zzz")
                : nil
            let behavior: AlarmPresentation.Alert.SecondaryButtonBehavior? = snooze == nil ? nil : .custom
            let alert = AlarmPresentation.Alert(title: LocalizedStringResource(stringLiteral: L("ring_greeting")), stopButton: stop,
                                                secondaryButton: snooze, secondaryButtonBehavior: behavior)
            let attributes = AlarmAttributes<PhaseWakeAlarm>(presentation: AlarmPresentation(alert: alert), tintColor: Brand.accentLight)
            // Beim Mitklingeln gehen Stopp und Schlummern an die Uhr. Eine Sicherung klingelt ohne Uhr: Ihr Stopp beendet
            // die Uhr nur für dieselbe Nacht (`BackupStopIntent`); sie schlummert auf dem iPhone selbst (`BackupSnoozeIntent`).
            let stopIntent: any LiveActivityIntent = fromWatch
                ? StopRingIntent(alarmId: id.uuidString)
                : BackupStopIntent(alarmId: id.uuidString)
            let snoozeIntent: (any LiveActivityIntent)?
            if snooze == nil {
                snoozeIntent = nil
            } else if fromWatch {
                snoozeIntent = SnoozeRingIntent(alarmId: id.uuidString)
            } else {
                snoozeIntent = BackupSnoozeIntent(alarmId: id.uuidString, snoozesLeft: snoozesLeft)
            }
            // Eigener Ton aus dem App-Bundle: Name mit Endung, sonst spielt AlarmKit den Standardton.
            let sound: AlertConfiguration.AlertSound = tone.map { .named($0.fileName) } ?? .default
            let configuration = AlarmManager.AlarmConfiguration<PhaseWakeAlarm>.alarm(
                schedule: .fixed(date),
                attributes: attributes,
                stopIntent: stopIntent,
                secondaryIntent: snoozeIntent,
                sound: sound
            )
            _ = try await AlarmManager.shared.schedule(id: id, configuration: configuration)
        } catch {
            await scheduleNotification(id: id, at: date, tone: tone)
        }
    }
    #endif

    /// Vor iOS 26: zeitkritische Mitteilung; der Stummschalter macht sie lautlos (Nacht-Check weist darauf hin).
    /// Zu Kalenderzeit ohne Zeitzone (`LocalTrigger`): Sie folgt einer Reise, auch ohne dass die App läuft.
    private func scheduleNotification(id: UUID, at date: Date, tone: WakeTone? = nil) async {
        let center = UNUserNotificationCenter.current()
        _ = try? await center.requestAuthorization(options: [.alert, .sound]) // «Zeitkritisch» kommt über die Berechtigung (Entitlement).
        let content = UNMutableNotificationContent()
        content.title = L("ring_greeting")
        content.body = L("ring_notification_text")
        content.sound = tone.map { UNNotificationSound(named: UNNotificationSoundName($0.fileName)) } ?? .default
        content.interruptionLevel = .timeSensitive
        try? await center.add(UNNotificationRequest(identifier: id.uuidString, content: content, trigger: LocalTrigger.at(date)))
    }
}

#if canImport(AlarmKit)
/// Zusatzdaten des Weckers für AlarmKit (keine nötig).
@available(iOS 26.0, *)
struct PhaseWakeAlarm: AlarmMetadata {}

/// «Stopp» am iPhone, während es mit der Uhr klingelt: stoppt auch die Uhr.
@available(iOS 26.0, *)
struct StopRingIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "ring_stop"
    static let isDiscoverable = false

    @Parameter(title: "Alarm")
    var alarmId: String

    init() { alarmId = "" }
    init(alarmId: String) { self.alarmId = alarmId }

    @MainActor
    func perform() async throws -> some IntentResult {
        // Nur ein Stopp durch dich: Stoppt die App den Alarm selbst (Befehl der Uhr), nichts zurückschicken.
        guard PhoneModel.shared.backup.isCurrentRing(alarmId) else { return .result() }
        PhoneModel.shared.transient.phoneAlerted(alarmId)
        await PhoneModel.shared.phoneRingEnded(snooze: false)
        return .result()
    }
}

/// «Stopp» an einer Sicherung (klingelt ohne Uhr): Die Uhr hört für dieselbe Nacht auch auf (`BackupAlarm.backupStopped`).
@available(iOS 26.0, *)
struct BackupStopIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "ring_stop"
    static let isDiscoverable = false

    @Parameter(title: "Alarm")
    var alarmId: String

    init() { alarmId = "" }
    init(alarmId: String) { self.alarmId = alarmId }

    @MainActor
    func perform() async throws -> some IntentResult {
        PhoneModel.shared.transient.phoneAlerted(alarmId)
        PhoneModel.shared.backup.backupStopped(alarmId)
        return .result()
    }
}

/// «Schlummern» am iPhone, während es mit der Uhr klingelt: Die Uhr schlummert und meldet sich wieder.
@available(iOS 26.0, *)
struct SnoozeRingIntent: LiveActivityIntent {
    // Nicht sichtbar (nicht in Kurzbefehlen); der Knopf trägt den übersetzten Text.
    static let title: LocalizedStringResource = "Snooze"
    static let isDiscoverable = false

    @Parameter(title: "Alarm")
    var alarmId: String

    init() { alarmId = "" }
    init(alarmId: String) { self.alarmId = alarmId }

    @MainActor
    func perform() async throws -> some IntentResult {
        guard PhoneModel.shared.backup.isCurrentRing(alarmId) else { return .result() }
        PhoneModel.shared.transient.phoneAlerted(alarmId)
        await PhoneModel.shared.phoneRingEnded(snooze: true)
        return .result()
    }
}

/// «Schlummern» an einer Sicherung, die ohne Uhr klingelt: Das iPhone klingelt in 5 Minuten wieder, mit einer Runde
/// weniger (höchstens `Snooze.maxCount`, wie auf der Uhr).
@available(iOS 26.0, *)
struct BackupSnoozeIntent: LiveActivityIntent {
    // Nicht sichtbar (nicht in Kurzbefehlen); der Knopf trägt den übersetzten Text.
    static let title: LocalizedStringResource = "Snooze"
    static let isDiscoverable = false

    @Parameter(title: "Alarm")
    var alarmId: String

    @Parameter(title: "Snoozes")
    var snoozesLeft: Int

    init() {
        alarmId = ""
        snoozesLeft = 0
    }

    init(alarmId: String, snoozesLeft: Int) {
        self.alarmId = alarmId
        self.snoozesLeft = snoozesLeft
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        PhoneModel.shared.transient.phoneAlerted(alarmId)
        await PhoneModel.shared.backup.backupSnoozed(alarmId, snoozesLeft: snoozesLeft)
        return .result()
    }
}
#endif
