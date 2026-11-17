import Foundation
import PhaseWakeCore
import UserNotifications
#if canImport(AlarmKit)
import AlarmKit
#endif

/// Zweitwecker auf dem iPhone.
///
/// Ab iOS 26 über AlarmKit: echter Wecker, der auch bei Lautlos und Fokus klingelt. Er klingelt,
/// wenn die Uhr den Weckmoment meldet, und als Sicherung nach der Nachfrist (`grace`: 2 Minuten mit Uhr, ohne Uhr
/// pünktlich), falls sich die Uhr nicht meldet. Vor iOS 26 bleibt nur eine zeitkritische Mitteilung (weniger
/// zuverlässig, der Stummschalter macht sie lautlos). Stoppen oder Schlummern am iPhone nimmt die Uhr mit (wie
/// Android), sofern sie das Klingeln ausgelöst hat; eine Sicherung schlummert auf dem iPhone selbst.
/// Einmalige Wecker: Sicherung nur für ihr erstes Vorkommen; danach schaltet das iPhone sie selbst aus.
@MainActor
final class BackupAlarm {
    /// Pro Wecker: Sicherung ausgesetzt bis (Sekunden seit 1970).
    private static let suppressedKey = "backupSuppressedByAlarm"
    private static let legacySuppressedKey = "backupSuppressedUntil"
    private static let ringingKey = "backupRingingId"
    private static let ringSnoozesKey = "backupRingSnoozesLeft"
    private static let snoozeSafetyKey = "backupSnoozeSafetyId"
    private static let snoozedAtKey = "backupSnoozedAt"
    /// Schlummern an einer Sicherung: der nächste Alarm in 5 Minuten und sein Weckton.
    static let backupSnoozeKey = "backupSnoozeId"
    /// Ende dieses Schlummerns (Sekunden seit 1970): Anzeige und Stopp in der App (`BackupAlarm+Snooze`).
    static let backupSnoozeUntilKey = "backupSnoozeUntil"
    private static let backupSnoozeToneKey = "backupSnoozeTone"
    /// Sicherung des laufenden Probeweckers oder Powernaps.
    static let transientKey = "backupTransientId"
    /// Das «stop» einer älteren Uhr direkt nach dem Schlummern ist nur das Echo davon, kein Beenden.
    private static let snoozeEcho: TimeInterval = 30
    /// Klingelt die Uhr nach dem Schlummern nicht wieder (Sitzung nicht gestartet): dann das iPhone
    /// (wie `RingMessage.SNOOZE_SAFETY_MS` auf Android).
    private static let snoozeSafetyDelay: TimeInterval = 90
    /// So weit im Voraus steht die Sicherung – auch wenn die App tagelang nicht geöffnet wird (14 Tage).
    static let horizon: TimeInterval = 14 * 24 * 3600
    /// Höchstens so viele Sicherungen. Als Mitteilung (vor iOS 26, AlarmKit gescheitert) stellt `apply` davon so viele,
    /// wie neben den übrigen wartenden Mitteilungen Platz haben (`NotificationBudget`, iOS behält 64 je App).
    static let maxSlots = 40
    /// Ort der gemerkten Kennungen, Nächte und Sperren (in der App ``SettingsFile/phone``).
    let settings: SettingsFile
    /// AlarmKit bzw. Mitteilungen (in Tests ein Ersatz).
    let platform: any BackupPlatform
    /// Wecker, Feiertage und «Gute Nacht» der App.
    let host: any BackupHost

    /// In der App ohne Angaben; Tests geben eigene Einstellungen, Plattform und App.
    init(settings: SettingsFile = .phone, platform: (any BackupPlatform)? = nil, host: (any BackupHost)? = nil) {
        self.settings = settings
        self.platform = platform ?? SystemBackupPlatform()
        self.host = host ?? AppBackupHost()
    }

    /// Gespeichert statt nur im Speicher: Startet iOS die App neu, kann «stop» der Uhr ihn trotzdem stoppen.
    private var ringingId: UUID? {
        get { storedId(Self.ringingKey) }
        set { settings.set(newValue?.uuidString, forKey: Self.ringingKey) }
    }
    /// Neuplanungen laufen nacheinander, nie verschränkt (sonst blieben Alarme ohne gemerkte Kennung).
    private var queue: Task<Void, Never>?
    /// Von der App selbst gestoppte oder verworfene Alarme (Befehl der Uhr, Neuplanen): Ihr Stopp-Intent schickt nichts
    /// an die Uhr (`backupStopped`). Nur im Speicher, wie der Intent im selben Prozess läuft.
    var stoppedByApp: Set<UUID> = []
    /// Zielzeit der Nacht, die zuletzt über einen Zeitzonenwechsel behalten wurde (Diagnose einmal je Nacht).
    var keptNight: Date?

