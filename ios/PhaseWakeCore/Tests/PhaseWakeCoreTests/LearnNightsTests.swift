import XCTest
@testable import PhaseWakeCore

/// Lern-Übersicht (``LearnNights``, ``LearnSkip``, ``LearnLog``); Gegenstück zu `LearnNightsTest` und `LearnSkipTest`
/// (Kotlin), gemeinsame Fälle in `shared/testdata/learn-nights.vectors`.
final class LearnNightsTests: XCTestCase {
    private let calendar = Calendar.gregorian(in: TimeZone(identifier: "Europe/Zurich")!)
    private let target = Date(timeIntervalSince1970: 1_800_000_000)

    private func at(_ day: Int, _ hour: Int, _ minute: Int = 0) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: 11, day: day, hour: hour, minute: minute))!
    }

    private func date(_ day: Int) -> String { String(format: "2026-11-%02d", day) }

    /// Aufzeichnung ab `start` mit `epochs` Epochen (Bewegung, Puls in jeder `pulseEvery`-ten), Ende `end`.
    private func recorded(_ start: Date, _ epochs: Int, pulseEvery: Int = 5, end: String? = "wake") -> LearnNights.Recorded {
        LearnNights.Recorded(night: Learning.Night(start: start, epochs: (0..<epochs).map { Epoch(motion: 0.02, heartRate: $0 % pulseEvery == 0 ? 55 : nil) }),
                             endCode: end)
    }

    // MARK: Grund je Nacht

    func testSharedVectors() throws {
        let text = try String(contentsOf: NightFixtures.directory.appendingPathComponent("learn-nights.vectors"), encoding: .utf8)
        let lines = text.components(separatedBy: "\n").filter { !$0.isEmpty && !$0.hasPrefix("#") }
        XCTAssertGreaterThanOrEqual(lines.count, 20)
        var seen = Set<LearnNights.Reason>()
        for line in lines {
            let parts = line.split(separator: "\t", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
            XCTAssertEqual(parts.count, 2, line)
            guard parts.count == 2, let expected = LearnNights.Reason(rawValue: parts[0]) else {
                XCTFail("unbekannter Grund: \(line)")
                continue
            }
            let reason = LearnNights.reason(facts(parts[1]))
            XCTAssertEqual(reason, expected, line)
            seen.insert(reason)
        }
        XCTAssertEqual(seen, Set(LearnNights.Reason.allCases), "jeder Grund mindestens einmal")
    }

    private func facts(_ text: String) -> LearnNights.Facts {
        var map: [String: String] = [:]
        for pair in text.split(separator: " ") where pair.contains("=") {
            let kv = pair.split(separator: "=", maxSplits: 1).map(String.init)
            map[kv[0]] = kv.count > 1 ? kv[1] : ""
        }
        func flag(_ key: String, _ value: Bool) -> Bool { map[key].map { $0 == "1" } ?? value }
        var f = LearnNights.Facts()
        f.recorded = flag("recorded", false)
        f.measuredEpochs = map["measured"].flatMap { Int($0) } ?? 0
        f.spanEpochs = map["span"].flatMap { Int($0) } ?? 0
        f.heartRateEpochs = map["hr"].flatMap { Int($0) } ?? 0
        f.endCode = map["end"]
        f.beforeStart = flag("before", false)
        f.skip = map["skip"].flatMap { LearnSkip.Reason(rawValue: $0) }
        f.consent = flag("consent", true)
        f.alarm = map["alarm"].flatMap { $0 == "-" ? nil : $0 == "1" }
        f.autoOnset = flag("auto", true)
        f.pending = flag("pending", false)
        return f
    }

    func testCountedAgreesWithTheLearningRule() {
        for epochs in [0, 100, Learning.minSpanEpochs - 1, Learning.minSpanEpochs, 900] {
            let night = recorded(at(1, 22), epochs).night
            var f = LearnNights.Facts()
            f.recorded = true
            f.measuredEpochs = Learning.measuredEpochs(night)
            f.spanEpochs = LearnNights.spanEpochs(night)
            f.heartRateEpochs = night.epochs.filter { $0.heartRate != nil }.count
            f.endCode = "wake"
            XCTAssertEqual(LearnNights.reason(f).isCounted, Learning.counts(night), "\(epochs) Epochen")
        }
        XCTAssertEqual(LearnNights.spanEpochs(Learning.Night(start: at(1, 22), epochs: Array(repeating: Epoch(motion: nil, heartRate: nil), count: 10))), 0)
        XCTAssertEqual(LearnNights.pulsePercent(measured: 100, heartRate: 20), 20)
        XCTAssertEqual(LearnNights.pulsePercent(measured: 0, heartRate: 5), 0)
        XCTAssertEqual(LearnNights.pulsePercent(measured: 10, heartRate: 30), 100)
    }

    // MARK: Liste der Nächte

    func testNightsSinceTheStartNewestFirst() {
        let since = at(1, 18)
        let recordings = [recorded(at(1, 22), 600), recorded(at(2, 23), 200, end: "watch")]
        let skips = [LearnSkip(target: at(4, 7), reason: .battery, battery: 35)]
        let alarms: Set<String> = [date(1), date(2), date(3), date(4), date(5)]
        func list(_ now: Date) -> [LearnNights.Night] {
            LearnNights.nights(recordings, skips: skips, alarmNights: alarms, alarmsKnownFrom: date(1), since: since, consent: true,
                               autoOnset: true, now: now, calendar: calendar)
        }
        let nights = list(at(6, 9))
        XCTAssertEqual(nights.map(\.date), [date(5), date(4), date(3), date(2), date(1)])
        XCTAssertEqual(nights.map(\.reason), [.pending, .noRecording, .battery, .endedStopped, .counted])
        XCTAssertEqual(nights[2].battery, 35)
        XCTAssertEqual(nights[4], LearnNights.Night(date: date(1), reason: .counted, minutes: 300, measuredEpochs: 600, pulsePercent: 20))
        XCTAssertEqual(nights[3].minutes, 100)
        // Vor Mitternacht erscheint die laufende Nacht ohne Angaben nicht; ab Mittag ist die letzte nicht mehr unterwegs.
        XCTAssertEqual(list(at(6, 13)).first?.date, date(5))
        XCTAssertEqual(list(at(6, 13)).first?.reason, .noRecording)
        XCTAssertEqual(list(at(5, 23)).first?.date, date(4))
    }

    func testAlarmKnowledgeAndStart() {
        let since = at(1, 18)
        let nights = LearnNights.nights([], skips: [], alarmNights: [date(2)], alarmsKnownFrom: date(3), since: since, consent: true,
                                        autoOnset: true, now: at(4, 13), calendar: calendar)
        XCTAssertEqual(nights.map(\.reason), [.noAlarm, .noRecording, .unknown])
        let before = LearnNights.nights([recorded(at(1, 17), 600)], skips: [], alarmNights: [], alarmsKnownFrom: nil, since: since,
                                        consent: true, autoOnset: true, now: at(2, 13), calendar: calendar)
        XCTAssertEqual(before.map(\.reason), [.learningOff])
        let auto = LearnNights.nights([], skips: [], alarmNights: [], alarmsKnownFrom: nil, since: since, consent: true,
                                      autoOnset: false, now: at(2, 13), calendar: calendar)
        XCTAssertEqual(auto.map(\.reason), [.autoOff])
        let long = LearnNights.nights([], skips: [], alarmNights: [], alarmsKnownFrom: nil, since: since.addingTimeInterval(-40 * 86_400),
                                      consent: true, autoOnset: true, now: at(2, 13), calendar: calendar)
        XCTAssertEqual(long.count, LearnNights.maxNights)
        // Die beste Aufzeichnung der Nacht: eine zählende vor einer mit mehr Messungen.
        let sampled = LearnNights.Recorded(night: Learning.Night(start: at(1, 22), epochs: (0..<400).map {
            $0 % 5 == 0 ? Epoch(motion: 0.02, heartRate: 50) : Epoch(motion: nil, heartRate: nil)
        }), endCode: "wake")
        let best = LearnNights.nights([sampled, recorded(at(1, 21), 350)], skips: [], alarmNights: [], alarmsKnownFrom: nil, since: since,
                                      consent: true, autoOnset: true, now: at(2, 13), calendar: calendar)
        XCTAssertEqual(best.map(\.reason), [.counted])
        XCTAssertEqual(best.first?.measuredEpochs, 80)
    }

    func testHomeShowsOnlyAnUnexpectedMissOfLastNight() {
        let now = at(6, 9)
        func miss(_ day: Int, _ reason: LearnNights.Reason) -> LearnNights.Night? {
            LearnNights.lastNightMiss([LearnNights.Night(date: date(day), reason: reason)], now: now, calendar: calendar)
        }
        XCTAssertEqual(miss(5, .battery)?.reason, .battery)
        XCTAssertEqual(miss(4, .sensors)?.reason, .sensors)
        XCTAssertNil(miss(3, .sensors), "älter als gestern")
        for quiet: LearnNights.Reason in [.counted, .countedNoPulse, .pending, .noAlarm, .learningOff] {
            XCTAssertNil(miss(5, quiet), quiet.rawValue)
        }
        XCTAssertNil(LearnNights.lastNightMiss([], now: now, calendar: calendar))
    }

    func testEveningWarningOneHourBeforeTheRecording() {
        let start = at(5, 22)
        let low = Learning.minBattery - 1
        func warn(_ now: Date, battery: Int? = Learning.minBattery - 1, charging: Bool = false, active: Bool = true) -> Bool {
            LearnNights.shouldWarn(active: active, battery: battery, charging: charging, now: now, recordStart: start)
        }
        XCTAssertEqual(LearnNights.warnAt(start), at(5, 21))
        XCTAssertTrue(warn(at(5, 21), battery: low))
        XCTAssertTrue(warn(at(5, 20, 50)), "ungenauer Alarm etwas früher")
        XCTAssertFalse(warn(at(5, 20, 49)))
        XCTAssertTrue(warn(at(5, 21, 59)))
        XCTAssertFalse(warn(start), "Aufzeichnung beginnt schon")
        XCTAssertFalse(warn(at(5, 21), battery: Learning.minBattery))
        XCTAssertFalse(warn(at(5, 21), battery: nil), "unbekannter Akku")
        XCTAssertFalse(warn(at(5, 21), charging: true), "lädt")
        XCTAssertFalse(warn(at(5, 21), active: false), "Lernphase läuft nicht")
    }

    // MARK: Meldung und Speicher

    func testSkipEncodesAndDecodes() {
        let skip = LearnSkip(target: target, reason: .battery, battery: 35)
        XCTAssertEqual(skip.encode(), "1800000000000|battery|35")
        XCTAssertEqual(LearnSkip.decode(skip.encode()), skip)
        XCTAssertEqual(LearnSkip(target: target, reason: .sensors).encode(), "1800000000000|sensors|")
        XCTAssertEqual(LearnSkip.decode(" 1800000000000|manual\n"), LearnSkip(target: target, reason: .manual))
        XCTAssertEqual(LearnSkip.decode("1800000000000|late|"), LearnSkip(target: target, reason: .late))
        XCTAssertEqual(LearnSkip.decode("1800000000000|battery|140"), LearnSkip(target: target, reason: .battery), "Akku unplausibel")
        XCTAssertNil(LearnSkip.decode(nil))
        XCTAssertNil(LearnSkip.decode(""))
        XCTAssertNil(LearnSkip.decode("1800000000000"))
        XCTAssertNil(LearnSkip.decode("x|battery|20"))
        XCTAssertNil(LearnSkip.decode("0|battery|20"))
        XCTAssertNil(LearnSkip.decode("9999999999999999|battery|20"), "Zeitpunkt unplausibel")
        XCTAssertNil(LearnSkip.decode("1800000000000|later|20"), "unbekannter Grund einer neueren Uhr")
    }

    func testAppleSkipReasonsBecomeReports() {
        XCTAssertEqual(AppleLearnRecord.learnSkip(reason: "battery 38%", target: target), LearnSkip(target: target, reason: .battery, battery: 38))
        XCTAssertEqual(AppleLearnRecord.learnSkip(reason: "too late", target: target), LearnSkip(target: target, reason: .late))
        XCTAssertEqual(AppleLearnRecord.learnSkip(reason: "motion not allowed", target: target), LearnSkip(target: target, reason: .sensors))
        XCTAssertEqual(AppleLearnRecord.learnSkip(reason: "recorder unavailable", target: target), LearnSkip(target: target, reason: .sensors))
        XCTAssertEqual(AppleLearnRecord.learnSkip(reason: "recording runs", target: target), LearnSkip(target: target, reason: .manual))
        XCTAssertNil(AppleLearnRecord.learnSkip(reason: "past", target: target))
        // Der Grund aus der Regel selbst ergibt die Meldung mit dem Akkustand.
        let night = target.addingTimeInterval(-8 * 3600)
        guard case .skip(let reason) = AppleLearnRecord.decide(now: night, target: target, onsetStart: night, battery: Learning.minBattery - 1) else {
            return XCTFail("unter der Schwelle: nicht starten")
        }
        XCTAssertEqual(AppleLearnRecord.learnSkip(reason: reason, target: target)?.battery, Learning.minBattery - 1)
    }

    func testSkipsOnePerNightNewestFourteen() {
        var skips: [LearnSkip] = []
        for day in 1...20 { skips = LearnLog.add(LearnSkip(target: at(day, 7), reason: .battery, battery: day), to: skips, calendar: calendar) }
        XCTAssertEqual(skips.count, LearnNights.maxNights)
        XCTAssertEqual(skips.first?.battery, 7)
        skips = LearnLog.add(LearnSkip(target: at(20, 6), reason: .sensors), to: skips, calendar: calendar)
        XCTAssertEqual(skips.count, LearnNights.maxNights)
        XCTAssertEqual(skips.last, LearnSkip(target: at(20, 6), reason: .sensors))
        XCTAssertEqual(LearnLog.decodeSkips(LearnLog.encodeSkips(skips)), skips)
        XCTAssertEqual(LearnLog.decodeSkips("kaputt\n\n"), [])
    }

    func testUpcomingTargetsOfTheNextTwoWeeks() throws {
        let now = at(2, 12)
        let weekdays = try AlarmSpec(id: "w", hour: 7, minute: 0, days: [.monday, .tuesday, .wednesday, .thursday, .friday])
        let once = try AlarmSpec(id: "o", hour: 6, minute: 30)
        let off = try AlarmSpec(id: "x", hour: 5, minute: 0, days: [.sunday], enabled: false)
        let targets = LearnLog.upcomingTargets([weekdays, once, off], now: now, calendar: calendar, holidays: [])
        // 2.11.2026 ist ein Montag: 10 Werktage bis zum 16., dazu der einmalige am 3.
        XCTAssertEqual(targets.count, 11)
        XCTAssertEqual(targets.first, at(3, 6, 30))
        XCTAssertTrue(targets.allSatisfy { $0 > now && $0 <= now.addingTimeInterval(TimeInterval(LearnLog.planDays * 86_400)) })
        let holidayAlarm = try AlarmSpec(id: "w", hour: 7, minute: 0, days: [.monday, .tuesday, .wednesday, .thursday, .friday], skipHolidays: true)
        XCTAssertEqual(LearnLog.upcomingTargets([holidayAlarm], now: now, calendar: calendar, holidays: ["2026-11-04"]).count, 9)
    }

    func testAlarmNightsKeepThePastAndReplaceTheFuture() {
        let now = at(5, 12)
        let old = ["2026-11-03": at(4, 7), "2026-11-05": at(6, 7)]
        let merged = LearnLog.mergeAlarmNights(old, planned: [at(7, 6), at(3, 7)], now: now, calendar: calendar)
        XCTAssertEqual(merged, ["2026-11-03": at(4, 7), "2026-11-06": at(7, 6)])
        XCTAssertEqual(LearnLog.mergeAlarmNights([:], planned: [at(7, 6), at(7, 5)], now: now, calendar: calendar)["2026-11-06"], at(7, 5))
        var many: [String: Date] = [:]
        for day in 1...30 { many[String(format: "2026-10-%02d", day)] = at(1, 7).addingTimeInterval(-TimeInterval(31 - day) * 86_400) }
        XCTAssertEqual(LearnLog.mergeAlarmNights(many, planned: [], now: now, calendar: calendar).count, LearnNights.maxNights)
        XCTAssertEqual(LearnLog.decodeAlarmNights(LearnLog.encodeAlarmNights(merged)), merged)
        XCTAssertEqual(LearnLog.encodeAlarmNights(merged), "2026-11-03=\(TimeBounds.millis(at(4, 7)))\n2026-11-06=\(TimeBounds.millis(at(7, 6)))")
        XCTAssertEqual(LearnLog.decodeAlarmNights("2026-13-01=5\nx=1\n2026-11-01=abc"), [:])
    }

    func testAlarmNightsKeepTheEarlierTargetAndTheBoundaryAtNow() {
        // Audit BE-4: zwei Wecker derselben Nacht, der frühere zuerst geplant – er bleibt (der spätere ersetzt ihn nicht).
        let now = at(5, 12)
        XCTAssertEqual(LearnLog.mergeAlarmNights([:], planned: [at(6, 5), at(6, 6)], now: now, calendar: calendar), ["2026-11-05": at(6, 5)])
        // Ein Eintrag genau zur Zeit des Neuplanens ist vergangen und bleibt, auch wenn der Plan ihn nicht mehr hat.
        let old = ["2026-11-04": now]
        XCTAssertEqual(LearnLog.mergeAlarmNights(old, planned: [], now: now, calendar: calendar), old)
        // Eine Zielzeit genau jetzt gehört nicht mehr zum Plan (nur echt künftige).
        XCTAssertEqual(LearnLog.mergeAlarmNights([:], planned: [now], now: now, calendar: calendar), [:])
        // Vergangene Nacht am selben Nachtdatum wie ein späterer zweiter Wecker: die vergangene (frühere) bleibt.
        let rang = at(6, 5).addingTimeInterval(-7 * 3600)
        let past = ["2026-11-05": rang]
        XCTAssertEqual(LearnLog.mergeAlarmNights(past, planned: [rang.addingTimeInterval(8 * 3600)], now: rang.addingTimeInterval(60),
                                                 calendar: calendar), past)
    }
}
