import Foundation
#if canImport(os)
import os
#endif

/// Schlüssel-Wert-Speicher mit den Zugriffen von `UserDefaults`, die Phase Wake braucht. `UserDefaults` erfüllt ihn
/// unverändert (Uhr, Tests); auf dem iPhone liegen Wecker und Einstellungen stattdessen in einer ``SettingsFile``.
public protocol SettingsStore: AnyObject {
    /// Gespeicherter Wert; `nil`, wenn es keinen gibt.
    func object(forKey defaultName: String) -> Any?
    /// Text; `nil` ohne Wert oder bei anderem Typ.
    func string(forKey defaultName: String) -> String?
    /// Ganze Zahl; 0 ohne Wert.
    func integer(forKey defaultName: String) -> Int
    /// Schalter; `false` ohne Wert.
    func bool(forKey defaultName: String) -> Bool
    /// Kommazahl; 0 ohne Wert.
    func double(forKey defaultName: String) -> Double
    /// Bytes; `nil` ohne Wert oder bei anderem Typ.
    func data(forKey defaultName: String) -> Data?
    /// Wörterbuch; `nil` ohne Wert oder bei anderem Typ.
    func dictionary(forKey defaultName: String) -> [String: Any]?
    /// Liste von Texten; `nil` ohne Wert oder bei anderem Typ.
    func stringArray(forKey defaultName: String) -> [String]?
    /// Setzt den Wert (Typen einer Property List); `nil` entfernt ihn.
    func set(_ value: Any?, forKey defaultName: String)
    /// Entfernt den Wert.
    func removeObject(forKey defaultName: String)
}

extension UserDefaults: SettingsStore {}

/// Einstellungen als eine Datei (binäre Property List) statt in `UserDefaults`: So kann die App sie in einen von der
/// iCloud-Sicherung ausgenommenen Ordner legen (wie `allowBackup="false"` auf Android). Jede Änderung schreibt die
/// ganze Datei atomar; scheitert das (Speicher voll), bleibt der Wert im Speicher und der nächste Schreibversuch
/// nimmt ihn mit. Ist die Datei vorhanden, aber (noch) nicht lesbar, z. B. vor dem ersten Entsperren, wird sie nie
/// überschrieben: Änderungen bleiben im Speicher und werden beim ersten gelungenen Lesen darübergelegt.
public final class SettingsFile: SettingsStore, @unchecked Sendable {
    private let url: URL
    private let lock = NSLock()
    /// Stand der Datei samt Änderungen dieser Sitzung; vor dem Lesen nur die Änderungen.
    private var values: [String: Any] = [:]
    /// Ob die Datei gelesen ist (oder es sie nicht gibt).
    private var loaded = false
    /// Seit dem letzten gelungenen Schreiben geänderte Schlüssel (auch entfernte).
    private var changed: Set<String> = []

    /// Schlüssel mit den Namen, die schon aus `UserDefaults` übernommen sind (``adopt(_:from:)``).
    static let adoptedKey = "settingsFile.adopted"

    /// Erzeugt den Speicher für die Datei `url`; ihr Ordner wird bei Bedarf angelegt.
    public init(url: URL) {
        self.url = url
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        lock.lock(); defer { lock.unlock() }
        loadLocked()
    }

    // MARK: Lesen

    /// Gespeicherter Wert; `nil`, wenn es keinen gibt.
    public func object(forKey defaultName: String) -> Any? {
        lock.lock(); defer { lock.unlock() }
        loadLocked()
        return values[defaultName]
    }

    /// Text; `nil` ohne Wert oder bei anderem Typ.
    public func string(forKey defaultName: String) -> String? { object(forKey: defaultName) as? String }

    /// Ganze Zahl; 0 ohne Wert.
    public func integer(forKey defaultName: String) -> Int { Self.number(object(forKey: defaultName))?.intValue ?? 0 }

    /// Schalter; `false` ohne Wert.
    public func bool(forKey defaultName: String) -> Bool { Self.number(object(forKey: defaultName))?.boolValue ?? false }

    /// Kommazahl; 0 ohne Wert.
    public func double(forKey defaultName: String) -> Double { Self.number(object(forKey: defaultName))?.doubleValue ?? 0 }

    /// Bytes; `nil` ohne Wert oder bei anderem Typ.
    public func data(forKey defaultName: String) -> Data? { object(forKey: defaultName) as? Data }

    /// Wörterbuch; `nil` ohne Wert oder bei anderem Typ.
    public func dictionary(forKey defaultName: String) -> [String: Any]? { object(forKey: defaultName) as? [String: Any] }

    /// Liste von Texten; `nil` ohne Wert oder bei anderem Typ.
    public func stringArray(forKey defaultName: String) -> [String]? { object(forKey: defaultName) as? [String] }

    // MARK: Schreiben

