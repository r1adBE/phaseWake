import Foundation
import PhaseWakeCore
import UserNotifications
import WatchConnectivity
#if canImport(AlarmKit)
import AlarmKit
#endif

/// Eine Nacht mit Zweitwecker: Wecker, Zielzeit, Klingelzeit (Zielzeit plus Nachfrist) und Weckton.
/// Gleich heisst: gleiche Kennung, gleicher Ton und gleiche Zeiten auf die Millisekunde.
struct BackupSlot: Codable, Equatable, Sendable {
    /// Kennung des Weckers.
    let alarmId: String
    /// Zielzeit der Nacht.
    let target: Date
    /// Zeitpunkt, zu dem das iPhone klingelt.
    let fireAt: Date
    /// Weckton des Weckers (`AlarmSpec.sound`).
    let sound: String

    /// Nacht, zu der die Sicherung gehört (für «stop» der Uhr).
    var night: RingNight { RingNight(alarmId: alarmId, target: target) }

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
        get { UserDefaults.standard.bool(forKey: watchPresentKey) }
        set { UserDefaults.standard.set(newValue, forKey: watchPresentKey) }
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
            guard let data = UserDefaults.standard.data(forKey: Self.plannedKey) else { return [] }
            return (try? JSONDecoder().decode([PlannedBackup].self, from: data)) ?? []
        }
        set { UserDefaults.standard.set(try? JSONEncoder().encode(newValue), forKey: Self.plannedKey) }
    }

    /// Gleicht die gestellten Sicherungen mit `wanted` ab: Gleiche, noch vorhandene bleiben; überholte, die noch nicht
    /// geklingelt haben, werden verworfen; fehlende werden gestellt und sofort einzeln gemerkt. Eine Sicherung, deren
    /// Klingelzeit schon da ist, wird nie abgebrochen (sie klingelt womöglich gerade).
    func apply(_ wanted: [BackupSlot], now: Date) async {
        await cancelLegacyBackups()
        let present = await pendingIds()
        var unused = plannedBackups
        var result: [PlannedBackup] = []
        var missing: [BackupSlot] = []
        for slot in wanted {
            if let i = unused.firstIndex(where: { $0.slot == slot && present.contains($0.id) }) {
                result.append(unused.remove(at: i))
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
        plannedBackups = result
        for slot in missing {
            let id = UUID()
            // Erst merken, dann stellen: Endet die App dazwischen, geht die Kennung nicht verloren.
            result.append(PlannedBackup(id: id, slot: slot))
            plannedBackups = result
            await schedule(id: id, at: slot.fireAt, snoozesLeft: Snooze.maxCount, tone: WakeTone(rawValue: slot.sound))
        }
        UserDefaults.standard.set(TimeZone.current.identifier, forKey: Self.zoneKey)
        pruneRingNights()
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

    /// Kennungen, die noch gestellt sind: AlarmKit-Alarme (ab iOS 26) und wartende Mitteilungen.
    private func pendingIds() async -> Set<UUID> {
        var ids = Set<UUID>()
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) {
            ids.formUnion(((try? AlarmManager.shared.alarms) ?? []).map(\.id))
        }
        #endif
        let requests = await UNUserNotificationCenter.current().pendingNotificationRequests()
        ids.formUnion(requests.compactMap { UUID(uuidString: $0.identifier) })
        return ids
    }

    /// Bis Version 16.2 nur Kennungen ohne Nacht gemerkt: einmal alle verwerfen, danach gilt `plannedBackups`.
    private func cancelLegacyBackups() async {
        guard let ids = UserDefaults.standard.stringArray(forKey: Self.legacyIdsKey) else { return }
        UserDefaults.standard.removeObject(forKey: Self.legacyIdsKey)
        for id in ids.compactMap(UUID.init(uuidString:)) { cancelAlarm(id) }
    }

    // MARK: Zeitzone

    /// Beim Start der App (auch im Hintergrund): Seit dem letzten Planen die Zeitzone gewechselt (Reise, App nicht
    /// offen), dann neu planen. AlarmKit-Alarme stehen auf festen Zeitpunkten und folgen der Ortszeit sonst nicht.
    func replanIfZoneChanged(_ alarms: [AlarmSpec]) async {
        guard let planned = UserDefaults.standard.string(forKey: Self.zoneKey), planned != TimeZone.current.identifier else { return }
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
            guard let data = UserDefaults.standard.data(forKey: Self.ringNightsKey) else { return [:] }
            return (try? JSONDecoder().decode([String: RingNight].self, from: data)) ?? [:]
        }
        set { UserDefaults.standard.set(try? JSONEncoder().encode(newValue), forKey: Self.ringNightsKey) }
    }

    /// Behält nur die Nächte der Alarme, die noch gemerkt sind.
    private func pruneRingNights() {
        let live = Set(activeIds.map(\.uuidString))
        let all = ringNights
        let kept = all.filter { live.contains($0.key) }
        if kept.count != all.count { ringNights = kept }
    }
}
