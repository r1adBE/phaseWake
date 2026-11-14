import Foundation
import HealthKit

/// Puls aus Apple Health nur von der Apple Watch (Prüfrunde 2 F-8): Apple Health sammelt Puls auch von Ringen,
/// Brustgurten und anderen Apps. Deren Werte zählten sonst als «Uhr getragen» (Puls-Regel der Einschlaf-Erkennung,
/// ``SleepOnset``), flössen in den gelernten Ruhepuls und in die Puls-Regel im Weckfenster. Apple Watch-Werte tragen das
/// Gerät mit Modell «Watch» (`HKDevice.model`); Werte ohne Gerät oder von einem anderen zählen nicht. Die Galaxy Watch
/// liest den Puls direkt am eigenen Sensor (kein Gegenstück nötig).
enum WatchPulseSource {
    /// `HKDevice.model` der Apple Watch.
    static let model = "Watch"

    /// Proben im Zeitraum `start`–`end` (`nil` = offen), nur von der Apple Watch.
    static func predicate(start: Date, end: Date?) -> NSPredicate {
        NSCompoundPredicate(andPredicateWithSubpredicates: [
            HKQuery.predicateForSamples(withStart: start, end: end),
            HKQuery.predicateForObjects(withDeviceProperty: HKDevicePropertyKeyModel, allowedValues: [model]),
        ])
    }

    /// Ob eine Probe vom Gerät mit Modell `deviceModel` zählt (Gegenprobe zur Abfrage, falls Apple Health sie nicht filtert).
    static func counts(deviceModel: String?) -> Bool {
        deviceModel == model
    }
}
