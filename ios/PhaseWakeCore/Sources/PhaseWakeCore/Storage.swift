import Foundation
#if canImport(os)
import os
#endif

/// Lokaler Speicher für Wecker und Nächte, auf iPhone und Uhr gleich. Gleiches Textformat wie Android.
public final class PhaseWakeStorage: @unchecked Sendable {
    /// Intern statt privat: Die Erweiterung in `Storage+Notes.swift` liest und schreibt darüber.
    let defaults: any SettingsStore
    private let nightsURL: URL
    /// Intern statt privat, wie `defaults`.
    let lock = NSLock()

    private static let alarmsKey = "alarms"
    private static let updatedKey = "alarmsUpdatedAt"
    /// Zeitpunkt je Wecker und gelöschte Wecker (Kennung → ms), Audit A-4.
    private static let stampsKey = "alarmsStamps"
    private static let deletedKey = "alarmsDeleted"
    private static let separator = "\n%%\n"

    /// App-Gruppe der Uhr-App und ihrer Komplikation.
    public static let watchGroup = "group.com.phasewake.app"

    /// Name der Einstellungsdatei der Uhr (Wecker, erledigte Nächte, Feiertage, Schlafziel) in ``watchGroupDirectory(create:)``.
    public static let watchSettingsName = "settings.v1"

