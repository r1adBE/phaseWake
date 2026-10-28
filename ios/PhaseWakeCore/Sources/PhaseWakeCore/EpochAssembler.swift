/// Ordnet Sensorwerte anhand ihres Zeitstempels den 30-s-Epochen zu (Regel, Abschnitt 7).
/// Identisch zu `EpochAssembler` in `android/core`.
public final class EpochAssembler {
    private static let maxBatch: Int64 = 1024
    /// 24 Stunden: Werte danach sind ein Zeitsprung der Sensoruhr, keine Messung (Abschnitt 7).
    public static let maxEpochs: Int64 = 24 * 60 * 60 / Int64(DetectorConfig.epochSeconds)

    private let startNanos: Int64
    private let epochNanos: Int64
    private let motion: MotionEnergyAccumulator
    private let heartRate: HeartRateAccumulator

    /// Index der Epoche, in die gerade Werte fliessen.
    public private(set) var currentIndex: Int64 = 0

    public init(
        startNanos: Int64,
        epochNanos: Int64 = Int64(DetectorConfig.epochSeconds) * 1_000_000_000,
        motion: MotionEnergyAccumulator = MotionEnergyAccumulator(),
        heartRate: HeartRateAccumulator = HeartRateAccumulator()
    ) {
        precondition(epochNanos > 0, "epochNanos muss positiv sein")
        self.startNanos = startNanos
        self.epochNanos = epochNanos
        self.motion = motion
        self.heartRate = heartRate
    }

    public func addMotion(timestampNanos: Int64, x: Double, y: Double, z: Double) -> [Epoch] {
        guard let index = indexOf(timestampNanos), index >= currentIndex else { return [] }
        let closed = closeUntil(index)
        motion.add(timestampNanos: timestampNanos, x: x, y: y, z: z)
        return closed
    }

    public func addHeartRate(timestampNanos: Int64, bpm: Double) -> [Epoch] {
        guard let index = indexOf(timestampNanos), index >= currentIndex else { return [] }
        let closed = closeUntil(index)
        heartRate.add(bpm: bpm)
        return closed
    }

    public func advance(nowNanos: Int64, graceNanos: Int64) -> [Epoch] {
        guard let index = indexOf(nowNanos - graceNanos) else { return [] }
        return closeUntil(index)
    }

    /// Index zum Zeitstempel; `nil` vor Fensterbeginn und mehr als `maxEpochs` danach (Zeitsprung).
    private func indexOf(_ timestampNanos: Int64) -> Int64? {
        guard timestampNanos >= startNanos else { return nil }
        let index = (timestampNanos - startNanos) / epochNanos
        return index > Self.maxEpochs ? nil : index
    }

    private func closeUntil(_ index: Int64) -> [Epoch] {
        guard index > currentIndex else { return [] }
        var closed: [Epoch] = []
        closed.reserveCapacity(Int(min(index - currentIndex, Self.maxBatch)))
        while currentIndex < index {
            closed.append(Epoch(motion: motion.closeEpoch(), heartRate: heartRate.closeEpoch()))
            currentIndex += 1
        }
        return closed
    }
}
