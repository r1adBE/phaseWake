/// Passt die Schwellen an den Nutzer an. Regel siehe `docs/PHASENERKENNUNG.md`, Abschnitt 4.
public enum Calibration {
    public static let minNights = 3
    public static let maxNights = 14
    public static let minValues = 30

    /// Verhältnis Wach- zu Leicht-Schwelle, aus den Startwerten (0.100 / 0.002).
    public static let wakeToLightRatio = 50.0

    /// - Parameters:
    ///   - nights: Bewegung aller Epochen je aufgezeichneter Nacht, älteste zuerst.
    ///     Die Aufwärm-Epochen werden hier entfernt.
    ///   - defaults: Startwerte; von ihnen werden alle übrigen Parameter übernommen.
    /// - Returns: `defaults` mit angepassten Schwellen, oder `defaults` selbst bei zu wenig Daten.
    public static func calibrate(nights: [[Double?]], defaults: DetectorConfig = .standard) -> DetectorConfig {
        guard nights.count >= minNights else { return defaults }

        let values = nights
            .suffix(maxNights)
            .flatMap { $0.dropFirst(defaults.warmupEpochs) }
            .compactMap { $0 }
            .sorted()
        guard values.count >= minValues else { return defaults }

        // ⌈0.8 · n⌉ ganzzahlig gerechnet, damit Kotlin und Swift exakt dieselbe Position wählen.
        let rank = (4 * values.count + 4) / 5
        let percentile80 = values[rank - 1]

        let light = min(
            max(percentile80, DetectorConfig.defaultLightThreshold * 0.5),
            DetectorConfig.defaultLightThreshold * 2.0
        )
        return defaults.withThresholds(light: light, wake: light * wakeToLightRatio)
    }
}
