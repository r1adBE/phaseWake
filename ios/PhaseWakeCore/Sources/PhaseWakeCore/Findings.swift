import Foundation

/// Was die Auffälligkeiten (``Findings``) von einer Nacht der Gesundheits-App brauchen. Identisch zu `NightFacts` (Kotlin).
/// Aus einer Sitzung, die als Nacht zählt (``HealthHistory/countedNights(_:clearedAt:calendar:)``).
public struct NightFacts: Equatable, Hashable, Sendable {
    /// Nachtdatum «yyyy-MM-dd» über die Mitte von ``asleepAt`` bis ``end`` in der Zone der Aufzeichnung (wie bei `SleepSummary`).
    public let date: String
    /// Einschlafbeginn (``HealthSession/asleepAt``): die «Bettzeit» wie in Regelmässigkeit und Trends.
    public let asleepAt: Date
    /// Ende der Nacht.
    public let end: Date
    /// Zone der Aufzeichnung; `nil` = die des Geräts.
    public let timeZone: TimeZone?
    /// Schlaf in Minuten (``HealthSession/asleepMinutes``).
    public let asleepMinutes: Int
    /// Einschlafdauer: aus der Gesundheits-App wie auf der Detailseite (``NightStages/latencyMinutes``), sonst aus der
    /// eigenen Messung der Uhr (``Findings/ownLatency(start:end:own:)``, Entscheid r1AD 15.11.2026); `nil` = unbekannt.
    public let latencyMinutes: Int?
    /// Tiefschlaf in Minuten; `nil` ohne Phasen (``StageStats/of(_:from:to:)`` ergibt `nil`).
    public let deepMinutes: Int?
    /// Wach nach dem Einschlafen in Minuten (alle Wachphasen ohne die Einschlafdauer); `nil` ohne Phasen.
    public let awakeMinutes: Int?

    /// Erzeugt die Fakten einer Nacht.
    public init(date: String, asleepAt: Date, end: Date, timeZone: TimeZone?, asleepMinutes: Int,
                latencyMinutes: Int? = nil, deepMinutes: Int? = nil, awakeMinutes: Int? = nil) {
        self.date = date
        self.asleepAt = asleepAt
        self.end = end
        self.timeZone = timeZone
        self.asleepMinutes = asleepMinutes
        self.latencyMinutes = latencyMinutes
        self.deepMinutes = deepMinutes
        self.awakeMinutes = awakeMinutes
    }

    /// `calendar` in der Zone der Aufzeichnung; ohne sie unverändert (Gerät).
    public func localCalendar(_ calendar: Calendar) -> Calendar {
        guard let timeZone else { return calendar }
        var local = calendar
        local.timeZone = timeZone
        return local
    }

    /// Fakten einer Nacht; Nachtdatum in der Zone der Aufzeichnung, ohne sie in `calendar`. Einschlafdauer aus der
    /// Gesundheits-App, ohne sie aus der eigenen Messung `own` (``Findings/ownLatency(start:end:own:)``).
    public static func of(_ session: HealthSession, calendar: Calendar, own: [OwnOnset] = []) -> NightFacts {
        let stats = StageStats.forSession(session)
        let latency = stats?.latencyMinutes
        return NightFacts(
            date: SleepNotes.nightDate(from: session.asleepAt, to: session.end, calendar: session.localCalendar(calendar)),
            asleepAt: session.asleepAt,
            end: session.end,
            timeZone: session.timeZone,
            asleepMinutes: session.asleepMinutes,
            latencyMinutes: latency ?? Findings.ownLatency(start: session.start, end: session.end, own: own),
            deepMinutes: stats?.total(.deep)?.minutes,
            // Nur die Einschlafdauer der Gesundheits-App liegt in deren Wachphasen.
            awakeMinutes: stats.map { max(0, ($0.total(.awake)?.minutes ?? 0) - (latency ?? 0)) }
        )
    }

