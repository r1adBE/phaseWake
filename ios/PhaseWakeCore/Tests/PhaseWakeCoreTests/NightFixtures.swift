import Foundation
import PhaseWakeCore

/// Eine Testnacht aus `shared/testdata`.
struct NightFixture {
    let name: String
    let expectedIndex: Int?
    let expectedReason: WakeReason?
    let epochs: [Epoch]
    /// Startwerte, mit den gelernten Werten aus `@floor` und `@resting` (Lernphase) und dem Grundrauschen der
    /// Einschlaf-Messung dieser Nacht aus `@onset-floor` (``DetectorConfig/withOnsetFloor(_:)``), falls angegeben.
    let config: DetectorConfig
    /// `@rearm-until`: Jeder Moment vor dieser Epoche wird übergangen (``WakeDetector/onBlocked()``, kurze Nacht),
    /// ausser die Person ist wach; `nil` = keiner.
    var rearmUntil: Int? = nil

    /// Erste weckende Epoche und Grund mit `config` und `rearmUntil`; `nil`, wenn keine weckt. Wie `firstWake`
    /// (Kotlin).
    func firstWake() -> (index: Int, reason: WakeReason)? {
        let detector = WakeDetector(config: config)
        for (index, epoch) in epochs.enumerated() {
            guard case .wake(let reason) = detector.onEpoch(epoch) else { continue }
            if let rearmUntil, index < rearmUntil {
                // Kurze Nacht: übergangen, ausser die Person ist wach («Wach geht vor»).
                guard let awake = detector.onBlocked() else { continue }
                return (index, awake)
            }
            return (index, reason)
        }
        return nil
    }
}

/// Liest die gemeinsamen Testnächte (Format siehe `docs/PHASENERKENNUNG.md`, Abschnitt 5).
enum NightFixtures {
    struct FormatError: Error, CustomStringConvertible {
        let description: String
    }

    /// `ios/PhaseWakeCore/Tests/PhaseWakeCoreTests/` → Repo-Wurzel → `shared/testdata`.
    static var directory: URL {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        return url.appendingPathComponent("shared/testdata", isDirectory: true)
    }

    static func all() throws -> [NightFixture] {
        try FileManager.default
            .contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "night" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
            .map(load)
    }

    /// Dateien laut `MANIFEST` (eine je Zeile, `#` = Kommentar); Tests prüfen, dass genau diese da sind.
    static func manifest() throws -> [String] {
        try String(contentsOf: directory.appendingPathComponent("MANIFEST"), encoding: .utf8)
            .components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && !$0.hasPrefix("#") }
    }

    /// Alle Dateien des Ordners ausser `MANIFEST`, `README.md` und versteckten, sortiert.
    static func files() throws -> [String] {
        try FileManager.default.contentsOfDirectory(atPath: directory.path)
            .filter { $0 != "MANIFEST" && $0 != "README.md" && !$0.hasPrefix(".") }
            .sorted()
    }

    static func load(_ file: URL) throws -> NightFixture {
        let name = file.deletingPathExtension().lastPathComponent
        var expectedIndex: Int?
        var expectedReason: WakeReason?
        var expectSeen = false
        var headerSeen = false
        var personalFloor: Double?
        var restingHeartRate: Double?
        var onsetFloor: Double?
        var rearmUntil: Int?
        var epochs: [Epoch] = []
        let lines = try String(contentsOf: file, encoding: .utf8).components(separatedBy: .newlines)
        for (lineNumber, raw) in lines.enumerated() {
            let line = raw.trimmingCharacters(in: .whitespaces)
            let position = "\(file.lastPathComponent):\(lineNumber + 1)"
            if line.isEmpty || line.hasPrefix("#") { continue }
            if line.hasPrefix("@expect") {
                if let expected = try expectation(line, position) {
                    expectedIndex = expected.index
                    expectedReason = expected.reason
                }
                expectSeen = true
                continue
            }
            if line.hasPrefix("@rearm-until") {
                let parts = line.split(whereSeparator: \.isWhitespace).map(String.init)
                guard parts.count == 2, let value = Int(parts[1]) else {
                    throw FormatError(description: "\(position): ungültiges @rearm-until")
                }
                rearmUntil = value
                continue
            }
            if line.hasPrefix("@floor") || line.hasPrefix("@resting") || line.hasPrefix("@onset-floor") {
                let (key, value) = try setting(line, position)
                switch key {
                case "@floor": personalFloor = value
                case "@onset-floor": onsetFloor = value
                case "@resting": restingHeartRate = value
                default: throw FormatError(description: "\(position): unbekannte Angabe \(key)")
                }
                continue
            }
            if !headerSeen {
                guard line == "motion,hr"
                else { throw FormatError(description: "\(position): Kopfzeile 'motion,hr' erwartet") }
                headerSeen = true
                continue
            }
            let fields = line.split(separator: ",", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
            guard fields.count == 2 else { throw FormatError(description: "\(position): zwei Felder erwartet") }
            epochs.append(Epoch(motion: try number(fields[0], position), heartRate: try number(fields[1], position)))
        }
        guard expectSeen else { throw FormatError(description: "\(file.lastPathComponent): @expect fehlt") }
        let config = try DetectorConfig(personalFloor: personalFloor,
                                        restingHeartRate: restingHeartRate).withOnsetFloor(onsetFloor)
        return NightFixture(
            name: name, expectedIndex: expectedIndex, expectedReason: expectedReason, epochs: epochs, config: config,
            rearmUntil: rearmUntil
        )
    }

    /// `@expect <Epoche> <Grund>` oder `@expect none`: erwartete weckende Epoche, `nil` = keine.
    private static func expectation(_ line: String, _ position: String) throws -> (index: Int, reason: WakeReason)? {
        let parts = line.split(whereSeparator: \.isWhitespace).map(String.init)
        guard parts.count >= 2, parts[1] != "none" else { return nil }
        guard parts.count == 3, let index = Int(parts[1]), let reason = WakeReason(rawValue: parts[2]) else {
            throw FormatError(description: "\(position): ungültiges @expect")
        }
        return (index, reason)
    }

    /// Angabe `@name <Zahl>` (z. B. `@floor 0.02`) als Name und Wert.
    private static func setting(_ line: String, _ position: String) throws -> (key: String, value: Double) {
        let parts = line.split(whereSeparator: \.isWhitespace).map(String.init)
        guard parts.count == 2, let value = Double(parts[1]) else {
            throw FormatError(description: "\(position): ungültige Angabe \(parts.first ?? "")")
        }
        return (parts[0], value)
    }

    private static func number(_ field: String, _ position: String) throws -> Double? {
        if field.isEmpty { return nil }
        guard let value = Double(field)
        else { throw FormatError(description: "\(position): '\(field)' ist keine Zahl") }
        return value
    }
}
