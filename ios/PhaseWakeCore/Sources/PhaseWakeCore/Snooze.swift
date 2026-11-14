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

    /// Getragen heisst (E-5, Prüfrunde 2 F-1): ein Puls höchstens so viele Epochen (15 Min) vor dem Beginn der Strecke oder
    /// in ihm, und mindestens einer in der Strecke nach ihrer ersten Epoche (wie `SleepOnset.WORN_EPOCHS`, Kotlin). Die
    /// Apple Watch misst den Puls ruhend von selbst, Apple nennt keinen festen Abstand; üblich sind 5 bis 15 Min (neuere
    /// Modelle öfter). 15 Min deckt den grössten üblichen Abstand; eine abgelegte Uhr misst ab dem Ablegen keinen mehr.
    public static let wornEpochs = 30

    /// Sucht die erste ruhige Strecke: `stillEpochs` Epochen, die erste und die letzte ruhig, dazwischen höchstens
    /// `maxTwitches` einzelne Zucker; eine leere Epoche oder zwei bewegte nacheinander unterbrechen sie.
    /// - Parameters:
    ///   - motions: Bewegung je Epoche seit «Gute Nacht», in zeitlicher Reihenfolge; `nil` (leere Epoche) unterbricht die Strecke.
    ///   - threshold: Leicht-Schwelle in m/s²; ruhig heisst Bewegung vorhanden und darunter.
    ///   - heartRates: Puls je Epoche oder `nil` (keiner bekannt): Dann beginnt eine Strecke nur, wenn die Uhr getragen
    ///     wurde, also höchstens `wornEpochs` Epochen davor oder in ihrer ersten Epoche ein Puls gemessen ist und mindestens
    ///     einer in der Strecke danach (Audit c3b8b01 E-5, Prüfrunde 2 F-1).
    ///   - heartLead: So viele Epochen vor `motions` beginnt `heartRates` (Puls vor dem Beginn der Messung zählt für die
    ///     erste Strecke); 0 = gleich gezählt.
    /// - Returns: Index der ersten Epoche der ersten ruhigen Strecke, oder `nil`, solange es keine gibt.
    public static func find(_ motions: [Double?], threshold: Double = DetectorConfig.defaultLightThreshold,
                            heartRates: [Double?]? = nil, heartLead: Int = 0) -> Int? {
        func quiet(_ index: Int) -> Bool {
            if let motion = motions[index] { return motion < threshold }
            return false
        }
        func pulse(_ index: Int) -> Bool {
            guard let heartRates else { return false }
            let at = index + heartLead
            return at >= 0 && at < heartRates.count && heartRates[at] != nil
        }
        func worn(_ start: Int, _ end: Int) -> Bool {
            guard heartRates != nil else { return true }
            return (max(-heartLead, start - wornEpochs)...start).contains(where: pulse) && (start + 1...end).contains(where: pulse)
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
            if still, worn(start, end) { return start }
        }
        return nil
    }
}
