import XCTest
import PhaseWakeCore

/// Prüft alle gemeinsamen Testnächte; dieselben Nächte prüft die Kotlin-Umsetzung.
final class SharedNightsTests: XCTestCase {

    func testAllNightsMatchExpectation() throws {
        let nights = try NightFixtures.all()
        // Genau die Nächte laut MANIFEST: Eine fehlende Datei fiele sonst nicht auf.
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
        // Die Leicht-Regel wartet ohne Grundrauschen: Ohne gelernten Wert zählt das Rauschen zu
        // Beginn nicht mehr (früher Epoche 5), es weckt wie mit gelerntem Wert erst die echte Bewegung.
        XCTAssertEqual(firstWake(byName["learned_floor_sensor_start"]), 32)
        XCTAssertNil(firstWake(byName["learned_resting_hr"]))
        XCTAssertNotNil(byName["learned_resting_hr"])
        // Ohne Obergrenze aus dem gelernten Grundrauschen bleibt der unruhige Beginn still.
        XCTAssertNil(firstWake(byName["restless_start_learned_floor"]))
        XCTAssertNotNil(byName["restless_start_learned_floor"])
        // Ohne Übergehen weckt der erste Moment (Regel S5: die erste starke Bewegung).
        XCTAssertEqual(firstWake(byName["rearm_new_hits"]), 38)
        // Ohne gelerntes Grundrauschen hebt das Fenster die Leicht-Schwelle wie früher: erst zur Zielzeit.
        XCTAssertNil(firstWake(byName["real_window_7"]))
        XCTAssertNotNil(byName["real_window_7"])
    }

    /// Echte Nacht 19.11.2026: Nur übergehen (ohne «Wach geht vor») weckte erst Epoche 40; mit ihr Epoche 38 `awake`.
    func testAwakeDuringBlockDecidesTheRealNight() throws {
        let night = try XCTUnwrap(try NightFixtures.all().first { $0.name == "real_window_8" })
        let detector = WakeDetector(config: night.config)
        var first: Int?
        for (index, epoch) in night.epochs.enumerated() {
            guard case .wake = detector.onEpoch(epoch) else { continue }
            if index < 40 {
                XCTAssertTrue(index == 38 || !detector.awakeDuringBlock(), "Epoche \(index)")
                detector.rearm()
                continue
            }
            first = index
            break
        }
        XCTAssertEqual(first, 40)
        XCTAssertEqual(night.firstWake()?.index, 38)
        XCTAssertEqual(night.firstWake()?.reason, .awake)
    }
}
