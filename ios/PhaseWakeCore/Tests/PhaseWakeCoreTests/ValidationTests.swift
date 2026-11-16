import XCTest
@testable import PhaseWakeCore

/// Validierungs-Modus (``Validation``, ``ValidationReport``); Gegenstück zu `ValidationTest` (Kotlin), gemeinsame Fälle in
/// `shared/testdata/validation.vectors`.
final class ValidationTests: XCTestCase {
    /// 2026-11-08 22:00 UTC.
    private let t0 = Date(timeIntervalSince1970: 1_794_175_200)
    private let zone = TimeZone(identifier: "Europe/Zurich")!

    private func minutes(_ m: String) -> Date { t0.addingTimeInterval((Double(m)! * 60).rounded()) }

    /// «Anzahl*Bewegung/Puls» mit Leerzeichen, «-» = leer.
    private func epochs(_ text: String) -> [Epoch] {
        text.split(separator: " ").flatMap { token -> [Epoch] in
            let parts = token.split(separator: "*")
            let values = parts[1].split(separator: "/")
            return Array(repeating: Epoch(motion: Double(values[0]), heartRate: Double(values[1])), count: Int(parts[0])!)
        }
    }

    /// «Beginn-Ende:PHASE» mit Kommas, «-» = keine; Zeiten über `at`.
    private func spans(_ text: String, _ at: (String) -> Date) -> [StageSpan] {
        if text == "-" { return [] }
        return text.split(separator: ",").map { item in
            let parts = item.split(separator: ":")
            let range = parts[0].split(separator: "-")
            return StageSpan(start: at(String(range[0])), end: at(String(range[1])), stage: SleepStage(rawValue: String(parts[1]))!)
        }
    }

    private final class Case {
        let name: String
        let calendar: Calendar
        var nights: [NightRecord] = []
        var records: [Learning.Night] = []
        var sessions: [HealthSession] = []
        var learned: Learning.Values?
        var rows: [String] = []
        var sums: [String] = []

        init(name: String, calendar: Calendar) {
            self.name = name
            self.calendar = calendar
        }
    }

    func testSharedVectors() throws {
        let text = try String(contentsOf: NightFixtures.directory.appendingPathComponent("validation.vectors"), encoding: .utf8)
        let lines = text.components(separatedBy: "\n").filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty && !$0.hasPrefix("#") }
        var cases: [Case] = []
        var refs = 0
        for line in lines {
            let f = line.components(separatedBy: "\t")
            let current = cases.last
            switch f[0] {
            case "ref":
                let stage = Validation.reference(spans(f[3]) { self.t0.addingTimeInterval(Double($0)!) }, at: t0.addingTimeInterval(Double(f[2])!))
                XCTAssertEqual(Validation.Ref.of(stage).rawValue, f[1], line)
                refs += 1
            case "case":
                cases.append(Case(name: f[1], calendar: Calendar.gregorian(in: TimeZone(identifier: f[2])!)))
            case "night":
                let p = f[1].components(separatedBy: " ")
                current?.nights.append(NightRecord(alarmId: "a", windowStart: minutes(p[0]), target: minutes(p[1]), wokeAt: minutes(p[2]),
                                                   reason: WakeReason(rawValue: p[3])!, snoozes: 0, epochs: epochs(f.count > 2 ? f[2] : "")))
            case "record":
                current?.records.append(Learning.Night(start: minutes(f[1]), epochs: epochs(f[2])))
            case "session":
                let p = f[1].components(separatedBy: " ")
                current?.sessions.append(HealthSession(start: minutes(p[0]), end: minutes(p[1]), stages: spans(f[2], minutes),
                                                       timeZone: p[2] == "-" ? nil : TimeZone(identifier: p[2])!))
            case "learned":
                let p = f[1].components(separatedBy: " ")
                current?.learned = Learning.Values(floor: Double(p[0]), restingHeartRate: Double(p[1]), latencyMinutes: nil, nights: Learning.nights)
            case "row": current?.rows.append(f[1])
            case "sum": current?.sums.append(f[1])
            default: XCTFail("unbekannte Zeile: \(line)")
            }
        }
        for c in cases {
            let entries = Validation.evaluate(nights: c.nights, recordings: c.records, sessions: c.sessions, clearedAt: nil, learned: c.learned,
                                              calendar: c.calendar)
            XCTAssertEqual(ValidationReport.exportCsv(entries).components(separatedBy: "\n").dropFirst().filter { !$0.isEmpty }, c.rows, c.name)
            let sums = ValidationReport.lines(entries).filter { line in
                line.hasPrefix("all:") || line.range(of: #"^night \d+: "#, options: .regularExpression) != nil
            }
            XCTAssertEqual(sums, c.sums, c.name)
        }
        XCTAssertEqual(refs, 18)
        XCTAssertEqual(cases.count, 8)
    }

