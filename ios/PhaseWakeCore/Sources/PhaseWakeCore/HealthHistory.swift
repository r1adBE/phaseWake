import Foundation

/// Schlafsitzung aus der Gesundheits-App (HealthKit bzw. Health Connect). Nur gelesen, nur im Speicher.
/// Identisch zu `HealthSession` (Kotlin).
///
/// Alle Minutenangaben schneiden die Phasen auf `start`–`end` zu, summieren Millisekunden und runden
/// erst am Ende auf ganze Minuten ab (wie `Duration.toMinutes()`).
public struct HealthSession: Equatable, Sendable {
    /// Sitzungsbeginn («im Bett»).
    public let start: Date
    /// Sitzungsende (Aufwachen).
    public let end: Date
    /// Schlafphasen der Sitzung, in beliebiger Reihenfolge; leer = die App kennt keine Phasen.
    public let stages: [StageSpan]
    /// Herkunft: Apple Bundle-ID bzw. Quellname, Android Paketname; `nil` = unbekannt.
    public let source: String?

    /// Erzeugt eine Sitzung; `start` liegt vor `end` (sonst übergeht ``HealthHistory/merge(nights:sessions:clearedAt:calendar:)`` sie).
    public init(start: Date, end: Date, stages: [StageSpan] = [], source: String? = nil) {
        self.start = start
        self.end = end
        self.stages = stages
        self.source = source
    }

    /// Länge `end` − `start` in ganzen Minuten, abgerundet, nie negativ.
    public var durationMinutes: Int { Self.minutes(Self.millis(start, end)) }

    /// Ob die Sitzung mindestens eine bekannte Phase (wach, leicht, tief, REM) hat; «unbekannt» zählt nicht.
    public var hasStages: Bool { stages.contains { ChartData.level($0.stage) != nil } }

    /// Einschlafen: Beginn der ersten Phase, die nicht «wach» ist («unbekannt» zählt als Schlaf), auf die
    /// Sitzung begrenzt; ohne solche Phase `start`.
    public var asleepAt: Date {
        clipped.filter { $0.stage != .awake }.map(\.start).min() ?? start
    }

    /// Geschlafene Minuten: alle Phasen ausser «wach» («unbekannt» zählt mit). Ohne jede Phase die ganze Dauer.
    public var asleepMinutes: Int {
        guard !stages.isEmpty else { return durationMinutes }
        return Self.minutes(clipped.filter { $0.stage != .awake }.reduce(0) { $0 + Self.millis($1.start, $1.end) })
    }

    /// Wache Minuten in der Sitzung; `nil` ohne bekannte Phasen (``hasStages``).
    public var awakeMinutes: Int? { hasStages ? minutesOf(.awake) : nil }

    /// Ob die Sitzung ein Nickerchen statt einer Nacht ist: kürzer als ``HealthHistory/minNightMinutes`` und ganz am
    /// Tag – Beginn frühestens ``HealthHistory/napEarliestHour`` Uhr, Ende spätestens ``HealthHistory/napLatestHour`` Uhr,
    /// beides Ortszeit in `calendar` am selben Tag. Kurzer Schlaf in der Nacht (z. B. 01:00–03:00) bleibt eine Nacht.
    public func isNap(calendar: Calendar) -> Bool {
        guard durationMinutes < HealthHistory.minNightMinutes, calendar.isDate(start, inSameDayAs: end),
              let earliest = calendar.date(bySettingHour: HealthHistory.napEarliestHour, minute: 0, second: 0, of: start),
              let latest = calendar.date(bySettingHour: HealthHistory.napLatestHour, minute: 0, second: 0, of: start) else { return false }
        return start >= earliest && end <= latest
    }

    /// Minuten der Phase `stage` in der Sitzung.
    public func minutesOf(_ stage: SleepStage) -> Int {
        Self.minutes(clipped.filter { $0.stage == stage }.reduce(0) { $0 + Self.millis($1.start, $1.end) })
    }

    /// Bekannte Phasen (ohne «unbekannt») als Stufen in Minuten ab `start`, nach Beginn sortiert – für das
    /// Phasenband der Karte.
    public var steps: [HypnoStep] {
        clipped.filter { ChartData.level($0.stage) != nil }.map {
            HypnoStep(fromMinute: $0.start.timeIntervalSince(start) / 60, toMinute: $0.end.timeIntervalSince(start) / 60, stage: $0.stage)
        }
    }

    /// Phasen auf die Sitzung zugeschnitten, nach Beginn sortiert.
    private var clipped: [StageSpan] { StageCheck.clip(stages, from: start, to: end) }

