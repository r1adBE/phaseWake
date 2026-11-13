import Foundation

/// Lern-Übersicht (Entscheid r1AD 13.11.2026): je Nacht seit dem Beginn der Lernphase, ob sie gezählt hat, und wenn
/// nicht, warum. Grundlage sind nur Angaben, die das iPhone wirklich hat: die Aufzeichnungen der Uhr, ihre Meldungen
/// (``LearnSkip``), die gemerkten Nächte mit Wecker (``LearnLog``), Zustimmung und Schalter. Dazu die Regel der Warnung
/// am Abend (``shouldWarn(active:battery:charging:now:recordStart:)``). Reine Logik; identisch zu `LearnNights` (Kotlin),
/// gemeinsame Fälle in `shared/testdata/learn-nights.vectors`.
public enum LearnNights {
    /// Höchstens so viele Nächte zeigt die Übersicht.
    public static let maxNights = 14

    /// Unter diesem Anteil (Prozent) Epochen mit Puls an den Epochen mit Bewegung gilt eine Nacht als «vermutlich nicht
    /// getragen». Getragen liegt er höher: Galaxy Watch in Stichproben rund 20 %, Apple Watch (Puls aus Apple Health, etwa
    /// alle 5 Min) rund 10 %. Bewegung allein unterscheidet nicht: Das Rauschen der Uhr ist am Handgelenk und auf dem Tisch
    /// gleich. Fehlt die Pulserlaubnis, sieht es gleich aus; der Tipp nennt beides.
    public static let minPulsePercent = 2

    /// Die Prüfung «getragen» erst ab so vielen Epochen mit Bewegung (10 Min); darunter sagt der Anteil nichts.
    public static let minWornEpochs = 20

    /// Die Warnung am Abend kommt so lange vor dem Beginn der Aufzeichnung (Beginn der Einschlaf-Messung).
    public static let warnLead: TimeInterval = 60 * 60

    /// Eine Hintergrund-Aktualisierung bzw. ein ungenauer Alarm darf so viel vor ``warnAt(_:)`` kommen.
    public static let warnEarly: TimeInterval = 10 * 60

    /// Von Hand gestoppt (Kennungen von ``RecordEnd``): auf der Uhr oder am Handy.
    private static let stopped: Set<String> = ["watch", "phone"]

    /// Warum eine Nacht zählt oder nicht; ``isCounted`` = Haken in der Übersicht. Reihenfolge in ``reason(_:)``.
    public enum Reason: String, Sendable, CaseIterable {
        /// Gezählt.
        case counted = "COUNTED"
        /// Gezählt, aber kaum Puls: vermutlich nicht getragen (oder keine Pulserlaubnis); der Ruhepuls fehlt dann.
        case countedNoPulse = "COUNTED_NO_PULSE"
        /// Von der ersten bis zur letzten Messung keine 3 Std. (``Learning/minSpanEpochs``).
        case tooShort = "TOO_SHORT"
        /// Lang genug, aber zu wenige Messungen (``Learning/minMeasuredEpochs``), z. B. nach Lücken.
        case tooFew = "TOO_FEW"
        /// Auf der Uhr oder am Handy vorzeitig gestoppt.
        case endedStopped = "ENDED_STOPPED"
        /// Vorzeitig beendet: Akku der Uhr in der Nacht zu tief (unter 15 %).
        case endedBattery = "ENDED_BATTERY"
        /// Vorzeitig beendet: vom System beendet oder nicht wieder aufgenommen.
        case endedService = "ENDED_SERVICE"
        /// Nicht gezählt und kaum Puls: vermutlich nicht getragen.
        case notWorn = "NOT_WORN"
        /// Gemeldet: Akku am Abend zu tief.
        case battery = "BATTERY"
        /// Gemeldet: keine Sensoren bzw. keine Erlaubnis für Bewegungsdaten.
        case sensors = "SENSORS"
        /// Gemeldet: Eine von Hand begonnene Aufzeichnung lief, ist aber nicht angekommen.
        case manual = "MANUAL"
        /// Gemeldet (Apple Watch): App am Abend zu spät gestartet.
        case late = "LATE"
        /// Lernphase damals aus bzw. noch nicht begonnen (Aufzeichnung vor ihrem Beginn).
        case learningOff = "LEARNING_OFF"
        /// Kein Wecker in dieser Nacht: Die Uhr zeichnet nur Nächte mit Wecker auf.
        case noAlarm = "NO_ALARM"
        /// Die letzte Nacht, Aufzeichnung kann noch kommen (bis Mittag).
        case pending = "PENDING"
        /// Galaxy Watch: «Einschlafen automatisch erkennen» aus (am iPhone nie).
        case autoOff = "AUTO_OFF"
        /// Wecker bekannt, aber nichts angekommen.
        case noRecording = "NO_RECORDING"
        /// Nichts angekommen, und ob es einen Wecker gab, ist unbekannt.
        case unknown = "UNKNOWN"

