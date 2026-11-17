import XCTest
@testable import PhaseWakeCore

/// Gemeinsame Fälle mit Kotlin (`shared/testdata/alarm-stamps.vectors`, Audit 17.11.2026 CODE-03): ``AlarmStamps/merge(local:remote:now:)``
/// je Wecker – neuere Fassung, fehlender Zeitpunkt, gleicher Zeitpunkt, Löschen gegen Bearbeiten, alte und unplausible
/// gelöschte Wecker. Jede Zeile auch mit vertauschten Seiten (beide Geräte kommen zum selben Stand). Gegenstück zu
/// `AlarmStampsVectorsTest` (Kotlin); Format im Kopf der Datei.
final class AlarmStampsVectorsTests: XCTestCase {
    private struct FormatError: Error, CustomStringConvertible {
        let description: String
    }

    func testSharedVectors() throws {
        let text = try String(contentsOf: NightFixtures.directory.appendingPathComponent("alarm-stamps.vectors"), encoding: .utf8)
        let lines = text.components(separatedBy: "\n").filter { !$0.isEmpty && !$0.hasPrefix("#") }
        for line in lines {
            let f = line.components(separatedBy: "\t")
            XCTAssertEqual(f.count, 4, line)
            guard f.count == 4 else { continue }
            XCTAssertEqual(try merged(f[0], f[1], f[2]), f[3], line)
            XCTAssertEqual(try merged(f[1], f[0], f[2]), f[3], "vertauscht: \(line)")
        }
        XCTAssertEqual(lines.count, 21)
    }

    /// Ergebnis von ``AlarmStamps/merge(local:remote:now:)`` der Stände `local` und `remote` zu `now` (ms), als Text wie
    /// in der Datei.
    private func merged(_ local: String, _ remote: String, _ now: String) throws -> String {
        guard let ms = Int64(now) else { throw FormatError(description: "now '\(now)'") }
        let mine = try snapshot(local), theirs = try snapshot(remote)
        return text(AlarmStamps.merge(local: mine, remote: theirs, now: ms))
    }

    /// «Listen-Zeitpunkt Wecker Zeitpunkte Gelöschte», mit Leerzeichen getrennt.
    private func snapshot(_ text: String) throws -> AlarmSnapshot {
        let f = text.components(separatedBy: " ")
        guard f.count == 4, let updatedAt = Int64(f[0]) else { throw FormatError(description: "Stand mit 4 Teilen erwartet: \(text)") }
        let alarms: [AlarmSpec] = try f[1] == "-" ? [] : f[1].components(separatedBy: ";").map(alarm)
        let stamps = try map(f[2]), deleted = try map(f[3])
        return AlarmSnapshot(alarms: alarms, updatedAt: updatedAt, stamps: stamps, deleted: deleted)
    }

    private func alarm(_ line: String) throws -> AlarmSpec {
        let alarms = try Codec.decodeAlarms("phasewake-alarms 1\n\(line)\n")
        guard alarms.count == 1 else { throw FormatError(description: "Weckerzeile unlesbar: \(line)") }
        return alarms[0]
    }

    private func map(_ text: String) throws -> [String: Int64] {
        if text == "-" { return [:] }
        var out: [String: Int64] = [:]
        for pair in text.components(separatedBy: ",") {
            let parts = pair.components(separatedBy: "=")
            guard parts.count == 2, let ms = Int64(parts[1]) else { throw FormatError(description: "Eintrag '\(pair)'") }
            out[parts[0]] = ms
        }
        return out
    }

    private func text(_ s: AlarmSnapshot) -> String {
        let alarms = Codec.encodeAlarms(s.alarms).components(separatedBy: "\n").dropFirst().filter { !$0.isEmpty }
        func pairs(_ m: [String: Int64]) -> String {
            m.isEmpty ? "-" : m.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: ",")
        }
        return [String(s.updatedAt), alarms.isEmpty ? "-" : alarms.joined(separator: ";"), pairs(s.stamps), pairs(s.deleted)]
            .joined(separator: " ")
    }
}
