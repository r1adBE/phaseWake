/// Messwerte einer Epoche (30 s).
public struct Epoch: Equatable, Sendable {
    /// Bewegung in m/s² aus ``MotionEnergyAccumulator``; `nil`, wenn zu wenig Sensordaten kamen.
    public let motion: Double?
    /// Mittlerer Puls in Schlägen pro Minute aus ``HeartRateAccumulator``; `nil`, wenn keiner gemessen wurde.
    public let heartRate: Double?

    public init(motion: Double?, heartRate: Double?) {
        self.motion = motion
        self.heartRate = heartRate
    }
}