    /// Plant die Sicherung für die nächsten Nächte mit Zweitwecker; danach die Nächte mit Wecker der Lern-Übersicht
    /// (``LearnLogStore/noteAlarms(_:holidays:now:)``, hängt an denselben Ereignissen wie auf Android).
    func reschedule(_ alarms: [AlarmSpec]) async {
        // Stand vor dem ersten Entsperren nicht lesbar: `alarms` und die gemerkten Sicherungen wären leer, das
        // Neuplanen verwürfe alle Zweitwecker oder liesse sie verwaist stehen. Nichts tun; ist er inzwischen
        // lesbar, liest `catchUpState` ihn und plant mit dem echten Stand neu (nie mit dem leeren `alarms` von hier).
        guard host.stateLoaded else {
            if !host.catchUpState() {
                DiagStore.shared.log(.scheduled, "phone backup not planned: settings not readable yet")
            }
            return
        }
        let previous = queue
        let task = Task { @MainActor in
            await previous?.value
            await self.rescheduleNow(alarms)
        }
        queue = task
        await task.value
        host.noteAlarms(alarms, now: Date())
    }

    /// Wartet, bis die laufende Neuplanung fertig ist (Nacht-Check liest danach die gestellten Sicherungen).
    func settled() async { await queue?.value }

    /// Die Uhr klingelt gerade: sofort mitklingeln (falls gewünscht) und die Sicherung dieser Nacht aussetzen.
    /// `alarmId` und `target` bestimmen die Nacht laut Uhr (ältere Versionen schicken die Zielzeit nicht).
    /// - Returns: Kennung des Mitklingel-Alarms, oder `nil`, wenn das iPhone nicht mitklingelt (Weckart «aus»).
    @discardableResult
    func ringNow(alarmId: String?, alarm: AlarmSpec?, target: Date?, snoozesLeft: Int) async -> UUID? {
        // Die Uhr klingelt wieder: Die Sicherung fürs Schlummern ist nicht mehr nötig.
        await cancelSnoozeSafety()
        settings.set(snoozesLeft, forKey: Self.ringSnoozesKey)
        var ring: UUID?
        if alarm?.phone != .off {
            let id = UUID()
            ringingId = id
            setNight(RingNight(alarmId: alarmId, target: target), for: id)
            ring = id
            await schedule(id: id, at: Date().addingTimeInterval(2), fromWatch: true, snoozesLeft: snoozesLeft,
                           tone: alarm.flatMap { WakeTone(rawValue: $0.sound) })
        }
        await watchHandled(alarmId: alarmId, target: target)
        return ring
    }

    /// Die Uhr hat diese Nacht übernommen (klingelt, gestoppt oder Nacht gemeldet): Sicherung bis kurz
    /// nach der Zielzeit dieser Nacht aussetzen – nicht länger, sonst fiele ein zweiter Wecker weg. Ohne Kennung
    /// (ältere Uhr): jeder Wecker, dessen Nacht gerade läuft, wie Android `BackupAlarm.watchHandled`.
    func watchHandled(alarmId: String?, target: Date?) async {
        let now = Date()
        let alarms = host.alarms
        let window = TimeInterval(AlarmSpec.maxWindowMinutes * 60)
        var until: [String: Date] = [:]
        if let alarmId {
            let spec = alarms.first { $0.id == alarmId }
            // Nach «Gute Nacht» meldet die Uhr die frühere Zielzeit: ausgesetzt bis zur eingestellten derselben Nacht.
            let nightTarget = host.originalTarget(alarmId: alarmId, target: target)
                ?? target
                ?? spec.flatMap { AlarmSchedule.nextTarget($0, now: now.addingTimeInterval(-window),
                                                           calendar: .gregorianLocal, holidays: host.holidayDates) }
                ?? now.addingTimeInterval(window)
            until[alarmId] = nightTarget.addingTimeInterval(currentGrace)
        } else {
            // Nacht läuft: Zielzeit nach jetzt − (längstes Fenster + Schlummern + 10 Min) und höchstens ein längstes
            // Fenster nach jetzt (Android `LOOKBACK`); auch ein soeben ausgeschalteter Einmal-Wecker zählt.
            let lookback = TimeInterval((AlarmSpec.maxWindowMinutes + Snooze.maxCount * Snooze.minutes + 10) * 60)
            for spec in alarms {
                guard let night = AlarmSchedule.nextTarget(spec.with(enabled: true),
                                                           now: now.addingTimeInterval(-lookback),
                                                           calendar: .gregorianLocal),
                      night <= now.addingTimeInterval(window) else { continue }
                until[spec.id] = night.addingTimeInterval(currentGrace)
            }
        }
        var raw = settings.dictionary(forKey: Self.suppressedKey) as? [String: Double] ?? [:]
        for (id, date) in until { raw[id] = max(raw[id] ?? 0, date.timeIntervalSince1970) }
        let keepAfter = now.addingTimeInterval(-24 * 3600).timeIntervalSince1970
        settings.set(raw.filter { $0.value > keepAfter }, forKey: Self.suppressedKey)
        settings.removeObject(forKey: Self.legacySuppressedKey)
        await reschedule(alarms)
    }