        /// Haken in der Übersicht: Die Nacht zählt.
        public var isCounted: Bool { self == .counted || self == .countedNoPulse }
    }

    /// Was über eine Nacht bekannt ist; Bedeutung wie `LearnNights.Facts` (Kotlin).
    public struct Facts: Equatable, Sendable {
        /// Eine Aufzeichnung ab dem Beginn der Lernphase liegt vor.
        public var recorded = false
        /// Epochen mit Bewegung der Aufzeichnung.
        public var measuredEpochs = 0
        /// Spanne von der ersten bis zur letzten Epoche mit Bewegung.
        public var spanEpochs = 0
        /// Epochen mit Puls.
        public var heartRateEpochs = 0
        /// Grund des Endes (``RecordEnd``); `nil` = unbekannt.
        public var endCode: String?
        /// Nur Aufzeichnungen, die vor dem Beginn der Lernphase begannen.
        public var beforeStart = false
        /// Meldung der Uhr für diese Nacht.
        public var skip: LearnSkip.Reason?
        /// Zustimmung zur Lernphase (jetzt).
        public var consent = true
        /// Wecker in dieser Nacht: `true` gemerkt oder belegt, `false` sicher keiner, `nil` unbekannt.
        public var alarm: Bool?
        /// Schalter «Einschlafen automatisch erkennen» (Apple: immer `true`).
        public var autoOnset = true
        /// Letzte Nacht, Aufzeichnung kann noch kommen.
        public var pending = false

        /// Fakten mit den Vorgaben (nichts aufgezeichnet, zugestimmt, Wecker unbekannt, Schalter ein).
        public init() {}
    }

    /// Grund einer Nacht. Reihenfolge: eine Aufzeichnung geht allem vor; dann Aufzeichnung vor dem Beginn, Meldung der
    /// Uhr, Zustimmung, kein Wecker, noch unterwegs, Schalter, nichts angekommen.
    public static func reason(_ f: Facts) -> Reason {
        if f.recorded {
            let counts = f.measuredEpochs >= Learning.minMeasuredEpochs && f.spanEpochs >= Learning.minSpanEpochs
            let notWorn = f.measuredEpochs >= minWornEpochs && f.heartRateEpochs * 100 < f.measuredEpochs * minPulsePercent
            if counts { return notWorn ? .countedNoPulse : .counted }
            if notWorn { return .notWorn }
            if let end = f.endCode, stopped.contains(end) { return .endedStopped }
            if f.endCode == "battery" { return .endedBattery }
            if f.endCode == "service" { return .endedService }
            return f.spanEpochs < Learning.minSpanEpochs ? .tooShort : .tooFew
        }
        if f.beforeStart { return .learningOff }
        if let skip = f.skip {
            switch skip {
            case .battery: return .battery
            case .sensors: return .sensors
            case .manual: return .manual
            case .late: return .late
            }
        }
        if !f.consent { return .learningOff }
        if f.alarm == false { return .noAlarm }
        if f.pending { return .pending }
        if !f.autoOnset { return .autoOff }
        return f.alarm == true ? .noRecording : .unknown
    }

    /// Spanne von der ersten bis zur letzten Epoche mit Bewegung (0 ohne Bewegung), wie in ``Learning/counts(_:)``.
    public static func spanEpochs(_ night: Learning.Night) -> Int {
        guard let first = night.epochs.firstIndex(where: { $0.motion != nil }),
              let last = night.epochs.lastIndex(where: { $0.motion != nil }) else { return 0 }
        return last - first + 1
    }

