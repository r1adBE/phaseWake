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
    /// 30 Epochen = 15 Minuten Ruhe.
    public static let stillEpochs = 30

    /// Höchstens so viele einzelne Zucker in einer ruhigen Strecke (Audit c3b8b01 E-4): eine Epoche mit Bewegung an oder
    /// über der Schwelle, deren beide Nachbarn ruhig sind (wie `SleepOnset.MAX_TWITCHES`, Kotlin).
    public static let maxTwitches = 2

    /// Getragen heisst: ein Puls höchstens so viele Epochen (5 Min) vor dem Beginn der Strecke oder in ihm (E-5, wie
    /// `SleepOnset.WORN_EPOCHS`, Kotlin).
    public static let wornEpochs = 10

    /// Sucht die erste ruhige Strecke: `stillEpochs` Epochen, die erste und die letzte ruhig, dazwischen höchstens
    /// `maxTwitches` einzelne Zucker; eine leere Epoche oder zwei bewegte nacheinander unterbrechen sie.
    /// - Parameters:
    ///   - motions: Bewegung je Epoche seit «Gute Nacht», in zeitlicher Reihenfolge; `nil` (leere Epoche) unterbricht die Strecke.
    ///   - threshold: Leicht-Schwelle in m/s²; ruhig heisst Bewegung vorhanden und darunter.
    ///   - heartRates: Puls je Epoche (gleich gezählt wie `motions`) oder `nil` (keiner bekannt): Dann beginnt eine Strecke
    ///     nur, wenn die Uhr getragen wurde, also höchstens `wornEpochs` Epochen davor oder in ihrer ersten Epoche ein Puls
    ///     gemessen ist (Audit c3b8b01 E-5, wie «vermutlich nicht getragen» der Lern-Übersicht).
    /// - Returns: Index der ersten Epoche der ersten ruhigen Strecke, oder `nil`, solange es keine gibt.
    public static func find(_ motions: [Double?], threshold: Double = DetectorConfig.defaultLightThreshold,
                            heartRates: [Double?]? = nil) -> Int? {
        func quiet(_ index: Int) -> Bool {
            if let motion = motions[index] { return motion < threshold }
            return false
        }
        guard motions.count >= stillEpochs else { return nil }
        for start in 0...(motions.count - stillEpochs) {
            let end = start + stillEpochs - 1
            if !quiet(start) || !quiet(end) { continue }
            var twitches = 0
            var still = true
            for index in (start + 1)..<end where !quiet(index) {
                twitches += 1
                if motions[index] == nil || !quiet(index - 1) || !quiet(index + 1) || twitches > maxTwitches {
                    still = false
                    break
                }
            }
            if still, heartRates.map({ rates in (max(0, start - wornEpochs)...start).contains { $0 < rates.count && rates[$0] != nil } }) ?? true {
                return start
            }
        }
        return nil
    }
}
