import Foundation

/// Findet Geräusche in einem Strom von Pegeln. Rein rechnerisch, ohne Audio: Der Aufrufer misst den Pegel
/// je Block von 100 ms und führt die Aktionen aus (Ausschnitt beginnen, beenden, speichern).
/// Identisch zu `SoundEventDetector` (Kotlin).
///
/// - Grundrauschen = Median der Sekundenpegel der letzten 60 s (Sekundenpegel = Mittel der Blockpegel
///   einer Sekunde); erst ab 10 s Vorlauf wird erkannt.
/// - Ein Geräusch beginnt, wenn der Pegel mindestens 500 ms am Stück über Grundrauschen + 12 dB liegt.
///   Beginn = erster dieser Blöcke.
/// - Laut ist ein Block nur, wenn er zudem mindestens −50 dBFS erreicht: In einem sehr stillen Zimmer zählen
///   leise Geräusche (Rascheln, Lüfter) nicht, auch wenn sie 12 dB über dem Grundrauschen liegen.
/// - Ausschnitt: 2 s vor dem Beginn bis höchstens 10 s insgesamt; früher zu Ende, wenn es 2 s lang leise ist.
/// - Das Geräusch dauert, bis es 30 s lang leise ist (Dauer = Beginn bis Ende des letzten lauten Blocks),
///   höchstens 10 Min. So gibt es höchstens einen Ausschnitt je 30 s, und langes Schnarchen zählt als ein
///   Geräusch mit seiner ganzen Dauer.
///
/// `atMs` = Beginn des Blocks in ms (beliebiger, gleichmässig steigender Bezug, z. B. ab Aufnahmebeginn).
public struct SoundEventDetector: Sendable {
    /// Was der Aufrufer nach einem Block tun soll; Zeiten in ms im Bezug von `atMs`.
    public enum Action: Equatable, Sendable {
        /// Ausschnitt beginnt bei `clipStartMs` (Vorlauf ab dort übernehmen); das Geräusch bei `onsetMs`.
        case clipStart(clipStartMs: Int64, onsetMs: Int64)
        /// Ausschnitt endet bei `clipEndMs` (exklusiv): speichern und einordnen.
        case clipEnd(onsetMs: Int64, clipEndMs: Int64)
        /// Geräusch zu Ende.
        case eventEnd(onsetMs: Int64, durationMs: Int64, peakDb: Double)
    }

    /// Länge eines Blocks in ms.
    public static let frameMs: Int64 = 100
    /// Sekunden, über die das Grundrauschen gebildet wird.
    public static let floorSeconds = 60
    /// Vorlauf in Sekunden, bevor erkannt wird.
    public static let minFloorSeconds = 10
    /// Abstand in dB über dem Grundrauschen, ab dem ein Block laut ist.
    public static let thresholdDb = 12.0
    /// Absolute Untergrenze für einen lauten Block in dBFS (leiser zählt nie als Geräusch).
    public static let minEventDb = -50.0
    /// So lange (ms) muss es am Stück laut sein, damit ein Geräusch beginnt.
    public static let minLoudMs: Int64 = 500
    /// Vorlauf des Ausschnitts vor dem Beginn in ms.
    public static let preRollMs: Int64 = 2_000
    /// Höchstlänge eines Ausschnitts in ms.
    public static let maxClipMs: Int64 = 10_000
    /// Ausschnitt endet früher nach so viel Stille (ms).
    public static let clipTailMs: Int64 = 2_000
    /// Geräusch endet nach so viel Stille (ms).
    public static let eventGapMs: Int64 = 30_000
    /// Höchstdauer eines Geräuschs in ms (10 Min).
    public static let maxEventMs: Int64 = 10 * 60_000

    private var seconds: [Double] = []
    private var secondSum = 0.0
    private var secondFrames = 0
    private var floor: Double?

    private var loudRun = 0
    private var runStartMs: Int64 = 0
    private var runPeak = -Double.infinity

