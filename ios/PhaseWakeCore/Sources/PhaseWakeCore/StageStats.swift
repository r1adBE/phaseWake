import Foundation

/// Bewertung eines Phasenanteils gegen den üblichen Bereich. Identisch zu `StageRating` (Kotlin).
public enum StageRating: String, Sendable {
    /// Anteil unter der unteren Grenze.
    case below = "BELOW"
    /// Anteil im üblichen Bereich (Grenzen eingeschlossen).
    case typical = "TYPICAL"
    /// Anteil über der oberen Grenze.
    case above = "ABOVE"
}

/// Üblicher Bereich eines Phasenanteils in ganzen Prozent, beide Grenzen eingeschlossen. Identisch zu Kotlin.
public struct StageRange: Equatable, Sendable {
    /// Untere Grenze in Prozent (eingeschlossen).
    public let low: Int
    /// Obere Grenze in Prozent (eingeschlossen).
    public let high: Int

    /// Erzeugt einen Bereich; `low` ≤ `high`.
    public init(low: Int, high: Int) {
        self.low = low
        self.high = high
    }

    /// `below` unter `low`, `above` über `high`, sonst `typical`.
    func rate(_ percent: Int) -> StageRating {
        percent < low ? .below : percent > high ? .above : .typical
    }
}

/// Ein zusammenhängender Block einer bekannten Phase (wach, REM, leicht, tief; nie «unbekannt»). Identisch zu Kotlin.
public struct StageBlock: Equatable, Sendable {
    /// Phase des Blocks.
    public let stage: SleepStage
    /// Beginn des Blocks.
    public let start: Date
    /// Ende des Blocks (ausschliesslich).
    public let end: Date

    /// Erzeugt einen Block; `start` liegt vor `end`.
    public init(stage: SleepStage, start: Date, end: Date) {
        self.stage = stage
        self.start = start
        self.end = end
    }

    /// Länge in ganzen Minuten, abgerundet (Millisekunden / 60 000), nie negativ.
    public var minutes: Int { StageStats.minutes(StageStats.millis(start, end)) }
}

/// Summe einer Phase in einer Nacht. Identisch zu `StageTotal` (Kotlin).
public struct StageTotal: Equatable, Sendable {
    /// Phase der Summe.
    public let stage: SleepStage
    /// Minuten der Phase: Millisekunden summiert, dann abgerundet.
    public let minutes: Int
    /// Anteil an allen vier Phasen in ganzen Prozent; die vier ergeben zusammen 100 (Restverfahren), 0 ohne Zeit.
    public let percent: Int
    /// Üblicher Bereich (``StageStats/typical``).
    public let range: StageRange
    /// Anteil gegen den üblichen Bereich.
    public let rating: StageRating
}

/// Ein Satz Einordnung je Nacht nach fester Regel (kein Medizin-Ton). Identisch zu `StageVerdict` (Kotlin).
public enum StageVerdict: String, Sendable {
    /// Mehr Wachzeit als üblich.
    case awakeAbove = "AWAKE_ABOVE"
    /// Weniger Tiefschlaf als üblich.
    case deepBelow = "DEEP_BELOW"
    /// Mehr Tiefschlaf als üblich.
    case deepAbove = "DEEP_ABOVE"
    /// Wenig REM.
    case remBelow = "REM_BELOW"
    /// Mehr REM als üblich.
    case remAbove = "REM_ABOVE"
    /// Weniger leichter Schlaf als üblich.
    case lightBelow = "LIGHT_BELOW"
    /// Mehr leichter Schlaf als üblich.
    case lightAbove = "LIGHT_ABOVE"
    /// Alle Phasen im üblichen Bereich.
    case typical = "TYPICAL"
}

