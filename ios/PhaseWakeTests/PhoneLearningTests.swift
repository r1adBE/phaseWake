import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Lernphase am iPhone (``PhoneLearning``, Audit F-03, wie `LearningFlowTest` auf Android): Beginn nur mit Zustimmung,
/// Aufzeichnungen der Uhr speichern (dieselbe zweimal nur einmal, unlesbare nicht) und zählen, «Verlauf löschen» von
/// vorn. Läuft in der App (Testwirt); ohne Apple Health bleibt die Einschlafdauer leer.
final class PhoneLearningTests: XCTestCase {

    /// Aufzeichnung ab `start` mit `epochs` Epochen mit Bewegung und Puls, abgeschlossen beim Wecken.
    private func recording(start: Date, epochs: Int) -> NightRecording {
        NightRecording(start: start, epochs: Array(repeating: Epoch(motion: 0.02, heartRate: 54), count: epochs),
                       end: start.addingTimeInterval(TimeInterval(epochs * DetectorConfig.epochSeconds)), endReason: .wake)
    }

    override func setUp() async throws {
        await MainActor.run { PhoneLearning.shared.restart() }
    }

    override func tearDown() async throws {
        await MainActor.run {
            PhoneLearning.shared.setConsent(false)
            PhoneLearning.shared.restart()
        }
    }

    @MainActor
    func testStartsOnlyWithConsent() {
        let learning = PhoneLearning.shared
        learning.setConsent(false)
        XCTAssertFalse(learning.state.active)
        XCTAssertNil(learning.progress)
        learning.setConsent(true)
        XCTAssertTrue(learning.state.active)
        XCTAssertNotNil(learning.state.since)
        XCTAssertEqual(learning.progress, 1)
        XCTAssertEqual(learning.countedNights, 0)
    }

    @MainActor
    func testRecordingIsStoredOnceAndCounted() async {
        let learning = PhoneLearning.shared
        learning.setConsent(true)
        // Ganze Sekunden: das Format speichert Millisekunden, ein Bruchteil davon ginge beim Lesen verloren.
        let start = Date(timeIntervalSince1970: (Date().timeIntervalSince1970 + 60).rounded(.down))
        let night = recording(start: start, epochs: 400)
        await learning.receive(NightRecording.encode(night))
        // Dieselbe Aufzeichnung nochmals (die Uhr sendet erneut): ersetzt, nicht doppelt.
        await learning.receive(NightRecording.encode(night))
        XCTAssertEqual(learning.recordings.map(\.start), [night.start])
        XCTAssertEqual(learning.countedNights, 1)
        XCTAssertEqual(learning.progress, 2)
        // Unlesbar: nichts geändert.
        await learning.receive("keine Aufzeichnung")
        XCTAssertEqual(learning.recordings.count, 1)
    }

    @MainActor
    func testShortRecordingIsKeptButNotCounted() async {
        let learning = PhoneLearning.shared
        learning.setConsent(true)
        await learning.receive(NightRecording.encode(recording(start: Date().addingTimeInterval(60), epochs: Learning.minSpanEpochs - 1)))
        XCTAssertEqual(learning.recordings.count, 1)
        XCTAssertEqual(learning.countedNights, 0)
    }

    @MainActor
    func testRestartForgetsRecordings() async {
        let learning = PhoneLearning.shared
        learning.setConsent(true)
        await learning.receive(NightRecording.encode(recording(start: Date().addingTimeInterval(60), epochs: 400)))
        learning.restart()
        XCTAssertTrue(learning.recordings.isEmpty)
        XCTAssertEqual(learning.countedNights, 0)
        // Zugestimmt bleibt zugestimmt: Die Lernphase läuft ab jetzt von vorn.
        XCTAssertTrue(learning.state.active)
        XCTAssertNil(learning.state.values)
    }
}
