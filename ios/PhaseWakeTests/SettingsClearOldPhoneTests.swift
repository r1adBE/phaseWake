import XCTest
@testable import PhaseWake

/// Rückfrage «Älter als 90 Tage löschen», iPhone: Nur bei offener Lernphase kommt der Zusatz, dass ihre Aufzeichnungen
/// bleiben. Wie `ClearOldTextTest` (Android).
final class SettingsClearOldPhoneTests: XCTestCase {
    @MainActor
    func testLearningNoteOnlyWhileLearningIsOpen() {
        XCTAssertEqual(SettingsView.clearOldText(learningOpen: false), L("settings_clear_old_text"))
        XCTAssertEqual(SettingsView.clearOldText(learningOpen: true),
                       L("settings_clear_old_text") + " " + L("settings_clear_old_learn"))
        XCTAssertNotEqual(L("settings_clear_old_learn"), "settings_clear_old_learn")
    }
}
