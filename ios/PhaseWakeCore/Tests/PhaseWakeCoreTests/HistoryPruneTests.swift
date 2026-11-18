import XCTest
import PhaseWakeCore

/// Wie `HistoryPruneTest` (Kotlin): «Älter als 90 Tage löschen».
final class HistoryPruneTests: XCTestCase {
    private let now = ISO8601DateFormatter().date(from: "2026-11-18T14:00:00Z")!
    private var zurich: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Europe/Zurich")!
        return c
    }

    private func night(_ wokeAt: Date, _ id: String) -> NightRecord {
        NightRecord(alarmId: id, windowStart: wokeAt.addingTimeInterval(-1200), target: wokeAt.addingTimeInterval(600),
                    wokeAt: wokeAt, reason: .lightSleep, snoozes: 0, epochs: [])
    }

    func testCutoffIsNinetyDaysAgo() {
        XCTAssertEqual(HistoryPrune.days, 90)
        XCTAssertEqual(HistoryPrune.cutoff(now: now), ISO8601DateFormatter().date(from: "2026-08-20T14:00:00Z"))
    }

    func testClearedAtNeverMovesBack() {
        let cutoff = HistoryPrune.cutoff(now: now)
        XCTAssertEqual(HistoryPrune.clearedAt(current: nil, cutoff: cutoff), cutoff)
        XCTAssertEqual(HistoryPrune.clearedAt(current: cutoff.addingTimeInterval(-1), cutoff: cutoff), cutoff)
        // Ein späteres «Verlauf löschen» bleibt, sonst kämen dort gelöschte Nächte aus Apple Health zurück.
        let later = now.addingTimeInterval(-3 * 86_400)
        XCTAssertEqual(HistoryPrune.clearedAt(current: later, cutoff: cutoff), later)
    }

    func testNightsEndedByTheCutoffGo() {
        let cutoff = HistoryPrune.cutoff(now: now)
        let nights = [night(cutoff.addingTimeInterval(-60), "alt"), night(cutoff, "grenze"),
                      night(cutoff.addingTimeInterval(0.001), "neu")]
        XCTAssertEqual(HistoryPrune.nights(nights, cutoff: cutoff).map(\.alarmId), ["neu"])
        let sessions = [HealthSession(start: cutoff.addingTimeInterval(-3600), end: cutoff),
                        HealthSession(start: cutoff, end: cutoff.addingTimeInterval(0.001))]
        XCTAssertEqual(HealthHistory.visible(sessions, clearedAt: HistoryPrune.clearedAt(current: nil, cutoff: cutoff)),
                       [sessions[1]])
    }

    func testDatesFromTheCutoffNightStay() {
        let cutoff = HistoryPrune.cutoff(now: now)
        // 20.08.2026 16:00 Zürich: Nachtdatum 20.08. (Abend); der 19.08. ist älter.
        let notes = ["2026-08-19": ["ALCOHOL"], "2026-08-20": ["NAP"], "2026-11-17": ["SICK"]]
        XCTAssertEqual(Set(HistoryPrune.dates(notes, cutoff: cutoff, calendar: zurich).keys),
                       ["2026-08-20", "2026-11-17"])
        XCTAssertTrue(HistoryPrune.dates([String: Int](), cutoff: cutoff, calendar: zurich).isEmpty)
    }

    /// Aufzeichnungen der noch offenen Lernphase bleiben, auch wenn sie älter als 90 Tage sind. Wie Kotlin.
    func testRecordingsOfTheOpenLearningPhaseStay() {
        let cutoff = HistoryPrune.cutoff(now: now)
        let since = cutoff.addingTimeInterval(-20 * 86_400)
        let old = since.addingTimeInterval(-86_400)
        func end(_ start: Date) -> Date { start.addingTimeInterval(8 * 3600) }
        // Ohne offene Lernphase: was bis zur Grenze endete, ist weg; laufende und neuere bleiben.
        XCTAssertFalse(HistoryPrune.keepsRecording(start: old, end: end(old), cutoff: cutoff, learningSince: nil))
        XCTAssertFalse(HistoryPrune.keepsRecording(start: since, end: end(since), cutoff: cutoff, learningSince: nil))
        XCTAssertTrue(HistoryPrune.keepsRecording(start: old, end: nil, cutoff: cutoff, learningSince: nil))
        XCTAssertTrue(HistoryPrune.keepsRecording(start: cutoff, end: end(cutoff), cutoff: cutoff, learningSince: nil))
        XCTAssertFalse(HistoryPrune.keepsRecording(start: cutoff.addingTimeInterval(-8 * 3600), end: cutoff,
                                                   cutoff: cutoff, learningSince: nil))
        // Offene Lernphase ab `since`: ihre Aufzeichnungen (Beginn ab `since`, wie Learning.counted) bleiben.
        XCTAssertTrue(HistoryPrune.keepsRecording(start: since, end: end(since), cutoff: cutoff, learningSince: since))
        XCTAssertFalse(HistoryPrune.keepsRecording(start: old, end: end(old), cutoff: cutoff, learningSince: since))
    }

    /// Offen ist die Lernphase ab ihrem Beginn, solange keine Werte da sind oder «Neu lernen» läuft.
    func testOpenLearningSince() {
        let since = now.addingTimeInterval(-86_400)
        XCTAssertNil(LearningState(since: nil, values: nil, seen: false, updatedAtMs: 0).openSince)
        XCTAssertEqual(LearningState(since: since, values: nil, seen: false, updatedAtMs: 0, consent: false).openSince,
                       since)
    }
}