    /// Fakten aller Nächte aus `sessions` (dieselben wie im Verlauf), nach Beginn; `own` siehe oben.
    public static func of(_ sessions: [HealthSession], clearedAt: Date?, calendar: Calendar, own: [OwnOnset] = []) -> [NightFacts] {
        HealthHistory.countedNights(sessions, clearedAt: clearedAt, calendar: calendar).map { of($0, calendar: calendar, own: own) }
    }
}

/// Eigene Messung des Einschlafens einer Nacht: abgeschlossene Aufzeichnung der Uhr von `start` bis `end` (Apple Watch
/// Rekorder bei «Nacht aufzeichnen», Lernphase, «Gute Nacht» oder automatisch; Galaxy Watch gleich) mit Beginn der
/// Einschlafdauer `bedAt` und Einschlafzeit `asleepAt` (Zeile `onset=` der Aufzeichnung). Identisch zu `OwnOnset` (Kotlin).
public struct OwnOnset: Equatable, Sendable {
    /// Beginn der Aufzeichnung.
    public let start: Date
    /// Ende der Aufzeichnung.
    public let end: Date
    /// Beginn der Einschlafdauer.
    public let bedAt: Date
    /// Einschlafzeit.
    public let asleepAt: Date

    /// Erzeugt eine Messung.
    public init(start: Date, end: Date, bedAt: Date, asleepAt: Date) {
        self.start = start
        self.end = end
        self.bedAt = bedAt
        self.asleepAt = asleepAt
    }
}

/// Art einer Auffälligkeit; die Reihenfolge ist die Priorität. Identisch zu `FindingKind` (Kotlin).
public enum FindingKind: String, CaseIterable, Sendable {
    case longLatency = "LONG_LATENCY", shortNights = "SHORT_NIGHTS", lowDeep = "LOW_DEEP", muchAwake = "MUCH_AWAKE",
         socialJetlag = "SOCIAL_JETLAG", bedtimeVaries = "BEDTIME_VARIES"

    /// Rang in der Priorität (0 = wichtigste).
    public var rank: Int { Self.allCases.firstIndex(of: self) ?? 0 }
}

/// Womit wenig Tiefschlaf zusammenhängt. Identisch zu `DeepLink` (Kotlin).
public enum DeepLink: String, CaseIterable, Sendable {
    case shortNights = "SHORT_NIGHTS", lateBedtime = "LATE_BEDTIME"
}

/// Eine Auffälligkeit der letzten 7 Nächte; ``value`` und Anteile als ganze Zahlen wie angezeigt. Identisch zu `Finding` (Kotlin).
public struct Finding: Equatable, Sendable {
    /// Art.
    public let kind: FindingKind
    /// Ø Einschlafdauer (Min), Zahl kurzer Nächte, Tiefschlaf- bzw. Wach-Anteil (%), Verschiebung bzw. Spanne (Min).
    public let value: Int
    /// Zusammenhang bei wenig Tiefschlaf; `nil` = keiner belegt.
    public let link: DeepLink?
    /// Tiefschlaf-Anteil (%) in den Nächten mit der Ursache.
    public let withPercent: Int
    /// Tiefschlaf-Anteil (%) in den Nächten ohne.
    public let withoutPercent: Int

    /// Erzeugt eine Auffälligkeit.
    public init(kind: FindingKind, value: Int, link: DeepLink? = nil, withPercent: Int = 0, withoutPercent: Int = 0) {
        self.kind = kind
        self.value = value
        self.link = link
        self.withPercent = withPercent
        self.withoutPercent = withoutPercent
    }
}

