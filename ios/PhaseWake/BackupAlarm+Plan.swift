import Foundation
import PhaseWakeCore
import UserNotifications
import WatchConnectivity
#if canImport(AlarmKit)
import AlarmKit
#endif

/// Eine Nacht mit Zweitwecker: Wecker, Zielzeit, Klingelzeit (Zielzeit plus Nachfrist), Weckton und Zeitzone der
/// Planung. Gleich heisst: gleiche Kennung, gleicher Ton und gleiche Zeiten auf die Millisekunde (die Zone zählt nicht).
struct BackupSlot: Codable, Equatable, Sendable {
    /// Kennung des Weckers.
    let alarmId: String
    /// Zielzeit der Nacht.
    let target: Date
    /// Zeitpunkt, zu dem das iPhone klingelt.
    let fireAt: Date
    /// Weckton des Weckers (`AlarmSpec.sound`).
    let sound: String
    /// Zeitzone, in der die Nacht berechnet wurde (`TimeZone.identifier`), für `AlarmSchedule.begunNight` nach einer
    /// Reise. Gemerkt ab 04.11.2026; ältere gespeicherte Sicherungen haben keine (`nil`: Verhalten wie bisher).
    let zone: String?

    /// Nacht, zu der die Sicherung gehört (für «stop» der Uhr).
    var night: RingNight { RingNight(alarmId: alarmId, target: target) }

    /// Kalender der Planung (gregorianisch in `zone`, wie `Calendar.gregorianLocal`), oder `nil` ohne bekannte Zone.
    var plannedCalendar: Calendar? {
        guard let zone = zone.flatMap(TimeZone.init(identifier:)) else { return nil }
        var calendar = Calendar.gregorian(in: zone)
        calendar.locale = .autoupdatingCurrent
        return calendar
    }

    /// Dieselbe Nacht mit Zone `zone`.
    func with(zone: String?) -> BackupSlot {
        BackupSlot(alarmId: alarmId, target: target, fireAt: fireAt, sound: sound, zone: zone)
    }

    static func == (lhs: BackupSlot, rhs: BackupSlot) -> Bool {
        lhs.alarmId == rhs.alarmId && lhs.sound == rhs.sound
            && Self.millis(lhs.target) == Self.millis(rhs.target) && Self.millis(lhs.fireAt) == Self.millis(rhs.fireAt)
    }

    private static func millis(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }
}

/// Ein gestellter Zweitwecker: iPhone-Alarm `id` für `slot`. Gemerkt, sobald er gestellt wird, damit ein Neuplanen
/// ihn behalten kann (Nachfrist) und «stop» der Uhr nur die eigene Nacht trifft.
struct PlannedBackup: Codable, Equatable, Sendable {
    /// Kennung des AlarmKit-Alarms bzw. der Mitteilung.
    let id: UUID
    /// Die Nacht dazu.
    let slot: BackupSlot
}

extension BackupAlarm {
    nonisolated private static let plannedKey = "backupPlanned"
    nonisolated private static let legacyIdsKey = "backupAlarmIds"
    nonisolated private static let ringNightsKey = "backupRingNights"
    nonisolated private static let zoneKey = "backupPlannedZone"
    nonisolated private static let watchPresentKey = "backupWatchPresent"
    /// Nachfrist mit Uhr: Die Uhr hat 2 Minuten, bevor das iPhone als Sicherung klingelt.
    nonisolated private static let watchGrace: TimeInterval = 120
    /// So lange nach dem Klingeln bleibt ein gestellter Zweitwecker gemerkt (für «stop» der Uhr, Schlummern).
    static let ringMemory: TimeInterval = 3600

    // MARK: Nachfrist

    /// Ob eine Apple Watch gekoppelt ist und Phase Wake darauf installiert ist, wie zuletzt von WatchConnectivity
    /// gemeldet (auch vor der Aktivierung nach einem Kaltstart bekannt). Unbekannt zählt als «keine Uhr».
    nonisolated static var watchPresent: Bool {
        get { SettingsFile.phone.bool(forKey: watchPresentKey) }
        set { SettingsFile.phone.set(newValue, forKey: watchPresentKey) }
    }

    /// Nachfrist des Zweitweckers: 120 s mit Uhr, ohne Uhr 0 s – dann klingelt das iPhone pünktlich zur Zielzeit
    /// (wie Android ohne bereite Uhr).
    nonisolated static var grace: TimeInterval { watchPresent ? watchGrace : 0 }

    /// Neuer Stand der Kopplung (aktiviert, Uhr gewechselt, App auf der Uhr installiert oder entfernt): merken und
    /// bei einer Änderung neu planen. Von jedem Thread aus.
    nonisolated static func noteWatchState(_ session: WCSession) {
        guard session.activationState == .activated else { return }
        let present = session.isPaired && session.isWatchAppInstalled
        guard present != watchPresent else { return }
        watchPresent = present
        Task { @MainActor in
            DiagStore.shared.log(.sync, "watch present \(present)")
            await PhoneModel.shared.backup.reschedule(PhoneModel.shared.alarms)
        }
    }

    // MARK: Gestellte Sicherungen

