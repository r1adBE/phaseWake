import Foundation

/// Wecker samt Zeitpunkt der letzten Änderung der Liste (`updatedAt`) für den Abgleich iPhone ↔ Uhr. Seit Audit A-4 dazu
/// je Wecker der Zeitpunkt seiner letzten Änderung (`stamps`; fehlt einer, gilt `updatedAt`, siehe ``stampOf(_:)``) und
/// die gelöschten Wecker mit dem Zeitpunkt des Löschens (`deleted`), siehe ``AlarmStamps``.
public struct AlarmSnapshot: Equatable, Sendable {
    /// Wecker, sortiert nach Uhrzeit und Kennung.
    public let alarms: [AlarmSpec]
    /// Zeitpunkt der letzten Änderung in Millisekunden seit 1970; 0 = nie geändert.
    public let updatedAt: Int64
    /// Zeitpunkt der letzten Änderung je Wecker (Kennung → ms).
    public let stamps: [String: Int64]
    /// Gelöschte Wecker mit Zeitpunkt des Löschens (Kennung → ms).
    public let deleted: [String: Int64]
    /// `false`: Stand einer früheren Version ohne Zeitpunkte je Wecker; beim Übernehmen gilt «neuere Liste gewinnt».
    public let perAlarm: Bool

    /// Erzeugt einen Stand.
    public init(alarms: [AlarmSpec], updatedAt: Int64, stamps: [String: Int64] = [:], deleted: [String: Int64] = [:], perAlarm: Bool = true) {
        self.alarms = alarms
        self.updatedAt = updatedAt
        self.stamps = stamps
        self.deleted = deleted
        self.perAlarm = perAlarm
    }

    /// Zeitpunkt der letzten Änderung des Weckers `id`.
    public func stampOf(_ id: String) -> Int64 { stamps[id] ?? updatedAt }
}

/// Abgleich der Wecker je Wecker (Audit A-4, Entscheid «per-alarm updatedAt merge», wie `AlarmStamps` auf Android):
/// Früher gewann die neuere ganze Liste; änderte ein Gerät automatisch einen Wecker (Einmal-Wecker nach dem Klingeln
/// aus), während auf dem anderen der Nutzer einen Wecker bearbeitete, ging eine der beiden Änderungen verloren. Jetzt
/// trägt jeder Wecker den Zeitpunkt seiner letzten Änderung, und ein gelöschter Wecker bleibt als Eintrag mit dem
/// Zeitpunkt des Löschens («Grabstein»), bis er ``tombstoneMaxAgeMs`` alt ist.
public enum AlarmStamps {
    /// So lange bleibt ein gelöschter Wecker gemerkt (ein Gerät, das länger getrennt war, brächte ihn sonst zurück).
    public static let tombstoneMaxAgeMs: Int64 = 90 * 24 * 3_600_000

    /// Höchstens so viele gelöschte Wecker bleiben gemerkt, die neuesten.
    public static let maxTombstones = 200

    /// Führt `local` und `remote` zusammen; das Ergebnis hängt nicht davon ab, welche Seite lokal ist (beide Geräte
    /// kommen zum selben Stand). Je Wecker gilt die Fassung mit dem neueren Zeitpunkt (``AlarmSnapshot/stampOf(_:)``);
    /// bei gleichem Zeitpunkt und verschiedenem Inhalt entscheidet ein fester Vergleich. Ein Löschen gewinnt, wenn es
    /// nicht älter als diese Fassung ist; ein Wecker, den eine Seite nie kannte und nirgends gelöscht ist, bleibt.
    /// Listen-Zeitpunkt: der spätere beider Seiten. Gelöschte Wecker älter als ``tombstoneMaxAgeMs`` vor `now` (ms)
    /// fallen weg. Die Wecker des Ergebnisses sind nach Kennung geordnet.
    public static func merge(local: AlarmSnapshot, remote: AlarmSnapshot, now: Int64) -> AlarmSnapshot {
        let mine = byId(local.alarms), theirs = byId(remote.alarms)
        let ids = Set(mine.keys).union(theirs.keys).union(local.deleted.keys).union(remote.deleted.keys).sorted()
        var alarms: [AlarmSpec] = []
        var stamps: [String: Int64] = [:]
        var deleted: [String: Int64] = [:]
        for id in ids {
            let version = newer(mine[id].map { ($0, local.stampOf(id)) }, theirs[id].map { ($0, remote.stampOf(id)) })
            let gone = [local.deleted[id], remote.deleted[id]].compactMap { $0 }.max()
            if let version, gone.map({ version.1 > $0 }) ?? true {
                alarms.append(version.0)
                stamps[id] = version.1
            } else if let gone {
                deleted[id] = gone
            }
        }
        return AlarmSnapshot(alarms: alarms, updatedAt: max(local.updatedAt, remote.updatedAt), stamps: stamps,
                             deleted: prune(deleted, now: now))
    }

