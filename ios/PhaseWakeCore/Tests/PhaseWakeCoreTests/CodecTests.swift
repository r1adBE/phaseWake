import XCTest
@testable import PhaseWakeCore

final class CodecTests: XCTestCase {

    func testAlarmsRoundTripAndFormat() throws {
        let alarms = [
            try AlarmSpec(id: "3f2a-77", hour: 6, minute: 30, days: [.friday, .monday], windowMinutes: 25, phone: .vibrate, watchSound: true, sound: "content://media/internal/audio/media/42", label: "Arbeit | früh, 100 %"),
            try AlarmSpec(id: "b", hour: 9, minute: 30, enabled: false, phone: .off, sleepDurationMinutes: 450, sunrise: .window),
        ]
        XCTAssertEqual(try Codec.decodeAlarms(Codec.encodeAlarms(alarms)), alarms)
        // Byte-gleich mit Android (CodecTest.alarmsFormatIsStable).
        XCTAssertEqual(
            Codec.encodeAlarms([alarms[0]]),
            "phasewake-alarms 1\n3f2a-77|6|30|MO,FR|25|1|VIBRATE|1||content%3A%2F%2Fmedia%2Finternal%2Faudio%2Fmedia%2F42|Arbeit+%7C+fr%C3%BCh%2C+100+%25|OFF\n"
        )
    }

    func testOlderFormatWithoutSunrise() throws {
        let old = try Codec.decodeAlarms("phasewake-alarms 1\nb|9|30||30|0|OFF|0|450||\n")
        XCTAssertEqual(old.first?.sunrise, .off)
        XCTAssertEqual(old.first?.sleepDurationMinutes, 450)
    }

    func testSunriseCurve() {
        let start = Date(timeIntervalSince1970: 1_000_000), end = start.addingTimeInterval(1800)
        XCTAssertEqual(Sunrise.progress(start: start, end: end, now: start.addingTimeInterval(-60)), 0)
        XCTAssertEqual(Sunrise.progress(start: start, end: end, now: start.addingTimeInterval(900)), 0.5)
        XCTAssertEqual(Sunrise.progress(start: start, end: end, now: end.addingTimeInterval(60)), 1)
        XCTAssertEqual(Sunrise.progress(start: end, end: start, now: start), 1)
        XCTAssertEqual(Sunrise.brightness(0), 0.01, accuracy: 1e-9)
        XCTAssertEqual(Sunrise.brightness(0.5), 0.2575, accuracy: 1e-9)
        XCTAssertEqual(Sunrise.brightness(1), 1, accuracy: 1e-9)
    }

    func testNightRoundTrip() throws {
        let night = NightRecord(
            alarmId: "3f2a-77",
            windowStart: Date(timeIntervalSince1970: 1_793_165_400),
            target: Date(timeIntervalSince1970: 1_793_167_200),
            wokeAt: Date(timeIntervalSince1970: 1_793_166_450),
            reason: .lightSleep, snoozes: 2,
            epochs: [Epoch(motion: 0, heartRate: 52), Epoch(motion: 0.000213456789123, heartRate: nil), Epoch(motion: nil, heartRate: 55.583333333333336), Epoch(motion: nil, heartRate: nil)]
        )
        XCTAssertEqual(try Codec.decodeNight(Codec.encodeNight(night)), night)
        XCTAssertEqual(night.minutesBeforeTarget, 12)
    }

    func testReadsAndroidNumberFormat() throws {
        let night = try Codec.decodeNight("phasewake-night 1\na|0|1800000|1080000|DEADLINE|0\n2.13E-4;52.0,;\n")
        XCTAssertEqual(night.epochs, [Epoch(motion: 0.000213, heartRate: 52), Epoch(motion: nil, heartRate: nil)])
    }