    /// Ganze Millisekunden von `from` bis `to` (wie `Instant`), nie negativ.
    private static func millis(_ from: Date, _ to: Date) -> Int64 {
        max(0, Int64((to.timeIntervalSince(from) * 1000).rounded()))
    }

    private static func minutes(_ millis: Int64) -> Int { Int(millis / 60_000) }
}

/// Art eines Verlaufseintrags. Identisch zu `HistoryKind` (Kotlin).
public enum HistoryKind: String, Sendable {
    /// Von Phase Wake geweckte Nacht.
    case phaseWake = "PHASE_WAKE"
    /// Sitzung aus der Gesundheits-App ohne Phase-Wake-Wecker.
    case health = "HEALTH"
}

/// Ein Eintrag im Verlauf. Genau eines von `night` und `session` ist gesetzt (nach `kind`).
/// Identisch zu `HistoryItem` (Kotlin).
public struct HistoryItem: Equatable, Sendable {
    /// Art des Eintrags.
    public let kind: HistoryKind
    /// Nachtdatum «yyyy-MM-dd»: `SleepNotes.nightDate(of:)` der Nacht bzw. `SleepNotes.nightDate` des Sitzungsendes.
    public let date: String
    /// Eigene Nacht bei ``HistoryKind/phaseWake``.
    public let night: NightRecord?
    /// Fremd-Sitzung bei ``HistoryKind/health``.
    public let session: HealthSession?

    /// Erzeugt einen Eintrag.
    public init(kind: HistoryKind, date: String, night: NightRecord? = nil, session: HealthSession? = nil) {
        self.kind = kind
        self.date = date
        self.night = night
        self.session = session
    }

    /// Zeitpunkt für die Sortierung: Weckmoment der Nacht bzw. Ende der Sitzung; ohne beides 1970-01-01 (wie `Instant.EPOCH`).
    public var at: Date { night?.wokeAt ?? session?.end ?? Date(timeIntervalSince1970: 0) }
}

/// Verlauf aus eigenen Nächten und Sitzungen der Gesundheits-App. Identisch zu `HealthHistory` (Kotlin).
public enum HealthHistory {
    /// Sitzungen unter 180 Minuten (3 h) können Nickerchen sein (keine Nacht), wenn sie ganz am Tag liegen
    /// (``HealthSession/isNap(calendar:)``) – dieselbe Regel gilt für Diagramme und Trends (``summaries(_:clearedAt:calendar:)``).
    public static let minNightMinutes = 180
    /// Frühester Beginn eines Nickerchens (Stunde Ortszeit, 09:00 einschliesslich): Wer vor 09:00 kurz schläft, hatte eine Nacht.
    public static let napEarliestHour = 9
    /// Spätestes Ende eines Nickerchens (Stunde Ortszeit, 19:00 einschliesslich): Wer nach 19:00 aufwacht, hatte eine Nacht.
    public static let napLatestHour = 19
    /// Lesefenster der Gesundheitsdaten in Tagen, so lang wie der eigene Verlauf (`NightHistory.maxNights`).
    public static let days = 90

    /// Sitzungen, die nach «Verlauf löschen» sichtbar bleiben: Ende echt nach `clearedAt` (gleich = ausgeblendet).
    /// `clearedAt` `nil` = alle. Die Reihenfolge bleibt.
    public static func visible(_ sessions: [HealthSession], clearedAt: Date?) -> [HealthSession] {
        guard let clearedAt else { return sessions }
        return sessions.filter { $0.end > clearedAt }
    }

    /// Verlauf, neueste zuerst: alle eigenen Nächte (vom Löschzeitpunkt unberührt) und je Nachtdatum höchstens
    /// eine Fremd-Sitzung. Es zählen nur Sitzungen, die als Nacht gelten (sichtbar, kein Nickerchen in `calendar`, `end` nach `start`,
    /// ohne Doppel – siehe ``distinct(_:)``) und mit keiner eigenen Nacht dasselbe Nachtdatum haben oder sich mit ihr
    /// überschneiden (Sitzungsbeginn vor dem Weckmoment und Sitzungsende nach dem Fensterbeginn). Mehrere am selben
    /// Nachtdatum: nach derselben Vorliebe wie ``distinct(_:)``. Sortierung nach ``HistoryItem/at`` absteigend; gleich:
    /// eigene Nacht vor Sitzung, zwei eigene Nächte nach Fensterbeginn absteigend, dann Wecker-Kennung aufsteigend.
    public static func merge(nights: [NightRecord], sessions: [HealthSession], clearedAt: Date?, calendar: Calendar) -> [HistoryItem] {
        let ownDates = Set(nights.map { SleepNotes.nightDate(of: $0, calendar: calendar) })
        let candidates = countedNights(sessions, clearedAt: clearedAt, calendar: calendar).filter { session in
            !ownDates.contains(SleepNotes.nightDate(session.end, calendar: calendar))
                && !nights.contains { session.start < $0.wokeAt && session.end > $0.windowStart }
        }
        var best: [String: HealthSession] = [:]
        for session in candidates {
            let date = SleepNotes.nightDate(session.end, calendar: calendar)
            if let current = best[date], !preferred(session, over: current) { continue }
            best[date] = session
        }
        let items = nights.map { HistoryItem(kind: .phaseWake, date: SleepNotes.nightDate(of: $0, calendar: calendar), night: $0) }
            + best.map { HistoryItem(kind: .health, date: $0.key, session: $0.value) }
        return items.sorted(by: newerFirst)
    }

