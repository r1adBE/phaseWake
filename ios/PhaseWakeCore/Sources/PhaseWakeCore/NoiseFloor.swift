import Foundation

/// Schwelle relativ zum Rauschen der Uhr. Regel siehe `docs/PHASENERKENNUNG.md`, Abschnitt 3.
///
/// Manche Uhren messen auch beim Stillliegen deutlich mehr Bewegung als die Startwerte annehmen (Galaxy Watch,
/// Befund 07.11.2026: ruhig rund 0.02 m/s², Leicht-Schwelle 0.002). Dann zählte jede Epoche als leichte Bewegung.
/// Darum gilt je Messung eine Untergrenze aus dem Grundrauschen dieser Nacht; sie hebt die Schwellen nur an.
///
/// Die Kotlin-Umsetzung (`android/core`) muss sich identisch verhalten.
public enum NoiseFloor {
    /// Erst ab so vielen gemessenen Epochen gibt es ein Grundrauschen; vorher gelten die Schwellen unverändert.
    public static let minFloorEpochs = 4
    /// Leicht-Schwelle mindestens dieses Vielfache des Grundrauschens.
    public static let lightFactor = 2.5
    /// Wach-Schwelle mindestens dieses Vielfache des Grundrauschens.
    public static let wakeFactor = 5.0
    /// Obergrenze des Grundrauschens (m/s²): Beginnt eine Messung unruhig, darf mässige Bewegung nicht als ruhig gelten.
    /// Rund das Doppelte des stillen Mittels der Galaxy Watch (07.11.2026); so bleibt Leicht ≤ 0.1 und Wach ≤ 0.2.
    public static let maxFloor = 0.04
    /// Im Weckfenster höchstens dieses Vielfache des bekannten Grundrauschens (``priorFloor(_:)``,
    /// ``windowFloor(_:config:)``): Beginnt das Fenster unruhig, hebt die Unruhe die Schwellen nicht bis ``maxFloor`` an.
    public static let priorFactor = 1.5
    /// Plausibilität des Vorwissens (Fix KN-2, vorsichtig, r1AD): Die Obergrenze ``priorFactor`` × Vorwissen gilt nur,
    /// wenn das Vorwissen (``priorFloor(_:)``) mindestens dieser Anteil des Grundrauschens des Fensters ohne Obergrenze
    /// ist. Ein unplausibel tiefes Vorwissen (z. B. 0.002 bei einer Uhr, die ruhig 0.02 misst) senkte die Schwellen sonst
    /// fast auf die Startwerte, und das Rauschen der Uhr zählte wieder als Bewegung.
    public static let priorMinShare = 0.25

    /// Grundrauschen: 20. Perzentil der vorhandenen Bewegungen (Rangmethode, aufsteigend sortiert, Position ⌈0.2 · n⌉,
    /// gezählt ab 1), höchstens ``maxFloor``. Leere Bewegungen zählen nicht.
    /// - Parameter personal: eigenes Grundrauschen aus der Lernphase (``DetectorConfig/personalFloor``): gilt (höchstens
    ///   ``maxFloor``), solange die Messung noch weniger als ``minFloorEpochs`` Werte hat.
    /// - Returns: bei weniger als ``minFloorEpochs`` Werten `personal` bzw. `nil`.
    public static func floor(_ motions: [Double?], personal: Double? = nil) -> Double? {
        let values = motions.compactMap { $0 }.sorted()
        if values.count < minFloorEpochs { return personal.map { min($0, maxFloor) } }
        // ⌈0.2 · n⌉ ganzzahlig gerechnet, damit Kotlin und Swift exakt dieselbe Position wählen.
        let rank = (values.count + 4) / 5
        return min(values[rank - 1], maxFloor)
    }

    /// Vorwissen für die Obergrenze im Weckfenster: das grössere von Grundrauschen der Einschlaf-Messung dieser Nacht
    /// (``DetectorConfig/onsetFloor``) und gelerntem (``DetectorConfig/personalFloor``); `nil` ohne beide (Fix KN-2).
    public static func priorFloor(_ config: DetectorConfig) -> Double? {
        [config.onsetFloor, config.personalFloor].compactMap { $0 }.max()
    }

    /// Grundrauschen im Weckfenster (``WakeDetector``): wie ``floor(_:personal:)``; solange die Messung zu wenige Werte
    /// hat, gilt das der Einschlaf-Messung dieser Nacht (``DetectorConfig/onsetFloor``), sonst das gelernte
    /// (``DetectorConfig/personalFloor``). Gibt es Vorwissen (``priorFloor(_:)``) und ist es mindestens
    /// ``priorMinShare`` × dieses Grundrauschen, liegt das Ergebnis höchstens bei ``priorFactor`` × Vorwissen (und nie
    /// über ``maxFloor``); sonst bleibt es bei ``floor(_:personal:)``.
    public static func windowFloor(_ motions: [Double?], config: DetectorConfig) -> Double? {
        guard let floor = Self.floor(motions, personal: config.onsetFloor ?? config.personalFloor) else { return nil }
        guard let prior = priorFloor(config), prior >= priorMinShare * floor else { return floor }
        return min(floor, priorFactor * prior)
    }

    /// Wirksame Leicht-Schwelle: `threshold` (Startwert oder kalibriert), mindestens ``lightFactor`` × `floor`.
    public static func light(threshold: Double, floor: Double?) -> Double {
        guard let floor else { return threshold }
        return max(threshold, lightFactor * floor)
    }

    /// Wirksame Leicht-Schwelle zu `config`.
    public static func light(config: DetectorConfig, floor: Double?) -> Double {
        light(threshold: config.lightThreshold, floor: floor)
    }

    /// Wirksame Wach-Schwelle: die aus `config`, mindestens ``wakeFactor`` × `floor`.
    public static func wake(config: DetectorConfig, floor: Double?) -> Double {
        guard let floor else { return config.wakeThreshold }
        return max(config.wakeThreshold, wakeFactor * floor)
    }

    /// Kennzahlen fürs Diagnose-Protokoll am Ende einer Messung: «floor=… light=… wake=…» (4 Nachkommastellen,
    /// «-» ohne Grundrauschen), aus allen Bewegungen `motions` der Messung, wie im Weckfenster gerechnet
    /// (``windowFloor(_:config:)``).
    public static func summary(config: DetectorConfig, motions: [Double?]) -> String {
        let noise = windowFloor(motions, config: config)
        return "floor=\(format(noise)) light=\(format(light(config: config, floor: noise))) "
            + "wake=\(format(wake(config: config, floor: noise)))"
    }

    /// Zahl mit 4 Nachkommastellen fürs Diagnose-Protokoll, «-» für `nil`.
    public static func format(_ value: Double?) -> String {
        guard let value else { return "-" }
        return String(format: "%.4f", value)
    }
}
