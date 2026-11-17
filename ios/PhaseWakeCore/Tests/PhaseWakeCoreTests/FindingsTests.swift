import XCTest
@testable import PhaseWakeCore

/// Auffälligkeiten (``Findings``, ``NightFacts``); Gegenstück zu `FindingsTest` (Kotlin), gemeinsame Fälle in
/// `shared/testdata/findings.vectors`.
final class FindingsTests: XCTestCase {
    private let zone = TimeZone(identifier: "Europe/Zurich")!
    private lazy var calendar = Calendar.gregorian(in: zone)

    func testSharedVectors() throws {
        let text = try String(contentsOf: NightFixtures.directory.appendingPathComponent("findings.vectors"), encoding: .utf8)
        let lines = text.components(separatedBy: "\n").filter { !$0.isEmpty && !$0.hasPrefix("#") }
        for line in lines {
            let parts = line.components(separatedBy: "\t")
            XCTAssertEqual(parts.count, 3, line)
            guard parts.count == 3 else { continue }
            let facts = parts[2].components(separatedBy: ";").map { self.facts($0) }
            XCTAssertEqual(describe(Findings.of(facts, today: parts[1], calendar: calendar)), parts[0], String(line.prefix(80)))
        }
        XCTAssertEqual(lines.count, 45)
    }

    /// «Nachtdatum Beginn Ende Schlaf Einschlafdauer Tief Wach [Zone]»; Beginn vor 12:00 am Tag danach, Ende danach.
    private func facts(_ token: String) -> NightFacts {
        let f = token.trimmingCharacters(in: .whitespaces).components(separatedBy: " ")
        let local = f.count > 7 ? TimeZone(identifier: f[7]) : nil
        let cal = Calendar.gregorian(in: local ?? zone)
        let day = f[0].components(separatedBy: "-").compactMap { Int($0) }
        let bedParts = f[1].components(separatedBy: ":").compactMap { Int($0) }
        let wakeParts = f[2].components(separatedBy: ":").compactMap { Int($0) }
        let bedDay = day[2] + (bedParts[0] < 12 ? 1 : 0)
        let bed = cal.date(from: DateComponents(year: day[0], month: day[1], day: bedDay, hour: bedParts[0], minute: bedParts[1]))!
        var end = cal.date(from: DateComponents(year: day[0], month: day[1], day: bedDay, hour: wakeParts[0], minute: wakeParts[1]))!
        if end <= bed { end = cal.date(byAdding: .day, value: 1, to: end)! }
        func num(_ i: Int) -> Int? { f[i] == "-" ? nil : Int(f[i]) }
        return NightFacts(date: f[0], asleepAt: bed, end: end, timeZone: local, asleepMinutes: Int(f[3])!,
                          latencyMinutes: num(4), deepMinutes: num(5), awakeMinutes: num(6))
    }

    private func describe(_ findings: [Finding]) -> String {
        findings.isEmpty ? "-" : findings.map { f in
            "\(f.kind.rawValue)=\(f.value)" + (f.link.map { "/\($0.rawValue)/\(f.withPercent)/\(f.withoutPercent)" } ?? "")
        }.joined(separator: ",")
    }