    /// Die gestellten Zweitwecker (auch schon geklingelte der letzten Stunde).
    var plannedBackups: [PlannedBackup] {
        get {
            guard let data = SettingsFile.phone.data(forKey: Self.plannedKey) else { return [] }
            return (try? JSONDecoder().decode([PlannedBackup].self, from: data)) ?? []
        }
        set { SettingsFile.phone.set(try? JSONEncoder().encode(newValue), forKey: Self.plannedKey) }
    }

    /// Gleicht die gestellten Sicherungen mit `wanted` ab: Gleiche, noch vorhandene bleiben (samt ihrer Zone; eine
    /// ältere ohne Zone bekommt die von `wanted`); überholte, die noch nicht geklingelt haben, werden verworfen; fehlende
    /// werden gestellt und sofort einzeln gemerkt. Eine Sicherung, deren Klingelzeit schon da ist, wird nie abgebrochen
    /// (sie klingelt womöglich gerade). Als Mitteilung (vor iOS 26, AlarmKit gescheitert) nach Klingelzeit im Rahmen von
    /// `NotificationBudget`: Eine bleibende, die näher gerückt ist, bekommt ihre Kette ergänzt; eine fehlende ohne Platz
    /// wird nicht gestellt und nicht gemerkt (der Nacht-Check sieht dann den kürzeren Vorlauf).
    func apply(_ wanted: [BackupSlot], now: Date) async {
        await cancelLegacyBackups()
        let pending = await pendingState()
        var unused = plannedBackups
        var result: [PlannedBackup] = []
        var matched: [PlannedBackup] = []
        var missing: [BackupSlot] = []
        for slot in wanted {
            if let i = unused.firstIndex(where: { $0.slot == slot && pending.present.contains($0.id) }) {
                let kept = unused.remove(at: i)
                matched.append(kept.slot.zone == nil ? PlannedBackup(id: kept.id, slot: kept.slot.with(zone: slot.zone)) : kept)
            } else {
                missing.append(slot)
            }
        }
        for entry in unused {
            if entry.slot.fireAt > now {
                cancelAlarm(entry.id)
            } else if now.timeIntervalSince(entry.slot.fireAt) < Self.ringMemory {
                result.append(entry)
            }
        }
        result += matched
        plannedBackups = result
        var budget = NotificationBudget(otherPending: pending.otherNotes)
        var notes = (slots: 0, chained: 0, dropped: 0)
        var order: [(slot: BackupSlot, kept: PlannedBackup?)] = matched.map { (slot: $0.slot, kept: Optional($0)) }
        order += missing.map { (slot: $0, kept: PlannedBackup?.none) }
        order.sort { $0.slot.fireAt < $1.slot.fireAt }
        for (slot, kept) in order {
            var count: Int
            if let kept {
                // AlarmKit: kein Platz nötig. Als Mitteilung: Kette ergänzen, falls näher gerückt.
                guard let have = pending.notes[kept.id] else { continue }
                count = budget.grant(slot.fireAt, now: now, have: have.count)
                if count > have.count {
                    count = await scheduleNotification(id: kept.id, at: slot.fireAt, tone: WakeTone(rawValue: slot.sound),
                                                       count: count, skip: have)
                }
            } else {
                let id = UUID()
                // Erst merken, dann stellen: Endet die App dazwischen, geht die Kennung nicht verloren.
                result.append(PlannedBackup(id: id, slot: slot))
                plannedBackups = result
                let placed = await schedule(id: id, at: slot.fireAt, snoozesLeft: Snooze.maxCount, tone: WakeTone(rawValue: slot.sound),
                                            chain: budget.grant(slot.fireAt, now: now))
                guard case .notifications(let placedCount) = placed else { continue }
                count = placedCount
                if placedCount == 0 {
                    result.removeAll { $0.id == id }
                    plannedBackups = result
                    notes.dropped += 1
                    continue
                }
            }
            budget.use(count)
            notes.slots += 1
            if count >= NotificationChain.count { notes.chained += 1 }
        }
        SettingsFile.phone.set(TimeZone.current.identifier, forKey: Self.zoneKey)
        pruneRingNights()
        guard notes.slots + notes.dropped > 0 else { return }
        let total = await UNUserNotificationCenter.current().pendingNotificationRequests().count
        DiagStore.shared.log(.scheduled, "backup notifications \(notes.slots) slots, \(notes.chained) chained, "
                             + "\(notes.dropped) without room, pending \(total)")
    }

    // MARK: Nacht-Check

    /// So knapp darf die Sicherung werden, bevor der Nacht-Check warnt.
    nonisolated static let shortHorizon: TimeInterval = 48 * 3600

