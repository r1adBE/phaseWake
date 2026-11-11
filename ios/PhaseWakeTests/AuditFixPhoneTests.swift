import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Vollaudit 071597f, iPhone: Schrift je Textfläche auf dem Sonnenaufgang samt Fläche dahinter (N-3, Design 5),
/// «Weiter» nur ohne Wecker (N-1), «Verlauf löschen» nie in der Zukunft (DA-1), Wiederherstellung ohne Nächte im
/// Arbeitsordner (DA-5), nachgereichter Start der Aufzeichnung (AP2-10), Bild je Einrichtungsschritt (Design 2).
final class AuditFixPhoneTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_793_000_000)

    // MARK: N-3, Design 5

    func testInkKeepsContrastOnEveryTextAreaWithPlaque() {
        // Ausdrücklich typisiert und in Schritten gebaut: sonst braucht der Swift-Compiler zu lange für die Typen.
        var areas: [SunriseSky.Area] = []
        let widths: [Double] = [0.3, 0.6, 0.9]
        for row in 0...19 {
            let top: Double = Double(row) * 0.05
            let bottom: Double = min(1.0, top + 0.08)
            for width in widths {
                let left: Double = (1.0 - width) / 2.0
                let right: Double = (1.0 + width) / 2.0
                areas.append(SunriseSky.Area(left: left, top: top, right: right, bottom: bottom))
            }
        }
        for step in 0...50 {
            let p = Double(step) / 50
            let aspects: [Double] = [1.8, SunriseSky.portrait, 2.2]
            for aspect in aspects {
                for area in areas {
                    let ink = SunriseSky.ink(p, area: area, aspect: aspect)
                    for background in SunriseSky.samples(p, area: area, aspect: aspect) {
                        let behind = SunriseSky.behindText(background, content: ink.content)
                        XCTAssertGreaterThanOrEqual(SunriseSky.contrast(ink.content, behind), SunriseSky.minContrast, "\(p) \(area)")
                        XCTAssertGreaterThanOrEqual(SunriseSky.contrast(ink.muted, behind), SunriseSky.minContrast, "muted \(p) \(area)")
                    }
                }
            }
        }
    }

    func testInkFollowsTheTextArea() {
        // Mitten im Sonnenaufgang: oben noch dunkler Himmel (helle Schrift), unten über der Sonne dunkle Schrift.
        let top = SunriseSky.Area(left: 0.2, top: 0.05, right: 0.8, bottom: 0.12)
        let bottom = SunriseSky.Area(left: 0.3, top: 0.88, right: 0.7, bottom: 0.95)
        XCTAssertEqual(SunriseSky.ink(0.6, area: top).content, SunriseSky.light)
        XCTAssertEqual(SunriseSky.ink(0.6, area: bottom).content, SunriseSky.dark)
        XCTAssertEqual(SunriseSky.Area.of(CGRect(x: 10, y: 20, width: 50, height: 10), in: CGRect(x: 0, y: 0, width: 100, height: 200)),
                       SunriseSky.Area(left: 0.1, top: 0.1, right: 0.6, bottom: 0.15))
        XCTAssertEqual(SunriseSky.Area.of(.zero, in: .zero), .center)
    }

    // MARK: N-1

    @MainActor
    func testOnboardingNextNeverCreatesASecondAlarm() {
        XCTAssertTrue(OnboardingView.createsOnNext(step: OnboardingView.alarmStep, created: false, hasAlarms: false, canAdd: true))
        // «Einrichtung wiederholen» mit vorhandenen Weckern: «Weiter» legt keinen weiteren an.
        XCTAssertFalse(OnboardingView.createsOnNext(step: OnboardingView.alarmStep, created: false, hasAlarms: true, canAdd: true))
    }

    // MARK: DA-1

    func testHistoryClearedAtIsNeverInTheFuture() {
        let nowMs = TimeBounds.millis(now)
        XCTAssertEqual(HistoryClear.clamped(ms: nowMs - 1000, now: now), nowMs - 1000)
        XCTAssertEqual(HistoryClear.clamped(ms: nowMs + 86_400_000, now: now), nowMs)
        XCTAssertNil(HistoryClear.clamped(ms: Int64.max, now: now))
        XCTAssertNil(HistoryClear.clamped(ms: -1, now: now))
        XCTAssertNil(HistoryClear.clamped(ms: nil, now: now))
    }

    // MARK: DA-5

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

    // MARK: AP2-10

    func testUnansweredRecordStartIsResentWithinTenMinutes() {
        let tap = now.addingTimeInterval(-300)
        XCTAssertTrue(RecordRetry.resend(unanswered: true, tapAt: tap, now: now))
        XCTAssertFalse(RecordRetry.resend(unanswered: false, tapAt: tap, now: now))
        XCTAssertFalse(RecordRetry.resend(unanswered: true, tapAt: nil, now: now))
        XCTAssertFalse(RecordRetry.resend(unanswered: true, tapAt: now.addingTimeInterval(-RecordRetry.window - 1), now: now))
    }

    // MARK: Design 2

    func testOnboardingGlyphMovesWithTheSteps() {
        // Schritte ab 0 (wie die Ansicht); Grenzen mit kleiner Toleranz (0.1 + 0.8 rechnet als 0.9000000000000001).
        let positions = (0...6).map { OnboardingGlyph.position(step: $0, steps: 6) }
        XCTAssertEqual(positions, positions.sorted())
        XCTAssertTrue(positions.allSatisfy { $0 >= 0.1 - 1e-9 && $0 <= 0.9 + 1e-9 }, "\(positions)")
        XCTAssertEqual(positions.first ?? 0, 0.1, accuracy: 1e-9)
        XCTAssertEqual(positions.last ?? 0, 0.9, accuracy: 1e-9)
    }
}