    private func at(_ day: Int, _ hour: Int, _ minute: Int = 0) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: 11, day: day, hour: hour, minute: minute))!
    }

    private func span(_ from: Date, _ minutes: Int, _ stage: SleepStage) -> StageSpan {
        StageSpan(start: from, end: from.addingTimeInterval(Double(minutes) * 60), stage: stage)
    }

    /// Sitzung wie Samsung Health: wach bis zum Einschlafen, Tief, Leicht, Wach-Rest am Ende.
    private func session(_ day: Int, _ hour: Int, _ minute: Int, latency: Int, deep: Int, asleep: Int, awakeAfter: Int,
                         stages: Bool = true) -> HealthSession {
        let asleepAt = at(day, hour, minute)
        let start = asleepAt.addingTimeInterval(Double(-latency) * 60)
        let end = asleepAt.addingTimeInterval(Double(asleep + awakeAfter) * 60)
        guard stages else { return HealthSession(start: start, end: end) }
        let light = asleepAt.addingTimeInterval(Double(deep) * 60)
        return HealthSession(start: start, end: end, stages: [
            span(start, latency, .awake), span(asleepAt, deep, .deep), span(light, asleep - deep, .light),
            span(light.addingTimeInterval(Double(asleep - deep) * 60), awakeAfter, .awake),
        ], timeZone: zone)
    }

    func testOwnerNightsFromSamsungSessions() {
        // Echte Werte (Samsung, Nächte 8.–14.11.2026): Einschlafbeginn und Aufwachen (am Morgen danach), Schlaf,
        // Einschlafdauer, Tief.
        let own = ["01:56 09:59 434 15 57", "03:08 11:25 432 51 42", "02:43 07:57 284 55 20", "01:36 10:23 474 103 67",
                   "03:11 11:56 438 95 56", "02:29 11:59 479 75 64", "04:30 11:25 353 85 15"]
        var sessions: [HealthSession] = []
        for (i, line) in own.enumerated() {
            let v = line.components(separatedBy: " ")
            let bed = v[0].components(separatedBy: ":").compactMap { Int($0) }
            let wake = v[1].components(separatedBy: ":").compactMap { Int($0) }
            let inBed = (wake[0] * 60 + wake[1]) - (bed[0] * 60 + bed[1])
            sessions.append(session(9 + i, bed[0], bed[1], latency: Int(v[3])!, deep: Int(v[4])!, asleep: Int(v[2])!, awakeAfter: inBed - Int(v[2])!))
        }
        let facts = NightFacts.of(sessions, clearedAt: nil, calendar: calendar)
        XCTAssertEqual(facts.map(\.date), (8...14).map { String(format: "2026-11-%02d", $0) })
        XCTAssertEqual(facts.map(\.latencyMinutes), [15, 51, 55, 103, 95, 75, 85])
        XCTAssertEqual(facts.map(\.deepMinutes), [57, 42, 20, 67, 56, 64, 15])
        XCTAssertEqual(facts.map(\.asleepMinutes), [434, 432, 284, 474, 438, 479, 353])
        let findings = Findings.of(facts, today: "2026-11-14", calendar: calendar)
        XCTAssertEqual(describe(findings), "LONG_LATENCY=68,LOW_DEEP=11/SHORT_NIGHTS/5/13,BEDTIME_VARIES=174")
        // Startseite: die wichtigste – lange Einschlafdauer; ausgeblendet die nächstwichtige.
        XCTAssertEqual(Findings.home(findings, hidden: [:], now: at(15, 9))?.kind, .longLatency)
        let hidden = Findings.hide([:], kind: .longLatency, now: at(15, 9))
        XCTAssertEqual(Findings.home(findings, hidden: hidden, now: at(15, 9, 1))?.kind, .lowDeep)
        XCTAssertEqual(Findings.home(findings, hidden: Findings.hide(hidden, kind: .lowDeep, now: at(15, 10)), now: at(15, 11))?.kind, .bedtimeVaries)
        // Nacht-Detail: 42 von 432 Min Schlaf = 10 % (gleich gerechnet wie die Regel).
        XCTAssertEqual(Findings.deepPercent(deepMinutes: 42, asleepMinutes: 432), 10)
        XCTAssertNil(Findings.deepPercent(deepMinutes: 42, asleepMinutes: 0))
    }

    func testFactsWithoutStagesHaveNoPhases() {
        let facts = NightFacts.of(session(9, 1, 0, latency: 20, deep: 60, asleep: 400, awakeAfter: 20, stages: false), calendar: calendar)
        XCTAssertNil(facts.deepMinutes)
        XCTAssertNil(facts.awakeMinutes)
        XCTAssertNil(facts.latencyMinutes)
        XCTAssertEqual(facts.asleepMinutes, 440)
        // Mit Phasen, ohne Wachphase am Anfang: Einschlafdauer unbekannt, Wach nach dem Einschlafen bleibt.
        let full = session(9, 1, 0, latency: 0, deep: 60, asleep: 400, awakeAfter: 30)
        let direct = NightFacts.of(HealthSession(start: full.start, end: full.end, stages: Array(full.stages.dropFirst()), timeZone: zone),
                                   calendar: calendar)
        XCTAssertNil(direct.latencyMinutes)
        XCTAssertEqual(direct.awakeMinutes, 30)
    }

    func testOwnOnsetVectors() throws {
        // Gemeinsame Fälle mit Kotlin (shared/testdata/findings-onset.vectors); Minuten ab 2026-11-08 22:00 UTC.
        let t0 = Date(timeIntervalSince1970: 1_794_175_200)
        func t(_ m: String) -> Date { t0.addingTimeInterval(Double(Int(m)!) * 60) }
        let text = try String(contentsOf: NightFixtures.directory.appendingPathComponent("findings-onset.vectors"), encoding: .utf8)
        let lines = text.components(separatedBy: "\n").filter { !$0.isEmpty && !$0.hasPrefix("#") }
        for line in lines {
            let parts = line.components(separatedBy: "\t")
            XCTAssertTrue(parts.count == 3 || parts.count == 4, line)
            guard parts.count >= 3 else { continue }
            let night = parts[1].components(separatedBy: " ")
            func tokens(_ field: String) -> [[String]] {
                field.components(separatedBy: ";").filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }.map { $0.components(separatedBy: " ") }
            }
            let own = tokens(parts[2]).map { f in OwnOnset(start: t(f[0]), end: t(f[1]), bedAt: t(f[2]), asleepAt: t(f[3])) }
            // Nächte der Uhr über NightRecord (Felder 11 und 12) und OwnOnset.of.
            let watch = tokens(parts.count > 3 ? parts[3] : "").compactMap { f in
                OwnOnset.of(NightRecord(alarmId: "a", windowStart: t(f[2]), target: t(f[2]), wokeAt: t(f[2]), reason: .deadline, snoozes: 0,
                                        epochs: [], bedAt: t(f[0]), asleepAt: t(f[1])))
            }
            XCTAssertEqual(Findings.ownLatency(start: t(night[0]), end: t(night[1]), own: own, watch: watch).map { String($0) } ?? "-",
                           parts[0], line)
        }
        XCTAssertEqual(lines.count, 13)
    }

    func testHealthLatencyBeforeOwnMeasurement() {
        // Samsung-Nacht mit Wachphase am Anfang (Einschlafdauer 20): die Gesundheits-App geht vor.
        let withLatency = session(9, 1, 0, latency: 20, deep: 60, asleep: 400, awakeAfter: 30)
        let own = [OwnOnset(start: withLatency.start.addingTimeInterval(-600), end: withLatency.end, bedAt: withLatency.start,
                            asleepAt: withLatency.start.addingTimeInterval(50 * 60))]
        XCTAssertEqual(NightFacts.of(withLatency, calendar: calendar, own: own).latencyMinutes, 20)
        // Ohne Wachphase am Anfang (Apple Watch): die eigene Messung (50 Min); Wach nach dem Einschlafen bleibt 30.
        let direct = HealthSession(start: withLatency.start, end: withLatency.end, stages: Array(withLatency.stages.dropFirst()), timeZone: zone)
        let facts = NightFacts.of(direct, calendar: calendar, own: own)
        XCTAssertEqual(facts.latencyMinutes, 50)
        XCTAssertEqual(facts.awakeMinutes, 30)
        XCTAssertNil(NightFacts.of(direct, calendar: calendar).latencyMinutes)
        // Ganz ohne Phasen ebenso; über die Liste der Sitzungen gleich.
        let bare = session(9, 1, 0, latency: 20, deep: 60, asleep: 400, awakeAfter: 30, stages: false)
        XCTAssertEqual(NightFacts.of(bare, calendar: calendar, own: own).latencyMinutes, 50)
        XCTAssertEqual(NightFacts.of([direct], clearedAt: nil, calendar: calendar, own: own).map(\.latencyMinutes), [50])
        // Nacht der Uhr (Felder 11 und 12) vor der Aufzeichnung: 35 statt 50; ohne Einschlafzeit keine Messung.
        let night = NightRecord(alarmId: "a", windowStart: withLatency.end, target: withLatency.end, wokeAt: withLatency.end, reason: .deadline,
                                snoozes: 0, epochs: [], bedAt: withLatency.start, asleepAt: withLatency.start.addingTimeInterval(35 * 60))
        let watch = [OwnOnset.of(night)].compactMap { $0 }
        XCTAssertEqual(NightFacts.of(direct, calendar: calendar, own: own, watch: watch).latencyMinutes, 35)
        XCTAssertEqual(NightFacts.of(withLatency, calendar: calendar, own: own, watch: watch).latencyMinutes, 20)
        XCTAssertNil(OwnOnset.of(night.with(bedAt: withLatency.start, asleepAt: nil)))
        XCTAssertNil(OwnOnset.of(night.with(bedAt: nil, asleepAt: withLatency.start)))
    }

    func testHiddenFindingReturnsAfterSevenDays() {
        let findings = [Finding(kind: .longLatency, value: 60), Finding(kind: .bedtimeVaries, value: 120)]
        let hiddenAt = at(10, 8)
        let hidden = Findings.hide([:], kind: .longLatency, now: hiddenAt)
        // Ausgeblendet: die nächste; genau 7 Tage danach wieder die wichtigste.
        XCTAssertEqual(Findings.home(findings, hidden: hidden, now: hiddenAt.addingTimeInterval(7 * 86_400 - 0.001))?.kind, .bedtimeVaries)
        XCTAssertEqual(Findings.home(findings, hidden: hidden, now: hiddenAt.addingTimeInterval(7 * 86_400))?.kind, .longLatency)
        let both = Findings.hide(hidden, kind: .bedtimeVaries, now: hiddenAt.addingTimeInterval(60))
        XCTAssertNil(Findings.home(findings, hidden: both, now: hiddenAt.addingTimeInterval(86_400)))
        XCTAssertNil(Findings.home([], hidden: [:], now: hiddenAt))
        // Abgelaufene fallen beim nächsten Ausblenden weg.
        XCTAssertEqual(Set(Findings.hide(hidden, kind: .shortNights, now: hiddenAt.addingTimeInterval(8 * 86_400)).keys), [.shortNights])
    }

    func testBedtimeCardDefersFindingsWithTheSameTip() {
        let findings = [Finding(kind: .shortNights, value: 3), Finding(kind: .lowDeep, value: 11), Finding(kind: .bedtimeVaries, value: 120)]
        let now = at(15, 21)
        // Ohne Bettzeit-Karte wie bisher die wichtigste; mit ihr tritt der gleiche Tipp zurück.
        XCTAssertEqual(Findings.home(findings, hidden: [:], now: now)?.kind, .shortNights)
        XCTAssertEqual(Findings.home(findings, hidden: [:], now: now, bedtimeAdvice: true)?.kind, .lowDeep)
        XCTAssertNil(Findings.home([findings[0], findings[2]], hidden: [:], now: now, bedtimeAdvice: true))
        XCTAssertEqual(Findings.bedtimeTip, [.shortNights, .bedtimeVaries])
    }

    func testHiddenEncodesAndDecodes() {
        let hidden: [FindingKind: Date] = [.bedtimeVaries: Date(timeIntervalSince1970: 2), .longLatency: Date(timeIntervalSince1970: 1)]
        let text = Findings.encodeHidden(hidden)
        XCTAssertEqual(text, "LONG_LATENCY|1000\nBEDTIME_VARIES|2000")
        XCTAssertEqual(Findings.decodeHidden(text), hidden)
        XCTAssertEqual(Findings.decodeHidden("OTHER|3\nMUCH_AWAKE|5\nLOW_DEEP|x\n\n"), [.muchAwake: Date(timeIntervalSince1970: 0.005)])
        XCTAssertEqual(Findings.decodeHidden(nil), [:])
    }

    private func night(_ day: Int, _ asleep: Int, _ deep: Int?) -> NightFacts {
        NightFacts(date: String(format: "2026-11-%02d", day), asleepAt: at(day + 1, 0), end: at(day + 1, 8), timeZone: nil,
                   asleepMinutes: asleep, deepMinutes: deep, awakeMinutes: deep.map { _ in 20 })
    }

    func testWeeklyDeepComparesWithTheWeekBefore() {
        // Woche So 8. bis Sa 14.11., Vorwoche 1. bis 7.11.; je mindestens 3 Nächte mit Phasen.
        let now = [night(8, 400, 40), night(10, 400, 44), night(12, 400, 48), night(13, 400, nil)]
        let before = [night(2, 400, 52), night(4, 400, 52), night(6, 400, 56)]
        let result = Findings.weekDeep(now + before, weekEnd: "2026-11-14")
        XCTAssertEqual(result?.week, 11)
        XCTAssertEqual(result?.before, 13)
        XCTAssertNil(Findings.weekDeep(now + Array(before.prefix(2)), weekEnd: "2026-11-14"))
        XCTAssertNil(Findings.weekDeep(Array(now.dropFirst()) + before, weekEnd: "2026-11-14"))
    }
}
