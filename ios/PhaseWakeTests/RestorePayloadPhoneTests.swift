import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Wiederherstellung, iPhone: Im Arbeitsordner bleiben nur Wecker, Abendroutine und Einstellungen, keine Nächte
/// (Herkunft: Vollaudit 071597f DA-5).
final class RestorePayloadPhoneTests: XCTestCase {
    @MainActor
    func testRestorePayloadKeepsOnlyAlarmsAndSettings() {
        let settings = BackupSettings(sleepGoal: 480, bedtimeReminder: true)
        let full = BackupPayload(createdAt: 42, alarms: "a", nights: ["n1", "n2"], notes: "x", customNotes: "y",
                                 routineItems: "r", routineTicks: "t", settings: settings, healthFeelings: "h")
        let slim = RestoreJournal.settingsOnly(full)
        XCTAssertEqual(slim.createdAt, 42)
        XCTAssertEqual(slim.alarms, "a")
        XCTAssertEqual(slim.routineItems, "r")
        XCTAssertEqual(slim.routineTicks, "t")
        XCTAssertTrue(slim.nights.isEmpty)
        XCTAssertEqual(slim.notes, "")
        XCTAssertEqual(slim.customNotes, "")
        XCTAssertEqual(slim.healthFeelings, "")
    }
}
