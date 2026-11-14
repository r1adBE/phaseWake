import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Prüfrunde 2, iPhone-Start vor dem ersten Entsperren (HG-1/SP-1, SP-2): Lernphase und Lern-Übersicht weder aus dem
/// leeren Stand stempeln noch schreiben, gemerkte Meldungen danach aufnehmen; Feiertagswahl nachlesen. Eine Datei mit
/// einem Ordner an ihrer Stelle steht für die geschützte Datei (wie `Audit93PhoneTests`).
final class LockedStartPhoneTests: XCTestCase {
    private var dir: URL!

    override func setUp() {
        super.setUp()
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("locked-phone-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: dir)
        super.tearDown()
    }

    /// Macht `url` unlesbar; der Aufruf von `restore` macht sie wieder lesbar.
    private func lock(_ url: URL) throws -> () throws -> Void {
        let aside = url.appendingPathExtension("aside")
        try FileManager.default.moveItem(at: url, to: aside)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return {
            try FileManager.default.removeItem(at: url)
            try FileManager.default.moveItem(at: aside, to: url)
        }
    }

    /// HG-1/SP-1: Stand nicht lesbar → kein Stempel «jetzt» (die Uhr übernähme sonst «Lernphase aus, keine Werte»),
    /// keine Änderung; danach gilt der gespeicherte Stand unverändert.
    @MainActor
    func testUnreadableLearningIsNotStamped() throws {
        var real = LearningState()
        _ = real.setConsent(true, now: Date(timeIntervalSince1970: 1_793_000_000))
        let url = dir.appendingPathComponent("learning.v1")
        try JSONEncoder().encode(real).write(to: url)
        let restore = try lock(url)

        let learning = PhoneLearning(directory: dir)
        XCTAssertFalse(learning.loaded)
        XCTAssertEqual(learning.watchSnapshot().updatedAtMs, 0, "nicht gestempelt")
        learning.setSeen()
        XCTAssertFalse(learning.state.seen)
        try restore()

        XCTAssertTrue(learning.load())
        XCTAssertEqual(learning.state, real)
        XCTAssertEqual(learning.watchSnapshot(), real.snapshot)
    }

    /// Datei fehlt (neue Installation): leerer Stand gilt als gelesen und wird wie bisher gestempelt (Fix S-3).
    @MainActor
    func testMissingLearningIsStamped() {
        let learning = PhoneLearning(directory: dir)
        XCTAssertTrue(learning.loaded)
        XCTAssertGreaterThan(learning.watchSnapshot().updatedAtMs, 0)
    }

    /// SP-1: Meldung der Uhr vor dem Lesen der Lern-Übersicht ersetzt die gespeicherten nicht; danach sind beide da.
    @MainActor
    func testLearnLogKeepsSkipsReceivedBeforeRead() throws {
        let first = LearnSkip(target: Date(timeIntervalSince1970: 1_800_000_000), reason: .sensors)
        let second = LearnSkip(target: Date(timeIntervalSince1970: 1_800_086_400), reason: .battery, battery: 20)
        LearnLogStore(directory: dir).receive(first.encode())
        let restore = try lock(dir.appendingPathComponent("learn-log.v1"))

        let log = LearnLogStore(directory: dir)
        XCTAssertFalse(log.loaded)
        log.receive(second.encode())
        XCTAssertEqual(log.skips, [])
        try restore()

        XCTAssertTrue(log.load())
        XCTAssertEqual(log.skips, [first, second])
        XCTAssertEqual(LearnLogStore(directory: dir).skips, [first, second])
    }

    /// SP-2: Beim Start galt das Land des Geräts; nach dem Lesen die gespeicherte Wahl, alte Feiertage verworfen.
    @MainActor
    func testHolidayChoiceReloadedAfterUnlock() throws {
        let suite = "locked-holidays-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = HolidayStore(defaults: defaults)
        let device = store.country
        store.adopt(lastSent: HolidayList(region: Holidays.regionCode(country: device, region: ""), dates: ["2026-08-01"]))
        let chosen = device == "DE" ? "AT" : "DE"
        defaults.set(chosen, forKey: "holidayCountry")
        defaults.set("BY", forKey: "holidayRegion")

        XCTAssertFalse(store.dates.isEmpty)
        XCTAssertTrue(store.reloadChoice(defaults))
        XCTAssertEqual(store.country, chosen)
        XCTAssertEqual(store.region, "BY")
        XCTAssertEqual(store.dates, [])
        XCTAssertFalse(store.reloadChoice(defaults), "unverändert: nichts zu tun")
    }
}
