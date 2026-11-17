import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Wecker-Editor nach Änderungen anderswo, iPhone. Gegenstück auf Android:
/// `EditsElsewhereTest`.
@MainActor
final class EditorElsewherePhoneTests: XCTestCase {
    /// Ein beim Öffnen gespeicherter Wecker, der fehlt, wurde anderswo gelöscht; Speichern legte ihn sonst neu an.
    /// Neuer Wecker bei acht gespeicherten: Hinweis statt still verwerfen.
    func testEditorSaveAfterChangesElsewhere() {
        let eight = (1...8).map { "a\($0)" }
        XCTAssertEqual(EditorSave.check(existed: true, storedIds: eight, draftId: "a3", maxAlarms: 8), .save)
        XCTAssertEqual(EditorSave.check(existed: true, storedIds: eight.filter { $0 != "a3" }, draftId: "a3",
                                        maxAlarms: 8), .gone)
        XCTAssertEqual(EditorSave.check(existed: false, storedIds: eight, draftId: "new", maxAlarms: 8), .full)
        XCTAssertEqual(EditorSave.check(existed: false, storedIds: Array(eight.prefix(7)), draftId: "new",
                                        maxAlarms: 8), .save)
        XCTAssertEqual(EditorSave.check(existed: false, storedIds: Array(eight.prefix(7)) + ["new"], draftId: "new",
                                        maxAlarms: 8), .save)
    }
}