    func testBrokenInput() {
        XCTAssertThrowsError(try Codec.decodeAlarms(""))
        XCTAssertThrowsError(try Codec.decodeAlarms("phasewake-alarms 1\nx|25|0||30|1|SOUND|0|||"))
        XCTAssertThrowsError(try Codec.decodeAlarms("phasewake-alarms 1\nx|6|0|XX|30|1|SOUND|0|||"))
        XCTAssertThrowsError(try Codec.decodeAlarms("phasewake-alarms 1\nx|6"))
        XCTAssertThrowsError(try Codec.decodeAlarms("phasewake-alarms 1\nx|6|0||30|1|LOUD|0|||"))
        XCTAssertThrowsError(try Codec.decodeNight("phasewake-night 1\na|1|2|3|NOPE|0\n"))
        XCTAssertThrowsError(try Codec.decodeNight("phasewake-alarms 1\n"))
    }

    func testDayFormat() {
        let de = Locale(identifier: "de_CH")
        func f(_ d: Set<Weekday>, _ l: Locale = de) -> String { DayFormat.format(d, locale: l, once: "Einmalig", daily: "Täglich") }
        XCTAssertEqual(f([]), "Einmalig")
        XCTAssertEqual(f(Set(Weekday.allCases)), "Täglich")
        XCTAssertEqual(f([.monday, .tuesday, .wednesday, .thursday, .friday]), "Mo–Fr")
        XCTAssertEqual(f([.sunday, .saturday]), "Sa, So")
        XCTAssertEqual(f([.monday, .wednesday, .friday]), "Mo, Mi, Fr")
        XCTAssertEqual(f([.monday, .tuesday, .wednesday, .thursday, .friday], Locale(identifier: "en_US")), "Mon–Fri")
    }

    func testHistoryCsv() {
        let start = Date(timeIntervalSince1970: 1_790_829_000)
        func night(_ day: Double, _ epochs: [Epoch] = [Epoch(motion: 0, heartRate: 52)]) -> NightRecord {
            let ws = start.addingTimeInterval(day * 86_400)
            return NightRecord(alarmId: "geheim-123", windowStart: ws, target: ws.addingTimeInterval(1800), wokeAt: ws.addingTimeInterval(1080), reason: .lightSleep, snoozes: 1, epochs: epochs)
        }
        let csv = NightHistory.exportCsv([night(1, [Epoch(motion: 0.0021, heartRate: 52.34), Epoch(motion: nil, heartRate: nil)]), night(0)])
        let lines = csv.split(separator: "\n").map(String.init)
        XCTAssertEqual(lines[1], "1,30,LIGHT_SLEEP,12,1,0,0.000000,52.0")
        XCTAssertEqual(lines[2], "2,30,LIGHT_SLEEP,12,1,0,0.002100,52.3")
        XCTAssertEqual(lines[3], "2,30,LIGHT_SLEEP,12,1,1,,")
        XCTAssertFalse(csv.contains("geheim"))
        XCTAssertEqual(NightHistory.trim((0..<100).map { night(Double($0)) }).count, 90)
    }

    func testEpochAssembler() {
        let s: Int64 = 1_000_000_000
        let start = 500 * s
        let a = EpochAssembler(startNanos: start)
        for i in 0..<750 { _ = a.addMotion(timestampNanos: start + Int64(i) * 40_000_000, x: i % 2 == 0 ? 0 : 0.25, y: 0, z: 9.81) }
        _ = a.addHeartRate(timestampNanos: start + 10 * s, bpm: 50)
        _ = a.addHeartRate(timestampNanos: start + 20 * s, bpm: 54)
        let closed = a.addMotion(timestampNanos: start + 30 * s, x: 0, y: 0, z: 9.81)
        XCTAssertEqual(closed.count, 1)
        XCTAssertEqual(closed[0].motion!, 0.20, accuracy: 1e-12)
        XCTAssertEqual(closed[0].heartRate, 52)
        XCTAssertTrue(a.addHeartRate(timestampNanos: start + 5 * s, bpm: 60).isEmpty)
        XCTAssertTrue(a.advance(nowNanos: start + 41 * s, graceNanos: 12 * s).isEmpty)
        XCTAssertEqual(a.advance(nowNanos: start + 73 * s, graceNanos: 12 * s).count, 1)
    }
}
