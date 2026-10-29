import Foundation

/// Schlummern: 5 Minuten, höchstens 3-mal.
public enum Snooze {
    /// Schlummerdauer in Minuten.
    public static let minutes = 5
    /// Höchstzahl Schlummern pro Weckvorgang; danach nur noch Stoppen, damit niemand den Morgen verschlummert.
    public static let maxCount = 3

    /// Ob nach `count` bisherigen Schlummern noch einmal geschlummert werden darf.
    public static func canSnooze(_ count: Int) -> Bool { count < maxCount }

    /// Zeitpunkt des nächsten Klingelns: `now` plus 5 Minuten.
    public static func nextRing(_ now: Date) -> Date { now.addingTimeInterval(TimeInterval(minutes * 60)) }
}

/// Einschlaf-Erkennung (Regel, Abschnitt 6).
public enum SleepOnset {
    /// 30 Epochen = 15 Minuten durchgehende Ruhe.
    public static let stillEpochs = 30

    /// Sucht die erste ruhige Strecke von `stillEpochs` Epochen.
    /// - Parameters:
    ///   - motions: Bewegung je Epoche seit «Gute Nacht», in zeitlicher Reihenfolge; `nil` (leere Epoche) unterbricht die Strecke.
    ///   - threshold: Leicht-Schwelle in m/s²; ruhig heisst Bewegung vorhanden und darunter.
    /// - Returns: Index der ersten Epoche der ersten ruhigen Strecke, oder `nil`, solange es keine gibt.
    public static func find(_ motions: [Double?], threshold: Double = DetectorConfig.defaultLightThreshold) -> Int? {
        var runStart = 0
        var runLength = 0
        for (index, motion) in motions.enumerated() {
            if let motion, motion < threshold {
                if runLength == 0 { runStart = index }
                runLength += 1
                if runLength == stillEpochs { return runStart }
            } else {
                runLength = 0
            }
        }
        return nil
    }
}
