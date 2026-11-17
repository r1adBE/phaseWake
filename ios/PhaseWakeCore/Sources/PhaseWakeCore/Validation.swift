import Foundation

/// Validierungs-Modus: Wie oft liegt der Weck-Entscheid wirklich im leichten Schlaf? Rechnet
/// nachträglich auf dem iPhone, weckt nie und ändert nichts am Wecken. Regel siehe `docs/PHASENERKENNUNG.md`, Abschnitt 12.
///
/// - **Echte Entscheide**: je Nacht (``NightRecord``) Weckzeit und Grund.
/// - **Schattenentscheide**: Der ``WakeDetector`` läuft über rollende Fenster von ``windowEpochs`` Epochen (30 Min), alle
///   ``stepEpochs`` Epochen (15 Min) eines, über die Epochen einer Aufzeichnung («Nacht aufzeichnen», Lernphase). Ein
///   Fenster zählt nur, wenn es durchgehend gemessen ist (``measured(_:)``); Stichproben liefern darum keine.
/// - **Referenz**: Phase der Gesundheits-App zum Entscheid (``reference(_:at:)``, ±``tolerance``). Sie ist eine Schätzung
///   der Uhr, kein Schlaflabor.
///
/// Reine Rechnung; identisch zu `Validation` (Kotlin), gemeinsame Fälle in `shared/testdata/validation.vectors`.
public enum Validation {
    /// Länge eines Schattenfensters in Epochen (30 Min, wie das grösste Weckfenster).
    public static let windowEpochs = 60
    /// Abstand der Schattenfenster in Epochen (15 Min); sie überlappen sich, die Entscheide sind nicht unabhängig.
    public static let stepEpochs = 30
    /// Höchster Abstand zur nächsten Phase in Sekunden, wenn keine den Entscheid abdeckt (einschliesslich).
    public static let tolerance: TimeInterval = 60

    private static let epochSeconds = TimeInterval(DetectorConfig.epochSeconds)

    /// Echter Entscheid (geweckt) oder Schattenentscheid (nur gerechnet). Der Rohwert steht im Export.
    public enum Kind: String, Sendable {
        case real
        case shadow

        var order: Int { self == .real ? 0 : 1 }
    }

    /// Referenzphase zum Entscheid. `unknown`: Nacht mit Phasen, aber keine bekannte bis ±``tolerance``; `noReference`
    /// («none», Kotlin `NONE`; nicht `none`, das mit `Optional.none` verwechselt würde): Nacht ohne.
    public enum Ref: String, Sendable {
        case awake, light, rem, deep, unknown
        case noReference = "none"

        /// Aus einer Phase der Gesundheits-App; `nil` und ``SleepStage/unknown`` ergeben `unknown`.
        public static func of(_ stage: SleepStage?) -> Ref {
            switch stage {
            case .awake: .awake
            case .light: .light
            case .rem: .rem
            case .deep: .deep
            case .unknown, nil: .unknown
            }
        }
    }

    /// Ein Entscheid zum Zeitpunkt `at` mit Grund `reason`.
    public struct Moment: Equatable, Sendable {
        /// Zeitpunkt des Entscheids.
        public let at: Date
        /// Grund des Entscheids.
        public let reason: WakeReason
    }

    /// Ein ausgewerteter Entscheid: Nacht `night` (1 = älteste mit Entscheiden), Zeitpunkt `at`, angezeigt in `timeZone`
    /// (Zone der Aufzeichnung der Gesundheits-App dieser Nacht, sonst des Geräts), Art, Grund und Referenzphase.
    public struct Entry: Equatable, Sendable {
        /// Nummer der Nacht (1 = älteste mit Entscheiden).
        public let night: Int
        /// Zeitpunkt des Entscheids.
        public let at: Date
        /// Zone für die Uhrzeit.
        public let timeZone: TimeZone
        /// Echt oder Schatten.
        public let kind: Kind
        /// Grund des Entscheids.
        public let reason: WakeReason
        /// Referenzphase.
        public let ref: Ref
    }

