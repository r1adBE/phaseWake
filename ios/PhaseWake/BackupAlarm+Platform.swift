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

    /// Wie ein iPhone-Alarm gestellt wurde.
    enum Placement: Equatable {
        /// Über AlarmKit (ab iOS 26).
        case alarmKit
        /// Als Mitteilungen; so viele wurden gestellt (0 = keine, kein Platz).
        case notifications(Int)
    }

    /// Stellt einen iPhone-Alarm: ab iOS 26 über AlarmKit, sonst (oder wenn AlarmKit scheitert) als zeitkritische
    /// Mitteilung. `tone` = eigener Weckton; `nil` = Standardton des Systems. `snoozesLeft` > 0: Knopf «Schlummern»
    /// (beim Mitklingeln geht er an die Uhr, sonst schlummert das iPhone selbst, nur mit AlarmKit). `chain` = so viele
    /// Mitteilungen der Kette als Mitteilung (`NotificationBudget`; Standard die ganze Kette).
    @discardableResult
    func schedule(id: UUID, at date: Date, fromWatch: Bool = false, snoozesLeft: Int = 0, tone: WakeTone? = nil,
                  chain: Int = NotificationChain.count) async -> Placement {
        await platform.place(id: id, at: date, fromWatch: fromWatch, snoozesLeft: snoozesLeft, tone: tone, chain: chain)
    }

    /// Verwirft einen gestellten iPhone-Alarm (AlarmKit und Mitteilung, je nachdem, was gestellt wurde). Mit AlarmKit
    /// schickt sein Stopp-Intent danach nichts an die Uhr (``stoppedByApp``).
    func cancelAlarm(_ id: UUID) {
        if platform.alarmKit { stoppedByApp.insert(id) }
        platform.cancel(id)
    }

    /// Vor iOS 26: zeitkritische Mitteilung; der Stummschalter macht sie lautlos (Nacht-Check weist darauf hin).
    /// Als Kette aus 4 Mitteilungen im Abstand von 30 s (`NotificationChain`, Kennungen «id», «id.1» … «id.3»): Wer
    /// die erste verschläft, wird von der nächsten geweckt. Zu Kalenderzeit ohne Zeitzone (`LocalTrigger`): Sie folgt
    /// einer Reise, auch ohne dass die App läuft. `fixed`: zum festen Zeitpunkt (`pinNotification`). `count` = nur die
    /// ersten so vielen der Kette (`NotificationBudget`), ohne die schon wartenden `skip`. - Returns: wie viele der
    /// Kette danach stehen (gestellte und `skip`).
    @discardableResult
    func scheduleNotification(id: UUID, at date: Date, tone: WakeTone? = nil, fixed: Bool = false,
                              count: Int = NotificationChain.count, skip: Set<String> = []) async -> Int {
        await platform.notify(id: id, at: date, tone: tone, fixed: fixed, count: count, skip: skip)
    }

    /// Vor iOS 26 beim Stopp: die Ketten (`NotificationChain`) des Mitklingel-Alarms `ringing` und jeder schon
    /// zugestellten Sicherung dieser Nacht (`night`, `nil` = alle) ganz entfernen, wartend und zugestellt – wie ab
    /// iOS 26 die klingelnden Alarme dieser Nacht gestoppt werden. Der Probewecker zählt, was zugestellt wurde.
    func removeNotificationChains(ringing: UUID?, night: RingNight?) async {
        let notes = await platform.deliveredNotificationIds()
        let delivered = Set(notes.compactMap { UUID(uuidString: NotificationChain.baseId($0)) })
        var ids = delivered.filter { $0 != ringing && belongs($0, to: night, unknown: false) }
        if let ringing { ids.insert(ringing) }
        guard !ids.isEmpty else { return }
        for id in ids where delivered.contains(id) { host.phoneAlerted(id.uuidString) }
        platform.removeNotifications(NotificationChain.allIds(ids.map(\.uuidString)))
    }

    /// Eine über einen Zeitzonenwechsel behaltene Sicherung (`AlarmSchedule.begunNight`) klingelt zur geplanten
    /// absoluten Zeit. AlarmKit steht ohnehin fest (`.fixed`); eine wartende Mitteilung zu Kalenderzeit ohne Zeitzone
    /// rutschte dagegen auf die neue Ortszeit. Sie wird durch eine zum festen Zeitpunkt ersetzt (`LocalTrigger.fixed`).
    func pinNotification(_ entry: PlannedBackup) async {
        let pending = await platform.pendingNotifications()
        guard pending.contains(where: { $0.id == entry.id.uuidString && $0.calendar }) else { return }
        await scheduleNotification(id: entry.id, at: entry.slot.fireAt, tone: WakeTone(rawValue: entry.slot.sound), fixed: true)
    }
}

