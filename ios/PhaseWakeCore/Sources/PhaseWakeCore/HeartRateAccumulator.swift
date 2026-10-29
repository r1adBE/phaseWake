/// Mittelt die Pulswerte einer Epoche. Regel siehe `docs/PHASENERKENNUNG.md`, Abschnitt 2.
///
/// Nicht threadsicher; Aufrufe aus derselben Warteschlange wie ``MotionEnergyAccumulator``.
public final class HeartRateAccumulator {
    private static let maxPlausibleBpm = 250.0

    private var sum = 0.0
    private var count = 0

    /// Erzeugt einen leeren Zähler.
    public init() {}

    /// Nimmt einen Pulswert auf. Unplausible Werte (≤ 0 oder > 250) werden ignoriert.
    public func add(bpm: Double) {
        if !bpm.isFinite || bpm <= 0 || bpm > Self.maxPlausibleBpm { return }
        sum += bpm
        count += 1
    }

    /// Schliesst die Epoche ab.
    /// - Returns: Mittelwert oder `nil` ohne gültigen Wert.
    public func closeEpoch() -> Double? {
        let mean: Double? = count > 0 ? sum / Double(count) : nil
        sum = 0
        count = 0
        return mean
    }
}