    /// Ob mindestens 3/4 der `epochs` Bewegung haben (durchgehend gemessen; Stichproben haben rund 1/5).
    public static func measured(_ epochs: ArraySlice<Epoch>) -> Bool {
        !epochs.isEmpty && epochs.filter { $0.motion != nil }.count * 4 >= epochs.count * 3
    }

    /// Ein Fenster ab `start`: erster Entscheid des ``WakeDetector`` mit `config`, Zeitpunkt am Ende der entscheidenden
    /// Epoche (Epoche `i` endet bei `start` + (i + 1) · 30 s); ohne Entscheid ``WakeReason/deadline`` am Ende des Fensters.
    public static func window(start: Date, epochs: ArraySlice<Epoch>, config: DetectorConfig) -> Moment {
        let detector = WakeDetector(config: config)
        for (i, epoch) in epochs.enumerated() {
            if case .wake(let reason) = detector.onEpoch(epoch) {
                return Moment(at: start.addingTimeInterval(Double(i + 1) * epochSeconds), reason: reason)
            }
        }
        return Moment(at: start.addingTimeInterval(Double(epochs.count) * epochSeconds), reason: detector.finish())
    }

    /// Schattenentscheide einer Aufzeichnung ab `start`: Fenster ab Epoche 0, ``stepEpochs``, 2 · ``stepEpochs`` …,
    /// solange ein ganzes Fenster (``windowEpochs``) Platz hat; nur durchgehend gemessene (``measured(_:)``). Entscheiden
    /// zwei überlappende Fenster im selben Moment gleich, zählt der Moment einmal. Identisch zu Kotlin.
    public static func shadows(start: Date, epochs: [Epoch], config: DetectorConfig) -> [Moment] {
        var moments: [Moment] = []
        var from = 0
        while from + windowEpochs <= epochs.count {
            let part = epochs[from..<(from + windowEpochs)]
            if measured(part) {
                let moment = window(start: start.addingTimeInterval(Double(from) * epochSeconds), epochs: part, config: config)
                if !moments.contains(moment) { moments.append(moment) }
            }
            from += stepEpochs
        }
        return moments
    }

    /// Referenzphase zum Zeitpunkt `at`: die bekannte Phase (ohne ``SleepStage/unknown``), die `at` abdeckt (Beginn
    /// einschliesslich, Ende ausschliesslich; bei Überlappung die zuletzt gelistete wie ``StageCheck/stageAt(_:_:)``);
    /// sonst die nächste bis ``tolerance`` davor (Abstand zum Ende) oder danach (Abstand zum Beginn), bei gleichem Abstand
    /// die frühere. `nil`: keine. Identisch zu Kotlin.
    public static func reference(_ spans: [StageSpan], at: Date) -> SleepStage? {
        let known = spans.filter { $0.stage != .unknown && $0.end > $0.start }
        if let stage = StageCheck.stageAt(known, at) { return stage }
        // Nach Beginn, bei gleichem Beginn in der gelisteten Reihenfolge (wie das stabile Sortieren in Kotlin).
        let sorted = known.enumerated().sorted { ($0.element.start, $0.offset) < ($1.element.start, $1.offset) }.map(\.element)
        var best: StageSpan?
        var bestGap = Int64.max
        for span in sorted {
            let gap = at >= span.end ? millis(span.end, at) : millis(at, span.start)
            if gap <= Int64(tolerance * 1000), gap < bestGap {
                best = span
                bestGap = gap
            }
        }
        return best?.stage
    }