    /// Gelöschte Wecker ohne zu alte (``tombstoneMaxAgeMs`` vor `now`, ms), höchstens ``maxTombstones``, die neuesten.
    public static func prune(_ deleted: [String: Int64], now: Int64) -> [String: Int64] {
        let kept = deleted.filter { now - $0.value <= tombstoneMaxAgeMs }
            .sorted { ($0.value, $1.key) > ($1.value, $0.key) }
            .prefix(maxTombstones)
        return Dictionary(uniqueKeysWithValues: kept.map { ($0.key, $0.value) })
    }

    /// Legt `snapshot` in den Anwendungskontext: Wecker, Listen-Zeitpunkt, Zeitpunkt je Wecker und gelöschte Wecker
    /// (`SyncKeys.alarmStamps`, `SyncKeys.alarmsDeleted`).
    public static func apply(_ snapshot: AlarmSnapshot, to context: inout [String: Any]) {
        context[SyncKeys.alarms] = Codec.encodeAlarms(snapshot.alarms)
        context[SyncKeys.updatedAt] = snapshot.updatedAt
        context[SyncKeys.alarmStamps] = Dictionary(snapshot.alarms.map { ($0.id, snapshot.stampOf($0.id)) }, uniquingKeysWith: max)
        context[SyncKeys.alarmsDeleted] = snapshot.deleted
    }

    /// Liest den Stand der Gegenseite aus dem Anwendungskontext; `nil` ohne oder mit unlesbaren Weckern. Fehlen die
    /// Zeitpunkte je Wecker (ältere Version), ist ``AlarmSnapshot/perAlarm`` `false`.
    public static func snapshot(context: [String: Any]) -> AlarmSnapshot? {
        guard let text = context[SyncKeys.alarms] as? String,
              let updatedAt = context[SyncKeys.updatedAt] as? Int64,
              let alarms = try? Codec.decodeAlarms(text) else { return nil }
        guard let stamps = int64Map(context[SyncKeys.alarmStamps]) else {
            return AlarmSnapshot(alarms: alarms, updatedAt: updatedAt, perAlarm: false)
        }
        return AlarmSnapshot(alarms: alarms, updatedAt: updatedAt, stamps: stamps, deleted: int64Map(context[SyncKeys.alarmsDeleted]) ?? [:])
    }

    /// Wörterbuch Kennung → ms aus einer Property List (Werte `Int64` oder `NSNumber`); `nil`, wenn es keines ist.
    /// Unlesbare Werte fallen weg.
    static func int64Map(_ value: Any?) -> [String: Int64]? {
        guard let raw = value as? [String: Any] else { return nil }
        return raw.compactMapValues { ($0 as? Int64) ?? ($0 as? NSNumber)?.int64Value }
    }

    /// Wecker nach Kennung; bei doppelter Kennung gilt der erste.
    static func byId(_ alarms: [AlarmSpec]) -> [String: AlarmSpec] {
        Dictionary(alarms.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
    }

    /// Die neuere von zwei Fassungen (Wecker, Zeitpunkt); bei Gleichstand fest nach Inhalt.
    private static func newer(_ a: (AlarmSpec, Int64)?, _ b: (AlarmSpec, Int64)?) -> (AlarmSpec, Int64)? {
        guard let a else { return b }
        guard let b else { return a }
        if a.1 != b.1 { return a.1 > b.1 ? a : b }
        return Codec.encodeAlarms([a.0]) >= Codec.encodeAlarms([b.0]) ? a : b
    }
}
