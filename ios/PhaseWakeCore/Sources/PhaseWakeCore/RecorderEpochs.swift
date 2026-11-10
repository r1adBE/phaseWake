import Foundation

/// Apple Watch, Lernphase: Epochen aus den Werten, die der Bewegungs-Rekorder von watchOS (`CMSensorRecorder`, 50 Hz)
/// über die Nacht aufzeichnet und die Uhr am Morgen abholt. Regel siehe `docs/PHASENERKENNUNG.md`, Abschnitte 1, 2, 7
/// und 10: dieselbe Bewegung je Epoche wie im Weckfenster (``MotionEnergyAccumulator``: Takt höchstens rund 28 Hz,
/// Rauschen 0.05 abziehen, ab 300 Differenzen), Epoche *k* = `start + k · 30 s` (einschliesslich) bis
/// `start + (k+1) · 30 s` (ausschliesslich). Die Werte tragen die Wanduhr (`startDate`); sie kommen sortiert.
///
/// Die Uhr holt die Werte in Abschnitten (``add(at:x:y:z:)``, dann ``close(until:)`` an einer Epochengrenze) und
/// speichert dazwischen die fertigen Epochen. Fährt sie nach einem Neustart der App fort (`motions`), beginnt der Zähler
/// neu: Die erste Differenz nach der Grenze fehlt dann (1 von rund 750 je Epoche).
///
/// Nicht threadsicher; nur aus einer Warteschlange benutzen.
public final class RecorderEpochs {
    /// Erdbeschleunigung: Der Rekorder liefert g, die Regel rechnet in m/s² (wie die Smart-Alarm-Sitzung).
    public static let gravity = 9.80665

    /// Beginn der Epoche 0.
    public let start: Date
    /// Abgeschlossene Epochen ab `start` (Bewegung oder leer), höchstens ``NightRecording/maxEpochs``.
    public private(set) var motions: [Double?]

    private var accumulator = MotionEnergyAccumulator()
    private let epochNanos = Int64(DetectorConfig.epochSeconds) * 1_000_000_000

    /// Beginnt bei `start` oder fährt mit den schon abgeschlossenen Epochen `motions` fort.
    public init(start: Date, motions: [Double?] = []) {
        self.start = start
        self.motions = Array(motions.prefix(NightRecording.maxEpochs))
    }

    /// Zeitpunkt, bis zu dem die Epochen abgeschlossen sind (Ende der letzten abgeschlossenen Epoche).
    public var closedUntil: Date {
        start.addingTimeInterval(TimeInterval(motions.count * DetectorConfig.epochSeconds))
    }

    /// Nimmt einen Wert zum Zeitpunkt `at` auf (x, y, z in g). Werte vor `start`, in einer schon abgeschlossenen
    /// Epoche oder nach ``NightRecording/maxEpochs`` werden verworfen; ein Wert für eine spätere Epoche schliesst die
    /// laufende und alle dazwischen ab (leer ohne Werte).
    public func add(at: Date, x: Double, y: Double, z: Double) {
        let nanos = Int64((at.timeIntervalSince(start) * 1e9).rounded())
        guard nanos >= 0 else { return }
        let index = Int(nanos / epochNanos)
        guard index >= motions.count, index < NightRecording.maxEpochs else { return }
        closeEpochs(below: index)
        accumulator.add(timestampNanos: nanos, x: x * Self.gravity, y: y * Self.gravity, z: z * Self.gravity)
    }

    /// Schliesst alle Epochen ab, die bis `until` ganz vergangen sind (höchstens ``NightRecording/maxEpochs``).
    public func close(until: Date) {
        // Auf Millisekunden gerundet: Rundungsfehler der Zeitpunkte dürfen eine Grenze nicht verfehlen.
        let millis = Int64((until.timeIntervalSince(start) * 1000).rounded())
        guard millis > 0 else { return }
        closeEpochs(below: min(Int(millis / Int64(DetectorConfig.epochSeconds * 1000)), NightRecording.maxEpochs))
    }

    /// Beginnt den Zähler neu (nach einem Abschnitt, dessen Werte unvollständig waren); die nächste Differenz fehlt.
    public func resetAccumulator() {
        accumulator = MotionEnergyAccumulator()
    }

    private func closeEpochs(below index: Int) {
        while motions.count < index {
            motions.append(accumulator.closeEpoch())
        }
    }

    /// Puls je Epoche ab `start` für `count` Epochen: Mittel der Werte (`at`, `bpm`) der Epoche nach
    /// ``HeartRateAccumulator`` (Abschnitt 2), leer ohne gültigen Wert. Werte ausserhalb werden verworfen.
    public static func heartRates(_ samples: [(at: Date, bpm: Double)], start: Date, count: Int) -> [Double?] {
        guard count > 0 else { return [] }
        let accumulators = (0..<count).map { _ in HeartRateAccumulator() }
        for sample in samples {
            let offset = sample.at.timeIntervalSince(start)
            guard offset >= 0 else { continue }
            let index = Int(offset) / DetectorConfig.epochSeconds
            guard index < count else { continue }
            accumulators[index].add(bpm: sample.bpm)
        }
        return accumulators.map { $0.closeEpoch() }
    }

    /// Epochen aus Bewegung `motions` und Puls `heartRates` (gleich lang; fehlt ein Puls, ist er leer).
    public static func epochs(motions: [Double?], heartRates: [Double?]) -> [Epoch] {
        motions.enumerated().map { index, motion in
            Epoch(motion: motion, heartRate: index < heartRates.count ? heartRates[index] : nil)
        }
    }
}
