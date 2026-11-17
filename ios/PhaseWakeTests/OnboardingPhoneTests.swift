import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Ersteinrichtung, iPhone: «Weiter» legt den gezeigten Wecker einmal an und nie einen zweiten, Doppeltipp nach einem
/// Seitenwechsel (Gegenstück auf Android: `DoubleTapTest`), Bild je Schritt.
final class OnboardingPhoneTests: XCTestCase {
    @MainActor
    func testOnboardingNextCreatesTheShownAlarmOnce() {
        XCTAssertTrue(OnboardingView.createsOnNext(step: OnboardingView.alarmStep, created: false, hasAlarms: false, canAdd: true))
        // Schon angelegt, kein Platz mehr oder ein anderer Schritt: nichts.
        XCTAssertFalse(OnboardingView.createsOnNext(step: OnboardingView.alarmStep, created: true, hasAlarms: false, canAdd: true))
        XCTAssertFalse(OnboardingView.createsOnNext(step: OnboardingView.alarmStep, created: false, hasAlarms: false, canAdd: false))
        XCTAssertFalse(OnboardingView.createsOnNext(step: OnboardingView.alarmStep + 1, created: false, hasAlarms: false, canAdd: true))
        XCTAssertFalse(OnboardingView.createsOnNext(step: OnboardingView.alarmStep - 1, created: false, hasAlarms: false, canAdd: true))
    }

    @MainActor
    func testOnboardingNextNeverCreatesASecondAlarm() {
        XCTAssertTrue(OnboardingView.createsOnNext(step: OnboardingView.alarmStep, created: false, hasAlarms: false, canAdd: true))
        // «Einrichtung wiederholen» mit vorhandenen Weckern: «Weiter» legt keinen weiteren an.
        XCTAssertFalse(OnboardingView.createsOnNext(step: OnboardingView.alarmStep, created: false, hasAlarms: true, canAdd: true))
    }

    /// Ein zweiter Tipp innerhalb der Sperre nach einem Seitenwechsel zählt nicht (sonst traf er «Später»).
    @MainActor
    func testOnboardingIgnoresSecondTapRightAfterPageChange() {
        let tapped: TimeInterval = 100
        let locked = tapped + OnboardingView.tapLock
        XCTAssertTrue(OnboardingView.tapAllowed(now: tapped, lockedUntil: 0))
        XCTAssertFalse(OnboardingView.tapAllowed(now: tapped + 0.15, lockedUntil: locked))
        XCTAssertTrue(OnboardingView.tapAllowed(now: tapped + OnboardingView.tapLock, lockedUntil: locked))
        XCTAssertGreaterThanOrEqual(OnboardingView.tapLock, 0.3)
    }

    func testOnboardingGlyphMovesWithTheSteps() {
        // Schritte ab 0 (wie die Ansicht); Grenzen mit kleiner Toleranz (0.1 + 0.8 rechnet als 0.9000000000000001).
        let positions = (0...6).map { OnboardingGlyph.position(step: $0, steps: 6) }
        XCTAssertEqual(positions, positions.sorted())
        XCTAssertTrue(positions.allSatisfy { $0 >= 0.1 - 1e-9 && $0 <= 0.9 + 1e-9 }, "\(positions)")
        XCTAssertEqual(positions.first ?? 0, 0.1, accuracy: 1e-9)
        XCTAssertEqual(positions.last ?? 0, 0.9, accuracy: 1e-9)
    }
}
