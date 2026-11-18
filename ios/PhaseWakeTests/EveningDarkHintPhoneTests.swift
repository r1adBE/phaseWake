import XCTest
@testable import PhaseWake

/// Hinweis «Gilt jetzt» bei «Abends dunkel», iPhone; Gegenstück zu `hintOnlyWhenTheRuleChangesSomething` (Android).
final class EveningDarkHintPhoneTests: XCTestCase {
    func testHintOnlyWhenTheRuleChangesSomething() {
        // Nur, wenn die App ohne die Regel hell wäre: «Hell», oder «System» bei hellem System.
        XCTAssertTrue(ThemeMode.light.eveningChangesTheme(evening: true, systemDark: false))
        XCTAssertTrue(ThemeMode.light.eveningChangesTheme(evening: true, systemDark: true))
        XCTAssertTrue(ThemeMode.system.eveningChangesTheme(evening: true, systemDark: false))
        XCTAssertFalse(ThemeMode.system.eveningChangesTheme(evening: true, systemDark: true))
        XCTAssertFalse(ThemeMode.dark.eveningChangesTheme(evening: true, systemDark: false))
        for mode in ThemeMode.allCases {
            for system in [false, true] {
                XCTAssertFalse(mode.eveningChangesTheme(evening: false, systemDark: system), "\(mode) \(system)")
            }
        }
    }

    func testEveningStillDarkensTheApp() {
        // Das Design selbst bleibt: abends dunkel bei «System» und «Hell», sonst die Wahl.
        XCTAssertEqual(ThemeMode.system.colorScheme(evening: true), .dark)
        XCTAssertEqual(ThemeMode.light.colorScheme(evening: true), .dark)
        XCTAssertNil(ThemeMode.system.colorScheme(evening: false))
        XCTAssertEqual(ThemeMode.light.colorScheme(evening: false), .light)
    }
}
