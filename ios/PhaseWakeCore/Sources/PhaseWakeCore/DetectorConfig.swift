/// Fehler bei ungültigen Parametern der Phasenerkennung.
public struct DetectorConfigError: Error, Equatable, Sendable {
    /// Beschreibung des ungültigen Werts (nur für Logs und Tests).
    public let message: String
}

/// Parameter der Phasenerkennung.
///
/// Die Standardwerte sind die Startwerte aus `docs/PHASENERKENNUNG.md`.
/// Nach drei aufgezeichneten Nächten liefert ``Calibration`` angepasste Schwellen.
public struct DetectorConfig: Equatable, Sendable {
    /// Länge einer Epoche in Sekunden.
    public static let epochSeconds = 30

    /// Startwert der Leicht-Schwelle in m/s² (Abschnitt 3).
    public static let defaultLightThreshold = 0.002
    /// Startwert der Wach-Schwelle in m/s² (Abschnitt 3).
    public static let defaultWakeThreshold = 0.100
    /// Startwert des nötigen Pulsanstiegs als Anteil (5 %).
    public static let defaultHeartRateRise = 0.05
    /// Aufwärm-Epochen zu Fensterbeginn (2 = 1 Minute); sie liefern den Basispuls.
    public static let defaultWarmupEpochs = 2

    /// Startwerte.
    public static let standard = DetectorConfig(
        unchecked: defaultLightThreshold, defaultWakeThreshold, defaultHeartRateRise, defaultWarmupEpochs, 3, 2, 4, 2
    )

    /// Bewegung (m/s²), ab der eine Epoche als leichte Bewegung zählt.
    public private(set) var lightThreshold: Double
    /// Bewegung (m/s²), ab der eine Epoche als Wachsein zählt.
    public private(set) var wakeThreshold: Double
    /// Nötiger Pulsanstieg gegenüber dem Basispuls, als Anteil (0.05 = 5 %).
    public let heartRateRise: Double
    /// Epochen zu Fensterbeginn, die nur beobachtet werden.
    public let warmupEpochs: Int
    /// Anzahl der letzten Epochen, die die Wach-Regel betrachtet.
    public let awakeWindow: Int
    /// Nötige Treffer im ``awakeWindow``.
    public let awakeHits: Int
    /// Anzahl der letzten Epochen, die die Leichtschlaf-Regel betrachtet.
    public let lightWindow: Int
    /// Nötige Treffer im ``lightWindow``.
    public let lightHits: Int

    /// Erzeugt eine geprüfte Konfiguration.
    /// - Throws: ``DetectorConfigError`` bei ungültigen Werten.
    public init(
        lightThreshold: Double = DetectorConfig.defaultLightThreshold,
        wakeThreshold: Double = DetectorConfig.defaultWakeThreshold,
        heartRateRise: Double = DetectorConfig.defaultHeartRateRise,
        warmupEpochs: Int = DetectorConfig.defaultWarmupEpochs,
        awakeWindow: Int = 3,
        awakeHits: Int = 2,
        lightWindow: Int = 4,
        lightHits: Int = 2
    ) throws {
        guard lightThreshold > 0 else { throw DetectorConfigError(message: "lightThreshold muss positiv sein") }
        guard wakeThreshold > lightThreshold else {
            throw DetectorConfigError(message: "wakeThreshold muss über lightThreshold liegen")
        }
        guard heartRateRise >= 0 else { throw DetectorConfigError(message: "heartRateRise darf nicht negativ sein") }
        guard warmupEpochs >= 0 else { throw DetectorConfigError(message: "warmupEpochs darf nicht negativ sein") }
        guard (1...max(1, awakeWindow)).contains(awakeHits), awakeWindow >= 1 else {
            throw DetectorConfigError(message: "awakeHits muss zwischen 1 und awakeWindow liegen")
        }
        guard (1...max(1, lightWindow)).contains(lightHits), lightWindow >= 1 else {
            throw DetectorConfigError(message: "lightHits muss zwischen 1 und lightWindow liegen")
        }
        self.init(
            unchecked: lightThreshold, wakeThreshold, heartRateRise, warmupEpochs,
            awakeWindow, awakeHits, lightWindow, lightHits
        )
    }

    private init(
        unchecked lightThreshold: Double, _ wakeThreshold: Double, _ heartRateRise: Double, _ warmupEpochs: Int,
        _ awakeWindow: Int, _ awakeHits: Int, _ lightWindow: Int, _ lightHits: Int
    ) {
        self.lightThreshold = lightThreshold
        self.wakeThreshold = wakeThreshold
        self.heartRateRise = heartRateRise
        self.warmupEpochs = warmupEpochs
        self.awakeWindow = awakeWindow
        self.awakeHits = awakeHits
        self.lightWindow = lightWindow
        self.lightHits = lightHits
    }

    /// Grösstes Fenster beider Regeln; so viele Epochen merkt sich der ``WakeDetector``.
    var maxWindow: Int { max(awakeWindow, lightWindow) }

    /// Kopie mit neuen Schwellen. Nur für ``Calibration``, die gültige Werte garantiert (wake = light × 50).
    func withThresholds(light: Double, wake: Double) -> DetectorConfig {
        var copy = self
        copy.lightThreshold = light
        copy.wakeThreshold = wake
        return copy
    }
}
