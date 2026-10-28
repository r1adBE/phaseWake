import Foundation
import PhaseWakeCore
import UserNotifications
#if canImport(AlarmKit)
import ActivityKit
import AlarmKit
import AppIntents
#endif

/// Zweitwecker auf dem iPhone.
///
/// Ab iOS 26 über AlarmKit: echter Wecker, der auch bei Lautlos und Fokus klingelt. Er klingelt,
/// wenn die Uhr den Weckmoment meldet, und als Sicherung 2 Minuten nach der Zielzeit, falls sich
/// die Uhr nicht meldet. Vor iOS 26 bleibt nur eine zeitkritische Mitteilung (weniger zuverlässig).
/// Stoppen oder Schlummern am iPhone nimmt die Uhr mit (wie Android), sofern sie das Klingeln ausgelöst hat.
/// Einmalige Wecker: Sicherung nur für ihr erstes Vorkommen; danach schaltet das iPhone sie selbst aus.
@MainActor
final class BackupAlarm {
    private static let grace: TimeInterval = 120
    /// Pro Wecker: Sicherung ausgesetzt bis (Sekunden seit 1970).
    private static let suppressedKey = "backupSuppressedByAlarm"
    private static let legacySuppressedKey = "backupSuppressedUntil"
    private static let backupIdsKey = "backupAlarmIds"
    private static let ringingKey = "backupRingingId"
    private static let oneShotKey = "backupOneShotTargets"
    private static let snoozeSafetyKey = "backupSnoozeSafetyId"
    private static let snoozedAtKey = "backupSnoozedAt"
    /// Das «stop» der Uhr direkt nach dem Schlummern ist nur das Echo davon, kein Beenden.
    private static let snoozeEcho: TimeInterval = 30
    /// Klingelt die Uhr nach dem Schlummern nicht wieder (Sitzung nicht gestartet): dann das iPhone.
    private static let snoozeSafetyDelay: TimeInterval = 90
    /// So viele Nächte im Voraus steht die Sicherung – auch wenn die App tagelang nicht geöffnet wird.
    private static let nightsAhead = 3
    /// Gespeichert statt nur im Speicher: Startet iOS die App neu, kann «stop» der Uhr ihn trotzdem stoppen.
    private var ringingId: UUID? {
        get { UserDefaults.standard.string(forKey: Self.ringingKey).flatMap(UUID.init(uuidString:)) }
        set { UserDefaults.standard.set(newValue?.uuidString, forKey: Self.ringingKey) }
    }
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
        let now = Date()
        let firstTargets = oneShotTargets(alarms, now: now)
        // Einmaliger Wecker, dessen erstes Vorkommen samt Nachfrist vorbei ist: ausschalten (geht auch an die Uhr).
        let expired = alarms.filter { spec in firstTargets[spec.id].map { now > $0.addingTimeInterval(Self.grace) } ?? false }
        for spec in expired { PhoneModel.shared.setEnabled(spec, false) }
        var skip = Set(expired.map(\.id))
        let suppressed = suppressedUntil()
        var from = now
        var ids: [String] = []
        while ids.count < Self.nightsAhead {
            guard let plan = AlarmSchedule.earliestPlan(alarms.filter { $0.phoneBackup && !skip.contains($0.id) }, now: from,
                                                        doneUntil: suppressed, calendar: .current) else { break }
            from = plan.target
            if let first = firstTargets[plan.alarmId] {
                // Einmalig: nur das erste Vorkommen. Ist es ausgesetzt, folgt keine weitere Nacht.
                skip.insert(plan.alarmId)
                if plan.target != first { continue }
            }
            let id = UUID()
            ids.append(id.uuidString)
            let tone = alarms.first { $0.id == plan.alarmId }.flatMap { WakeTone(rawValue: $0.sound) }
            await schedule(id: id, at: plan.target.addingTimeInterval(Self.grace), tone: tone)
        }
        UserDefaults.standard.set(ids, forKey: Self.backupIdsKey)
    }

    /// Zielzeit des ersten Vorkommens je eingeschaltetem einmaligem Wecker, gemerkt ab dem ersten Planen.
    /// Neu berechnet, wenn sich Uhrzeit oder Zeitzone ändern; ausgeschaltete Wecker fallen heraus.
    private func oneShotTargets(_ alarms: [AlarmSpec], now: Date) -> [String: Date] {
        let stored = UserDefaults.standard.dictionary(forKey: Self.oneShotKey) as? [String: Double] ?? [:]
        var keep: [String: Double] = [:]
        var targets: [String: Date] = [:]
        for spec in alarms where spec.isOneShot && spec.enabled {
            let key = "\(spec.id)|\(spec.hour):\(spec.minute)|\(TimeZone.current.identifier)"
            guard let target = stored[key].map(Date.init(timeIntervalSince1970:))
                ?? AlarmSchedule.nextTarget(spec, now: now, calendar: .current) else { continue }
            keep[key] = target.timeIntervalSince1970
            targets[spec.id] = target
        }
        UserDefaults.standard.set(keep, forKey: Self.oneShotKey)
        return targets
    }

    /// Die Uhr klingelt gerade: sofort mitklingeln (falls gewünscht) und die Sicherung dieser Nacht aussetzen.
    /// `target` ist die Zielzeit der Nacht laut Uhr (ältere Versionen schicken sie nicht).
    func ringNow(alarm: AlarmSpec?, target: Date?, snoozesLeft: Int) async {
        // Die Uhr klingelt wieder: Die Sicherung fürs Schlummern ist nicht mehr nötig.
        await cancelSnoozeSafety()
        if alarm?.phone != .off {
            let id = UUID()
            ringingId = id
            await schedule(id: id, at: Date().addingTimeInterval(2), fromWatch: true, snoozesLeft: snoozesLeft,
                           tone: alarm.flatMap { WakeTone(rawValue: $0.sound) })
        }
        await watchHandled(alarmId: alarm?.id, target: target)
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
        // Ohne Kennung (ältere Uhr): alle Wecker dieser Nacht, wie bisher.
        let ids = alarmId.map { [$0] } ?? alarms.map(\.id)
        var raw = UserDefaults.standard.dictionary(forKey: Self.suppressedKey) as? [String: Double] ?? [:]
        for id in ids { raw[id] = max(raw[id] ?? 0, until.timeIntervalSince1970) }
        let keepAfter = now.addingTimeInterval(-24 * 3600).timeIntervalSince1970
        UserDefaults.standard.set(raw.filter { $0.value > keepAfter }, forKey: Self.suppressedKey)
        UserDefaults.standard.removeObject(forKey: Self.legacySuppressedKey)
        await reschedule(alarms)
    }

    private func suppressedUntil() -> [String: Date] {
        let raw = UserDefaults.standard.dictionary(forKey: Self.suppressedKey) as? [String: Double] ?? [:]
        return raw.mapValues { Date(timeIntervalSince1970: $0) }
    }

    /// Stoppt den Mitklingel-Wecker und jeden gerade klingelnden Alarm (auch eine Sicherung).
    func stopRinging() async {
        let id = ringingId
        ringingId = nil
        // Beendet (nicht nur das Echo des eigenen Schlummerns): auch die Schlummer-Sicherung weg.
        let snoozedAt = UserDefaults.standard.double(forKey: Self.snoozedAtKey)
        if Date().timeIntervalSince1970 - snoozedAt > Self.snoozeEcho { await cancelSnoozeSafety() }
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) {
            if let id {
                try? AlarmManager.shared.stop(id: id)
                // Noch nicht klingelnd (geplant in 2 s): verwerfen.
                try? AlarmManager.shared.cancel(id: id)
            }
            let alerting = ((try? AlarmManager.shared.alarms) ?? []).filter { $0.state == .alerting }
            for alarm in alerting { try? AlarmManager.shared.stop(id: alarm.id) }
            return
        }
        #endif
        guard let id else { return }
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [id.uuidString])
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [id.uuidString])
    }

    /// Ob die Kennung zum gerade mitklingelnden Wecker gehört (nicht zu einem schon per Code gestoppten).
    func isCurrentRing(_ alarmId: String) -> Bool { ringingId?.uuidString == alarmId }

    /// Am iPhone geschlummert: Eigene Sicherung kurz nach dem Schlummer-Ende, falls die Uhr dann nicht
    /// klingelt (ihre neue Sitzung startet im Hintergrund unter Umständen nicht).
    func snoozeSafety() async {
        await cancelSnoozeSafety()
        let id = UUID()
        UserDefaults.standard.set(id.uuidString, forKey: Self.snoozeSafetyKey)
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: Self.snoozedAtKey)
        await schedule(id: id, at: Snooze.nextRing(Date()).addingTimeInterval(Self.snoozeSafetyDelay))
    }

    private func cancelSnoozeSafety() async {
        guard let id = UserDefaults.standard.string(forKey: Self.snoozeSafetyKey).flatMap(UUID.init(uuidString:)) else { return }
        UserDefaults.standard.removeObject(forKey: Self.snoozeSafetyKey)
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) {
            try? AlarmManager.shared.cancel(id: id)
            return
        }
        #endif
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [id.uuidString])
    }

    // MARK: Plattform

    /// `tone` = eigener Weckton des Weckers; `nil` = Standardton des Systems (auch bei der Schlummer-Sicherung).
    private func schedule(id: UUID, at date: Date, fromWatch: Bool = false, snoozesLeft: Int = 0, tone: WakeTone? = nil) async {
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) {
            await scheduleAlarmKit(id: id, at: date, fromWatch: fromWatch, snoozesLeft: snoozesLeft, tone: tone)
            return
        }
        #endif
        await scheduleNotification(id: id, at: date, tone: tone)
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
    private func scheduleAlarmKit(id: UUID, at date: Date, fromWatch: Bool, snoozesLeft: Int, tone: WakeTone?) async {
        do {
            if AlarmManager.shared.authorizationState != .authorized {
                _ = try await AlarmManager.shared.requestAuthorization()
            }
            let stop = AlarmButton(text: LocalizedStringResource(stringLiteral: L("ring_stop")), textColor: .white, systemImageName: "stop.fill")
            // Klingelt das iPhone mit der Uhr: Schlummern nur, solange die Uhr noch Runden hat.
            let snooze = fromWatch && snoozesLeft > 0
                ? AlarmButton(text: LocalizedStringResource(stringLiteral: LP("ring_snooze", snoozesLeft, Int64(Snooze.minutes), Int64(snoozesLeft))),
                              textColor: .white, systemImageName: "zzz")
                : nil
            let behavior: AlarmPresentation.Alert.SecondaryButtonBehavior? = snooze == nil ? nil : .custom
            let alert = AlarmPresentation.Alert(title: LocalizedStringResource(stringLiteral: L("ring_greeting")), stopButton: stop,
                                                secondaryButton: snooze, secondaryButtonBehavior: behavior)
            let attributes = AlarmAttributes<PhaseWakeAlarm>(presentation: AlarmPresentation(alert: alert), tintColor: Brand.accentLight)
            // Nur beim Mitklingeln geht Stopp bzw. Schlummern an die Uhr; eine Sicherung klingelt ohne Uhr.
            let stopIntent: (any LiveActivityIntent)? = fromWatch ? StopRingIntent(alarmId: id.uuidString) : nil
            let snoozeIntent: (any LiveActivityIntent)? = snooze == nil ? nil : SnoozeRingIntent(alarmId: id.uuidString)
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

    private func scheduleNotification(id: UUID, at date: Date, tone: WakeTone? = nil) async {
        let center = UNUserNotificationCenter.current()
        _ = try? await center.requestAuthorization(options: [.alert, .sound]) // «Zeitkritisch» kommt über die Berechtigung (Entitlement).
        let content = UNMutableNotificationContent()
        content.title = L("ring_greeting")
        content.body = L("ring_notification_text")
        content.sound = tone.map { UNNotificationSound(named: UNNotificationSoundName($0.fileName)) } ?? .default
        content.interruptionLevel = .timeSensitive
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(1, date.timeIntervalSinceNow), repeats: false)
        try? await center.add(UNNotificationRequest(identifier: id.uuidString, content: content, trigger: trigger))
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
        await PhoneModel.shared.phoneRingEnded(snooze: false)
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
        await PhoneModel.shared.phoneRingEnded(snooze: true)
        return .result()
    }
}
#endif
