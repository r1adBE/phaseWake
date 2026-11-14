import Foundation
import Observation
import PhaseWakeCore

/// Feiertagsdaten aus `holidays.json.gz` (Python-Paket «holidays», MIT): nur auf dem iPhone, ohne Internet.
/// Format: `{"v":1,"countries":{"CH":{"name":…,"regions":{"BE":"Bern"},"days":{"":[["2026-01-01","…"]],"BE":[…]}}}}`;
/// `days[""]` gilt landesweit, `days[region]` sind die zusätzlichen Tage der Region.
struct HolidayDatabase: Sendable {
    struct Country: Sendable {
        /// Region (Kürzel) → Name, wie in den Daten.
        let regions: [String: String]
        /// "" (landesweit) bzw. Region → Daten «yyyy-MM-dd», die als frei gelten.
        let days: [String: [String]]
        /// Wie `days`, aber nur geschätzte Tage (``Holidays/isEstimated(_:)``, Audit F-2): gelten nicht als frei.
        var estimatedDays: [String: [String]] = [:]
    }

    /// Nur Länder mit zweistelligem Kürzel; die dreistelligen sind Doppel in der Datei.
    let countries: [String: Country]

    /// Liest die Datei aus dem App-Bündel; `nil`, wenn sie fehlt oder unlesbar ist (dann gibt es keine Feiertage).
    static func load(bundle: Bundle = .main) -> HolidayDatabase? {
        guard let url = bundle.url(forResource: "holidays.json", withExtension: "gz"),
              let packed = try? Data(contentsOf: url), let json = Gzip.inflate(packed),
              let root = try? JSONSerialization.jsonObject(with: json) as? [String: Any],
              let all = root["countries"] as? [String: Any] else { return nil }
        var countries: [String: Country] = [:]
        for (code, value) in all where code.count == 2 {
            guard let entry = value as? [String: Any] else { continue }
            let rawDays = entry["days"] as? [String: Any] ?? [:]
            // Einträge [Datum, Name]; geschätzte (Name mit «estimated») getrennt, sie gelten nicht als frei (Audit F-2).
            let entries = rawDays.mapValues { list in
                ((list as? [Any]) ?? []).compactMap { item -> (date: String, estimated: Bool)? in
                    guard let pair = item as? [Any], let date = pair.first as? String else { return nil }
                    return (date: date, estimated: Holidays.isEstimated((pair.dropFirst().first as? String) ?? ""))
                }
            }
            countries[code] = Country(regions: entry["regions"] as? [String: String] ?? [:],
                                      days: entries.mapValues { list in list.filter { !$0.estimated }.map { $0.date } },
                                      estimatedDays: entries.mapValues { list in list.filter { $0.estimated }.map { $0.date } })
        }
        return HolidayDatabase(countries: countries)
    }

    /// Feiertage von heute bis `Holidays.syncDays` voraus für Land und Region (leer = ganzes Land); nur sichere Tage.
    func dates(country: String, region: String, today: String) -> Set<String> {
        guard let entry = countries[country] else { return [] }
        return Holidays.resolve(national: entry.days[""] ?? [], regional: region.isEmpty ? [] : entry.days[region] ?? [], today: today)
    }

    /// Nur geschätzte Feiertage im selben Zeitraum (Audit F-2), ohne Tage, die auch sicher Feiertag sind.
    func estimatedDates(country: String, region: String, today: String) -> Set<String> {
        guard let entry = countries[country] else { return [] }
        let estimated = Holidays.resolve(national: entry.estimatedDays[""] ?? [],
                                         regional: region.isEmpty ? [] : entry.estimatedDays[region] ?? [], today: today)
        return estimated.subtracting(dates(country: country, region: region, today: today))
    }
}

/// Entpackt gzip (RFC 1952) mit dem eingebauten DEFLATE von Foundation (`NSData.decompressed(using: .zlib)`
/// ist reines DEFLATE ohne Kopf).
enum Gzip {
    private static let headerBytes = 10
    private static let trailerBytes = 8

    static func inflate(_ data: Data) -> Data? {
        let b = [UInt8](data)
        guard b.count > headerBytes + trailerBytes, b[0] == 0x1F, b[1] == 0x8B, b[2] == 8 else { return nil }
        let flags = b[3]
        var i = headerBytes
        if flags & 0x04 != 0, i + 2 <= b.count { i += 2 + (Int(b[i]) | (Int(b[i + 1]) << 8)) }
        if flags & 0x08 != 0 { i = skipZeroTerminated(b, from: i) }
        if flags & 0x10 != 0 { i = skipZeroTerminated(b, from: i) }
        if flags & 0x02 != 0 { i += 2 }
        guard i < b.count - trailerBytes else { return nil }
        return try? (Data(b[i..<(b.count - trailerBytes)]) as NSData).decompressed(using: .zlib) as Data
    }

    private static func skipZeroTerminated(_ b: [UInt8], from start: Int) -> Int {
        var i = start
        while i < b.count, b[i] != 0 { i += 1 }
        return i + 1
    }
}

