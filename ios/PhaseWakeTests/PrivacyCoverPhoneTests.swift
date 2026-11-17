import SwiftUI
import UIKit
import XCTest
@testable import PhaseWake

/// Abdeckung über der App, solange sie nicht aktiv ist (Vorschaubild im App-Umschalter ohne Schlafdaten).
@MainActor
final class PrivacyCoverPhoneTests: XCTestCase {
    func testCoverLiesOverTheAppUntilItIsActiveAgain() throws {
        try XCTSkipIf(UIApplication.shared.connectedScenes.isEmpty, "ohne Fenster-Szene (Test ohne App) nichts abzudecken")
        defer { PrivacyCover.update(.active) }
        XCTAssertFalse(PrivacyCover.isShown)
        PrivacyCover.update(.inactive)
        XCTAssertTrue(PrivacyCover.isShown, "App-Umschalter: abgedeckt")
        PrivacyCover.update(.background)
        XCTAssertTrue(PrivacyCover.isShown, "Hintergrund: bleibt abgedeckt (kein zweites Fenster)")
        let covers = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows)
            .filter { !$0.isHidden && $0.windowLevel.rawValue > UIWindow.Level.alert.rawValue }
        XCTAssertEqual(covers.count, 1, "genau eine Abdeckung über allem")
        PrivacyCover.update(.active)
        XCTAssertFalse(PrivacyCover.isShown, "wieder aktiv: Inhalt sichtbar")
    }
}
