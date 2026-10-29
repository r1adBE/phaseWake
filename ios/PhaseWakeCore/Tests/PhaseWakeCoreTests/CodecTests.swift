import XCTest
@testable import PhaseWakeCore

final class CodecTests: XCTestCase {

    func testAlarmsRoundTripAndFormat() throws {
        let alarms = [
            try AlarmSpec(id: "3f2a-77", hour: 6, minute: 30, days: [.friday, .monday], windowMinutes: 25, phone: .vibrate, watchSound: true, sound: "content://media/internal/audio/media/42", label: "Arbeit | früh, 100 %"),
            try AlarmSpec(id: "b", hour: 9, minute: 30, enabled: false, phone: .off, sleepDurationMinutes: 450, sunrise: .window),
        ]
        XCTAssertEqual(try Codec.decodeAlarms(Codec.encodeAlarms(alarms)), alarms)
        XCTAssertEqual(try Codec.decodeAlarms(Codec.encodeAlarms([])), [])
        // Byte-gleich mit Android (CodecTest.alarmsFormatIsStable).
        XCTAssertEqual(
            Codec.encodeAlarms([alarms[0]]),
            "phasewake-alarms 1\n3f2a-77|6|30|MO,FR|25|1|VIBRATE|1||content%3A%2F%2Fmedia%2Finternal%2Faudio%2Fmedia%2F42|Arbeit+%7C+fr%C3%BCh%2C+100+%25|OFF|NONE|PHONE|0|0\n"
        )
    }

    func testWakeTaskField() throws {
        let math = try AlarmSpec(id: "m", hour: 6, minute: 0, wakeTask: .math)
        XCTAssertEqual(try Codec.decodeAlarms(Codec.encodeAlarms([math])), [math])
        // Byte-gleich mit Android: 13. Feld am Ende.
        XCTAssertEqual(Codec.encodeAlarms([math]), "phasewake-alarms 1\nm|6|0||30|1|SOUND|0||||OFF|MATH|PHONE|0|0\n")
        // Ältere Stände ohne 13. Feld und unbekannte Werte → Halten.
        XCTAssertEqual(try Codec.decodeAlarms("phasewake-alarms 1\nm|6|0||30|1|SOUND|0||||OFF\n").first?.wakeTask, WakeTask.none)
        XCTAssertEqual(try Codec.decodeAlarms("phasewake-alarms 1\nm|6|0||30|1|SOUND|0||||RING|PUZZLE|extra\n").first?.wakeTask, WakeTask.none)
        XCTAssertEqual(try Codec.decodeAlarms("phasewake-alarms 1\nm|6|0||30|1|SOUND|0||||RING|MATH|extra\n").first?.wakeTask, .math)
        // Ein-/Ausschalten behält die Aufgabe.
        XCTAssertEqual(math.with(enabled: false).wakeTask, .math)
    }