/// Auffälligkeiten: feste Regeln über die letzten 7 Nachtdaten, je erst ab 5 Nächten mit den nötigen Daten; Werte erst
/// gerundet (halbe aufwärts), dann verglichen. Keine Diagnose. Identisch zu `Findings` (Kotlin), gemeinsame Fälle in
/// `shared/testdata/findings.vectors`.
public enum Findings {
    /// Betrachtete Nachtdaten bis heute.
    public static let nights = 7
    /// Mindestens so viele Nächte mit den Daten der Regel.
    public static let minNights = 5
    /// Lange Einschlafdauer: Ø über diesem Wert (Minuten).
    public static let latencyMinutes = 45
    /// Kurze Nacht: weniger Schlaf als das (Minuten).
    public static let shortNightMinutes = 360
    /// Kurze Nächte: mindestens so viele.
    public static let shortNights = 3
    /// Wenig Tiefschlaf: Anteil am Schlaf unter diesem Wert (%; untere Grenze von ``StageStats/typical``).
    public static let deepPercent = StageStats.typical[.deep]?.low ?? 13
    /// Zusammenhang: mindestens so viele Prozentpunkte weniger …
    public static let linkPoints = 3
    /// … und je mindestens so viele Nächte mit und ohne die Ursache.
    public static let linkNights = 2
    /// Späte Bettzeit: später als der Median plus so viele Minuten.
    public static let lateMinutes = 60
    /// Viel Wachzeit: Wach-Anteil nach dem Einschlafen über diesem Wert (%).
    public static let awakePercent = 20
    /// Sozialer Jetlag: Mitte des Schlafs am Wochenende um mehr als so viele Minuten verschoben.
    public static let jetlagMinutes = 90
    /// Je mindestens so viele Nächte am Wochenende und unter der Woche.
    public static let jetlagNights = 2
    /// Schwankende Bettzeit: Spanne über diesem Wert (Minuten).
    public static let bedtimeRangeMinutes = 90
    /// Ausgeblendet auf der Startseite: erscheint frühestens nach dieser Zeit wieder (7 Tage).
    public static let hideFor: TimeInterval = 7 * 86_400
    /// Wochenrückblick: Tiefschlaf-Anteil nur mit so vielen Nächten mit Phasen je Woche (``Trends/minNightsPerWeek``).
    public static let weekNights = Trends.minNightsPerWeek

    /// Nächte der 7 Nachtdaten bis `today` (einschliesslich); je Nachtdatum die mit dem meisten Schlaf.
    public static func recent(_ facts: [NightFacts], today: String) -> [NightFacts] {
        byDate(facts, from: NightDate.adding(-(nights - 1), to: today), to: today)
    }

    /// Alle Auffälligkeiten bis `today` (Nachtdatum von «jetzt»), wichtigste zuerst; Uhrzeiten in der Zone der Aufzeichnung, sonst `calendar`.
    public static func of(_ facts: [NightFacts], today: String, calendar: Calendar) -> [Finding] {
        let list = recent(facts, today: today)
        let staged = list.filter { $0.deepMinutes != nil && $0.awakeMinutes != nil }
        var out: [Finding] = []
        let latencies = list.compactMap(\.latencyMinutes)
        if latencies.count >= minNights {
            let average = round(Double(latencies.reduce(0, +)) / Double(latencies.count))
            if average > latencyMinutes { out.append(Finding(kind: .longLatency, value: average)) }
        }
        let short = list.filter(isShort).count
        if list.count >= minNights && short >= shortNights { out.append(Finding(kind: .shortNights, value: short)) }
        if staged.count >= minNights {
            if let share = deepShare(staged), share < deepPercent { out.append(lowDeep(share, staged: staged, nights: list, calendar: calendar)) }
            let awake = staged.reduce(0) { $0 + ($1.awakeMinutes ?? 0) }
            if let share = percent(awake, staged.reduce(0) { $0 + $1.asleepMinutes } + awake), share > awakePercent {
                out.append(Finding(kind: .muchAwake, value: share))
            }
        }
        if list.count >= minNights {
            let jetlag = self.jetlag(list, calendar: calendar).flatMap { $0 > jetlagMinutes ? $0 : nil }
            if let jetlag { out.append(Finding(kind: .socialJetlag, value: jetlag)) }
            // Verschiebung am Wochenende erklärt die Schwankung schon (gleicher Tipp): nicht zweimal.
            let times = bedtimes(list, calendar: calendar)
            let range = round((times.max() ?? 0) - (times.min() ?? 0))
            if jetlag == nil && range > bedtimeRangeMinutes { out.append(Finding(kind: .bedtimeVaries, value: range)) }
        }
        return out.sorted { $0.kind.rank < $1.kind.rank }
    }

