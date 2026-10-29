import Foundation

/// Ein gekoppeltes Bluetooth-Gerät, für die Wahl der Uhr; Gegenstück zu `PairedDevice` (Kotlin). Nur Android nutzt
/// es (CompanionDeviceManager); iOS kennt keine Begleit-App-Zuordnung, hier nur für gleiche Fälle in beiden Kernen.
public struct PairedDevice: Equatable, Sendable {
    /// Bluetooth-Adresse (bleibt auf dem Gerät).
    public let address: String
    /// Angezeigter Name; `nil` = unbekannt.
    public let name: String?
    /// Geräteklasse «Wearable».
    public let wearable: Bool

    /// Gerät mit Adresse, Name (`nil` = unbekannt) und Geräteklasse.
    public init(address: String, name: String?, wearable: Bool) {
        self.address = address
        self.name = name
        self.wearable = wearable
    }
}

/// Welche gekoppelte Uhr als Begleitgerät angefragt wird; Gegenstück zu `CompanionPick` (Kotlin).
public enum CompanionPick {
    /// Gerät, dessen Name zu einer verbundenen Uhr aus `nodeNames` passt: zuerst gleich (ohne Gross/Klein, ohne
    /// Leerraum am Rand), dann einer im anderen enthalten; ohne Treffer das einzige «Wearable»; sonst `nil`.
    public static func choose(_ devices: [PairedDevice], nodeNames: [String]) -> PairedDevice? {
        let nodes = nodeNames.map(normalized).filter { !$0.isEmpty }
        let named: [(PairedDevice, String)] = devices.compactMap { device in
            guard let name = device.name.map(normalized), !name.isEmpty else { return nil }
            return (device, name)
        }
        if let hit = named.first(where: { nodes.contains($0.1) }) { return hit.0 }
        if let hit = named.first(where: { pair in nodes.contains { pair.1.contains($0) || $0.contains(pair.1) } }) { return hit.0 }
        let wearables = devices.filter(\.wearable)
        return wearables.count == 1 ? wearables[0] : nil
    }

    private static func normalized(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }
}