    /// Ob die Sicherung bald aufgebraucht ist (Nacht-Check, rot): Ein eingeschalteter Wecker mit Zweitwecker hat noch
    /// einen Termin, aber es ist keine Sicherung gestellt, oder die letzte klingelt in weniger als `shortHorizon` und
    /// danach folgt noch ein Termin eines wiederholten Weckers (einmalige haben nach ihrem ersten keinen mehr). Dann hat
    /// iOS die App zu lange nicht geweckt; Öffnen plant wieder `horizon` weit. Wartet die laufende Neuplanung ab.
    func horizonShort(_ alarms: [AlarmSpec], now: Date = Date()) async -> Bool {
        await settled()
        let eligible = alarms.filter(\.phoneBackup)
        let holidays = PhoneModel.shared.holidays.dates
        let suppressed = suppressedUntil()
        func hasPlan(_ specs: [AlarmSpec], after from: Date) -> Bool {
            AlarmSchedule.earliestPlan(specs, now: from, doneUntil: suppressed, calendar: .gregorianLocal, holidays: holidays) != nil
        }
        let planned = plannedBackups
        guard let last = planned.filter({ $0.slot.fireAt > now }).max(by: { $0.slot.fireAt < $1.slot.fireAt }) else {
            // Ein einmaliger Wecker, dessen Sicherung schon geklingelt hat, schaltet sich gleich aus: zählt nicht.
            return hasPlan(eligible.filter { spec in !spec.isOneShot || !planned.contains { $0.slot.alarmId == spec.id } }, after: now)
        }
        guard last.slot.fireAt.timeIntervalSince(now) < Self.shortHorizon else { return false }
        return hasPlan(eligible.filter { !$0.isOneShot }, after: last.slot.target)
    }

    /// Was noch gestellt ist: `present` = Kennungen der AlarmKit-Alarme (ab iOS 26) und wartenden Mitteilungen; `notes`
    /// = je gemerkter Sicherung die wartenden Mitteilungen ihrer Kette; `otherNotes` = wartende Mitteilungen ohne die
    /// der gemerkten Sicherungen (für `NotificationBudget`).
    private func pendingState() async -> (present: Set<UUID>, notes: [UUID: Set<String>], otherNotes: Int) {
        var present = Set<UUID>()
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) {
            do {
                let alarms = try AlarmManager.shared.alarms
                present.formUnion(alarms.map(\.id))
            } catch {
                DiagStore.shared.log(.error, "alarmkit list failed \((error as NSError).code)")
            }
        }
        #endif
        let requests = await UNUserNotificationCenter.current().pendingNotificationRequests()
        let backups = Set(plannedBackups.map(\.id))
        var notes: [UUID: Set<String>] = [:]
        var other = 0
        for request in requests {
            // Vor iOS 26 eine Kette aus Mitteilungen: Steht noch eine davon, gilt die Sicherung als gestellt.
            guard let id = UUID(uuidString: NotificationChain.baseId(request.identifier)) else {
                other += 1
                continue
            }
            present.insert(id)
            if backups.contains(id) {
                notes[id, default: []].insert(request.identifier)
            } else {
                other += 1
            }
        }
        return (present, notes, other)
    }

    /// Bis Version 16.2 nur Kennungen ohne Nacht gemerkt: einmal alle verwerfen, danach gilt `plannedBackups`.
    private func cancelLegacyBackups() async {
        guard let ids = SettingsFile.phone.stringArray(forKey: Self.legacyIdsKey) else { return }
        SettingsFile.phone.removeObject(forKey: Self.legacyIdsKey)
        for id in ids.compactMap(UUID.init(uuidString:)) { cancelAlarm(id) }
    }

    // MARK: Zeitzone

    /// Beim Start der App (auch im Hintergrund): Seit dem letzten Planen die Zeitzone gewechselt (Reise, App nicht
    /// offen), dann neu planen. AlarmKit-Alarme stehen auf festen Zeitpunkten und folgen der Ortszeit sonst nicht.
    func replanIfZoneChanged(_ alarms: [AlarmSpec]) async {
        guard let planned = SettingsFile.phone.string(forKey: Self.zoneKey), planned != TimeZone.current.identifier else { return }
        DiagStore.shared.log(.scheduled, "time zone changed since planning")
        await reschedule(alarms)
    }

    // MARK: Nacht je Alarm

    /// Merkt, zu welcher Nacht ein iPhone-Alarm gehört (`nil` = vergessen).
    func setNight(_ night: RingNight?, for id: UUID) {
        var all = ringNights
        all[id.uuidString] = night
        ringNights = all
    }

    /// Nacht des iPhone-Alarms `id`: gestellte Sicherung oder gemerkt (Mitklingeln, Schlummern, Probewecker).
    func night(for id: UUID) -> RingNight? {
        plannedBackups.first { $0.id == id }?.slot.night ?? ringNights[id.uuidString]
    }

    private var ringNights: [String: RingNight] {
        get {
            guard let data = SettingsFile.phone.data(forKey: Self.ringNightsKey) else { return [:] }
            return (try? JSONDecoder().decode([String: RingNight].self, from: data)) ?? [:]
        }
        set { SettingsFile.phone.set(try? JSONEncoder().encode(newValue), forKey: Self.ringNightsKey) }
    }

    /// Behält nur die Nächte der Alarme, die noch gemerkt sind.
    private func pruneRingNights() {
        let live = Set(activeIds.map(\.uuidString))
        let all = ringNights
        let kept = all.filter { live.contains($0.key) }
        if kept.count != all.count { ringNights = kept }
    }
}