    /// Setzt den Wert und schreibt die Datei; `nil` entfernt ihn. Kein Typ einer Property List: bleibt unverändert.
    public func set(_ value: Any?, forKey defaultName: String) {
        lock.lock(); defer { lock.unlock() }
        if let value {
            guard PropertyListSerialization.propertyList(value, isValidFor: .binary) else {
                Self.log("\(defaultName) nicht gespeichert: kein Property-List-Typ")
                return
            }
            values[defaultName] = value
        } else {
            values.removeValue(forKey: defaultName)
        }
        changed.insert(defaultName)
        persistLocked()
    }

    /// Entfernt den Wert.
    public func removeObject(forKey defaultName: String) { set(nil, forKey: defaultName) }

    /// Schreibt noch nicht gespeicherte Änderungen. - Returns: ob alles in der Datei steht.
    @discardableResult
    public func flush() -> Bool {
        lock.lock(); defer { lock.unlock() }
        return changed.isEmpty || persistLocked()
    }

    /// Übernimmt `keys` einmalig aus `legacy` (frühere Versionen) und löscht sie dort erst, nachdem die Datei
    /// geschrieben ist. Ein schon in der Datei stehender Wert gilt (er ist neuer). Die übernommenen Namen stehen in
    /// derselben Datei: Endet die App vor dem Löschen, holt der nächste Start einen Wert, den die App inzwischen
    /// entfernt hat, nicht zurück, sondern löscht nur den Rest in `legacy`. Datei nicht lesbar: beim nächsten Mal.
    public func adopt(_ keys: [String], from legacy: UserDefaults) {
        lock.lock(); defer { lock.unlock() }
        loadLocked()
        guard loaded else { return }
        let adopted = Set(values[Self.adoptedKey] as? [String] ?? [])
        let found = keys.filter { legacy.object(forKey: $0) != nil }
        let fresh = found.filter { !adopted.contains($0) }
        if !fresh.isEmpty {
            for key in fresh where values[key] == nil {
                values[key] = legacy.object(forKey: key)
                changed.insert(key)
            }
            values[Self.adoptedKey] = adopted.union(fresh).sorted()
            changed.insert(Self.adoptedKey)
        }
        guard changed.isEmpty || persistLocked() else { return }
        for key in found { legacy.removeObject(forKey: key) }
    }

    // MARK: Datei

    /// Liest die Datei, falls noch nicht geschehen, legt die Änderungen dieser Sitzung darüber und schreibt sie dann.
    /// Fehlt die Datei, gilt ein leerer Stand. Ist sie da, aber nicht lesbar, bleibt `loaded` falsch (der nächste
    /// Zugriff versucht es wieder). Eine kaputte Datei wird als «.broken» beiseitegelegt, dann gilt ein leerer Stand.
    private func loadLocked() {
        guard !loaded else { return }
        var disk: [String: Any] = [:]
        if FileManager.default.fileExists(atPath: url.path) {
            let data: Data
            do {
                data = try Data(contentsOf: url)
            } catch {
                Self.log("Einstellungen (noch) nicht lesbar: \(error)")
                return
            }
            if let plist = (try? PropertyListSerialization.propertyList(from: data, format: nil)) as? [String: Any] {
                disk = plist
            } else {
                Self.log("Einstellungen unlesbar, beiseitegelegt")
                let broken = url.appendingPathExtension("broken")
                try? FileManager.default.removeItem(at: broken)
                try? FileManager.default.moveItem(at: url, to: broken)
            }
        }
        for key in changed { disk[key] = values[key] }
        values = disk
        loaded = true
        if !changed.isEmpty { persistLocked() }
    }

    /// Schreibt den ganzen Stand atomar, nie vor dem Lesen der Datei (``loadLocked()`` schreibt nach dem ersten
    /// gelungenen Lesen selbst). - Returns: ob alles in der Datei steht.
    @discardableResult
    private func persistLocked() -> Bool {
        guard loaded else {
            loadLocked()
            return changed.isEmpty
        }
        do {
            let data = try PropertyListSerialization.data(fromPropertyList: values, format: .binary, options: 0)
            try data.write(to: url, options: .atomic)
            changed.removeAll()
            return true
        } catch {
            Self.log("Einstellungen nicht gespeichert, nächster Versuch bei der nächsten Änderung: \(error)")
            return false
        }
    }

    /// Zahl aus dem gespeicherten Wert (`NSNumber`, auch Bool, Int, Double); sonst `nil`.
    private static func number(_ value: Any?) -> NSNumber? {
        switch value {
        case let number as NSNumber: number
        case let int as Int: NSNumber(value: int)
        case let int as Int64: NSNumber(value: int)
        case let double as Double: NSNumber(value: double)
        case let bool as Bool: NSNumber(value: bool)
        default: nil
        }
    }

    private static func log(_ message: String) {
        #if canImport(os)
        logger.error("\(message, privacy: .public)")
        #endif
    }

    #if canImport(os)
    private static let logger = Logger(subsystem: "com.phasewake.app", category: "SettingsFile")
    #endif
}