/// Gewähltes Land und Region samt den aufgelösten Feiertagen. Ohne Wahl gilt das Land des Geräts.
@MainActor
@Observable
final class HolidayStore {
    private static let countryKey = "holidayCountry"
    private static let regionKey = "holidayRegion"

    private(set) var database: HolidayDatabase?
    /// Land (Kürzel, z. B. «CH»); leer = keine Feiertage.
    private(set) var country: String
    /// Region (Kürzel, z. B. «BE»); leer = ganzes Land.
    private(set) var region: String
    /// Feiertage «yyyy-MM-dd» der nächsten 400 Tage; bis die Daten geladen sind, der zuletzt gesendete Stand.
    private(set) var dates: Set<String>
    /// Nur geschätzte Feiertage im selben Zeitraum (Audit F-2): gelten nicht als frei; die Einstellungen zeigen den nächsten.
    private(set) var estimated: Set<String> = []

    init(defaults: any SettingsStore = SettingsFile.phone) {
        let stored = Self.choice(defaults)
        country = stored.country
        region = stored.region
        dates = []
    }

    /// Gespeicherte Wahl; ohne Wahl das Land des Geräts, ganzes Land.
    private static func choice(_ defaults: any SettingsStore) -> (country: String, region: String) {
        (defaults.string(forKey: countryKey) ?? Locale.current.region?.identifier ?? "", defaults.string(forKey: regionKey) ?? "")
    }

    /// Wahl neu lesen (Prüfrunde 2 SP-2): Vor dem ersten Entsperren gestartet, galt das Land des Geräts; danach Region
    /// prüfen und neu auflösen (mit geladenen Daten). - Returns: ob sich die Feiertage geändert haben.
    @discardableResult
    func reloadChoice(_ defaults: any SettingsStore = SettingsFile.phone) -> Bool {
        let stored = Self.choice(defaults)
        guard stored.country != country || stored.region != region else { return false }
        country = stored.country
        region = stored.region
        validateRegion()
        let changed = refresh()
        // Daten noch nicht geladen: die bisherigen Feiertage gehören zur alten Wahl (`load` löst danach auf).
        if database == nil, !dates.isEmpty {
            dates = []
            return true
        }
        return changed
    }

    /// Bis die Daten geladen sind: der zuletzt an die Uhr gesendete Stand, falls er zur Wahl passt.
    func adopt(lastSent: HolidayList) {
        if database == nil, lastSent.region == Holidays.regionCode(country: country, region: region) { dates = lastSent.dates }
    }

    /// Liste für die Uhr: Region und Daten.
    var list: HolidayList { HolidayList(region: Holidays.regionCode(country: country, region: region), dates: dates) }

    /// Länder mit übersetztem Namen, alphabetisch.
    var countries: [(code: String, name: String)] {
        (database?.countries.keys.map { (code: $0, name: Locale.current.localizedString(forRegionCode: $0) ?? $0) } ?? [])
            .sorted { $0.name.localizedCompare($1.name) == .orderedAscending }
    }

    /// Regionen des gewählten Landes, Namen wie in den Daten.
    var regions: [(code: String, name: String)] {
        (database?.countries[country]?.regions.map { (code: $0.key, name: $0.value) } ?? [])
            .sorted { $0.name.localizedCompare($1.name) == .orderedAscending }
    }

    /// Lädt die Daten im Hintergrund (3,7 MB JSON) und löst die Feiertage auf. - Returns: ob sich etwas geändert hat.
    @discardableResult
    func load() async -> Bool {
        if database == nil { database = await Task.detached(priority: .utility) { HolidayDatabase.load() }.value }
        validateRegion()
        return refresh()
    }

    /// Neu auflösen (neuer Tag, neue Wahl). - Returns: ob sich die Feiertage geändert haben.
    @discardableResult
    func refresh(now: Date = Date()) -> Bool {
        guard let database else { return false }
        let today = NightDate.today(now, calendar: .gregorianLocal)
        estimated = database.estimatedDates(country: country, region: region, today: today)
        let fresh = database.dates(country: country, region: region, today: today)
        guard fresh != dates else { return false }
        dates = fresh
        return true
    }

    /// Neue Wahl; eine Region gehört immer zum Land (sonst ganzes Land). Solange die Daten noch laden (z. B.
    /// Wiederherstellen direkt nach dem Start), bleibt die Region ungeprüft stehen; `load` prüft sie danach.
    func select(country: String, region: String) {
        self.country = country
        self.region = region
        validateRegion()
        save()
        refresh()
        if database == nil { dates = [] }
    }

    /// Region, die es im Land nicht gibt, wird zu «ganzes Land»; ohne Daten keine Prüfung.
    private func validateRegion() {
        guard let database, !region.isEmpty, database.countries[country]?.regions[region] == nil else { return }
        region = ""
        save()
    }

    private func save() {
        SettingsFile.phone.set(country, forKey: Self.countryKey)
        SettingsFile.phone.set(region, forKey: Self.regionKey)
    }
}
