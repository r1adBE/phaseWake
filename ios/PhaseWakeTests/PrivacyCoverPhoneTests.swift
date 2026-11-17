import SwiftUI
import UIKit
import XCTest
@testable import PhaseWake

/// Abdeckung über der App, solange sie nicht aktiv ist (Vorschaubild im App-Umschalter ohne Schlafdaten).
@MainActor
final class PrivacyCoverPhoneTests: XCTestCase {
    func testCoverLiesOverTheAppUntilItIsActiveAgain() throws {
        try XCTSkipIf(UIApplication.shared.connectedScenes.isEmpty,
                      "ohne Fenster-Szene (Test ohne App) nichts abzudecken")
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

    /// Eigene Abfrage (Mitteilungen, Health …): «inaktiv» deckt nicht ab, der Hintergrund schon.
    func testOwnPermissionPromptKeepsTheTextVisible() throws {
        try XCTSkipIf(UIApplication.shared.connectedScenes.isEmpty,
                      "ohne Fenster-Szene (Test ohne App) nichts abzudecken")
        defer { PrivacyCover.update(.active) }
        PermissionPrompt.begin()
        PrivacyCover.update(.inactive)
        XCTAssertFalse(PrivacyCover.isShown, "Abfrage: Text dahinter bleibt sichtbar")
        PrivacyCover.update(.background)
        XCTAssertTrue(PrivacyCover.isShown, "Hintergrund: abgedeckt, auch während der Abfrage")
        PrivacyCover.update(.active)
        PermissionPrompt.end()
        XCTAssertFalse(PermissionPrompt.isAsking)
        PrivacyCover.update(.inactive)
        XCTAssertTrue(PrivacyCover.isShown, "nach der Abfrage wieder abgedeckt")
    }

    /// Hell wie das App-Fenster (Creme statt Nachthimmel).
    func testCoverFollowsTheAppAppearance() throws {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        let window = scene?.windows.first { $0.windowLevel == .normal }
        try XCTSkipIf(window == nil, "ohne App-Fenster (Test ohne App) nichts abzudecken")
        let app = try XCTUnwrap(window)
        let before = app.overrideUserInterfaceStyle
        defer { app.overrideUserInterfaceStyle = before; PrivacyCover.update(.active) }
        app.overrideUserInterfaceStyle = .light
        PrivacyCover.update(.inactive)
        XCTAssertEqual(PrivacyCover.style, .light)
        PrivacyCover.update(.active)
        app.overrideUserInterfaceStyle = .dark
        PrivacyCover.update(.inactive)
        XCTAssertEqual(PrivacyCover.style, .dark)
    }
}
