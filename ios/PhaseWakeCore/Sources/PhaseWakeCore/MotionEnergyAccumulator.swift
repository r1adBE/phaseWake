/// Verdichtet rohe Beschleunigungswerte zur Bewegung einer Epoche (Aktigraphie).
///
/// Regel siehe `docs/PHASENERKENNUNG.md`, Abschnitt 1. Kurz: Takt auf höchstens ~28 Hz begrenzen,
/// Abstand aufeinanderfolgender Messvektoren bilden, Sensorrauschen abziehen, über die Epoche mitteln.
///
/// Nicht threadsicher; die Smart-Alarm-Sitzung ruft ``add(timestampNanos:x:y:z:)`` und
/// ``closeEpoch()`` aus derselben Warteschlange auf.
public final class MotionEnergyAccumulator {
    /// 35 ms Mindestabstand zwischen übernommenen Werten: begrenzt auf rund 28 Hz, damit beide Plattformen gleich messen (Abschnitt 1).
    public static let defaultMinIntervalNanos: Int64 = 35_000_000
    /// Mehr als 1 s zwischen zwei Werten gilt als Sensorlücke; diese Differenz zählt nicht.
    public static let defaultMaxGapNanos: Int64 = 1_000_000_000
    /// Sensorrauschen in m/s², das von jeder Differenz abgezogen wird.
    public static let defaultNoiseFloor = 0.05
    /// Mindestens so viele Differenzen je Epoche, sonst ist die Bewegung leer (zu wenig Daten).
    public static let defaultMinDiffs = 300

    private let minIntervalNanos: Int64
    private let maxGapNanos: Int64
    private let noiseFloor: Double
    private let minDiffs: Int

    private var hasLast = false
    private var lastTimestamp: Int64 = 0
    private var lastX = 0.0
    private var lastY = 0.0
    private var lastZ = 0.0

    private var sum = 0.0
    private var count = 0

    /// Erzeugt einen Zähler; die Standardwerte sind die Regel. Andere Werte nur in Tests.
    public init(
        minIntervalNanos: Int64 = MotionEnergyAccumulator.defaultMinIntervalNanos,
        maxGapNanos: Int64 = MotionEnergyAccumulator.defaultMaxGapNanos,
        noiseFloor: Double = MotionEnergyAccumulator.defaultNoiseFloor,
        minDiffs: Int = MotionEnergyAccumulator.defaultMinDiffs
    ) {
        self.minIntervalNanos = minIntervalNanos
        self.maxGapNanos = maxGapNanos
        self.noiseFloor = noiseFloor
        self.minDiffs = minDiffs
    }

    /// Nimmt einen Beschleunigungswert auf.
    /// - Parameters:
    ///   - timestampNanos: Monotoner Zeitstempel in Nanosekunden.
    ///   - x: Beschleunigung in m/s² (CoreMotion liefert g; der Aufrufer rechnet mit 9.81 um).
    ///   - y: Beschleunigung in m/s².
    ///   - z: Beschleunigung in m/s².
    public func add(timestampNanos: Int64, x: Double, y: Double, z: Double) {
        // Ungültige Messwerte (NaN, unendlich) zählen nicht und werden nicht Bezugspunkt.
        guard x.isFinite, y.isFinite, z.isFinite else { return }
        if hasLast {
            let dt = timestampNanos - lastTimestamp
            // Zu dicht (oder rückwärts): verwerfen, damit beide Plattformen im selben Takt messen.
            if dt < minIntervalNanos { return }
            // Nach einer Sensorlücke ist der Abstand zum alten Wert keine Bewegung.
            if dt <= maxGapNanos {
                let dx = x - lastX
                let dy = y - lastY
                let dz = z - lastZ
                sum += max(0.0, (dx * dx + dy * dy + dz * dz).squareRoot() - noiseFloor)
                count += 1
            }
        }
        hasLast = true
        lastTimestamp = timestampNanos
        lastX = x
        lastY = y
        lastZ = z
    }

    /// Schliesst die laufende Epoche ab und beginnt eine neue.
    ///
    /// Der letzte Messwert bleibt Bezugspunkt für die nächste Epoche.
    /// - Returns: Bewegung in m/s² oder `nil`, wenn weniger als `minDiffs` Differenzen vorlagen.
    public func closeEpoch() -> Double? {
        let motion: Double? = count >= minDiffs ? sum / Double(count) : nil
        sum = 0
        count = 0
        return motion
    }
}