    func testSunriseDeviceField() throws {
        let watch = try AlarmSpec(id: "d", hour: 6, minute: 15, windowMinutes: 20, phone: .off, sunrise: .ring, sunriseDevice: .watch)
        XCTAssertEqual(try Codec.decodeAlarms(Codec.encodeAlarms([watch])), [watch])
        // Byte-gleich mit Android (CodecTest.alarmsFormatIsStable): 14. Feld am Ende.
        XCTAssertEqual(Codec.encodeAlarms([watch]), "phasewake-alarms 1\nd|6|15||20|1|OFF|0||||RING|NONE|WATCH|0|0\n")
        // Ältere Stände ohne 14. Feld und unbekannte Werte → Handy (wie CodecTest.sunriseDeviceIsTolerant).
        XCTAssertEqual(try Codec.decodeAlarms("phasewake-alarms 1\nd|6|15||20|1|OFF|0||||RING|NONE\n").first?.sunriseDevice, .phone)
        XCTAssertEqual(try Codec.decodeAlarms("phasewake-alarms 1\nd|6|15||20|1|OFF|0||||RING|NONE|LAMP\n").first?.sunriseDevice, .phone)
        XCTAssertEqual(try Codec.decodeAlarms("phasewake-alarms 1\nd|6|15||20|1|OFF|0||||RING|NONE|BOTH|neu\n").first?.sunriseDevice, .both)
        // Pro Gerät (wie CodecTest.sunrisePerDevice); Ein-/Ausschalten behält das Gerät.
        XCTAssertEqual(watch.phoneSunrise, .off)
        XCTAssertEqual(watch.watchSunrise, .ring)
        XCTAssertEqual(watch.with(enabled: false).sunriseDevice, .watch)
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

    func testNightWithFeelingAndOlderFormat() throws {
        let old = try Codec.decodeNight("phasewake-night 1\na|0|1800000|1080000|DEADLINE|0\n0.0;52.0\n")
        XCTAssertNil(old.feeling)
        let rated = old.with(feeling: 5)
        XCTAssertEqual(try Codec.decodeNight(Codec.encodeNight(rated)), rated)
        // Byte-gleich mit Android (CodecTest.nightWithFeelingAndOlderFormat).
        XCTAssertEqual(Codec.encodeNight(rated), "phasewake-night 1\na|0|1800000|1080000|DEADLINE|0|5\n0.0;52.0\n")
        XCTAssertThrowsError(try Codec.decodeNight("phasewake-night 1\na|0|1800000|1080000|DEADLINE|0|9\n\n"))
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
        let empty = NightRecord(alarmId: night.alarmId, windowStart: night.windowStart, target: night.target, wokeAt: night.wokeAt,
                                reason: night.reason, snoozes: night.snoozes, epochs: [])
        XCTAssertEqual(try Codec.decodeNight(Codec.encodeNight(empty)), empty)
        XCTAssertEqual(night.minutesBeforeTarget, 12)
    }

    func testReadsAndroidNumberFormat() throws {
        let night = try Codec.decodeNight("phasewake-night 1\na|0|1800000|1080000|DEADLINE|0\n2.13E-4;52.0,;\n")
        XCTAssertEqual(night.epochs, [Epoch(motion: 0.000213, heartRate: 52), Epoch(motion: nil, heartRate: nil)])
    }

    func testBrokenInput() throws {
        XCTAssertThrowsError(try Codec.decodeAlarms(""))
        // Kaputte Zeilen fallen einzeln weg (wie Kotlin).
        XCTAssertEqual(try Codec.decodeAlarms("phasewake-alarms 1\nx|25|0||30|1|SOUND|0|||"), [])
        XCTAssertEqual(try Codec.decodeAlarms("phasewake-alarms 1\nx|6|0|XX|30|1|SOUND|0|||"), [])
        XCTAssertEqual(try Codec.decodeAlarms("phasewake-alarms 1\nx|6"), [])
        XCTAssertEqual(try Codec.decodeAlarms("phasewake-alarms 1\nx|6\nb|9|30||30|0|OFF|0|450||\n").map(\.id), ["b"])
        let newer = try Codec.decodeAlarms("phasewake-alarms 1\nx|6|0||30|1|LOUD|0||||MOON|extra\n")
        XCTAssertEqual(newer.first?.phone, .sound)
        XCTAssertEqual(newer.first?.sunrise, .off)
        XCTAssertEqual(newer.first?.wakeTask, WakeTask.none)
        XCTAssertThrowsError(try Codec.decodeNight("phasewake-night 1\na|1|2|3|NOPE|0\n"))
        XCTAssertThrowsError(try Codec.decodeNight("phasewake-alarms 1\n"))
    }

    func testNightFromNewerVersionWithExtraField() throws {
        let read = try Codec.decodeNight("phasewake-night 1\na|0|1800000|1080000|DEADLINE|0|4|extra\n0.0;52.0\n")
        XCTAssertEqual(read.feeling, 4)
        XCTAssertEqual(read.epochs.count, 1)
        XCTAssertThrowsError(try Codec.decodeNight("phasewake-night 1\na|0|1800000|1080000|DEADLINE\n\n"))
    }

    func testDayFormat() {
        let de = Locale(identifier: "de_CH")
        func f(_ d: Set<Weekday>, _ l: Locale = de) -> String { DayFormat.format(d, locale: l, once: "Einmalig", daily: "Täglich") }
        XCTAssertEqual(f([]), "Einmalig")
        XCTAssertEqual(f(Set(Weekday.allCases)), "Täglich")
        XCTAssertEqual(f([.monday, .tuesday, .wednesday, .thursday, .friday]), "Mo–Fr")
        XCTAssertEqual(f([.sunday, .saturday]), "Sa, So")
        XCTAssertEqual(f([.monday, .wednesday, .friday]), "Mo, Mi, Fr")
        XCTAssertEqual(f([.monday, .tuesday, .wednesday, .saturday]), "Mo–Mi, Sa")
        XCTAssertEqual(f([.monday, .tuesday, .wednesday, .thursday, .friday], Locale(identifier: "en_US")), "Mon–Fri")
        // Wie Kotlin: Spanne über das Wochenende bleibt zusammen.
        XCTAssertEqual(f([.saturday, .sunday, .monday]), "Sa–Mo")
        XCTAssertEqual(f([.friday, .saturday, .sunday, .monday, .wednesday]), "Fr–Mo, Mi")
        XCTAssertEqual(f([.sunday, .monday, .tuesday, .wednesday], Locale(identifier: "en_US")), "Sun–Wed")
    }
}
