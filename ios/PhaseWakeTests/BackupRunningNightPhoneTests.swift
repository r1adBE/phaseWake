import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// «Uhr hat übernommen» ohne Kennung (`BackupAlarm.runningNight`): ausgesetzt nur, wenn das eigene Fenster des Weckers
/// begonnen hat; Rückblick je Wecker. Feste Zeiten in Zürich, wie Android `BackupWatchTest` und `RingRulesTest`.
@MainActor
final class BackupRunningNightPhoneTests: XCTestCase {
    private let zurich = Calendar.gregorian(in: TimeZone(identifier: "Europe/Zurich")!)

    private func at(_ hour: Int, _ minute: Int, day: Int = 4) throws -> Date {
        try XCTUnwrap(zurich.date(from: DateComponents(year: 2026, month: 11, day: day, hour: hour, minute: minute)))
    }

    private func alarm(_ hour: Int, _ minute: Int, window: Int,
                       days: Set<Weekday> = Set(Weekday.allCases)) throws -> AlarmSpec {
        try AlarmSpec(id: "\(hour):\(minute)/\(window)", hour: hour, minute: minute, days: days, windowMinutes: window)
    }

    private func night(_ spec: AlarmSpec, _ now: Date) -> Date? {
        BackupAlarm.runningNight(spec, now: now, calendar: zurich)
    }

    /// Klingeln 07:00 ohne Kennung: nur Wecker, deren eigenes Fenster (bei «Genau» die Messdauer) schon läuft.
    func testOnlyAlarmsWhoseOwnWindowBeganArePaused() throws {
        let ring = try at(7, 0), exact = AlarmSpec.exactWindow
        XCTAssertNil(night(try alarm(7, 45, window: 30), ring), "07:45 mit 30 Min: Fenster ab 07:15")
        XCTAssertEqual(night(try alarm(7, 20, window: 30), ring), try at(7, 20), "07:20 mit 30 Min: ab 06:50")
        XCTAssertNil(night(try alarm(7, 15, window: 10), ring), "07:15 mit 10 Min: ab 07:05")
        XCTAssertEqual(night(try alarm(7, 25, window: exact), ring), try at(7, 25), "«Genau» 07:25 misst ab 06:55")
        XCTAssertNil(night(try alarm(7, 35, window: exact), ring), "«Genau» 07:35 misst ab 07:05")
        // Beginn des Fensters genau jetzt zählt schon.
        XCTAssertEqual(night(try alarm(7, 30, window: 30), ring), try at(7, 30))
        XCTAssertNil(night(try alarm(7, 30, window: 30), try at(6, 59)))
    }

    /// Nacht im Schlummern: Rückblick eigenes Fenster + Schlummern + 10 Min (30 Min: 55, 10 Min: 35).
    func testLookbackFollowsTheOwnWindow() throws {
        let ring = try at(7, 0)
        let w30 = try alarm(7, 0, window: 30), w10 = try alarm(7, 0, window: 10)
        XCTAssertEqual(BackupAlarm.nightLookback(w30), 55 * 60)
        XCTAssertEqual(BackupAlarm.nightLookback(w10), 35 * 60)
        XCTAssertEqual(BackupAlarm.nightLookback(try alarm(7, 0, window: AlarmSpec.exactWindow)), 55 * 60)
        XCTAssertEqual(night(w30, try at(7, 36)), ring)
        XCTAssertNil(night(w10, try at(7, 36)), "Nacht vorbei, morgen noch nicht im Fenster")
        XCTAssertEqual(night(w10, try at(7, 34)), ring)
    }

    /// Zwei Wecker am selben Morgen: um 07:00 nur der erste, ab 07:15 auch der zweite; über Mitternacht.
    func testTwoAlarmsOnTheSameMorningAndMidnight() throws {
        let ring = try at(7, 0), later = try at(7, 20)
        let first = try alarm(7, 0, window: 20), second = try alarm(7, 45, window: 30)
        XCTAssertEqual(night(first, ring), ring)
        XCTAssertNil(night(second, ring))
        XCTAssertEqual(night(second, later), try at(7, 45))
        XCTAssertEqual(night(first, later), ring)
        // Einmaliger Wecker 00:10 mit 30 Min: Fenster ab 23:40 des Vortags.
        let midnight = try alarm(0, 10, window: 30, days: [])
        XCTAssertEqual(night(midnight, try at(23, 45)), try at(0, 10, day: 5))
    }
}
