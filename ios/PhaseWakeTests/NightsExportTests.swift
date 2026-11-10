import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Export des Verlaufs ([NightsExport], Audit F-03, wie `Export.kt` auf Android): immer die Datei der Nächte, ausser es
/// gibt nur Aufzeichnungen; die Aufzeichnungen der Uhr nur abgeschlossen und in eigener Datei; Aufräumen der temporären
/// Dateien.
final class NightsExportTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_793_000_000)

    private var finished: NightRecording {
        NightRecording(start: start, epochs: [Epoch(motion: 0.01, heartRate: 55), Epoch(motion: nil, heartRate: nil)],
                       end: start.addingTimeInterval(60), endReason: .wake)
    }

    private var running: NightRecording {
        NightRecording(start: start.addingTimeInterval(86400), epochs: [Epoch(motion: 0.02, heartRate: nil)])
    }

    private var night: NightRecord {
        NightRecord(alarmId: "a", windowStart: start, target: start.addingTimeInterval(1800), wokeAt: start.addingTimeInterval(1200),
                    reason: .lightSleep, snoozes: 1, epochs: [Epoch(motion: 0.01, heartRate: 52)])
    }

    func testNightsFileEvenWhenEmpty() {
        let files = NightsExport.files(nights: [], notes: [:], recordings: [running])
        XCTAssertEqual(files.count, 1)
        guard case .nights(let nights, let notes) = files[0].content else { return XCTFail("Datei der Nächte erwartet") }
        XCTAssertTrue(nights.isEmpty)
        XCTAssertTrue(notes.isEmpty)
    }

    func testOnlyFinishedRecordingsInTheirOwnFile() {
        let files = NightsExport.files(nights: [], notes: [:], recordings: [running, finished])
        XCTAssertEqual(files.count, 1)
        guard case .recordings(let recordings) = files[0].content else { return XCTFail("Datei der Aufzeichnungen erwartet") }
        XCTAssertEqual(recordings, [finished])
        // Inhalt: Kopf, je Epoche eine Zeile mit Grund des Endes.
        let lines = NightRecording.exportCsv(recordings).split(separator: "\n")
        XCTAssertEqual(lines.first, "recording,end_reason,epoch,motion,hr")
        XCTAssertEqual(lines.count, 1 + finished.epochs.count)
        XCTAssertTrue(lines.dropFirst().allSatisfy { $0.contains(",wake,") })
    }

    func testNightsAndRecordingsTogether() {
        let notes = ["2026-10-26": ["coffee"]]
        let files = NightsExport.files(nights: [night], notes: notes, recordings: [finished])
        XCTAssertEqual(files.count, 2)
        guard case .nights(let nights, let exportedNotes) = files[0].content,
              case .recordings(let recordings) = files[1].content else { return XCTFail("Nächte, dann Aufzeichnungen erwartet") }
        XCTAssertEqual(nights, [night])
        XCTAssertEqual(exportedNotes, notes)
        XCTAssertEqual(recordings, [finished])
        let csv = NightHistory.exportCsv(nights, notes: exportedNotes)
        XCTAssertTrue(csv.hasPrefix("night,window_minutes,reason,woke_minutes_before_target,snoozes,feeling,epoch,motion,hr,notes\n"))
    }

    func testCleanUpRemovesExportsAndTheOldFile() throws {
        let manager = FileManager.default
        let folder = manager.temporaryDirectory.appendingPathComponent("export/\(UUID().uuidString)", isDirectory: true)
        try manager.createDirectory(at: folder, withIntermediateDirectories: true)
        try "x".write(to: folder.appendingPathComponent("phasewake-nights.csv"), atomically: true, encoding: .utf8)
        // Datei früherer Versionen direkt in den temporären Dateien.
        let legacy = manager.temporaryDirectory.appendingPathComponent("phasewake-nights.csv")
        try "x".write(to: legacy, atomically: true, encoding: .utf8)

        // Mit Mindestalter bleibt ein eben geschriebener Export (die andere Datei desselben Teilens).
        NightsExport.cleanUp(olderThan: 3600)
        XCTAssertTrue(manager.fileExists(atPath: folder.path))
        XCTAssertFalse(manager.fileExists(atPath: legacy.path))

        NightsExport.cleanUp()
        XCTAssertFalse(manager.fileExists(atPath: folder.path))
    }
}