    /// Anteil der Epochen mit Puls an denen mit Bewegung in ganzen Prozent (abgerundet, höchstens 100; 0 ohne Bewegung).
    public static func pulsePercent(measured: Int, heartRate: Int) -> Int {
        measured <= 0 ? 0 : min(100, heartRate * 100 / measured)
    }

    /// Eine Aufzeichnung für die Übersicht: `night` und Grund des Endes `endCode` (``RecordEnd``).
    public struct Recorded: Equatable, Sendable {
        /// Die Nacht für die Lernphase.
        public let night: Learning.Night
        /// Grund des Endes (``RecordEnd``); `nil` = unbekannt.
        public let endCode: String?

        /// Aufzeichnung `night` mit Grund des Endes `endCode`.
        public init(night: Learning.Night, endCode: String?) {
            self.night = night
            self.endCode = endCode
        }
    }

    /// Eine Zeile der Übersicht: Nachtdatum `date` («yyyy-MM-dd», Abend, an dem die Nacht beginnt), Grund `reason` und,
    /// falls aufgezeichnet, gemessene Minuten (Spanne), Epochen mit Bewegung und Pulsanteil; bei einer Meldung zum Akku
    /// dessen Stand.
    public struct Night: Equatable, Sendable, Identifiable {
        /// Nachtdatum «yyyy-MM-dd».
        public let date: String
        /// Grund.
        public let reason: Reason
        /// Gemessene Spanne in Minuten; `nil` ohne Aufzeichnung.
        public var minutes: Int?
        /// Epochen mit Bewegung; `nil` ohne Aufzeichnung.
        public var measuredEpochs: Int?
        /// Anteil der Epochen mit Puls in Prozent; `nil` ohne Aufzeichnung.
        public var pulsePercent: Int?
        /// Akkustand der Meldung in Prozent; `nil` ohne.
        public var battery: Int?

        /// Kennung für Listen: das Nachtdatum.
        public var id: String { date }

        /// Zeile für `date` mit Grund `reason`, ohne Angaben.
        public init(date: String, reason: Reason, minutes: Int? = nil, measuredEpochs: Int? = nil, pulsePercent: Int? = nil, battery: Int? = nil) {
            self.date = date
            self.reason = reason
            self.minutes = minutes
            self.measuredEpochs = measuredEpochs
            self.pulsePercent = pulsePercent
            self.battery = battery
        }
    }

    /// Die Nächte seit dem Beginn der Lernphase `since` bis jetzt, höchstens ``maxNights``, jüngste zuerst; Regel wie
    /// `LearnNights.nights` (Kotlin). Wecker: `alarmNights`; ab `alarmsKnownFrom` gilt ein fehlendes Datum als «kein
    /// Wecker», davor als unbekannt.
    public static func nights(_ recordings: [Recorded], skips: [LearnSkip], alarmNights: Set<String>, alarmsKnownFrom: String?,
                              since: Date, consent: Bool, autoOnset: Bool, now: Date, calendar: Calendar) -> [Night] {
        let today = SleepNotes.nightDate(now, calendar: calendar)
        let first = SleepNotes.nightDate(since, calendar: calendar)
        let localToday = SleepNotes.dateString(now, calendar: calendar)
        let byDate = Dictionary(grouping: recordings) { SleepNotes.nightDate($0.night.start, calendar: calendar) }
        var skipByDate: [String: LearnSkip] = [:]
        for skip in skips { skipByDate[SleepNotes.nightDate(skip.target, calendar: calendar)] = skip }
        var result: [Night] = []
        var date = today
        while date >= first && result.count < maxNights {
            let all = byDate[date] ?? []
            let own = all.filter { $0.night.start >= since }
            let best = own.max { better($1, than: $0) }
            let skip = skipByDate[date]
            let afterMidnight = localToday == NightDate.adding(1, to: date)
            let current = date == today
            if !current || best != nil || skip != nil || !all.isEmpty || afterMidnight {
                result.append(night(date, best: best, beforeStart: !all.isEmpty && own.isEmpty, skip: skip, consent: consent,
                                    alarm: alarmOf(date, nights: alarmNights, knownFrom: alarmsKnownFrom), autoOnset: autoOnset,
                                    pending: current))
            }
            let previous = NightDate.adding(-1, to: date)
            guard previous != date else { break }
            date = previous
        }
        return result
    }

