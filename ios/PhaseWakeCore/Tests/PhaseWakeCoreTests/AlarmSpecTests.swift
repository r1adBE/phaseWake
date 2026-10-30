import XCTest
import PhaseWakeCore

/// Grenzwerte und abgeleitete Eigenschaften eines Weckers; Gegenstück zu `AlarmSpecTest` und `SunriseTest` (Kotlin).
final class AlarmSpecTests: XCTestCase {
    private func alarm() -> AlarmSpec { try! AlarmSpec(id: "a", hour: 7, minute: 0) }

    func testLimitsAreInclusive() throws {
        XCTAssertNoThrow(try AlarmSpec(id: "a", hour: 0, minute: 0, windowMinutes: AlarmSpec.minWindowMinutes))
        XCTAssertNoThrow(try AlarmSpec(id: "a", hour: 23, minute: 59, windowMinutes: AlarmSpec.maxWindowMinutes))
        XCTAssertNoThrow(try AlarmSpec(id: "a", hour: 7, minute: 0, sleepDurationMinutes: AlarmSpec.minSleepMinutes))
        XCTAssertNoThrow(try AlarmSpec(id: "a", hour: 7, minute: 0, sleepDurationMinutes: AlarmSpec.maxSleepMinutes))
        XCTAssertThrowsError(try AlarmSpec(id: "a", hour: -1, minute: 0))
        XCTAssertThrowsError(try AlarmSpec(id: "a", hour: 7, minute: -1))
        XCTAssertThrowsError(try AlarmSpec(id: "a", hour: 7, minute: 0, sleepDurationMinutes: 721))
        XCTAssertThrowsError(try AlarmSpec(id: "", hour: 7, minute: 0))
        // Neue Wecker: 20 Min; gespeicherte tragen ihr Fenster selbst (CodecTests.testStoredWindowIgnoresDefault).
        XCTAssertEqual(alarm().windowMinutes, 20)
    }

    func testExactWindowIsZeroAndMeasuresThirtyMinutes() throws {
        let exact = try AlarmSpec(id: "a", hour: 7, minute: 0, windowMinutes: AlarmSpec.exactWindow)
        XCTAssertTrue(exact.isExact)
        XCTAssertEqual(exact.measureWindowMinutes, AlarmSpec.measureMinutes)
        XCTAssertFalse(alarm().isExact)
        XCTAssertEqual(alarm().measureWindowMinutes, 20)
        XCTAssertEqual(try AlarmSpec(id: "a", hour: 7, minute: 0, windowMinutes: 10).measureWindowMinutes, 10)
        // Zwischen 0 und 10 gibt es nichts.
        XCTAssertThrowsError(try AlarmSpec(id: "a", hour: 7, minute: 0, windowMinutes: 5))
        XCTAssertThrowsError(try AlarmSpec(id: "a", hour: 7, minute: 0, windowMinutes: -1))
        XCTAssertThrowsError(try AlarmSpec(id: "a", hour: 7, minute: 0, windowMinutes: 31))
        // Der Codec schreibt 0 wie jede andere Zahl.
        XCTAssertEqual(try Codec.decodeAlarms(Codec.encodeAlarms([exact])), [exact])
    }

    func testPhoneBackupFollowsPhoneAlert() throws {
        XCTAssertFalse(try AlarmSpec(id: "a", hour: 7, minute: 0, phone: .off).phoneBackup)
        XCTAssertTrue(try AlarmSpec(id: "a", hour: 7, minute: 0, phone: .vibrate).phoneBackup)
        XCTAssertTrue(try AlarmSpec(id: "a", hour: 7, minute: 0, phone: .sound).phoneBackup)
    }

    func testRepeatingAlarmIsNotOneShot() throws {
        XCTAssertTrue(alarm().isOneShot)
        XCTAssertFalse(try AlarmSpec(id: "a", hour: 7, minute: 0, days: [.monday]).isOneShot)
    }

    func testSunriseDeviceSides() throws {
        XCTAssertEqual(SunriseDevice.allCases.map(\.onPhone), [true, false, true])
        XCTAssertEqual(SunriseDevice.allCases.map(\.onWatch), [false, true, true])
        let window = try AlarmSpec(id: "a", hour: 7, minute: 0, sunrise: .window)
        XCTAssertEqual(window.phoneSunrise, .window)
        XCTAssertEqual(window.watchSunrise, .off)
        let onWatch = try AlarmSpec(id: "a", hour: 7, minute: 0, sunrise: .window, sunriseDevice: .watch)
        XCTAssertEqual(onWatch.phoneSunrise, .off)
        XCTAssertEqual(onWatch.watchSunrise, .window)
    }

    func testSunriseBrightnessBelowZeroIsDimmest() {
        XCTAssertEqual(Sunrise.brightness(-1), 0.01, accuracy: 1e-9)
        let epoch = Date(timeIntervalSince1970: 0)
        XCTAssertEqual(Sunrise.progress(start: epoch, end: epoch, now: epoch), 1)
    }

    // MARK: Wie `SunriseTest` (Kotlin)

    private let start = ISO8601DateFormatter().date(from: "2026-10-28T05:30:00Z")!
    private let end = ISO8601DateFormatter().date(from: "2026-10-28T06:00:00Z")!

    func testProgressRunsFromStartToEnd() {
        XCTAssertEqual(Sunrise.progress(start: start, end: end, now: start.addingTimeInterval(-60)), 0)
        XCTAssertEqual(Sunrise.progress(start: start, end: end, now: start), 0)
        XCTAssertEqual(Sunrise.progress(start: start, end: end, now: start.addingTimeInterval(900)), 0.5)
        XCTAssertEqual(Sunrise.progress(start: start, end: end, now: end), 1)
        XCTAssertEqual(Sunrise.progress(start: start, end: end, now: end.addingTimeInterval(60)), 1)
        XCTAssertEqual(Sunrise.progress(start: end, end: start, now: start), 1)
    }

    func testBrightnessStartsDimAndEndsFull() {
        XCTAssertEqual(Sunrise.brightness(0), 0.01, accuracy: 1e-9)
        XCTAssertEqual(Sunrise.brightness(1), 1, accuracy: 1e-9)
        XCTAssertEqual(Sunrise.brightness(0.5), 0.2575, accuracy: 1e-6)
        XCTAssertEqual(Sunrise.brightness(2), 1, accuracy: 1e-9)
    }
}