    /// Alle Entscheide, nach Nacht (Nachtdatum in `calendar`, ``SleepNotes/nightDate(of:calendar:)``: eigene Nacht über
    /// den Fensterbeginn, Aufzeichnung über ihren Beginn) und Zeit; echte vor Schattenentscheiden zur selben Zeit.
    ///
    /// Schattenentscheide rechnen mit derselben Konfiguration wie das echte Fenster: Kalibrierung aus den eigenen Nächten
    /// vor der Aufzeichnung (``Calibration/calibrate(nights:defaults:)``) und den gelernten Werten `learned` (aktueller
    /// Stand). Das Grundrauschen der Einschlaf-Messung kennt das iPhone nicht; ab 4 Werten gilt ohnehin das des Fensters.
    /// Referenz: Phasen der Sitzungen, die als Nacht zählen (``HealthHistory/stages(_:clearedAt:calendar:)``). Eine Nacht
    /// hat Referenz, wenn eine bekannte Phase ihre Zeit (Fensterbeginn bzw. Beginn der Aufzeichnung bis spätestes Wecken,
    /// Zielzeit bzw. Ende, je ±``tolerance``) berührt; sonst `noReference`. Nächte ohne Entscheid fehlen. Identisch zu Kotlin.
    ///
    /// - Parameter recordings: abgeschlossene Aufzeichnungen (``Learning/Night``).
    public static func evaluate(nights: [NightRecord], recordings: [Learning.Night], sessions: [HealthSession], clearedAt: Date?,
                                learned: Learning.Values?, calendar: Calendar) -> [Entry] {
        let spans = HealthHistory.stages(sessions, clearedAt: clearedAt, calendar: calendar).filter { $0.stage != .unknown }
        let counted = HealthHistory.countedNights(sessions, clearedAt: clearedAt, calendar: calendar)
        func config(before: Date) -> DetectorConfig {
            let calibrated = Calibration.calibrate(nights: NightHistory.motionsForCalibration(nights.filter { $0.windowStart < before }))
            return Learning.withLearned(calibrated, learned)
        }
        let own = Dictionary(grouping: sortedStable(nights, by: \.windowStart)) { SleepNotes.nightDate(of: $0, calendar: calendar) }
        let recorded = Dictionary(grouping: sortedStable(recordings, by: \.start)) { SleepNotes.nightDate($0.start, calendar: calendar) }
        var entries: [Entry] = []
        var number = 0
        for date in Set(own.keys).union(recorded.keys).sorted() {
            let real = own[date] ?? []
            let records = recorded[date] ?? []
            let realMoments: [(Kind, Moment)] = real.map { (Kind.real, Moment(at: $0.wokeAt, reason: $0.reason)) }
            let shadowMoments: [(Kind, Moment)] = records.flatMap { r -> [(Kind, Moment)] in
                shadows(start: r.start, epochs: r.epochs, config: config(before: r.start)).map { (Kind.shadow, $0) }
            }
            let moments = realMoments + shadowMoments
            if moments.isEmpty { continue }
            number += 1
            let ranges: [(Date, Date)] = real.map { ($0.windowStart, max($0.wokeAt, $0.target)) } +
                records.map { ($0.start, $0.start.addingTimeInterval(Double($0.epochs.count) * epochSeconds)) }
            guard let first = ranges.map(\.0).min(), let last = ranges.map(\.1).max() else { continue }
            let from = first.addingTimeInterval(-tolerance)
            let to = last.addingTimeInterval(tolerance)
            let known = spans.filter { $0.start <= to && $0.end >= from }
            let zone = counted.first { $0.start <= to && $0.end >= from }?.timeZone ?? calendar.timeZone
            // Nach Zeit, echte zuerst, sonst in der Reihenfolge oben (wie das stabile Sortieren in Kotlin).
            let ordered = moments.enumerated().sorted {
                ($0.element.1.at, $0.element.0.order, $0.offset) < ($1.element.1.at, $1.element.0.order, $1.offset)
            }.map(\.element)
            for (kind, moment) in ordered {
                let ref: Ref = known.isEmpty ? .noReference : .of(reference(known, at: moment.at))
                entries.append(Entry(night: number, at: moment.at, timeZone: zone, kind: kind, reason: moment.reason, ref: ref))
            }
        }
        return entries
    }

    /// Stabil nach `key` sortiert (Swift sortiert nicht stabil; Kotlin `sortedBy` schon).
    private static func sortedStable<T>(_ items: [T], by key: (T) -> Date) -> [T] {
        items.enumerated().sorted { (key($0.element), $0.offset) < (key($1.element), $1.offset) }.map(\.element)
    }

    private static func millis(_ from: Date, _ to: Date) -> Int64 {
        Int64((to.timeIntervalSince(from) * 1000).rounded())
    }
}
