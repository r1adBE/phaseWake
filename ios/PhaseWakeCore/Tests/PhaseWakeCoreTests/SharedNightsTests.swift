import XCTest
import PhaseWakeCore

/// Prüft alle gemeinsamen Testnächte; dieselben Nächte prüft die Kotlin-Umsetzung.
final class SharedNightsTests: XCTestCase {

    func testAllNightsMatchExpectation() throws {
        let nights = try NightFixtures.all()
        // Genau die Nächte laut MANIFEST (Vollaudit K-8): Eine fehlende Datei fiele sonst nicht auf.
        XCTAssertEqual(nights.count, try NightFixtures.manifest().filter { $0.hasSuffix(".night") }.count,
                       "Testnächte in \(NightFixtures.directory.path)")

        for night in nights {
            let wake = night.firstWake()
            XCTAssertEqual(wake?.index, night.expectedIndex, "\(night.name): Weck-Epoche")
            XCTAssertEqual(wake?.reason, night.expectedReason, "\(night.name): Grund")
        }
    }

    func testFolderMatchesTheManifest() throws {
        let manifest = try NightFixtures.manifest()
        XCTAssertEqual(try NightFixtures.files(), manifest.sorted(), "Dateien in shared/testdata und MANIFEST")
        XCTAssertEqual(Set(manifest).count, manifest.count, "MANIFEST ohne doppelte Einträge")
    }

    /// Ohne gelernte Werte entscheiden die Lernphasen-Nächte anders: Das zeigt, dass die Angaben wirken.
    func testLearnedNightsDependOnTheLearnedValues() throws {
        let byName = Dictionary(uniqueKeysWithValues: try NightFixtures.all().map { ($0.name, $0) })
        func firstWake(_ night: NightFixture?) -> Int? {
            let detector = WakeDetector()
            for (index, epoch) in (night?.epochs ?? []).enumerated() {
                if case .wake = detector.onEpoch(epoch) { return index }
            }
            return nil
        }
        // Seit E-1 (Audit c3b8b01) wartet die Leicht-Regel ohne Grundrauschen: Ohne gelernten Wert zählt das Rauschen zu
        // Beginn nicht mehr (früher Epoche 5), es weckt wie mit gelerntem Wert erst die echte Bewegung.
        XCTAssertEqual(firstWake(byName["learned_floor_sensor_start"]), 32)
        XCTAssertNil(firstWake(byName["learned_resting_hr"]))
        XCTAssertNotNil(byName["learned_resting_hr"])
        // Ohne Obergrenze aus dem gelernten Grundrauschen bleibt der unruhige Beginn still (Vollaudit K-1).
        XCTAssertNil(firstWake(byName["restless_start_learned_floor"]))
        XCTAssertNotNil(byName["restless_start_learned_floor"])
        // Ohne Übergehen weckt der erste Moment (Vollaudit K-5).
        XCTAssertEqual(firstWake(byName["rearm_new_hits"]), 39)
    }
}
