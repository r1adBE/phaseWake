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
    /// Setzt die Sitzung eine Nacht fort (Weiterschlafen, ``HealthHistory/continuationGap``), zählt sie trotzdem zu dieser Nacht.
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

    /// Ganze Millisekunden von `from` bis `to`, abgerundet wie `Duration.toMillis` in Kotlin (HealthKit-Zeiten sind nicht
    /// immer ganze Millisekunden; aufgerundet würde eine Sitzung knapp unter 180 Minuten hier eine Nacht), nie negativ.
    private static func millis(_ from: Date, _ to: Date) -> Int64 {
        max(0, Int64((to.timeIntervalSince(from) * 1000).rounded(.down)))
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
    /// Nachtdatum «yyyy-MM-dd»: `SleepNotes.nightDate(of:)` der Nacht bzw. der Sitzung (über ihre Mitte).
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

    /// Eindeutiger Schlüssel des Eintrags, z. B. für die Detailseite: eigene Nacht «n:» + Wecker-Kennung + «:» +
    /// Fensterbeginn in Millisekunden seit 1970, Nacht aus der Gesundheits-App «h:» + Nachtdatum.
    public var key: String {
        if let night {
            return "n:\(night.alarmId):\(Int64((night.windowStart.timeIntervalSince1970 * 1000).rounded()))"
        }
        return "h:" + date
    }

    /// Zeitpunkt für die Sortierung: Weckmoment der Nacht bzw. Ende der Sitzung; ohne beides 1970-01-01 (wie `Instant.EPOCH`).
    public var at: Date { night?.wokeAt ?? session?.end ?? Date(timeIntervalSince1970: 0) }
}

/// Verlauf aus eigenen Nächten und Sitzungen der Gesundheits-App. Identisch zu `HealthHistory` (Kotlin).
/// Weiterschlafen nach dem Wecken (``continuationGap``) gehört zur Nacht davor.
public enum HealthHistory {
    /// Sitzungen unter 180 Minuten (3 h) können Nickerchen sein (keine Nacht), wenn sie ganz am Tag liegen
    /// (``HealthSession/isNap(calendar:)``) – dieselbe Regel gilt für Diagramme und Trends (``summaries(_:clearedAt:calendar:)``).
    public static let minNightMinutes = 180
    /// Frühester Beginn eines Nickerchens (Stunde Ortszeit, 09:00 einschliesslich): Wer vor 09:00 kurz schläft, hatte eine Nacht.
    public static let napEarliestHour = 9
    /// Spätestes Ende eines Nickerchens (Stunde Ortszeit, 19:00 einschliesslich): Wer nach 19:00 aufwacht, hatte eine Nacht.
    public static let napLatestHour = 19
    /// Beginnt eine Sitzung höchstens so lange (Sekunden) nach dem Ende einer Nacht (einschliesslich, auch in Kette), setzt
    /// sie diese Nacht fort – z. B. Weiterschlafen nach dem Wecken, das Samsung Health als zweite Sitzung schreibt. Sie ist
    /// dann nie ein Nickerchen und zählt überall mit der Nacht zusammen (``merge(nights:sessions:clearedAt:calendar:)``,
    /// ``summaries(_:clearedAt:calendar:)``, ``stages(_:clearedAt:calendar:)``).
    public static let continuationGap: TimeInterval = 60 * 60

    /// Kleine Überlappung, die noch als Anschluss gilt, nicht als Doppel (Audit 2c82e15): Health-Daten schreiben die Teile
    /// einer getrennten Nacht oft mit einer Sekunde Überlappung. Erst wer sich länger überschneidet, ist ein Doppel.
    public static let overlapTolerance: TimeInterval = 5 * 60

    /// Überschneiden sich `a` und `b` um mehr als ``overlapTolerance`` (dann zählt nur eine, ``distinct(_:)``)?
    private static func clash(_ a: HealthSession, _ b: HealthSession) -> Bool {
        min(a.end, b.end).timeIntervalSince(max(a.start, b.start)) > overlapTolerance
    }
    /// Lesefenster der Gesundheitsdaten in Tagen, so lang wie der eigene Verlauf (`NightHistory.maxNights`).
    public static let days = 90

    /// Sitzungen, die nach «Verlauf löschen» sichtbar bleiben: Ende echt nach `clearedAt` (gleich = ausgeblendet).
    /// `clearedAt` `nil` = alle. Die Reihenfolge bleibt.
    public static func visible(_ sessions: [HealthSession], clearedAt: Date?) -> [HealthSession] {
        guard let clearedAt else { return sessions }
        return sessions.filter { $0.end > clearedAt }
    }

