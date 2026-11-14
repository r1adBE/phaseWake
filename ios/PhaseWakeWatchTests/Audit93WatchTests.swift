import PhaseWakeCore
import WatchKit
import XCTest
@testable import PhaseWakeWatch

/// Tiefenaudit c3b8b01, Apple Watch: spät gestarteter Rekorder (AP3-2), Wecker am iPhone geändert, während die Uhr-App
/// nicht offen ist (AP3-3), fremde Sitzung nach einem Neustart der App (AP3-4).
final class Audit93WatchTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_793_000_000)

    private func minutes(_ value: Double) -> Date { now.addingTimeInterval(value * 60) }

    // MARK: AP3-2

    /// Ruhe ab dem Start eines spät gestarteten Rekorders ergab bisher eine Einschlafzeit genau bei seinem Start
    /// (Beweis des Audits: Start nach 4 Std. → «eingeschlafen» nach 4 Std.). Nach dem Beginn der Einschlafdauer gestartet,
    /// gilt die Einschlafzeit darum als unbekannt.
    func testRecorderStartedAfterBedtimeLeavesOnsetUnknown() {
        let latest = minutes(9 * 60)
        let begin = ShortNight.autoOnsetStart(target: latest, sleepGoalMinutes: 450, sleepDurationMinutes: nil)
        let bedAt = RecorderOnset.bedAt(since: begin, automatic: true, latest: latest, sleepMinutes: 450)
        XCTAssertEqual(bedAt, begin.addingTimeInterval(60 * 60))
        // Gestartet 4 Std. nach der Bettzeit, ruhig ab der ersten Epoche: Die Rechnung allein meldete «eingeschlafen» beim Start.
        let late = bedAt.addingTimeInterval(4 * 3600)
        let quiet: [Double?] = Array(repeating: 0.0005, count: 40)
        let found = RecorderOnset.find(motions: quiet, since: late, threshold: DetectorConfig.defaultLightThreshold, personalFloor: nil,
                                       latest: latest, extendCap: nil)
        XCTAssertEqual(found, .asleep(at: late, index: 0))
        XCTAssertTrue(RecorderOnset.startedLate(recorderFrom: late, bedAt: bedAt))
        // Vor der Bettzeit oder zur Bettzeit gestartet (auch Sekundenbruchteile nach «Gute Nacht»): Rechnung gilt.
        XCTAssertFalse(RecorderOnset.startedLate(recorderFrom: begin.addingTimeInterval(20 * 60), bedAt: bedAt))
        XCTAssertFalse(RecorderOnset.startedLate(recorderFrom: bedAt.addingTimeInterval(RecorderOnset.lateStartTolerance), bedAt: bedAt))
        XCTAssertTrue(RecorderOnset.startedLate(recorderFrom: bedAt.addingTimeInterval(RecorderOnset.lateStartTolerance + 1), bedAt: bedAt))
        let tap = minutes(60)
        let manualBed = RecorderOnset.bedAt(since: tap, automatic: false, latest: latest, sleepMinutes: 450)
        XCTAssertFalse(RecorderOnset.startedLate(recorderFrom: tap.addingTimeInterval(0.2), bedAt: manualBed))
    }

    // MARK: O-03

    /// Erinnerung am Abend: 60 Min vor der einen Bettzeit. Zielzeit in 7 Std., 7½ Std., gelernt 15 Min → 1 Std. 45 Min
    /// vor jetzt statt 1 Std. 30 Min; ohne gelernte Einschlafdauer wie bisher der Beginn der Einschlaf-Messung.
    func testEveningReminderUsesTheOneBedtime() {
        let target = minutes(7 * 60)
        XCTAssertEqual(EveningReminder.reminderAt(target: target, sleepMinutes: 450, latencyMinutes: nil),
                       ShortNight.autoOnsetStart(target: target, sleepGoalMinutes: 450, sleepDurationMinutes: nil))
        XCTAssertEqual(EveningReminder.reminderAt(target: target, sleepMinutes: 450, latencyMinutes: nil), minutes(-90))
        XCTAssertEqual(EveningReminder.reminderAt(target: target, sleepMinutes: 450, latencyMinutes: 15), minutes(-105))
    }

    // MARK: AP3-3

    /// Wecker 07:00 (Fenster 30 Min, Sitzung ab 06:35) am iPhone auf 06:50 verschoben: Die geplante Sitzung übernimmt die
    /// Nacht (wie `startNight` prüft) statt verworfen zu werden. Eine spätere Zielzeit deckt sie nicht.
    func testScheduledSessionServesEarlierChangedTarget() {
        func plan(target: Date) -> WakePlan { WakePlan(alarmId: "d", windowStart: target.addingTimeInterval(-1800), target: target) }
        func sessionStart(_ plan: WakePlan) -> Date { max(plan.windowStart, plan.target.addingTimeInterval(-1500)) }
        let old = plan(target: minutes(7 * 60))
        let start = sessionStart(old)
        XCTAssertEqual(start, minutes(7 * 60 - 25))
        func serves(_ new: WakePlan) -> Bool {
            NightRules.sessionServes(start: start, plan: new, sessionStart: sessionStart(new), tolerance: 120, delay: 60)
        }
        XCTAssertTrue(serves(plan(target: minutes(6 * 60 + 50))))
        XCTAssertTrue(serves(old))
        // Später (07:30): Sitzung endete vor der Zielzeit; viel früher (05:00): Zielzeit schon vorbei.
        XCTAssertFalse(serves(plan(target: minutes(7 * 60 + 30))))
        XCTAssertFalse(serves(plan(target: minutes(5 * 60))))
        // Grenze: Start bis 2 Min vor dem Sitzungsstart der neuen Nacht.
        XCTAssertTrue(serves(plan(target: minutes(7 * 60 + 2))))
        XCTAssertFalse(serves(plan(target: minutes(7 * 60 + 3))))
    }

    // MARK: AP3-4

    /// Startet eine fremde Sitzung (aus der Zeit vor einem Neustart der App), bleibt die eigene geplante oder laufende.
    func testOwnSessionIsKeptAgainstIncomingOne() {
        XCTAssertTrue(SessionRules.keepsOwn(.scheduled))
        XCTAssertTrue(SessionRules.keepsOwn(.running))
        XCTAssertFalse(SessionRules.keepsOwn(.invalid))
        XCTAssertFalse(SessionRules.keepsOwn(.notStarted))
        XCTAssertFalse(SessionRules.keepsOwn(nil))
    }
}
