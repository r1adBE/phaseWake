import PhaseWakeCore
import XCTest
@testable import PhaseWakeWatch

/// Wecker-Editor der Apple Watch nach Änderungen am iPhone.
@MainActor
final class EditorElsewhereWatchTests: XCTestCase {
    func testEditorSaveAfterChangesOnPhone() {
        let eight = (1...8).map { "a\($0)" }
        let limit = WatchModel.maxAlarms
        XCTAssertEqual(EditorSave.check(existed: true, storedIds: eight.filter { $0 != "a1" }, draftId: "a1",
                                        maxAlarms: limit), .gone)
        XCTAssertEqual(EditorSave.check(existed: false, storedIds: eight, draftId: "new", maxAlarms: limit), .full)
        XCTAssertEqual(EditorSave.check(existed: true, storedIds: eight, draftId: "a1", maxAlarms: limit), .save)
    }
}
