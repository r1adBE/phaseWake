import Foundation

/// Ein Faktor des Schlafwerts (``SleepScore``); Reihenfolge = Reihenfolge der Kacheln. Identisch zu `ScoreFactor` (Kotlin).
public enum ScoreFactor: String, CaseIterable, Sendable {
    /// Schlaf (ohne Wachphasen) gegen das Schlafziel.
    case duration = "DURATION"
    /// Anteil Tiefschlaf.
    case deep = "DEEP"
    /// Anteil REM-Schlaf.
    case rem = "REM"
    /// Anteil Wachzeit.
    case awake = "AWAKE"
    /// Einschlafdauer.
    case latency = "LATENCY"
}

/// Bewertung in Worten, für den Schlafwert und jeden Faktor gleich (``SleepScore/band(_:)``). Identisch zu Kotlin.
public enum ScoreBand: String, Sendable {
    /// 85–100.
    case excellent = "EXCELLENT"
    /// 70–84.
    case good = "GOOD"
    /// 50–69.
    case fair = "FAIR"
    /// 0–49.
    case poor = "POOR"
}

/// Messwerte einer Nacht für den Schlafwert; fehlende Werte `nil` (der Faktor fällt dann weg).
/// Prozente wie in ``StageTotal/percent`` (Anteil an wach, REM, leicht, tief). Identisch zu `ScoreInput` (Kotlin).
public struct ScoreInput: Equatable, Sendable {
    /// Schlaf ohne Wachphasen in Minuten.
    public let asleepMinutes: Int?
    /// Schlafziel in Minuten (``SleepGoal``).
    public let goalMinutes: Int
    /// Anteil Tiefschlaf in Prozent.
    public let deepPercent: Int?
    /// Anteil REM-Schlaf in Prozent.
    public let remPercent: Int?
    /// Anteil Wachzeit in Prozent.
    public let awakePercent: Int?
    /// Einschlafdauer in Minuten.
    public let latencyMinutes: Int?

    /// Erzeugt die Eingabe; fehlende Werte `nil`.
    public init(asleepMinutes: Int?, goalMinutes: Int, deepPercent: Int? = nil, remPercent: Int? = nil,
                awakePercent: Int? = nil, latencyMinutes: Int? = nil) {
        self.asleepMinutes = asleepMinutes
        self.goalMinutes = goalMinutes
        self.deepPercent = deepPercent
        self.remPercent = remPercent
        self.awakePercent = awakePercent
        self.latencyMinutes = latencyMinutes
    }
}

/// Ein Faktor mit seinem Wert: `points` 0–100, `weight` laut ``SleepScore/weights``, `band` aus `points`.
public struct FactorScore: Equatable, Sendable {
    /// Der Faktor.
    public let factor: ScoreFactor
    /// Punkte 0–100.
    public let points: Int
    /// Gewicht laut ``SleepScore/weights``.
    public let weight: Int
    /// Bewertung der Punkte.
    public let band: ScoreBand
}

/// Schlafwert einer Nacht: `score` 0–100, `band` in Worten, die vorhandenen Faktoren in der Reihenfolge von
/// ``ScoreFactor``. `provisional`: ohne Schlafphasen (weder Tief noch REM noch Wach) nur vorläufig, höchstens
/// ``SleepScore/provisionalMax``. Identisch zu `SleepScoreResult` (Kotlin).
public struct SleepScoreResult: Equatable, Sendable {
    /// Wert 0–100.
    public let score: Int
    /// Bewertung des Werts.
    public let band: ScoreBand
    /// Vorhandene Faktoren in der Reihenfolge von ``ScoreFactor``.
    public let factors: [FactorScore]
    /// Ohne Schlafphasen: vorläufig, höchstens ``SleepScore/provisionalMax``.
    public let provisional: Bool

    /// Faktor `factor`; `nil`, wenn er fehlt.
    public func factor(_ factor: ScoreFactor) -> FactorScore? { factors.first { $0.factor == factor } }
}

/// Schlafwert 0–100 mit Bewertung in Worten: reine Rechnung, Regel in `docs/PHASENERKENNUNG.md`, Abschnitt 11.
/// Jeder Faktor gibt 0–100 Punkte (ganze Zahl, gerundet); der Wert ist das gewichtete Mittel der vorhandenen Faktoren
/// (fehlt einer, zählen die übrigen mit ihren Gewichten), gerundet. Ohne Schlafdauer kein Wert. Ohne Schlafphasen
/// (weder Tief noch REM noch Wach) ist der Wert vorläufig und höchstens ``provisionalMax`` («Gut»).
/// Nur zur Orientierung, keine medizinische Bewertung. Identisch zu `SleepScore` (Kotlin).
public enum SleepScore {
    /// Gewichte der Faktoren; zusammen 100.
    public static let weights: [ScoreFactor: Int] = [
        .duration: 35,
        .deep: 20,
        .rem: 20,
        .awake: 15,
        .latency: 10,
    ]

    /// Ab dieser Wachzeit in Prozent gibt «Wach» 0 Punkte (bis zum üblichen Bereich 100).
    public static let awakeZeroPercent = 30
    /// Einschlafdauer bis hierhin (Minuten, eingeschlossen) gibt 100 Punkte.
    public static let latencyFullMinutes = 20
    /// Ab dieser Einschlafdauer (Minuten) 0 Punkte.
    public static let latencyZeroMinutes = 60
    /// Höchster Wert ohne Schlafphasen: Dauer und Einschlafdauer allein reichen nicht für «Ausgezeichnet».
    public static let provisionalMax = 84

