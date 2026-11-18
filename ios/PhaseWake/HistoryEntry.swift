import Foundation
import PhaseWakeCore

/// Eintrag im Verlauf: eine aufgezeichnete Nacht, eine Nacht aus Apple Health oder nur Geräusche eines Nachtdatums.
enum HistoryEntry: Identifiable {
    case night(NightRecord)
    /// `date` = Nachtdatum der Sitzung (`HistoryItem.date`).
    case health(HealthSession, date: String)
    /// `last` = Beginn des jüngsten Ausschnitts.
    case sounds(night: String, last: Date)
    /// Aufzeichnung der Uhr («Nacht aufzeichnen», Lernphase).
    case recording(NightRecording)

    var id: String {
        switch self {
        case .night(let night): night.listId
        case .health(_, let date): "health|\(date)"
        case .sounds(let night, _): "sounds|\(night)"
        case .recording(let recording): "recording@\(OnsetShift.millis(recording.start))"
        }
    }

    /// Zeitpunkt für die Reihenfolge (neueste zuerst): Wecken, Ende der Sitzung, jüngster Ausschnitt, Ende der
    /// Aufzeichnung.
    var at: Date {
        switch self {
        case .night(let night): night.wokeAt
        case .health(let session, _): session.end
        case .sounds(_, let last): last
        case .recording(let recording): recording.until
        }
    }

    /// Nur Geräusche, keine Nacht.
    var isSoundsOnly: Bool {
        if case .sounds = self { return true }
        return false
    }

    /// Nachtdatum, wenn der Eintrag eine Nacht ist (eigene oder aus Apple Health); `nil` für Aufzeichnungen und
    /// Geräusche ohne Nacht. Nur Nächte zählen in den Wochen- und Jahreszeilen (``HistoryGroups``).
    var nightDate: String? {
        switch self {
        case .night(let night): SleepNotes.nightDate(of: night, calendar: .gregorianLocal)
        case .health(_, let date): date
        case .sounds, .recording: nil
        }
    }

    /// Gruppen der Liste aus `entries` (neueste zuerst): die neusten Nächte einzeln, ältere je Woche bzw. Jahr, nach
    /// dem Tag des Eintrags (Datum des Morgens, wie auf der Karte); Wochenbeginn nach der Sprache des Geräts.
    static func groups(_ entries: [HistoryEntry], today: String) -> [HistoryGroup<HistoryEntry>] {
        // Calendar: 1 = Sonntag; Weekday: 1 = Montag.
        let firstDay = Weekday(rawValue: (Calendar.current.firstWeekday + 5) % 7 + 1) ?? .monday
        return HistoryGroups.of(entries, dayOf: { SleepNotes.dateString($0.at, calendar: .gregorianLocal) },
                                isNight: { $0.nightDate != nil }, today: today, firstDay: firstDay)
    }

    /// Schnitt einer Woche oder eines Jahres: Schlaf je Nachtdatum aus Apple Health (`asleep`), Befinden der eigenen
    /// Nächte und zu Nächten aus anderen Apps (`healthFeelings`).
    static func summary(_ entries: [HistoryEntry], asleep: [String: Int],
                        healthFeelings: [String: Int]) -> HistoryGroupSummary {
        HistoryGroups.summary(entries, nightDate: \.nightDate, asleep: asleep) { entry in
            switch entry {
            case .night(let night): night.feeling
            case .health(_, let date): healthFeelings[date]
            case .sounds, .recording: nil
            }
        }
    }
}

private extension NightRecord {
    /// Eine Nacht ist Wecker plus Fensterbeginn (zwei Wecker können gleich beginnen).
    var listId: String { "\(alarmId)|\(windowStart.timeIntervalSince1970)" }
}