    /// Die wichtigste Auffälligkeit, die nicht ausgeblendet ist (ausgeblendet um `hidden`, seit weniger als ``hideFor``).
    public static func home(_ findings: [Finding], hidden: [FindingKind: Date], now: Date) -> Finding? {
        findings.first { finding in hidden[finding.kind].map { now < $0.addingTimeInterval(hideFor) } != true }
    }

    /// `hidden` mit `kind` ausgeblendet um `now`; Abgelaufene fallen weg.
    public static func hide(_ hidden: [FindingKind: Date], kind: FindingKind, now: Date) -> [FindingKind: Date] {
        var out = hidden.filter { now < $0.value.addingTimeInterval(hideFor) }
        out[kind] = now
        return out
    }

    /// Speicherformat «ART|ms» je Zeile (ms abgerundet wie `toEpochMilli`).
    public static func encodeHidden(_ hidden: [FindingKind: Date]) -> String {
        hidden.sorted { $0.key.rank < $1.key.rank }
            .map { "\($0.key.rawValue)|\(Int64(($0.value.timeIntervalSince1970 * 1000).rounded(.down)))" }
            .joined(separator: "\n")
    }

    /// Liest ``encodeHidden(_:)``; Unbekanntes und Unlesbares fällt weg, `nil` ergibt nichts.
    public static func decodeHidden(_ text: String?) -> [FindingKind: Date] {
        var out: [FindingKind: Date] = [:]
        for line in (text ?? "").components(separatedBy: "\n") {
            let parts = line.split(separator: "|", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
            guard parts.count == 2, let kind = FindingKind(rawValue: parts[0]), let ms = Int64(parts[1]) else { continue }
            out[kind] = Date(timeIntervalSince1970: Double(ms) / 1000)
        }
        return out
    }

    /// Wochenrückblick: Tiefschlaf-Anteil am Schlaf (%) der Woche bis `weekEnd` und der Woche davor, je nur mit
    /// ``weekNights`` Nächten mit Phasen; sonst `nil`.
    public static func weekDeep(_ facts: [NightFacts], weekEnd: String) -> (week: Int, before: Int)? {
        func week(_ end: String) -> Int? {
            let staged = byDate(facts, from: NightDate.adding(-(nights - 1), to: end), to: end).filter { $0.deepMinutes != nil }
            return staged.count >= weekNights ? deepShare(staged) : nil
        }
        guard let now = week(weekEnd), let before = week(NightDate.adding(-nights, to: weekEnd)) else { return nil }
        return (now, before)
    }

    /// Einschlafdauer aus der eigenen Messung für eine Nacht der Gesundheits-App von `start` bis `end` (nur, wenn diese
    /// keine liefert): die Messung aus `own`, deren Aufzeichnung sich am längsten mit der Nacht überschneidet (gleich: die
    /// erste; wie die Aufzeichnung zur Nacht auf der Detailseite), in ganzen Minuten von `bedAt` bis `asleepAt`
    /// (abgerundet). `nil` ohne Überschneidung oder wenn die Einschlafzeit vor dem Beginn liegt. Wie Kotlin.
    public static func ownLatency(start: Date, end: Date, own: [OwnOnset]) -> Int? {
        var best: (onset: OwnOnset, overlap: Int64)?
        for onset in own where onset.start < end && onset.end > start {
            let overlap = StageStats.millis(max(onset.start, start), min(onset.end, end))
            if best.map({ overlap > $0.overlap }) ?? true { best = (onset, overlap) }
        }
        guard let best, best.onset.asleepAt >= best.onset.bedAt else { return nil }
        return StageStats.minutes(StageStats.millis(best.onset.bedAt, best.onset.asleepAt))
    }

    /// Tiefschlaf-Anteil am Schlaf in ganzen Prozent (halbe aufwärts) – eine Nacht wie die Regel; `nil` ohne Schlaf.
    public static func deepPercent(deepMinutes: Int, asleepMinutes: Int) -> Int? {
        StageStats.sleepPercent(deepMinutes, asleepMinutes: asleepMinutes)
    }

    private static func lowDeep(_ share: Int, staged: [NightFacts], nights list: [NightFacts], calendar: Calendar) -> Finding {
        let minutes = bedtimes(list, calendar: calendar)
        let median = SoundEventDetector.median(minutes)
        let late = Set(zip(list, minutes).filter { $0.1 > median + Double(lateMinutes) }.map(\.0))
        let links = [link(.shortNights, staged, isShort), link(.lateBedtime, staged) { late.contains($0) }].compactMap { $0 }
        // Der grössere Unterschied; gleich: kurze Nächte (Reihenfolge von DeepLink, erster bleibt).
        var best: Finding?
        for candidate in links where best.map({ candidate.withoutPercent - candidate.withPercent > $0.withoutPercent - $0.withPercent }) ?? true {
            best = candidate
        }
        guard let best else { return Finding(kind: .lowDeep, value: share) }
        return Finding(kind: .lowDeep, value: share, link: best.link, withPercent: best.withPercent, withoutPercent: best.withoutPercent)
    }

    private static func link(_ link: DeepLink, _ staged: [NightFacts], _ test: (NightFacts) -> Bool) -> Finding? {
        let with = staged.filter(test), without = staged.filter { !test($0) }
        guard with.count >= linkNights, without.count >= linkNights, let a = deepShare(with), let b = deepShare(without), b - a >= linkPoints else { return nil }
        return Finding(kind: .lowDeep, value: 0, link: link, withPercent: a, withoutPercent: b)
    }

    /// Mitte des Schlafs am Wochenende (Nachtdatum Freitag, Samstag) gegenüber unter der Woche in Minuten; `nil` mit zu wenig Nächten.
    private static func jetlag(_ list: [NightFacts], calendar: Calendar) -> Int? {
        let mids = Regularity.unwrap(list.map { night in
            Regularity.minutesFromNoon(night.asleepAt.addingTimeInterval(night.end.timeIntervalSince(night.asleepAt) / 2),
                                       calendar: night.localCalendar(calendar))
        })
        let weekend = list.indices.filter { index in
            let day = NightDate.weekday(list[index].date)
            return day == .friday || day == .saturday
        }
        let weekdays = list.indices.filter { !weekend.contains($0) }
        guard weekend.count >= jetlagNights, weekdays.count >= jetlagNights else { return nil }
        func mean(_ indices: [Int]) -> Double { indices.reduce(0) { $0 + mids[$1] } / Double(indices.count) }
        return round(abs(mean(weekend) - mean(weekdays)))
    }

    /// Bettzeiten in Minuten ab Mittag ohne Sprung (``Regularity/unwrap(_:)``), Reihenfolge wie `list`.
    private static func bedtimes(_ list: [NightFacts], calendar: Calendar) -> [Double] {
        Regularity.unwrap(list.map { Regularity.minutesFromNoon($0.asleepAt, calendar: $0.localCalendar(calendar)) })
    }

    private static func isShort(_ night: NightFacts) -> Bool { night.asleepMinutes < shortNightMinutes }

    private static func byDate(_ facts: [NightFacts], from: String, to: String) -> [NightFacts] {
        var best: [String: NightFacts] = [:]
        // Je Nachtdatum die mit dem meisten Schlaf; gleich: die erste (wie `maxBy` in Kotlin).
        for night in facts where night.date >= from && night.date <= to && (best[night.date].map { night.asleepMinutes > $0.asleepMinutes } ?? true) {
            best[night.date] = night
        }
        return best.values.sorted { $0.date < $1.date }
    }

    /// Tiefschlaf aller Nächte am Schlaf aller Nächte, gerundet.
    private static func deepShare(_ list: [NightFacts]) -> Int? {
        percent(list.reduce(0) { $0 + ($1.deepMinutes ?? 0) }, list.reduce(0) { $0 + $1.asleepMinutes })
    }

    private static func percent(_ part: Int, _ whole: Int) -> Int? { whole <= 0 ? nil : round(Double(part) * 100 / Double(whole)) }

    /// Halbe aufwärts (wie `floor(x + 0.5)` in Kotlin).
    private static func round(_ value: Double) -> Int { Int((value + 0.5).rounded(.down)) }
}