    private static let full = 100.0

    /// Schlafwert aus `input`; `nil` ohne Schlafdauer (oder mit Schlafziel ≤ 0).
    public static func of(_ input: ScoreInput) -> SleepScoreResult? {
        guard let asleep = input.asleepMinutes, input.goalMinutes > 0 else { return nil }
        var raw: [(ScoreFactor, Int)] = [(.duration, durationPoints(asleep, goalMinutes: input.goalMinutes))]
        if let deep = input.deepPercent { raw.append((.deep, atLeastPoints(deep, low: low(.deep)))) }
        if let rem = input.remPercent { raw.append((.rem, atLeastPoints(rem, low: low(.rem)))) }
        if let awake = input.awakePercent { raw.append((.awake, awakePoints(awake))) }
        if let latency = input.latencyMinutes { raw.append((.latency, latencyPoints(latency))) }
        let factors = raw.map { FactorScore(factor: $0.0, points: $0.1, weight: weights[$0.0] ?? 0, band: band($0.1)) }
        let weightSum = factors.reduce(0) { $0 + $1.weight }
        let weighted = round(Double(factors.reduce(0) { $0 + $1.points * $1.weight }) / Double(weightSum))
        let provisional = input.deepPercent == nil && input.remPercent == nil && input.awakePercent == nil
        let score = provisional ? min(weighted, provisionalMax) : weighted
        return SleepScoreResult(score: score, band: band(score), factors: factors, provisional: provisional)
    }

    /// Bewertung zu `points`: ab 85 ausgezeichnet, ab 70 gut, ab 50 mittel, darunter schwach.
    public static func band(_ points: Int) -> ScoreBand {
        if points >= 85 { return .excellent }
        if points >= 70 { return .good }
        if points >= 50 { return .fair }
        return .poor
    }

    /// Schlafdauer: 100 ab dem Ziel, 0 bei höchstens der Hälfte, dazwischen gerade. Kein Abzug für längeren Schlaf.
    public static func durationPoints(_ asleepMinutes: Int, goalMinutes: Int) -> Int {
        if asleepMinutes >= goalMinutes { return 100 }
        let half = Double(goalMinutes) / 2
        return round(clamp(full * (Double(asleepMinutes) - half) / half))
    }

    /// Tief und REM: 100 ab der unteren Grenze des üblichen Bereichs (``StageStats/typical``), darunter anteilig.
    public static func atLeastPoints(_ percent: Int, low: Int) -> Int {
        percent >= low ? 100 : round(clamp(full * Double(percent) / Double(low)))
    }

    /// Wach: 100 im üblichen Bereich (bis 9 %), 0 ab ``awakeZeroPercent``, dazwischen gerade.
    public static func awakePoints(_ percent: Int) -> Int {
        let high = StageStats.typical[.awake]?.high ?? 9
        if percent <= high { return 100 }
        return round(clamp(full * Double(awakeZeroPercent - percent) / Double(awakeZeroPercent - high)))
    }

    /// Einschlafdauer: 100 bis ``latencyFullMinutes``, 0 ab ``latencyZeroMinutes``, dazwischen gerade.
    public static func latencyPoints(_ minutes: Int) -> Int {
        if minutes <= latencyFullMinutes { return 100 }
        let span = Double(latencyZeroMinutes - latencyFullMinutes)
        return round(clamp(full * Double(latencyZeroMinutes - minutes) / span))
    }

    /// Eingabe aus den Phasen einer Nacht (``StageStats``): Schlaf, Anteile Tief, REM und Wach, Einschlafdauer (falls bekannt).
    public static func inputOf(_ stats: NightStages, goalMinutes: Int) -> ScoreInput {
        ScoreInput(
            asleepMinutes: stats.asleepMinutes,
            goalMinutes: goalMinutes,
            deepPercent: stats.total(.deep)?.percent,
            remPercent: stats.total(.rem)?.percent,
            awakePercent: stats.total(.awake)?.percent,
            latencyMinutes: stats.latencyMinutes
        )
    }

    /// Eingabe ohne Phasen, aus einer eigenen Aufzeichnung mit erkanntem Einschlafen: Schlaf = `asleepAt` bis `end`
    /// (ganze Minuten, abgerundet), Einschlafdauer = `bedAt` bis `asleepAt`, falls `bedAt` nicht danach liegt.
    /// `nil`, wenn `end` nicht nach `asleepAt` liegt.
    public static func inputOf(bedAt: Date?, asleepAt: Date, end: Date, goalMinutes: Int) -> ScoreInput? {
        guard end > asleepAt else { return nil }
        let latency = bedAt.flatMap { $0 <= asleepAt ? StageStats.minutes(StageStats.millis($0, asleepAt)) : nil }
        return ScoreInput(asleepMinutes: StageStats.minutes(StageStats.millis(asleepAt, end)), goalMinutes: goalMinutes,
                          latencyMinutes: latency)
    }

    private static func low(_ stage: SleepStage) -> Int { StageStats.typical[stage]?.low ?? 0 }

    private static func clamp(_ value: Double) -> Double { min(max(value, 0), full) }

    /// Kaufmännisch gerundet (x.5 aufwärts); alle Werte sind ≥ 0.
    private static func round(_ value: Double) -> Int { Int((value + 0.5).rounded(.down)) }
}