    /// Pro Wecker: Sicherung ausgesetzt bis (die Uhr hat die Nacht übernommen); auch Teil von `PhoneModel.doneUntil`.
    func suppressedUntil() -> [String: Date] {
        let raw = settings.dictionary(forKey: Self.suppressedKey) as? [String: Double] ?? [:]
        return raw.mapValues { Date(timeIntervalSince1970: $0) }
    }

    /// Stoppt, was klingelt. `night` = «stop» der Uhr: nur Alarme dieser Nacht (der Mitklingel-Alarm auch, wenn seine
    /// Nacht unbekannt ist); ein «stop» zu einer anderen Nacht lässt das iPhone weiterklingeln. `nil` = Stopp am
    /// iPhone: der Mitklingel-Alarm und jeder gerade klingelnde Alarm. `snoozed`: Die Uhr schlummert nur
    /// (Schlummer-Ende bekannt), ihre Sicherung bleibt.
    func stopRinging(night: RingNight? = nil, snoozed: Bool = false) async {
        let id = ringingId
        let ownsRing = id.map { ring in night.map { stop in self.night(for: ring).map(stop.same) ?? true } ?? true }
            ?? false
        if ownsRing { ringingId = nil }
        // Beendet (nicht nur das Echo des eigenen Schlummerns): auch die Schlummer-Sicherungen dieser Nacht weg.
        let snoozedAt = settings.double(forKey: Self.snoozedAtKey)
        if !snoozed, Date().timeIntervalSince1970 - snoozedAt > Self.snoozeEcho {
            if belongs(storedId(Self.snoozeSafetyKey), to: night, unknown: true) { await cancelSnoozeSafety() }
            if belongs(storedId(Self.backupSnoozeKey), to: night, unknown: true) { cancelBackupSnooze() }
        }
        if platform.alarmKit {
            let alerting = platform.alertingIds().filter { alarm in
                alarm == id ? ownsRing : belongs(alarm, to: night, unknown: false)
            }
            // Vor dem Stoppen: Der Probewecker zählt nur, was wirklich geklingelt hat.
            for alarm in alerting { host.phoneAlerted(alarm.uuidString) }
            if ownsRing, let id {
                platform.stop(id)
                // Noch nicht klingelnd (geplant in 2 s): verwerfen.
                platform.cancelScheduled(id)
            }
            for alarm in alerting {
                stoppedByApp.insert(alarm)
                platform.stop(alarm)
            }
        }
        // Vor iOS 26, und ab iOS 26 für Alarme, die nach gescheitertem AlarmKit als Mitteilung kamen.
        await removeNotificationChains(ringing: ownsRing ? id : nil, night: night)
    }

    /// Ob der iPhone-Alarm `id` zur Nacht `night` gehört; `night == nil` (Stopp am iPhone) trifft alles. Ist die Nacht
    /// des Alarms nicht gemerkt, gilt `unknown`.
    func belongs(_ id: UUID?, to night: RingNight?, unknown: Bool) -> Bool {
        guard let id else { return false }
        guard let night else { return true }
        return self.night(for: id).map(night.same) ?? unknown
    }

    /// Ob das iPhone gerade mit der Uhr klingelt oder nach dem Schlummern auf sie wartet (Probewecker/Powernap
    /// sperren).
    var ringActive: Bool { ringingId != nil || storedId(Self.snoozeSafetyKey) != nil }

    /// Ob die Kennung zum gerade mitklingelnden Wecker gehört (nicht zu einem schon per Code gestoppten).
    func isCurrentRing(_ alarmId: String) -> Bool { ringingId?.uuidString == alarmId }

    // MARK: Schlummern

    /// Nacht des gerade mitklingelnden Alarms (vor dem Stoppen lesen); `nil`, wenn keiner klingelt oder unbekannt.
    var currentRingNight: RingNight? { ringingId.flatMap { self.night(for: $0) } }

    /// Am iPhone geschlummert: Eigene Sicherung kurz nach dem Schlummer-Ende, falls die Uhr dann nicht
    /// klingelt (ihre neue Sitzung startet im Hintergrund unter Umständen nicht). `night` = `currentRingNight`.
    func snoozeSafety(night: RingNight?) async {
        settings.set(Date().timeIntervalSince1970, forKey: Self.snoozedAtKey)
        await scheduleSnoozeSafety(at: Snooze.nextRing(Date()).addingTimeInterval(Self.snoozeSafetyDelay), night: night)
    }

