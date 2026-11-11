import XCTest
import PhaseWakeCore

/// Diagnose-Bericht der Apple Watch ans iPhone (wie Android `WatchReportTest`): Ein voller Bericht passt nicht in die
/// Antwort von WatchConnectivity und geht als Datei; die Datei kommt vollständig an, über der Grenze nicht.
final class DiagTransferTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("diag-transfer-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try FileManager.default.removeItem(at: directory)
    }

    /// Voller Bericht: `DiagLog.maxEntries` Einträge mit dem längsten Text (auch Zeichen mit mehreren Bytes).
    private func fullReport() -> DiagReport {
        let startMs: Int64 = 1_794_348_000_000
        let kinds = DiagKind.allCases
        let entries = (0..<DiagLog.maxEntries).map { i in
            let detail = String(String(repeating: "\(i) record ä–ö phase light sleep window ", count: 10).prefix(DiagLog.maxDetail))
                .trimmingCharacters(in: .whitespaces)
            return DiagEntry(at: Date(timeIntervalSince1970: Double(startMs + Int64(i) * 60_000) / 1000), device: .watch,
                             kind: kinds[i % kinds.count], detail: detail)
        }
        let status = [(key: "version", value: "16.2.2 (82)"), (key: "next", value: "2026-11-12T05:30:00Z"), (key: "battery", value: "64")]
        return DiagReport(status: status, entries: entries)
    }

    func testFullReportGoesAsFileAndRoundTrips() throws {
        let report = fullReport()
        let text = DiagReports.encode(report)
        // Über der Grenze einer Antwort (WatchConnectivity etwa 65 KB): Auf dem früheren Weg ging er verloren.
        XCTAssertGreaterThan(text.utf8.count, 65 * 1024)
        XCTAssertFalse(DiagTransfer.fitsReply(text))
        let url = try DiagTransfer.write(text, in: directory)
        XCTAssertEqual(DiagTransfer.read(fileAt: url), report)
    }

    func testSmallReportFitsTheReply() {
        let report = DiagReport(status: [(key: "version", value: "16.2.2 (83)")], entries: Array(fullReport().entries.prefix(3)))
        XCTAssertTrue(DiagTransfer.fitsReply(DiagReports.encode(report)))
    }

    func testFileAboveTheLimitOrForeignIsRejected() throws {
        let url = try DiagTransfer.write(DiagReports.encode(fullReport()), in: directory)
        XCTAssertNil(DiagTransfer.read(fileAt: url, max: 10_000))
        XCTAssertNil(DiagTransfer.read(fileAt: try DiagTransfer.write("anders", in: directory)))
        XCTAssertNil(DiagTransfer.read(fileAt: directory.appendingPathComponent("fehlt.txt")))
    }
}