    /// Ohne doppelte Nächte: Von Sitzungen, die sich zeitlich überschneiden (Beginn vor dem Ende der anderen und
    /// umgekehrt; blosses Berühren zählt nicht), bleibt nur eine – zuerst die mit Phasen (``HealthSession/hasStages``),
    /// dann die längste, dann der frühere Beginn, dann die Quelle (`nil` zuerst, sonst alphabetisch). Nach Beginn sortiert.
    public static func distinct(_ sessions: [HealthSession]) -> [HealthSession] {
        var kept: [HealthSession] = []
        for session in sessions.sorted(by: preferred) where !kept.contains(where: { session.start < $0.end && $0.start < session.end }) {
            kept.append(session)
        }
        return kept.sorted { $0.start < $1.start }
    }

    /// Phasen der Sitzungen, die als Nacht zählen (ohne Doppel), nach Beginn – für das Schlafprofil eigener Nächte.
    public static func stages(_ sessions: [HealthSession], clearedAt: Date?, calendar: Calendar) -> [StageSpan] {
        countedNights(sessions, clearedAt: clearedAt, calendar: calendar).flatMap(\.stages).sorted { $0.start < $1.start }
    }

    /// Nächte für Diagramme, Trends, Wochenbilanz, Serien und Regelmässigkeit: `SleepSummary.group` über die
    /// Phasen der Sitzungen, die als Nacht zählen (sichtbar, kein Nickerchen, ohne Doppel – nichts zählt zweimal);
    /// eine Sitzung ohne Phasen zählt als eine «unbekannt»-Phase über die ganze Dauer.
    public static func summaries(_ sessions: [HealthSession], clearedAt: Date?, calendar: Calendar) -> [SleepSummary] {
        SleepSummary.group(countedNights(sessions, clearedAt: clearedAt, calendar: calendar).flatMap { session in
            session.stages.isEmpty ? [StageSpan(start: session.start, end: session.end, stage: .unknown)] : session.stages
        })
    }

    /// Sitzungen, die als Nacht zählen: sichtbar, Ende nach Beginn, kein Nickerchen in `calendar`, ohne Doppel (``distinct(_:)``).
    private static func countedNights(_ sessions: [HealthSession], clearedAt: Date?, calendar: Calendar) -> [HealthSession] {
        distinct(visible(sessions, clearedAt: clearedAt).filter { $0.end > $0.start && !$0.isNap(calendar: calendar) })
    }

    /// Ob `candidate` vor `current` gewählt wird: mit Phasen, länger, früher begonnen, dann Quelle.
    private static func preferred(_ candidate: HealthSession, over current: HealthSession) -> Bool {
        if candidate.hasStages != current.hasStages { return candidate.hasStages }
        let a = candidate.end.timeIntervalSince(candidate.start), b = current.end.timeIntervalSince(current.start)
        if a != b { return a > b }
        if candidate.start != current.start { return candidate.start < current.start }
        switch (candidate.source, current.source) {
        case (nil, nil), (_, nil): return false
        case (nil, _): return true
        case let (x?, y?): return x < y
        }
    }

    /// Reihenfolge des Verlaufs (siehe ``merge(nights:sessions:clearedAt:calendar:)``).
    private static func newerFirst(_ a: HistoryItem, _ b: HistoryItem) -> Bool {
        if a.at != b.at { return a.at > b.at }
        if a.kind != b.kind { return a.kind == .phaseWake }
        guard let x = a.night, let y = b.night else { return false }
        if x.windowStart != y.windowStart { return x.windowStart > y.windowStart }
        return x.alarmId < y.alarmId
    }
}
