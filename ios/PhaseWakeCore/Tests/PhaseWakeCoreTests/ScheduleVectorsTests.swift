import XCTest
@testable import PhaseWakeCore

/// Gemeinsame Fälle mit Kotlin (`shared/testdata/schedule.vectors`): ``AlarmSchedule`` mit
/// Wochentagen, Feiertagen, Überspringen, erledigten Nächten, Nachfrist, begonnener Nacht über einen Zonenwechsel und
/// Nachholen, auch über die Sommer- und Winterzeit in Zürich. Gegenstück zu `ScheduleVectorsTest` (Kotlin); Format im
/// Kopf der Datei.
final class ScheduleVectorsTests: XCTestCase {
    private struct FormatError: Error, CustomStringConvertible {
        let description: String
    }

    func testSharedVectors() throws {
        let text = try String(contentsOf: NightFixtures.directory.appendingPathComponent("schedule.vectors"),
                              encoding: .utf8)
        let lines = text.components(separatedBy: "\n").filter { !$0.isEmpty && !$0.hasPrefix("#") }
        var kinds: [String: Int] = [:]
        for line in lines {
            let fields = line.components(separatedBy: "\t")
            kinds[fields[0], default: 0] += 1
            XCTAssertEqual(try actual(fields), try fields.suffix(2).map(expected), line)
        }
        XCTAssertEqual(kinds, ["next": 29, "planned": 20, "missed": 12])
    }

    /// Die beiden erwarteten Felder der Zeile `f`, so wie der Kern sie heute ergibt (Zeitpunkte als ms).
    private func actual(_ f: [String]) throws -> [String] {
        let now = try instant(f[1])
        let calendar = try self.calendar(f[2])
        switch f[0] {
        case "next":
            guard f.count == 8 else { throw FormatError(description: "next: 8 Felder erwartet") }
            let holidays = dates(f[3])
            let done = try optionalInstant(f[4])
            let spec = try alarm(f[5])
            let next = AlarmSchedule.nextTarget(spec, now: max(now, done ?? now), calendar: calendar,
                                                holidays: holidays)
            let earliest = AlarmSchedule.earliestPlan([spec], now: now, doneUntil: done.map { [spec.id: $0] } ?? [:],
                                                      calendar: calendar, holidays: holidays)
            XCTAssertEqual(earliest?.target, next, "earliestPlan weicht von nextTarget ab")
            let skip = AlarmSchedule.skipNext(spec, now: now, doneUntil: done, calendar: calendar,
                                              holidays: holidays).skipUntil
            return [next.map { String(TimeBounds.millis($0)) } ?? "-", String(skip)]
        case "planned":
            guard f.count == 11 else { throw FormatError(description: "planned: 11 Felder erwartet") }
            let plannedCalendar = try optionalCalendar(f[3])
            let holidays = dates(f[4])
            let spec = try alarm(f[5])
            let target = try optionalInstant(f[6])
            guard let minutes = Int(f[7]) else { throw FormatError(description: "Nachfrist '\(f[7])'") }
            let grace = TimeInterval(minutes * 60)
            let suppressed = try optionalInstant(f[8])
            let inGrace = AlarmSchedule.inGrace(spec, plannedTarget: target, now: now, grace: grace, calendar: calendar,
                                                holidays: holidays, suppressedUntil: suppressed)
            let begun = AlarmSchedule.begunNight(spec, plannedTarget: target, plannedCalendar: plannedCalendar,
                                                 now: now, grace: grace, holidays: holidays,
                                                 suppressedUntil: suppressed)
            return [inGrace, begun].map { (plan: WakePlan?) -> String in
                guard let plan else { return "-" }
                XCTAssertEqual(plan, target.map { AlarmSchedule.plan(spec, target: $0) },
                               "Plan nicht zur geplanten Zielzeit")
                return "+"
            }
        case "missed":
            guard f.count == 9 else { throw FormatError(description: "missed: 9 Felder erwartet") }
            let holidays = dates(f[3])
            let done = try doneUntil(f[4])
            let catchUp = try catchUpWindow(f[5])
            let specs = try f[6].components(separatedBy: ";").map(alarm)
            let missed = AlarmSchedule.missedPlan(specs, catchUp: catchUp, now: now, doneUntil: done,
                                                  calendar: calendar, holidays: holidays)
            let earliest = AlarmSchedule.earliestPlan(specs, now: now, doneUntil: done, calendar: calendar,
                                                      holidays: holidays)
            return [missed, earliest].map { (plan: WakePlan?) -> String in
                plan.map { "\($0.alarmId)@\(TimeBounds.millis($0.target))" } ?? "-"
            }
        default:
            throw FormatError(description: "Unbekannte Art \(f[0])")
        }
    }

    /// Erwartetes Feld als Vergleichswert wie `actual` (Zeitpunkte als ms; Wandzeit «yyyy-MM-ddTHH:mm:ss» als UTC).
    private func expected(_ field: String) throws -> String {
        if field == "-" || field == "+" || field == "0" { return field }
        if let at = field.firstIndex(of: "@") {
            let target = try instant(String(field[field.index(after: at)...]))
            return "\(field[..<at])@\(TimeBounds.millis(target))"
        }
        let at = try instant(field.count == 19 ? field + "Z" : field)
        return String(TimeBounds.millis(at))
    }

    /// Erledigte Nächte «id=Zeitpunkt,…» je Wecker; «-» = keine.
    private func doneUntil(_ field: String) throws -> [String: Date] {
        var done: [String: Date] = [:]
        if field == "-" { return done }
        for pair in field.components(separatedBy: ",") {
            let parts = pair.components(separatedBy: "=")
            guard parts.count == 2 else { throw FormatError(description: "doneUntil '\(pair)'") }
            done[parts[0]] = try instant(parts[1])
        }
        return done
    }

    /// Nachholfenster «von bis id,…»; «-» = keines.
    private func catchUpWindow(_ field: String) throws -> CatchUpWindow? {
        if field == "-" { return nil }
        let parts = field.components(separatedBy: " ")
        guard parts.count == 3 else { throw FormatError(description: "catchUp '\(field)'") }
        return try CatchUpWindow(from: instant(parts[0]), until: instant(parts[1]),
                                 alarmIds: Set(parts[2].components(separatedBy: ",")))
    }

    /// Eine Weckerzeile im `Codec`-Format (16 Felder).
    private func alarm(_ line: String) throws -> AlarmSpec {
        let alarms = try Codec.decodeAlarms("phasewake-alarms 1\n\(line)\n")
        guard alarms.count == 1 else { throw FormatError(description: "Weckerzeile unlesbar: \(line)") }
        return alarms[0]
    }

    private func dates(_ text: String) -> Set<String> {
        text == "-" ? [] : Set(text.components(separatedBy: ","))
    }

    private func instant(_ text: String) throws -> Date {
        guard let date = ISO8601DateFormatter().date(from: text)
        else { throw FormatError(description: "Zeitpunkt '\(text)'") }
        return date
    }

    /// Zeitpunkt oder `nil` für «-».
    private func optionalInstant(_ text: String) throws -> Date? {
        if text == "-" { return nil }
        return try instant(text)
    }

    /// Kalender der Zone oder `nil` für «-».
    private func optionalCalendar(_ zone: String) throws -> Calendar? {
        if zone == "-" { return nil }
        return try calendar(zone)
    }

    private func calendar(_ zone: String) throws -> Calendar {
        guard let timeZone = TimeZone(identifier: zone) else { throw FormatError(description: "Zone '\(zone)'") }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        return calendar
    }
}