    /// Ob `a` die bessere Aufzeichnung ist als `b`: eine zählende vor einer mit mehr Messungen (wie `compareBy` in Kotlin).
    private static func better(_ a: Recorded, than b: Recorded) -> Bool {
        let (ca, cb) = (Learning.counts(a.night), Learning.counts(b.night))
        if ca != cb { return ca }
        return Learning.measuredEpochs(a.night) > Learning.measuredEpochs(b.night)
    }

    private static func alarmOf(_ date: String, nights: Set<String>, knownFrom: String?) -> Bool? {
        if nights.contains(date) { return true }
        if let knownFrom, date >= knownFrom { return false }
        return nil
    }

    private static func night(_ date: String, best: Recorded?, beforeStart: Bool, skip: LearnSkip?, consent: Bool, alarm: Bool?,
                              autoOnset: Bool, pending: Bool) -> Night {
        let measured = best.map { Learning.measuredEpochs($0.night) } ?? 0
        let span = best.map { spanEpochs($0.night) } ?? 0
        let pulse = best?.night.epochs.filter { $0.heartRate != nil }.count ?? 0
        var facts = Facts()
        facts.recorded = best != nil
        facts.measuredEpochs = measured
        facts.spanEpochs = span
        facts.heartRateEpochs = pulse
        facts.endCode = best?.endCode
        facts.beforeStart = beforeStart
        facts.skip = skip?.reason
        facts.consent = consent
        facts.alarm = alarm ?? (skip != nil ? true : nil)
        facts.autoOnset = autoOnset
        facts.pending = pending
        let recorded = best != nil
        return Night(date: date, reason: reason(facts),
                     minutes: recorded ? span * DetectorConfig.epochSeconds / 60 : nil,
                     measuredEpochs: recorded ? measured : nil,
                     pulsePercent: recorded ? pulsePercent(measured: measured, heartRate: pulse) : nil,
                     battery: skip?.reason == .battery && !recorded ? skip?.battery : nil)
    }

    /// Gründe, die auf der Startseite nicht als «letzte Nacht keine Lerndaten» erscheinen: erwartet bzw. noch offen.
    private static let quiet: Set<Reason> = [.pending, .noAlarm, .learningOff]

    /// Startseite: die letzte Nacht aus `nights` (jüngste zuerst), wenn sie nicht gezählt hat und das nicht erwartet war;
    /// sonst `nil`. «Letzte» heisst: Nachtdatum heute oder gestern.
    public static func lastNightMiss(_ nights: [Night], now: Date, calendar: Calendar) -> Night? {
        guard let newest = nights.first,
              newest.date >= NightDate.adding(-1, to: SleepNotes.nightDate(now, calendar: calendar)),
              !newest.reason.isCounted, !quiet.contains(newest.reason) else { return nil }
        return newest
    }

    /// Zeitpunkt der Warnung am Abend: ``warnLead`` vor dem Beginn der Aufzeichnung `recordStart`.
    public static func warnAt(_ recordStart: Date) -> Date { recordStart.addingTimeInterval(-warnLead) }

    /// Ob die Uhr um `now` warnt «Lade die Uhr, sonst zählt die Lernnacht nicht»: Lernphase läuft, Akku bekannt und unter
    /// ``Learning/minBattery``, die Uhr lädt nicht, und `now` liegt ab ``warnAt(_:)`` (höchstens ``warnEarly`` früher) vor
    /// dem Beginn `recordStart`. Unbekannter Akku warnt nicht.
    public static func shouldWarn(active: Bool, battery: Int?, charging: Bool, now: Date, recordStart: Date) -> Bool {
        guard active, !charging, let battery, battery < Learning.minBattery else { return false }
        return now >= warnAt(recordStart).addingTimeInterval(-warnEarly) && now < recordStart
    }
}