    /// Verlauf, neueste zuerst: alle eigenen Nächte (vom Löschzeitpunkt unberührt) und je Nachtdatum höchstens
    /// eine Fremd-Sitzung. Es zählen nur Sitzungen, die als Nacht gelten (sichtbar, kein Nickerchen in `calendar` oder Fortsetzung
    /// einer Nacht – ``continuationGap``, `end` nach `start`, ohne Doppel – siehe ``distinct(_:)``; Teile einer Nacht stehen als eine
    /// Sitzung vom Beginn des ersten bis zum Ende des letzten Teils im Verlauf, Phasen aller Teile, Quelle des ersten) und mit keiner eigenen Nacht dasselbe Nachtdatum haben oder sich mit ihr
    /// überschneiden (Sitzungsbeginn vor dem Weckmoment und Sitzungsende nach dem Fensterbeginn). Mehrere am selben
    /// Nachtdatum: nach derselben Vorliebe wie ``distinct(_:)``. Sortierung nach ``HistoryItem/at`` absteigend; gleich:
    /// eigene Nacht vor Sitzung, zwei eigene Nächte nach Fensterbeginn absteigend, dann Wecker-Kennung aufsteigend.
    public static func merge(nights: [NightRecord], sessions: [HealthSession], clearedAt: Date?, calendar: Calendar) -> [HistoryItem] {
        let ownDates = Set(nights.map { SleepNotes.nightDate(of: $0, calendar: calendar) })
        let candidates = countedNights(sessions, clearedAt: clearedAt, calendar: calendar).filter { session in
            !ownDates.contains(SleepNotes.nightDate(of: session, calendar: calendar))
                && !nights.contains { session.start < $0.wokeAt && session.end > $0.windowStart }
        }
        var best: [String: HealthSession] = [:]
        for session in candidates {
            let date = SleepNotes.nightDate(of: session, calendar: calendar)
            if let current = best[date], !preferred(session, over: current) { continue }
            best[date] = session
        }
        let items = nights.map { HistoryItem(kind: .phaseWake, date: SleepNotes.nightDate(of: $0, calendar: calendar), night: $0) }
            + best.map { HistoryItem(kind: .health, date: $0.key, session: $0.value) }
        return items.sorted(by: newerFirst)
    }

    /// Eintrag zum Nachtdatum `date` («yyyy-MM-dd»), z. B. für «Blase antippen → Detailseite»: der erste in `items`
    /// (neueste zuerst) mit diesem Datum; `nil` ohne Eintrag.
    public static func itemFor(_ items: [HistoryItem], date: String) -> HistoryItem? {
        items.first { $0.date == date }
    }

    /// Ohne doppelte Nächte: Von Sitzungen, die sich zeitlich überschneiden (Beginn vor dem Ende der anderen und
    /// umgekehrt; blosses Berühren zählt nicht), bleibt nur eine – zuerst die mit Phasen (``HealthSession/hasStages``),
    /// dann die längste, dann der frühere Beginn, dann die Quelle (`nil` zuerst, sonst alphabetisch). Nach Beginn sortiert.
    public static func distinct(_ sessions: [HealthSession]) -> [HealthSession] {
        var kept: [HealthSession] = []
        for session in sessions.sorted(by: preferred) where !kept.contains(where: { clash(session, $0) }) {
            kept.append(session)
        }
        return kept.sorted { $0.start < $1.start }
    }

    /// Phasen der Sitzungen, die als Nacht zählen (ohne Doppel, mit Weiterschlafen – ``continuationGap``), nach Beginn –
    /// für das Schlafprofil eigener Nächte. Je Teil auf ihn zugeschnitten und ab dem Ende des vorigen Teils (``trimmed(_:filler:)``).
    public static func stages(_ sessions: [HealthSession], clearedAt: Date?, calendar: Calendar) -> [StageSpan] {
        chains(sessions, clearedAt: clearedAt, calendar: calendar).flatMap { trimmed($0, filler: false) }.sorted { $0.start < $1.start }
    }

    /// Nächte für Diagramme, Trends, Wochenbilanz, Serien und Regelmässigkeit: je Kette eine Nacht (`SleepSummary.of`),
    /// dieselbe Regel wie der Verlauf (Sitzungen, die als Nacht zählen: sichtbar, kein Nickerchen, ohne Doppel – nichts
    /// zählt zweimal; Weiterschlafen nach ``continuationGap`` zählt zur Nacht, die Lücke nicht als Schlaf); eine Sitzung
    /// ohne Phasen zählt als eine «unbekannt»-Phase über die ganze Dauer. Zwei Ketten mit mehr als ``continuationGap``
    /// Lücke sind zwei Nächte, auch am selben Nachtdatum (Entscheid r1AD 14.11.2026, Audit W-2). Nach Beginn.
    public static func summaries(_ sessions: [HealthSession], clearedAt: Date?, calendar: Calendar) -> [SleepSummary] {
        // Je Teil zugeschnitten und ab dem Ende des vorigen wie in der Detailseite (`join`, `trimmed`).
        chains(sessions, clearedAt: clearedAt, calendar: calendar).compactMap { SleepSummary.of(trimmed($0)) }
    }