    // Laufendes Geräusch.
    private var onsetMs: Int64?
    private var lastLoudEndMs: Int64 = 0
    private var peak = -Double.infinity
    private var clipStartMs: Int64?

    /// Erzeugt einen Detektor ohne Vorlauf.
    public init() {}

    /// Aktuelle Schwelle in dBFS; `nil` während des Vorlaufs.
    public var threshold: Double? { floor.map { $0 + Self.thresholdDb } }

    /// Wertet einen Block aus. `atMs` = Beginn des Blocks, `levelDb` = Pegel in dBFS (`SoundEvents.levelDb`). Liefert
    /// die Aktionen dieses Blocks, meist keine.
    public mutating func onFrame(atMs: Int64, levelDb: Double) -> [Action] {
        var actions: [Action] = []
        let loud = (threshold.map { levelDb > $0 } ?? false) && levelDb >= Self.minEventDb
        let frameEnd = atMs + Self.frameMs

        if let onset = onsetMs {
            if loud {
                lastLoudEndMs = frameEnd
                peak = max(peak, levelDb)
            }
            if let clip = clipStartMs,
               frameEnd - clip >= Self.maxClipMs || frameEnd - lastLoudEndMs >= Self.clipTailMs {
                actions.append(.clipEnd(onsetMs: onset, clipEndMs: min(frameEnd, clip + Self.maxClipMs)))
                clipStartMs = nil
            }
            if frameEnd - lastLoudEndMs >= Self.eventGapMs || frameEnd - onset >= Self.maxEventMs {
                actions += endEvent()
            }
        } else if loud {
            if loudRun == 0 {
                runStartMs = atMs
                runPeak = -Double.infinity
            }
            loudRun += 1
            runPeak = max(runPeak, levelDb)
            if Int64(loudRun) * Self.frameMs >= Self.minLoudMs {
                onsetMs = runStartMs
                lastLoudEndMs = frameEnd
                peak = runPeak
                let start = runStartMs - Self.preRollMs
                clipStartMs = start
                loudRun = 0
                actions.append(.clipStart(clipStartMs: start, onsetMs: runStartMs))
            }
        } else {
            loudRun = 0
        }

        addToFloor(levelDb)
        return actions
    }

    /// Aufnahme endet: offenen Ausschnitt und offenes Geräusch abschliessen.
    public mutating func finish(atMs: Int64) -> [Action] {
        guard let onset = onsetMs else { return [] }
        var actions: [Action] = []
        if let clip = clipStartMs {
            actions.append(.clipEnd(onsetMs: onset, clipEndMs: max(onset, min(atMs, clip + Self.maxClipMs))))
        }
        clipStartMs = nil
        actions += endEvent()
        return actions
    }

    private mutating func endEvent() -> [Action] {
        guard let onset = onsetMs else { return [] }
        var result: [Action] = []
        if let clip = clipStartMs {
            result.append(.clipEnd(onsetMs: onset, clipEndMs: min(lastLoudEndMs, clip + Self.maxClipMs)))
        }
        result.append(.eventEnd(onsetMs: onset, durationMs: max(0, lastLoudEndMs - onset), peakDb: peak))
        onsetMs = nil
        clipStartMs = nil
        peak = -Double.infinity
        loudRun = 0
        return result
    }

    private mutating func addToFloor(_ levelDb: Double) {
        secondSum += levelDb
        secondFrames += 1
        if Int64(secondFrames) * Self.frameMs < 1000 { return }
        seconds.append(secondSum / Double(secondFrames))
        secondSum = 0
        secondFrames = 0
        while seconds.count > Self.floorSeconds { seconds.removeFirst() }
        floor = seconds.count >= Self.minFloorSeconds ? Self.median(seconds) : nil
    }

    /// Median der Werte (bei gerader Anzahl Mittel der beiden mittleren); 0 bei leerer Liste.
    public static func median(_ values: [Double]) -> Double {
        let sorted = values.sorted()
        guard !sorted.isEmpty else { return 0 }
        let mid = sorted.count / 2
        return sorted.count % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2
    }
}
