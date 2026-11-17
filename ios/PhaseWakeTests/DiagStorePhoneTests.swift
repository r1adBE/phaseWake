import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Diagnose-Protokoll hängt an statt neu zu schreiben (wie `DiagnosticsLog` auf Android): je
/// Eintrag eine Zeile mit Umbruch, gekürzt erst über `DiagLog.maxEntries` + ``DiagStore/slack``; Dateien früherer
/// Versionen (ohne Umbruch am Ende) bleiben lesbar.
final class DiagStorePhoneTests: XCTestCase {
    private var dir: URL!

    override func setUp() {
        super.setUp()
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("diag-\(UUID().uuidString)")
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: dir)
        super.tearDown()
    }

    private var file: URL { dir.appendingPathComponent("diag.v1") }

    private func lines() throws -> [String] {
        try String(contentsOf: file, encoding: .utf8).components(separatedBy: "\n")
    }

    func testEachEntryIsAppendedAsOneLine() throws {
        let store = DiagStore(directory: dir)
        store.log(.boot, "app start")
        store.log(.sync, "sent 2 alarms")
        store.log(.error, "a|b")
        XCTAssertEqual(store.entries().map(\.kind), [.boot, .sync, .error])
        let text = try String(contentsOf: file, encoding: .utf8)
        XCTAssertTrue(text.hasSuffix("\n"))
        XCTAssertEqual(try lines().filter { !$0.isEmpty }.count, 3)
        // Eine neue Instanz liest dieselbe Datei.
        XCTAssertEqual(DiagStore(directory: dir).entries().map(\.detail), ["app start", "sent 2 alarms", "a b"])
    }

    func testOldFileWithoutTrailingBreakStaysReadable() throws {
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let now = Date()
        let old = [DiagEntry(at: now.addingTimeInterval(-120), device: DiagStore.device, kind: .boot, detail: "old 1"),
                   DiagEntry(at: now.addingTimeInterval(-60), device: DiagStore.device, kind: .ring, detail: "old 2")]
        // Format früherer Versionen: Zeilen nur getrennt, kein Umbruch am Ende.
        try Data(old.map(DiagLog.encode).joined(separator: "\n").utf8).write(to: file)
        let store = DiagStore(directory: dir)
        store.log(.stop, "new")
        XCTAssertEqual(store.entries().map(\.detail), ["old 1", "old 2", "new"])
        XCTAssertEqual(try lines(),
                       [DiagLog.encode(old[0]), DiagLog.encode(old[1]), DiagLog.encode(store.entries()[2]), ""])
        XCTAssertEqual(DiagStore(directory: dir).entries().map(\.detail), ["old 1", "old 2", "new"])
    }

    func testFileIsTrimmedOnlyAboveTheSlack() throws {
        let store = DiagStore(directory: dir)
        let limit = DiagLog.maxEntries + DiagStore.slack
        for i in 0..<limit { store.log(.phase, "e\(i)") }
        _ = store.entries()
        // Bis zur Grenze nur angehängt: Die Datei hält alle Zeilen, die Anzeige die neuesten `maxEntries`.
        XCTAssertEqual(try lines().filter { !$0.isEmpty }.count, limit)
        XCTAssertEqual(store.entries().count, DiagLog.maxEntries)
        XCTAssertEqual(store.entries().last?.detail, "e\(limit - 1)")
        // Eine Zeile mehr: gekürzt auf die neuesten `maxEntries`.
        store.log(.phase, "e\(limit)")
        _ = store.entries()
        let kept = try lines().filter { !$0.isEmpty }
        XCTAssertEqual(kept.count, DiagLog.maxEntries)
        XCTAssertEqual(kept.compactMap(DiagLog.decode).first?.detail, "e\(limit + 1 - DiagLog.maxEntries)")
        XCTAssertEqual(kept.compactMap(DiagLog.decode).last?.detail, "e\(limit)")
        // Danach wieder angehängt.
        store.log(.phase, "after")
        XCTAssertEqual(store.entries().last?.detail, "after")
        XCTAssertEqual(try lines().filter { !$0.isEmpty }.count, DiagLog.maxEntries + 1)
    }

    func testClearEmptiesTheFile() {
        let store = DiagStore(directory: dir)
        store.log(.boot, "one")
        store.clear()
        store.log(.boot, "two")
        XCTAssertEqual(store.entries().map(\.detail), ["two"])
        XCTAssertEqual(DiagStore(directory: dir).entries().map(\.detail), ["two"])
    }
}
