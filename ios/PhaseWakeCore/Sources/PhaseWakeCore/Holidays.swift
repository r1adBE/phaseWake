import Foundation

/// Feiertage eines Landes (und einer Region) als Daten, wie sie das iPhone an die Uhr schickt. Identisch zu
/// `HolidayList` (Kotlin); Daten als «yyyy-MM-dd» wie `NightDate`.
public struct HolidayList: Equatable, Sendable {
    /// «CH» (ganzes Land) oder «CH-BE» (Land und Region); leer = keine Feiertage gewählt.
    public let region: String
    /// Feiertage als Kalendertage «yyyy-MM-dd», unabhängig von der Zeitzone.
    public let dates: Set<String>

    /// Erzeugt eine Liste.
    public init(region: String, dates: Set<String>) {
        self.region = region
        self.dates = dates
    }
}

/// Feiertage für «An Feiertagen nicht wecken». Die Daten (Python-Paket «holidays», MIT) liegen nur auf dem
/// iPhone; die Uhr bekommt die Tage der nächsten `syncDays` Tage als kurze Liste (`encode`).
/// Lokale Feiertage einzelner Gemeinden sind nicht enthalten. Identisch zu `Holidays` (Kotlin).
public enum Holidays {
    /// So weit voraus (Tage ab heute) gehen die Feiertage an die Uhr; gut ein Jahr.
    public static let syncDays = 400

    private static let separator = "|"
    private static let dateSeparator = ","

    /// Landesweite Tage (`national`) und die zusätzlichen der Region (`regional`) als Daten «yyyy-MM-dd»,
    /// eingeschränkt auf `today` bis vor `today + days`. Unlesbare Einträge fallen weg.
    public static func resolve(national: [String], regional: [String], today: String, days: Int = Holidays.syncDays) -> Set<String> {
        let end = NightDate.adding(days, to: today)
        return Set((national + regional).compactMap(parse).filter { $0 >= today && $0 < end })
    }

    /// Schreibt «region|yyyy-MM-dd,yyyy-MM-dd» mit aufsteigend sortierten Daten; ohne Daten «region|».
    public static func encode(_ list: HolidayList) -> String {
        list.region + separator + list.dates.sorted().joined(separator: dateSeparator)
    }

    /// Liest `encode` tolerant: leer oder unlesbar ergibt eine leere Liste ohne Region, falsche Daten fallen weg.
    public static func decode(_ text: String?) -> HolidayList {
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let cut = text.range(of: separator) else { return HolidayList(region: "", dates: []) }
        let region = String(text[..<cut.lowerBound])
        let dates = text[cut.upperBound...].components(separatedBy: dateSeparator).compactMap(parse)
        return HolidayList(region: region, dates: Set(dates))
    }

    /// Code für `HolidayList.region`: «CH» oder mit Region «CH-BE»; ohne Land leer.
    public static func regionCode(country: String, region: String?) -> String {
        if country.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return "" }
        guard let region, !region.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return country }
        return "\(country)-\(region)"
    }

    private static func parse(_ text: String) -> String? {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return NightDate.isValid(clean) ? clean : nil
    }
}