    func testWindowsAndMeasured() {
        let quiet = Epoch(motion: 0.02, heartRate: 55)
        let empty = Epoch(motion: nil, heartRate: nil)
        // 45 von 60 Epochen mit Bewegung zählen, 44 nicht; leer nie.
        XCTAssertTrue(Validation.measured((Array(repeating: empty, count: 15) + Array(repeating: quiet, count: 45))[...]))
        XCTAssertFalse(Validation.measured((Array(repeating: empty, count: 16) + Array(repeating: quiet, count: 44))[...]))
        XCTAssertFalse(Validation.measured([Epoch]()[...]))
        // 119 Epochen: Fenster ab 0 und 30 (das ab 60 hat keinen Platz); ruhig = Zielzeit am Ende des Fensters.
        let moments = Validation.shadows(start: t0, epochs: Array(repeating: quiet, count: 119), config: .standard)
        XCTAssertEqual(moments.map(\.at), [t0.addingTimeInterval(30 * 60), t0.addingTimeInterval(45 * 60)])
        XCTAssertTrue(moments.allSatisfy { $0.reason == .deadline })
        XCTAssertEqual(Validation.shadows(start: t0, epochs: Array(repeating: quiet, count: 120), config: .standard).count, 3)
        XCTAssertEqual(Validation.shadows(start: t0, epochs: Array(repeating: quiet, count: 59), config: .standard).count, 0)
    }

    func testNightWithoutDecisionIsLeftOut() {
        // Nur Stichproben, keine eigene Nacht: kein Entscheid, keine Nacht, kein Abschnitt.
        let block = Array(repeating: Epoch(motion: 0.02, heartRate: 55), count: 2) + Array(repeating: Epoch(motion: nil, heartRate: nil), count: 8)
        let sampled = (0..<12).flatMap { _ in block }
        let entries = Validation.evaluate(nights: [], recordings: [Learning.Night(start: t0, epochs: sampled)], sessions: [], clearedAt: nil,
                                          learned: nil, calendar: Calendar.gregorian(in: zone))
        XCTAssertEqual(entries, [])
        XCTAssertEqual(ValidationReport.lines(entries), [])
        XCTAssertEqual(ValidationReport.exportCsv(entries), "night,time,kind,reason,ref_stage\n")
    }

    func testDiagLines() {
        let night = NightRecord(alarmId: "a", windowStart: t0, target: t0.addingTimeInterval(30 * 60), wokeAt: t0.addingTimeInterval(20 * 60),
                                reason: .lightSleep, snoozes: 0, epochs: [])
        let from = t0.addingTimeInterval(-6 * 3600)
        let to = t0.addingTimeInterval(3600)
        let session = HealthSession(start: from, end: to, stages: [StageSpan(start: from, end: to, stage: .deep)])
        let calendar = Calendar.gregorian(in: zone)
        let lines = ValidationReport.lines(Validation.evaluate(nights: [night], recordings: [], sessions: [session], clearedAt: nil, learned: nil,
                                                               calendar: calendar))
        XCTAssertEqual(lines, [
            ValidationReport.note,
            "night 1 23:20 real LIGHT_SLEEP deep",
            "night 1: LIGHT_SLEEP real 0/1 shadow - · target real - shadow - · hits real 0/1",
            "all: 1 nights, 1 with reference · LIGHT_SLEEP real 0/1 shadow - · target real - shadow - · hits real 0/1",
        ])
        // «Verlauf löschen» blendet die Sitzung aus: keine Referenz mehr.
        let cleared = Validation.evaluate(nights: [night], recordings: [], sessions: [session], clearedAt: t0.addingTimeInterval(2 * 3600),
                                          learned: nil, calendar: calendar)
        XCTAssertEqual(cleared.map(\.ref), [.noReference])
    }
}