/// Auswertung der Schlafphasen einer Nacht für die Detailseite. Identisch zu `NightStages` (Kotlin).
public struct NightStages: Equatable, Sendable {
    /// Beginn des ausgewerteten Bereichs.
    public let start: Date
    /// Ende des ausgewerteten Bereichs.
    public let end: Date
    /// Bekannte Phasen, zugeschnitten, nach Beginn; gleiche Phasen, die sich berühren oder überlappen, zusammengelegt.
    public let blocks: [StageBlock]
    /// Immer vier Einträge: wach, REM, leicht, tief.
    public let totals: [StageTotal]
    /// Einordnung; `nil`, wenn die vier Phasen zusammen unter ``StageStats/minVerdictMinutes`` liegen.
    public let verdict: StageVerdict?
    /// Einschlafdauer in Minuten (abgerundet): nur wenn der erste Block «wach» ist und ein weiterer folgt, sonst `nil`.
    public let latencyMinutes: Int?
    /// Schlaf (alles ausser «wach», «unbekannt» zählt als Schlaf) im Verhältnis zum Bereich, kaufmännisch gerundet, höchstens 100.
    public let efficiencyPercent: Int
    /// Wachphasen: Blöcke «wach» mit einem Schlafblock davor und danach (Anfang und Ende zählen nicht).
    public let awakenings: Int
    /// Längster Tiefschlaf-Block in Minuten; 0 ohne Tiefschlaf.
    public let longestDeepMinutes: Int
    /// Geschlafene Minuten (wie bei der Effizienz), abgerundet.
    public let asleepMinutes: Int

    /// Summe der Phase `stage`; `nil` für «unbekannt».
    public func total(_ stage: SleepStage) -> StageTotal? { totals.first { $0.stage == stage } }

    /// Letzter Block mit Beginn ≤ `at` < Ende; `nil` in einer Lücke oder ausserhalb.
    public func blockAt(_ at: Date) -> StageBlock? { blocks.last { $0.start <= at && at < $0.end } }
}

/// Phasen-Summen, Anteile, Einordnung und Kennzahlen einer Nacht: reine Rechnung. Identisch zu `StageStats` (Kotlin).
public enum StageStats {
    /// Unter 120 Minuten (2 Std.) Phasen sagen die Anteile wenig: keine Einordnung.
    public static let minVerdictMinutes = 120

    /// Übliche Anteile für Erwachsene in Prozent (Richtwerte laut Auftrag r1AD vom 29.10.2026): wach «unter 10 %».
    public static let typical: [SleepStage: StageRange] = [
        .awake: StageRange(low: 0, high: 9),
        .rem: StageRange(low: 20, high: 25),
        .light: StageRange(low: 50, high: 60),
        .deep: StageRange(low: 13, high: 23),
    ]

    /// Reihenfolge der Summen und Vorrang bei gleichem Rest beim Restverfahren: wach, REM, leicht, tief (wie im Schlafprofil von oben).
    public static let order: [SleepStage] = [.awake, .rem, .light, .deep]

    /// Auswertung von `spans`, zugeschnitten auf `from`–`to` (``StageCheck/clip(_:from:to:)``).
    /// `nil`, wenn `to` nicht nach `from` liegt oder keine bekannte Phase im Bereich ist.
    public static func of(_ spans: [StageSpan], from: Date, to: Date) -> NightStages? {
        guard to > from else { return nil }
        let clipped = StageCheck.clip(spans, from: from, to: to)
        let known = clipped.filter { ChartData.level($0.stage) != nil }
        guard !known.isEmpty else { return nil }
        let blocks = merge(known)
        let totals = totalsOf(known)
        let knownMs = order.reduce(Int64(0)) { sum, stage in sum + known.filter { $0.stage == stage }.reduce(0) { $0 + millis($1.start, $1.end) } }
        let sleepMs = clipped.filter { $0.stage != .awake }.reduce(Int64(0)) { $0 + millis($1.start, $1.end) }
        let rangeMs = millis(from, to)
        let latency: Int? = blocks.count > 1 && blocks[0].stage == .awake ? minutes(millis(blocks[0].start, blocks[1].start)) : nil
        return NightStages(
            start: from,
            end: to,
            blocks: blocks,
            totals: totals,
            verdict: knownMs < Int64(minVerdictMinutes) * 60_000 ? nil : verdictOf(totals),
            latencyMinutes: latency,
            efficiencyPercent: rangeMs > 0 ? Int(min(100, (sleepMs * 100 + rangeMs / 2) / rangeMs)) : 0,
            awakenings: awakenings(blocks),
            longestDeepMinutes: blocks.filter { $0.stage == .deep }.map(\.minutes).max() ?? 0,
            asleepMinutes: minutes(sleepMs)
        )
    }