    /// Sitzungen, die als Nacht zählen (``chains(_:clearedAt:calendar:)``), je Nacht zu einer Sitzung zusammengefasst.
    private static func countedNights(_ sessions: [HealthSession], clearedAt: Date?, calendar: Calendar) -> [HealthSession] {
        chains(sessions, clearedAt: clearedAt, calendar: calendar).map { join($0) }
    }

    /// Nächte als Ketten von Sitzungen, nach Beginn: sichtbar, Ende nach Beginn, ohne Doppel (``distinct(_:)``). Jede Kette
    /// beginnt mit einer Sitzung, die kein Nickerchen in `calendar` ist; jede weitere beginnt nicht vor dem Ende der vorigen
    /// und höchstens ``continuationGap`` danach. Nickerchen ohne Anschluss fallen weg, ebenso solche, die sich mit einer
    /// Nacht überschneiden (andere App, dieselbe Zeit).
    private static func chains(_ sessions: [HealthSession], clearedAt: Date?, calendar: Calendar) -> [[HealthSession]] {
        let valid = visible(sessions, clearedAt: clearedAt).filter { $0.end > $0.start }
        let nights = distinct(valid.filter { !$0.isNap(calendar: calendar) })
        let naps = distinct(valid.filter { $0.isNap(calendar: calendar) })
            .filter { nap in !nights.contains(where: { clash(nap, $0) }) }
        // Nach Beginn; bei gleichem Beginn zuerst die Nacht (stabil, wie Kotlin `sortedBy`).
        let ordered = (nights.map { (session: $0, isNight: true) } + naps.map { (session: $0, isNight: false) })
            .sorted { $0.session.start != $1.session.start ? $0.session.start < $1.session.start : ($0.isNight && !$1.isNight) }
        var result: [[HealthSession]] = []
        for entry in ordered {
            // Anschluss: beginnt höchstens ``overlapTolerance`` vor dem Ende des vorigen Teils, endet danach, Lücke ≤ continuationGap.
            if let last = result.last?.last, entry.session.start >= last.end.addingTimeInterval(-overlapTolerance),
               entry.session.end > last.end, entry.session.start.timeIntervalSince(last.end) <= continuationGap {
                result[result.count - 1].append(entry.session)
            } else if entry.isNight {
                result.append([entry.session])
            }
        }
        return result
    }

    /// Eine Kette (``chains(_:clearedAt:calendar:)``) als eine Sitzung: Beginn des ersten bis Ende des letzten Teils, Quelle
    /// des ersten, Phasen aller Teile (auf ihren Teil zugeschnitten, ``spans(_:)``) – die Lücken dazwischen zählen nicht als Schlaf.
    private static func join(_ parts: [HealthSession]) -> HealthSession {
        let first = parts[0]
        guard parts.count > 1, let last = parts.last else { return first }
        return HealthSession(start: first.start, end: last.end, stages: trimmed(parts), source: first.source)
    }

    /// Phasen der Teile einer Kette (``spans(_:)``), jeder Teil erst ab dem Ende des vorigen: Eine kurze Überlappung
    /// (≤ ``overlapTolerance``) zählt nicht doppelt – gleich in Verlauf, Detailseite (``join(_:)``), Diagrammen und Trends
    /// (``summaries(_:clearedAt:calendar:)``) und im Schlafprofil eigener Nächte (``stages(_:clearedAt:calendar:)``);
    /// Audit BE-2. `filler` `false`: Teile ohne Phasen liefern nichts statt einer «unbekannt»-Phase. Wie Kotlin.
    private static func trimmed(_ parts: [HealthSession], filler: Bool = true) -> [StageSpan] {
        var stages: [StageSpan] = []
        for (i, part) in parts.enumerated() {
            let from = i == 0 ? part.start : max(part.start, parts[i - 1].end)
            let own = filler || !part.stages.isEmpty ? spans(part) : []
            for span in own where span.end > from {
                stages.append(StageSpan(start: max(span.start, from), end: span.end, stage: span.stage))
            }
        }
        return stages
    }

    /// Phasen der Sitzung, auf sie zugeschnitten; ohne Phasen eine «unbekannt»-Phase über die ganze Dauer.
    private static func spans(_ session: HealthSession) -> [StageSpan] {
        session.stages.isEmpty ? [StageSpan(start: session.start, end: session.end, stage: .unknown)]
            : StageCheck.clip(session.stages, from: session.start, to: session.end)
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
