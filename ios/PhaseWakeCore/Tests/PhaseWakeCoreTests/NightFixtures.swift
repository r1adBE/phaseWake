import Foundation
import PhaseWakeCore

/// Eine Testnacht aus `shared/testdata`.
struct NightFixture {
    let name: String
    let expectedIndex: Int?
    let expectedReason: WakeReason?
    let epochs: [Epoch]
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

    static func load(_ file: URL) throws -> NightFixture {
        let name = file.deletingPathExtension().lastPathComponent
        var expectedIndex: Int?
        var expectedReason: WakeReason?
        var expectSeen = false
        var headerSeen = false
        var epochs: [Epoch] = []

        let lines = try String(contentsOf: file, encoding: .utf8).components(separatedBy: .newlines)
        for (lineNumber, raw) in lines.enumerated() {
            let line = raw.trimmingCharacters(in: .whitespaces)
            let position = "\(file.lastPathComponent):\(lineNumber + 1)"
            if line.isEmpty || line.hasPrefix("#") { continue }
            if line.hasPrefix("@expect") {
                let parts = line.split(whereSeparator: \.isWhitespace).map(String.init)
                if parts.count >= 2, parts[1] != "none" {
                    guard parts.count == 3, let index = Int(parts[1]), let reason = WakeReason(rawValue: parts[2]) else {
                        throw FormatError(description: "\(position): ungültiges @expect")
                    }
                    expectedIndex = index
                    expectedReason = reason
                }
                expectSeen = true
                continue
            }
            if !headerSeen {
                guard line == "motion,hr" else { throw FormatError(description: "\(position): Kopfzeile 'motion,hr' erwartet") }
                headerSeen = true
                continue
            }
            let fields = line.split(separator: ",", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
            guard fields.count == 2 else { throw FormatError(description: "\(position): zwei Felder erwartet") }
            epochs.append(Epoch(motion: try number(fields[0], position), heartRate: try number(fields[1], position)))
        }
        guard expectSeen else { throw FormatError(description: "\(file.lastPathComponent): @expect fehlt") }
        return NightFixture(name: name, expectedIndex: expectedIndex, expectedReason: expectedReason, epochs: epochs)
    }

    private static func number(_ field: String, _ position: String) throws -> Double? {
        if field.isEmpty { return nil }
        guard let value = Double(field) else { throw FormatError(description: "\(position): '\(field)' ist keine Zahl") }
        return value
    }
}
