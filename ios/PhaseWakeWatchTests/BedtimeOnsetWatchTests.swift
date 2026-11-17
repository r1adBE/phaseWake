import PhaseWakeCore
import WatchKit
import XCTest
@testable import PhaseWakeWatch

/// Bettzeit und Einschlafen auf der Apple Watch: spät gestarteter Rekorder, Erinnerung am Abend zur einen Bettzeit,
/// Rekorder startet beim Öffnen zur Erinnerung.
final class BedtimeOnsetWatchTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_793_000_000)

    private func minutes(_ value: Double) -> Date { now.addingTimeInterval(value * 60) }

    /// Ruhe ab dem Start eines spät gestarteten Rekorders ergab bisher eine Einschlafzeit genau bei seinem Start
    /// (Beispiel: Start nach 4 Std. → «eingeschlafen» nach 4 Std.). Nach dem Beginn der Einschlafdauer gestartet,
    /// gilt die Einschlafzeit darum als unbekannt.
    func testRecorderStartedAfterBedtimeLeavesOnsetUnknown() {
        let latest = minutes(9 * 60)
        let begin = ShortNight.autoOnsetStart(target: latest, sleepGoalMinutes: 450, sleepDurationMinutes: nil)
        let bedAt = RecorderOnset.bedAt(since: begin, automatic: true, latest: latest, sleepMinutes: 450)
        XCTAssertEqual(bedAt, begin.addingTimeInterval(60 * 60))
        // Gestartet 4 Std. nach der Bettzeit, ruhig ab der ersten Epoche: Die Rechnung allein meldete «eingeschlafen»
        // beim Start.
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

    /// Erinnerung am Abend: 60 Min vor der einen Bettzeit. Zielzeit in 7 Std., 7½ Std., gelernt 15 Min → 1 Std. 45 Min
    /// vor jetzt statt 1 Std. 30 Min; ohne gelernte Einschlafdauer wie bisher der Beginn der Einschlaf-Messung.
    func testEveningReminderUsesTheOneBedtime() {
        let target = minutes(7 * 60)
        XCTAssertEqual(EveningReminder.reminderAt(target: target, sleepMinutes: 450, latencyMinutes: nil),
                       ShortNight.autoOnsetStart(target: target, sleepGoalMinutes: 450, sleepDurationMinutes: nil))
        XCTAssertEqual(EveningReminder.reminderAt(target: target, sleepMinutes: 450, latencyMinutes: nil), minutes(-90))
        XCTAssertEqual(EveningReminder.reminderAt(target: target, sleepMinutes: 450, latencyMinutes: 15), minutes(-105))
    }

    /// Wer die App bei der Erinnerung öffnet, startet den Rekorder sicher (Einschlaf-Messung und
    /// Lernphase). Mit dem bisherigen Beginn (60 Min vor der Bettzeit ohne Einschlafdauer) hätte er dann noch gewartet.
    func testRecorderStartsWhenAppIsOpenedAtTheReminder() {
        let target = minutes(9 * 60)
        let window = target.addingTimeInterval(-1800)
        let reminder = EveningReminder.reminderAt(target: target, sleepMinutes: 450, latencyMinutes: 20)
        let oldStart = ShortNight.autoOnsetStart(target: target, sleepGoalMinutes: 450, sleepDurationMinutes: nil)
        XCTAssertEqual(AppleAutoOnset.decide(now: reminder, target: target, windowStart: window, onsetStart: oldStart, battery: 80),
                       .wait(until: oldStart))
        XCTAssertEqual(AppleAutoOnset.decide(now: reminder, target: target, windowStart: window, onsetStart: reminder, battery: 80),
                       .start(until: window))
        XCTAssertEqual(AppleLearnRecord.decide(now: reminder, target: target, onsetStart: reminder, battery: 80),
                       .start(from: reminder, until: target.addingTimeInterval(AppleLearnRecord.afterTarget),
                              duration: target.addingTimeInterval(AppleLearnRecord.afterTarget).timeIntervalSince(reminder)))
    }
}