    /// Die Uhr hat geschlummert (ihr «stop» trägt das Schlummer-Ende `until`): Sicherung 90 s nach dem Schlummer-Ende,
    /// wie `RingMessage.snoozeSafetyAt` auf Android. Liegt der Zeitpunkt schon zurück (verspätet zugestellt, die Uhr
    /// hat inzwischen wieder geklingelt), keine.
    func watchSnoozed(night: RingNight, until: Date) async {
        let at = until.addingTimeInterval(Self.snoozeSafetyDelay)
        guard at > Date() else { return }
        await scheduleSnoozeSafety(at: at, night: night)
        DiagStore.shared.log(.snooze, "watch, phone safety \(Format.logTime(at))")
    }

    private func scheduleSnoozeSafety(at date: Date, night: RingNight?) async {
        await cancelSnoozeSafety()
        let id = UUID()
        settings.set(id.uuidString, forKey: Self.snoozeSafetyKey)
        setNight(night, for: id)
        // Runden wie auf der Uhr: Diese Runde ist verbraucht.
        let left = max(0, settings.integer(forKey: Self.ringSnoozesKey) - 1)
        await schedule(id: id, at: date, snoozesLeft: left)
    }

    private func cancelSnoozeSafety() async {
        guard let id = storedId(Self.snoozeSafetyKey) else { return }
        settings.removeObject(forKey: Self.snoozeSafetyKey)
        cancelAlarm(id)
    }

    /// «Schlummern» an einer Sicherung (klingelt ohne Uhr, nur ab iOS 26): Alarm `alarmId` stoppen und in 5 Minuten
    /// wieder klingeln, mit `snoozesLeft` − 1 Runden; gehört zur selben Nacht wie der geschlummerte Alarm.
    func backupSnoozed(_ alarmId: String, snoozesLeft: Int) async {
        guard let id = UUID(uuidString: alarmId) else { return }
        if platform.alarmKit {
            stoppedByApp.insert(id)
            platform.stop(id)
        }
        let tone = plannedBackups.first { $0.id == id }?.slot.sound
            ?? (storedId(Self.backupSnoozeKey) == id ? settings.string(forKey: Self.backupSnoozeToneKey) : nil)
        let snoozedNight = self.night(for: id)
        cancelBackupSnooze()
        let next = UUID()
        settings.set(next.uuidString, forKey: Self.backupSnoozeKey)
        settings.set(tone, forKey: Self.backupSnoozeToneKey)
        setNight(snoozedNight, for: next)
        let ringAt = Snooze.nextRing(Date())
        settings.set(ringAt.timeIntervalSince1970, forKey: Self.backupSnoozeUntilKey)
        PrivateSettingChanges.shared.bump()
        await schedule(id: next, at: ringAt, snoozesLeft: max(0, snoozesLeft - 1),
                       tone: tone.flatMap(WakeTone.init(rawValue:)))
        DiagStore.shared.log(.snooze, "phone backup, \(max(0, snoozesLeft - 1)) left")
    }

    /// «Stopp» an einer Sicherung, die ohne Uhr klingelt (nur ab iOS 26): Die Uhr hört für dieselbe Nacht auch auf (sie
    /// klingelt etwa über ihren Rückfall oder schlummert noch). Hat die App den Alarm selbst gestoppt, nichts senden;
    /// ohne gemerkte Nacht auch nicht (sonst träfe es jede Nacht der Uhr).
    func backupStopped(_ alarmId: String) {
        guard let id = UUID(uuidString: alarmId), stoppedByApp.remove(id) == nil,
              let night = night(for: id) else { return }
        DiagStore.shared.log(.stop, "phone backup, watch too")
        host.sendStop(night)
    }

    func cancelBackupSnooze() {
        guard let id = storedId(Self.backupSnoozeKey) else { return }
        settings.removeObject(forKey: Self.backupSnoozeKey)
        settings.removeObject(forKey: Self.backupSnoozeUntilKey)
        PrivateSettingChanges.shared.bump()
        cancelAlarm(id)
    }

    // MARK: Gemerkte Kennungen

    /// Kennungen der einzeln gemerkten Alarme (Mitklingeln, Schlummer-Sicherung, Schlummern einer Sicherung,
    /// Probewecker) samt gestellter Sicherungen; nur deren Nächte bleiben gemerkt.
    var activeIds: [UUID] {
        [Self.ringingKey, Self.snoozeSafetyKey, Self.backupSnoozeKey, Self.transientKey].compactMap(storedId)
            + plannedBackups.map(\.id)
    }

    func storedId(_ key: String) -> UUID? {
        settings.string(forKey: key).flatMap(UUID.init(uuidString:))
    }
}