    /// Phase-Wake-Nacht: Bereich der Phasen im Schlafprofil der Nacht (``ChartData/hypnogram(_:night:)``) –
    /// früheste bis späteste Phase, die ganz darin liegt. `nil` ohne Schlafprofil oder ohne solche Phase.
    public static func forNight(_ stages: [StageSpan], night: NightRecord) -> NightStages? {
        guard let h = ChartData.hypnogram(stages, night: night) else { return nil }
        let inRange = stages.filter { $0.start >= h.start && $0.end <= h.end }
        guard let from = inRange.map(\.start).min(), let to = inRange.map(\.end).max() else { return nil }
        return of(stages, from: from, to: to)
    }

    /// Nacht aus der Gesundheits-App: Phasen der Sitzung über die ganze Sitzung.
    public static func forSession(_ session: HealthSession) -> NightStages? {
        of(session.stages, from: session.start, to: session.end)
    }

    /// Gleiche Phase, die die vorige berührt oder überlappt, verlängert den Block (Ende = das spätere).
    private static func merge(_ known: [StageSpan]) -> [StageBlock] {
        var out: [StageBlock] = []
        for span in known {
            if let last = out.last, last.stage == span.stage, span.start <= last.end {
                out[out.count - 1] = StageBlock(stage: last.stage, start: last.start, end: max(last.end, span.end))
            } else {
                out.append(StageBlock(stage: span.stage, start: span.start, end: span.end))
            }
        }
        return out
    }

    /// Summen in der Reihenfolge wach, REM, leicht, tief; Prozent nach dem Restverfahren (Summe 100).
    private static func totalsOf(_ known: [StageSpan]) -> [StageTotal] {
        let ms = order.map { stage in known.filter { $0.stage == stage }.reduce(Int64(0)) { $0 + millis($1.start, $1.end) } }
        let total = ms.reduce(0, +)
        var percent = ms.map { total > 0 ? Int($0 * 100 / total) : 0 }
        if total > 0 {
            let rest = ms.map { $0 * 100 % total }
            // Grösster Rest zuerst; bei Gleichstand die Reihenfolge wach, REM, leicht, tief.
            let ranked = order.indices.sorted { rest[$0] != rest[$1] ? rest[$0] > rest[$1] : $0 < $1 }
            for i in ranked.prefix(max(0, 100 - percent.reduce(0, +))) { percent[i] += 1 }
        }
        return order.indices.map { i in
            let range = typical[order[i]] ?? StageRange(low: 0, high: 100)
            return StageTotal(stage: order[i], minutes: minutes(ms[i]), percent: percent[i], range: range, rating: range.rate(percent[i]))
        }
    }

    /// Erste Abweichung in fester Reihenfolge: wach darüber, tief darunter, tief darüber, REM darunter, REM darüber,
    /// leicht darunter, leicht darüber; keine: `typical`.
    public static func verdictOf(_ totals: [StageTotal]) -> StageVerdict {
        func rating(_ stage: SleepStage) -> StageRating { totals.first { $0.stage == stage }?.rating ?? .typical }
        let rules: [(SleepStage, StageRating, StageVerdict)] = [
            (.awake, .above, .awakeAbove), (.deep, .below, .deepBelow), (.deep, .above, .deepAbove),
            (.rem, .below, .remBelow), (.rem, .above, .remAbove), (.light, .below, .lightBelow), (.light, .above, .lightAbove),
        ]
        return rules.first { rating($0.0) == $0.1 }?.2 ?? .typical
    }

    /// Blöcke «wach» mit einem Schlafblock irgendwo davor und danach.
    private static func awakenings(_ blocks: [StageBlock]) -> Int {
        blocks.indices.filter { i in
            blocks[i].stage == .awake
                && blocks[..<i].contains { $0.stage != .awake }
                && blocks[(i + 1)...].contains { $0.stage != .awake }
        }.count
    }

    /// Ganze Millisekunden von `from` bis `to` (wie `Instant`), nie negativ.
    static func millis(_ from: Date, _ to: Date) -> Int64 {
        max(0, Int64((to.timeIntervalSince(from) * 1000).rounded()))
    }

    /// Ganze Minuten aus Millisekunden, abgerundet.
    static func minutes(_ millis: Int64) -> Int { Int(millis / 60_000) }
}