/// Das System als ``BackupPlatform``: AlarmKit ab iOS 26, sonst (oder wenn AlarmKit scheitert) zeitkritische
/// Mitteilungen.
@MainActor
final class SystemBackupPlatform: BackupPlatform {
    var alarmKit: Bool {
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) { return true }
        #endif
        return false
    }

    func place(id: UUID, at date: Date, fromWatch: Bool, snoozesLeft: Int, tone: WakeTone?, chain: Int) async -> BackupAlarm.Placement {
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) {
            return await scheduleAlarmKit(id: id, at: date, fromWatch: fromWatch, snoozesLeft: snoozesLeft, tone: tone, chain: chain)
        }
        #endif
        return .notifications(await notify(id: id, at: date, tone: tone, fixed: false, count: chain, skip: []))
    }

    func cancel(_ id: UUID) {
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) {
            do {
                try AlarmManager.shared.cancel(id: id)
            } catch {
                // Kein AlarmKit-Alarm (Mitteilung) oder schon vorbei: normal. Steht er noch, ins Protokoll.
                if ((try? AlarmManager.shared.alarms) ?? []).contains(where: { $0.id == id }) {
                    DiagStore.shared.log(.error, "alarmkit cancel failed \((error as NSError).code)")
                }
            }
        }
        #endif
        // Vor iOS 26 (oder nach gescheitertem AlarmKit) samt der Kette (`NotificationChain`).
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: NotificationChain.ids(id.uuidString))
    }

    func cancelScheduled(_ id: UUID) {
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) { try? AlarmManager.shared.cancel(id: id) }
        #endif
    }

    func stop(_ id: UUID) {
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) { try? AlarmManager.shared.stop(id: id) }
        #endif
    }

    func alarmKitIds() async -> Set<UUID> {
        var present = Set<UUID>()
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) {
            var attempt = 1
            while true {
                do {
                    let alarms = try AlarmManager.shared.alarms
                    present.formUnion(alarms.map(\.id))
                    if attempt > 1 { DiagStore.shared.log(.scheduled, ReadRetry.recoveredLine("alarmkit list", attempt: attempt)) }
                    break
                } catch {
                    DiagStore.shared.log(.error, ReadRetry.failureLine("alarmkit list", attempt: attempt, code: (error as NSError).code))
                    guard ReadRetry.retries(after: attempt) else { break }
                    try? await Task.sleep(for: .seconds(ReadRetry.pause))
                    attempt += 1
                }
            }
        }
        #endif
        return present
    }

    func scheduledAlarmKitIds() -> [UUID]? {
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) {
            guard let alarms = try? AlarmManager.shared.alarms else { return nil }
            return alarms.filter { $0.state == .scheduled }.map(\.id)
        }
        #endif
        return nil
    }

    func alertingIds() -> [UUID] {
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) {
            return ((try? AlarmManager.shared.alarms) ?? []).filter { $0.state == .alerting }.map(\.id)
        }
        #endif
        return []
    }

    func pendingNotifications() async -> [(id: String, calendar: Bool)] {
        await UNUserNotificationCenter.current().pendingNotificationRequests()
            .map { (id: $0.identifier, calendar: $0.trigger is UNCalendarNotificationTrigger) }
    }

    func deliveredNotificationIds() async -> [String] {
        await UNUserNotificationCenter.current().deliveredNotifications().map(\.request.identifier)
    }

    func removeNotifications(_ ids: [String]) {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: ids)
        center.removeDeliveredNotifications(withIdentifiers: ids)
    }

    func notify(id: UUID, at date: Date, tone: WakeTone?, fixed: Bool, count: Int, skip: Set<String>) async -> Int {
        let members = Array(zip(NotificationChain.ids(id.uuidString), NotificationChain.dates(date)).prefix(max(0, count)))
        guard members.contains(where: { !skip.contains($0.0) }) else { return members.count }
        // «Zeitkritisch» kommt über die Berechtigung (Entitlement).
        await NotificationLog.requestPermission("backup")
        let content = UNMutableNotificationContent()
        content.title = L("ring_greeting")
        content.body = L("ring_notification_text")
        content.sound = tone.map { UNNotificationSound(named: UNNotificationSoundName($0.fileName)) } ?? .default
        content.interruptionLevel = .timeSensitive
        let now = Date()
        var placed = members.filter { skip.contains($0.0) }.count
        for (member, at) in members where !skip.contains(member) {
            let trigger = fixed ? LocalTrigger.fixed(at, now: now) : LocalTrigger.at(at, now: now)
            if await NotificationLog.add(UNNotificationRequest(identifier: member, content: content, trigger: trigger), "backup") {
                placed += 1
            }
        }
        return placed
    }

    #if canImport(AlarmKit)
    @available(iOS 26.0, *)
    private func scheduleAlarmKit(id: UUID, at date: Date, fromWatch: Bool, snoozesLeft: Int, tone: WakeTone?,
                                  chain: Int) async -> BackupAlarm.Placement {
        do {
            if AlarmManager.shared.authorizationState != .authorized {
                _ = try await AlarmManager.shared.requestAuthorization()
            }
            let stop = AlarmButton(text: LocalizedStringResource(stringLiteral: L("ring_stop")), textColor: .white, systemImageName: "stop.fill")
            // Schlummern nur, solange Runden übrig sind (beim Mitklingeln die der Uhr). Restanzahl wie auf der Uhr
            // «noch N» statt «N übrig».
            let snoozeText = L("ring_snooze_button", Int64(Snooze.minutes)) + " · " + LP("ring_snoozes_left", snoozesLeft, Int64(snoozesLeft))
            let snooze = snoozesLeft > 0
                ? AlarmButton(text: LocalizedStringResource(stringLiteral: snoozeText), textColor: .white, systemImageName: "zzz")
                : nil
            let behavior: AlarmPresentation.Alert.SecondaryButtonBehavior? = snooze == nil ? nil : .custom
            let alert = AlarmPresentation.Alert(title: LocalizedStringResource(stringLiteral: L("ring_greeting")), stopButton: stop,
                                                secondaryButton: snooze, secondaryButtonBehavior: behavior)
            let attributes = AlarmAttributes<PhaseWakeAlarm>(presentation: AlarmPresentation(alert: alert), tintColor: Brand.accentLight)
            // Beim Mitklingeln gehen Stopp und Schlummern an die Uhr. Eine Sicherung klingelt ohne Uhr: Ihr Stopp
            // beendet die Uhr nur für dieselbe Nacht (`BackupStopIntent`); sie schlummert auf dem iPhone selbst
            // (`BackupSnoozeIntent`).
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
            return .alarmKit
        } catch {
            // Erlaubnis fehlt oder AlarmKit lehnt ab: als Mitteilung (im Protokoll sichtbar), im Rahmen von `chain`.
            DiagStore.shared.log(.error, "alarmkit failed \((error as NSError).code), notification instead")
            return .notifications(await notify(id: id, at: date, tone: tone, fixed: false, count: chain, skip: []))
        }
    }
    #endif
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

/// «Stopp» an einer Sicherung (klingelt ohne Uhr): Die Uhr hört für dieselbe Nacht auch auf
/// (`BackupAlarm.backupStopped`).
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
