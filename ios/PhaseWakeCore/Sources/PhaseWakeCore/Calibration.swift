/// Passt die Schwellen an den Nutzer an. Regel siehe `docs/PHASENERKENNUNG.md`, Abschnitt 4.
public enum Calibration {
    /// Mindestens so viele gemessene Nächte, bevor angepasst wird (Abschnitt 4).
    public static let minNights = 3
    /// Höchstens so viele jüngste Nächte fliessen ein, damit alte Gewohnheiten auslaufen.
    public static let maxNights = 14
    /// Mindestens so viele Bewegungswerte insgesamt, damit das 80. Perzentil belastbar ist.
    public static let minValues = 30

    /// Verhältnis Wach- zu Leicht-Schwelle, aus den Startwerten (0.100 / 0.002).
    public static let wakeToLightRatio = 50.0

    /// - Parameters:
    ///   - nights: Bewegung aller Epochen je aufgezeichneter Nacht, älteste zuerst.
    ///     Die Aufwärm-Epochen werden hier entfernt.
    ///   - defaults: Startwerte; von ihnen werden alle übrigen Parameter übernommen.
    /// - Returns: `defaults` mit angepassten Schwellen, oder `defaults` selbst bei zu wenig Daten.
    public static func calibrate(nights: [[Double?]], defaults: DetectorConfig = .standard) -> DetectorConfig {
        // Nur Nächte mit mindestens einem entscheidbaren Wert zählen (Uhr nicht getragen = keine Nacht).
        let measured = nights.map { $0.dropFirst(defaults.warmupEpochs).compactMap { $0 } }.filter { !$0.isEmpty }
        guard measured.count >= minNights else { return defaults }

        // Je Nacht ihr Grundrauschen (``NoiseFloor/floor(_:personal:)``, ab 4 Werten, höchstens 0.04) abziehen (Audit
        // c3b8b01 E-2, Entscheid r1AD 14.11.2026): Sonst hob das Rauschen einer Uhr wie der Galaxy Watch (ruhig 0.02) das
        // Perzentil.
        let values = measured
            .suffix(maxNights)
            .flatMap { night -> [Double] in
                let floor = NoiseFloor.floor(night.map { Optional($0) }) ?? 0.0
                return night.map { max(0.0, $0 - floor) }
            }
            .sorted()
        guard values.count >= minValues else { return defaults }

        // ⌈0.8 · n⌉ ganzzahlig gerechnet, damit Kotlin und Swift exakt dieselbe Position wählen.
        let rank = (4 * values.count + 4) / 5
        let percentile80 = values[rank - 1]

        let light = min(
            max(percentile80, DetectorConfig.defaultLightThreshold * 0.5),
            DetectorConfig.defaultLightThreshold * 2.0
        )
        // Wach-Schwelle höchstens beim Startwert (E-2, r1AD 14.11.2026): Sie passt sich nur nach unten an und verdoppelt sich
        // nicht mehr mit der oberen Grenze der Leicht-Schwelle (0.004 × 50 = 0.2).
        let wake = min(light * wakeToLightRatio, DetectorConfig.defaultWakeThreshold)
        return defaults.withThresholds(light: light, wake: wake)
    }
}
