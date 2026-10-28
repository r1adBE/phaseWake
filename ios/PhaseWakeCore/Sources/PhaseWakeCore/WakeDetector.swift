/// Entscheidet Epoche für Epoche, ob jetzt ein guter Moment zum Wecken ist.
///
/// Regel siehe `docs/PHASENERKENNUNG.md`, Abschnitt 3. Ein Detektor gilt für genau ein Weckfenster;
/// für das nächste Fenster wird ein neuer erzeugt. Sobald er einmal geweckt hat, bleibt die
/// Entscheidung bestehen.
///
/// Reine Logik ohne Plattformabhängigkeit. Die Kotlin-Umsetzung (`android/core`) muss sich
/// identisch verhalten.
public final class WakeDetector {
    private static let currentHeartRateEpochs = 2

    private let config: DetectorConfig

    /// Die letzten entscheidbaren Epochen, höchstens `config.maxWindow`.
    private var recent: [Epoch] = []

    private var epochIndex = 0
    private var warmupHeartRateSum = 0.0
    private var warmupHeartRateCount = 0
    private var baselineHeartRate: Double?

    /// Weckgrund, sobald entschieden; sonst `nil`.
    public private(set) var result: WakeReason?

    public init(config: DetectorConfig = .standard) {
        self.config = config
        recent.reserveCapacity(config.maxWindow)
    }

    /// Wertet die nächste Epoche aus.
    public func onEpoch(_ epoch: Epoch) -> Decision {
        if let result { return .wake(result) }

        let index = epochIndex
        epochIndex += 1
        if index < config.warmupEpochs {
            if let heartRate = epoch.heartRate {
                warmupHeartRateSum += heartRate
                warmupHeartRateCount += 1
            }
            return .wait
        }
        if index == config.warmupEpochs, warmupHeartRateCount > 0 {
            baselineHeartRate = warmupHeartRateSum / Double(warmupHeartRateCount)
        }

        if recent.count == config.maxWindow { recent.removeFirst() }
        recent.append(epoch)

        if hits(window: config.awakeWindow, threshold: config.wakeThreshold) >= config.awakeHits {
            return wake(.awake)
        }
        if hits(window: config.lightWindow, threshold: config.lightThreshold) >= config.lightHits,
           heartRateRisen() {
            return wake(.lightSleep)
        }
        return .wait
    }

    /// Zielzeit erreicht: liefert den bestehenden Grund oder weckt mit ``WakeReason/deadline``.
    @discardableResult
    public func finish() -> WakeReason {
        if let result { return result }
        result = .deadline
        return .deadline
    }

    /// Zählt unter den letzten `window` Epochen jene mit Bewegung ≥ `threshold`. Leere Bewegung zählt nie.
    private func hits(window: Int, threshold: Double) -> Int {
        var count = 0
        for epoch in recent.suffix(window) {
            if let motion = epoch.motion, motion >= threshold { count += 1 }
        }
        return count
    }

    /// Pulsbedingung: aktueller Puls (Mittel der letzten zwei Epochen) liegt mindestens um
    /// `heartRateRise` über dem Basispuls. Fehlt einer der beiden Werte, ist sie erfüllt.
    private func heartRateRisen() -> Bool {
        guard let baseline = baselineHeartRate else { return true }
        var sum = 0.0
        var count = 0
        for epoch in recent.suffix(Self.currentHeartRateEpochs) {
            if let heartRate = epoch.heartRate {
                sum += heartRate
                count += 1
            }
        }
        if count == 0 { return true }
        return sum / Double(count) >= baseline * (1.0 + config.heartRateRise)
    }

    private func wake(_ reason: WakeReason) -> Decision {
        result = reason
        return .wake(reason)
    }
}