    /// Ordner «Private» in der App-Gruppe der Uhr; `nil` ohne App-Gruppe. Mit `create` angelegt und von der Sicherung
    /// ausgenommen (wie `PrivateFiles`: das Flag sitzt auf dem Ordner, weil atomares Schreiben die Datei ersetzt). Nur
    /// die Uhr-App legt an; die Komplikation liest bloss.
    public static func watchGroupDirectory(create: Bool) -> URL? {
        #if canImport(Darwin)
        guard let group = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: watchGroup) else { return nil }
        let dir = group.appendingPathComponent("Private", isDirectory: true)
        if create {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            var flagged = dir
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? flagged.setResourceValues(values)
        }
        return dir
        #else
        return nil
        #endif
    }

    /// Wecker der Uhr für die Komplikation, nur lesend: die Datei der Uhr-App (``watchSettingsName``), bei jedem Zugriff
    /// frisch gelesen (``SettingsFile`` mit `readOnly`). Gibt es sie noch nicht (die Uhr-App hat die Werte früherer
    /// Versionen noch nicht übernommen), die `UserDefaults` der App-Gruppe wie bisher; ohne App-Gruppe kein Wecker.
    public static var watchShared: PhaseWakeStorage {
        guard let url = watchSharedURL else { return watchSharedNone }
        return FileManager.default.fileExists(atPath: url.path) ? watchSharedFile : watchSharedLegacy
    }

    private static let watchSharedURL = watchGroupDirectory(create: false)?.appendingPathComponent(watchSettingsName)
    /// Pfad, den es nie gibt: ein nur lesender Speicher darauf ist immer leer.
    private static let noFile = URL(fileURLWithPath: "/dev/null/none")
    private static let watchSharedFile = PhaseWakeStorage(defaults: SettingsFile(url: watchSharedURL ?? noFile, readOnly: true))
    private static let watchSharedLegacy = PhaseWakeStorage(defaults: UserDefaults(suiteName: watchGroup) ?? UserDefaults.standard)
    private static let watchSharedNone = PhaseWakeStorage(defaults: SettingsFile(url: noFile, readOnly: true))

    /// Erzeugt einen Speicher. `defaults` = Ort der Wecker und Einstellungen (iPhone und Uhr: eine ``SettingsFile``
    /// ausserhalb der Sicherung). `directory` = Ordner für die Nächte (Standard: Application Support); Tests geben
    /// einen eigenen.
    public init(defaults: any SettingsStore = UserDefaults.standard, directory: URL? = nil) {
        self.defaults = defaults
        let dir = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        nightsURL = dir.appendingPathComponent("nights.v1")
    }

    // MARK: Wecker

    /// Aktueller Stand der Wecker; leer, wenn nie gespeichert oder unlesbar.
    public func snapshot() -> AlarmSnapshot {
        lock.lock(); defer { lock.unlock() }
        return snapshotLocked()
    }

    /// Ändert die Wecker und setzt einen streng steigenden Zeitstempel; geänderte Wecker bekommen ihn als eigenen
    /// Zeitpunkt, fehlende gelten ab dann als gelöscht. Lesen und Schreiben unter einer Sperre: Zwei gleichzeitige
    /// Änderungen gehen nicht verloren. Sind die gespeicherten Wecker unlesbar (z. B. Format einer neueren Version),
    /// wird nichts geschrieben: Die leere Liste darf sie nicht überschreiben (Fix KN-5, wie `AlarmRepository` auf
    /// Android). - Returns: neuer Stand, `nil` = verweigert.
    @discardableResult
    public func mutate(_ change: ([AlarmSpec]) -> [AlarmSpec], now: Date = Date()) -> AlarmSnapshot? {
        lock.lock(); defer { lock.unlock() }
        guard alarmsReadableLocked() else {
            Self.log("Gespeicherte Wecker nicht lesbar, Änderung verworfen")
            return nil
        }
        let current = snapshotLocked()
        let updated = Self.sorted(change(current.alarms))
        let old = AlarmStamps.byId(current.alarms)
        let changed = Set(updated.filter { old[$0.id] != $0 }.map(\.id))
        return writeLocked(updated, before: current, changed: changed, now: now)
    }

    /// Auto-Aus eines Einmal-Weckers nach dem Klingeln (``AlarmStamps/autoOff(_:id:listStamp:rang:)``, Fix G-1/S-1):
    /// nur, wenn `rang` den gespeicherten Wecker `id` als die geklingelte Fassung erkennt
    /// (``AlarmStamps/rang(_:rung:target:calendar:shifted:)``); sein Zeitpunkt wird der seiner bisherigen Fassung + 1 ms,
    /// damit eine neuere Änderung der Gegenseite gewinnt. Lesen und Schreiben unter einer Sperre (wie Android
    /// `AlarmRepository.disableAfterRing`). - Returns: neuer Stand, `nil` = nichts ausgeschaltet.
    @discardableResult
    public func disableAfterRing(_ id: String, now: Date = Date(), rang: (AlarmSpec) -> Bool) -> AlarmSnapshot? {
        lock.lock(); defer { lock.unlock() }
        guard alarmsReadableLocked() else { return nil }
        let current = snapshotLocked()
        let listStamp = TimeBounds.next(now: TimeBounds.millis(now), previous: current.updatedAt)
        guard let off = AlarmStamps.autoOff(current, id: id, listStamp: listStamp, rang: rang) else { return nil }
        return saveLocked(Self.sorted(off.alarms), updatedAt: off.updatedAt, stamps: off.stamps, deleted: off.deleted,
                          now: TimeBounds.millis(now))
    }

    /// Ersetzt alle Wecker (Wiederherstellen einer Sicherung; gleiche Kennung zählt einmal). Alle gelten als jetzt
    /// geändert, die bisherigen, die fehlen, als jetzt gelöscht: Die Uhr übernimmt den Stand. - Returns: neuer Stand.
    @discardableResult
    public func replaceAll(_ alarms: [AlarmSpec], now: Date = Date()) -> AlarmSnapshot {
        lock.lock(); defer { lock.unlock() }
        let unique = Self.sorted(Array(AlarmStamps.byId(alarms).values))
        return writeLocked(unique, before: snapshotLocked(), changed: Set(unique.map(\.id)), now: now)
    }

    /// Übernimmt den Stand der Gegenseite je Wecker (``AlarmStamps/merge(local:remote:now:)``, Audit A-4). Ein Stand
    /// einer früheren Version (``AlarmSnapshot/perAlarm`` `false`) oder ein unlesbarer eigener gilt wie bisher nur,
    /// wenn er neuer ist, und ersetzt dann die Liste. - Returns: ob sich die Wecker geändert haben.
    @discardableResult
    public func applyRemote(_ remote: AlarmSnapshot, now: Date = Date()) -> Bool {
        lock.lock(); defer { lock.unlock() }
        let nowMs = TimeBounds.millis(now)
        let current = snapshotLocked()
        let full = Dictionary(current.alarms.map { ($0.id, current.stampOf($0.id)) }, uniquingKeysWith: max)
        guard remote.perAlarm, alarmsReadableLocked() else {
            guard remote.updatedAt > current.updatedAt else { return false }
            let list = Self.sorted(Array(AlarmStamps.byId(remote.alarms).values))
            let ids = Set(list.map(\.id))
            let gone = Dictionary(current.alarms.filter { !ids.contains($0.id) }.map { ($0.id, remote.updatedAt) }, uniquingKeysWith: max)
            let deleted = current.deleted.merging(remote.deleted, uniquingKeysWith: max).merging(gone, uniquingKeysWith: max)
            saveLocked(list, updatedAt: remote.updatedAt, stamps: Dictionary(list.map { ($0.id, remote.stampOf($0.id)) }, uniquingKeysWith: max),
                       deleted: deleted, now: nowMs)
            return list != current.alarms
        }
        let local = AlarmSnapshot(alarms: current.alarms, updatedAt: current.updatedAt, stamps: full, deleted: current.deleted)
        let merged = AlarmStamps.merge(local: local, remote: remote, now: nowMs)
        let list = Self.sorted(merged.alarms)
        if list == current.alarms && merged.stamps == full && merged.deleted == current.deleted && merged.updatedAt == current.updatedAt {
            return false
        }
        saveLocked(list, updatedAt: merged.updatedAt, stamps: merged.stamps, deleted: merged.deleted, now: nowMs)
        return list != current.alarms
    }

    /// Wie ``applyRemote(_:now:)`` für einen Anwendungskontext (Fix S-5): WatchConnectivity liefert den zuletzt
    /// empfangenen Kontext bei jedem Start erneut (`receivedApplicationContext`). Ein Stand, der genau so schon übernommen
    /// wurde (``AlarmStamps/fingerprint(_:)``), wird nicht nochmals angewendet: Sonst brächte ein alter Kontext einen
    /// hier gelöschten Wecker zurück, sobald dessen Grabstein nach ``AlarmStamps/tombstoneMaxAgeMs`` verfallen ist.
    /// - Returns: ob sich die Wecker geändert haben.
    @discardableResult
    public func applyRemoteContext(_ remote: AlarmSnapshot, now: Date = Date()) -> Bool {
        let key = AlarmStamps.fingerprint(remote)
        lock.lock()
        let seen = defaults.string(forKey: Self.appliedContextKey) == key
        lock.unlock()
        if seen { return false }
        let changed = applyRemote(remote, now: now)
        lock.lock(); defer { lock.unlock() }
        defaults.set(key, forKey: Self.appliedContextKey)
        return changed
    }

    /// Fingerabdruck des zuletzt übernommenen Anwendungskontexts (``applyRemoteContext(_:now:)``).
    private static let appliedContextKey = "alarmsAppliedContext"

    /// Pro Wecker: bis zu diesem Zeitpunkt ist seine Weck-Nacht erledigt; sie wird nicht noch einmal geplant.
    public var doneUntil: [String: Date] {
        lock.lock(); defer { lock.unlock() }
        let raw = defaults.dictionary(forKey: Self.doneKey) as? [String: Double] ?? [:]
        return raw.mapValues { Date(timeIntervalSince1970: $0) }
    }

    /// Merkt die Nacht des Weckers bis `until` als erledigt; ein späterer Wert bleibt, alte fallen weg.
    public func markDone(alarmId: String, until: Date, now: Date = Date()) {
        lock.lock(); defer { lock.unlock() }
        var raw = defaults.dictionary(forKey: Self.doneKey) as? [String: Double] ?? [:]
        raw[alarmId] = max(raw[alarmId] ?? 0, until.timeIntervalSince1970)
        let keepAfter = now.addingTimeInterval(-2 * 24 * 3600).timeIntervalSince1970
        defaults.set(raw.filter { $0.value > keepAfter }, forKey: Self.doneKey)
        defaults.removeObject(forKey: Self.legacyDoneKey)
    }

    private static let doneKey = "nightDoneUntilByAlarm"
    private static let legacyDoneKey = "nightDoneUntil"

    private func snapshotLocked() -> AlarmSnapshot {
        let text = defaults.string(forKey: Self.alarmsKey) ?? ""
        let alarms = (try? Codec.decodeAlarms(text)) ?? []
        // Als NSNumber lesen: Millisekunden passen nicht in Int auf 32-Bit-Uhren (arm64_32). Unplausibel (``TimeBounds``,
        // z. B. aus einer kaputten Nachricht früherer Versionen) gilt als nie geändert (Fix KN-4).
        let updatedAt = TimeBounds.checked((defaults.object(forKey: Self.updatedKey) as? NSNumber)?.int64Value) ?? 0
        let ids = Set(alarms.map(\.id))
        let stamps = (AlarmStamps.int64Map(defaults.object(forKey: Self.stampsKey)) ?? [:]).filter { ids.contains($0.key) }
        return AlarmSnapshot(alarms: alarms, updatedAt: updatedAt, stamps: stamps,
                             deleted: AlarmStamps.int64Map(defaults.object(forKey: Self.deletedKey)) ?? [:])
    }

    /// Ob die gespeicherten Wecker lesbar sind (nichts gespeichert zählt als lesbar).
    private func alarmsReadableLocked() -> Bool {
        let text = defaults.string(forKey: Self.alarmsKey) ?? ""
        return text.isEmpty || (try? Codec.decodeAlarms(text)) != nil
    }

    /// Schreibt `alarms` mit einem streng steigenden Listen-Zeitpunkt: Wecker in `changed` bekommen ihn als eigenen,
    /// die übrigen behalten ihren; Wecker aus `before`, die fehlen, gelten ab dann als gelöscht.
    private func writeLocked(_ alarms: [AlarmSpec], before: AlarmSnapshot, changed: Set<String>, now: Date) -> AlarmSnapshot {
        let nowMs = TimeBounds.millis(now)
        // Streng steigend, ohne Überlauf (``TimeBounds/next(now:previous:)``, Fix KN-4).
        let stamp = TimeBounds.next(now: nowMs, previous: before.updatedAt)
        let stamps = Dictionary(alarms.map { ($0.id, changed.contains($0.id) ? stamp : before.stampOf($0.id)) }, uniquingKeysWith: max)
        let ids = Set(alarms.map(\.id))
        let gone = Dictionary(before.alarms.filter { !ids.contains($0.id) }.map { ($0.id, stamp) }, uniquingKeysWith: max)
        return saveLocked(alarms, updatedAt: stamp, stamps: stamps, deleted: before.deleted.merging(gone) { $1 }, now: nowMs)
    }

    @discardableResult
    private func saveLocked(_ alarms: [AlarmSpec], updatedAt: Int64, stamps: [String: Int64], deleted: [String: Int64], now: Int64) -> AlarmSnapshot {
        let ids = Set(alarms.map(\.id))
        let own = stamps.filter { ids.contains($0.key) }
        let kept = AlarmStamps.prune(deleted.filter { !ids.contains($0.key) }, now: now)
        defaults.set(Codec.encodeAlarms(alarms), forKey: Self.alarmsKey)
        // Nie über Int: auf Apple Watch Series 4–8, SE und Ultra 1 ist Int 32 Bit (Absturz).
        defaults.set(NSNumber(value: updatedAt), forKey: Self.updatedKey)
        defaults.set(own.mapValues { NSNumber(value: $0) }, forKey: Self.stampsKey)
        defaults.set(kept.mapValues { NSNumber(value: $0) }, forKey: Self.deletedKey)
        return AlarmSnapshot(alarms: alarms, updatedAt: updatedAt, stamps: own, deleted: kept)
    }

    /// Anzeige- und Speicherreihenfolge: nach Uhrzeit, dann Kennung.
    private static func sorted(_ alarms: [AlarmSpec]) -> [AlarmSpec] {
        alarms.sorted { ($0.hour, $0.minute, $0.id) < ($1.hour, $1.minute, $1.id) }
    }

    // MARK: Nächte

    /// Alle gespeicherten Nächte, älteste zuerst; unlesbare fallen weg.
    public func nights() -> [NightRecord] {
        lock.lock(); defer { lock.unlock() }
        return nightsLocked()
    }

    /// Fügt eine Nacht hinzu (gleicher Wecker und Fensterbeginn ersetzt) und kürzt auf 90 Nächte.
    /// Ein schon angegebenes Befinden bleibt erhalten, falls die Uhr dieselbe Nacht noch einmal schickt.
    /// Alte Dateien brauchen keine Umstellung: Jede Nacht trägt ihren Wecker schon.
    public func add(_ night: NightRecord) {
        lock.lock(); defer { lock.unlock() }
        let current = nightsLocked()
        let feeling = night.feeling ?? current.first { Self.sameNight($0, night) }?.feeling
        writeNightsLocked(NightHistory.trim(current.filter { !Self.sameNight($0, night) } + [night.with(feeling: feeling)]))
    }

    /// Speichert das Befinden (1–5) zur Nacht dieses Weckers mit diesem Fensterbeginn.
    public func setFeeling(_ feeling: Int, alarmId: String, windowStart: Date) {
        lock.lock(); defer { lock.unlock() }
        writeNightsLocked(nightsLocked().map { $0.alarmId == alarmId && $0.windowStart == windowStart ? $0.with(feeling: feeling) : $0 })
    }

    /// Dieselbe Nacht: gleicher Wecker und gleicher Fensterbeginn. Zwei Wecker mit exakt gleichem
    /// Fenster bleiben so getrennt (wie `NightRepository` auf Android).
    private static func sameNight(_ a: NightRecord, _ b: NightRecord) -> Bool {
        a.alarmId == b.alarmId && a.windowStart == b.windowStart
    }

    private func nightsLocked() -> [NightRecord] {
        guard let text = try? String(contentsOf: nightsURL, encoding: .utf8) else { return [] }
        return text.components(separatedBy: Self.separator)
            .filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            .compactMap { try? Codec.decodeNight($0.trimmingCharacters(in: CharacterSet(charactersIn: "\n")) + "\n") }
    }

    /// Schreibt atomar (temporäre Datei, dann ersetzen): Scheitert das Schreiben, etwa bei vollem
    /// Speicher, bleibt die bisherige Datei unverändert stehen. Der Fehler wird protokolliert.
    private func writeNightsLocked(_ nights: [NightRecord]) {
        let text = nights.map(Codec.encodeNight).joined(separator: Self.separator)
        do {
            try Data(text.utf8).write(to: nightsURL, options: .atomic)
        } catch {
            Self.log("Verlauf nicht gespeichert, bisheriger Stand bleibt: \(error)")
        }
    }

    /// Ersetzt alle Nächte (Wiederherstellen einer Sicherung); gleiche Nächte zählen einmal, höchstens 90.
    public func replaceNights(_ nights: [NightRecord]) {
        lock.lock(); defer { lock.unlock() }
        var unique: [NightRecord] = []
        for night in nights where !unique.contains(where: { Self.sameNight($0, night) }) { unique.append(night) }
        writeNightsLocked(NightHistory.trim(unique))
    }

    /// Löscht alle Nächte (Datei weg); fehlt sie schon, ist das kein Fehler.
    public func clearNights() {
        lock.lock(); defer { lock.unlock() }
        do {
            try FileManager.default.removeItem(at: nightsURL)
        } catch CocoaError.fileNoSuchFile {
            // Kein Verlauf vorhanden.
        } catch {
            Self.log("Verlauf nicht gelöscht: \(error)")
        }
    }

    private static func log(_ message: String) {
        #if canImport(os)
        logger.error("\(message, privacy: .public)")
        #endif
    }

    #if canImport(os)
    private static let logger = Logger(subsystem: "com.phasewake.app", category: "Storage")
    #endif
}
