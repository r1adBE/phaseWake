import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Prüfrunde 2 SP-7 (wie `LearningStampTest`/`PhaseWakeStoreTest` auf Android): Nach einer kaputten Datei beginnt der
/// Stand leer, die Kopie bleibt, und der leere Stand wird nicht als neuer an die Uhr geschickt. Dazu B-12: Probewecker
/// und Powernap ersetzen einander auch am iPhone.
final class ResetPhoneTests: XCTestCase {
    private var dir: URL!

    override func setUp() {
        super.setUp()
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("reset-phone-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: dir)
        super.tearDown()
    }

    /// `learning.v1` lesbar, aber kaputt: leerer Stand, Kopie `.broken` mit dem Inhalt, kein Stempel – auch nach einem
    /// Neustart nicht. Eine Änderung hier stempelt wie sonst.
    @MainActor
    func testBrokenLearningIsKeptAndNotStamped() throws {
        let url = dir.appendingPathComponent("learning.v1")
        let garbage = Data("kein json".utf8)
        try garbage.write(to: url)

        let learning = PhoneLearning(directory: dir)
        XCTAssertTrue(learning.loaded)
        XCTAssertTrue(learning.wasReset)
        XCTAssertEqual(learning.state, LearningState())
        XCTAssertEqual(try Data(contentsOf: url.appendingPathExtension("broken")), garbage)
        XCTAssertEqual(learning.watchSnapshot().updatedAtMs, 0, "leerer Stand nicht gestempelt")
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path), "nichts geschrieben")

        let again = PhoneLearning(directory: dir)
        XCTAssertTrue(again.wasReset)
        XCTAssertEqual(again.watchSnapshot().updatedAtMs, 0, "auch nach dem Neustart nicht")
        again.setConsent(true)
        XCTAssertGreaterThan(again.watchSnapshot().updatedAtMs, 0, "eine Änderung gilt wie sonst")
    }

    /// Ohne kaputte Datei bleibt es beim Stempel (Fix S-3).
    @MainActor
    func testNewLearningIsStillStamped() {
        let learning = PhoneLearning(directory: dir)
        XCTAssertFalse(learning.wasReset)
        XCTAssertGreaterThan(learning.watchSnapshot().updatedAtMs, 0)
    }

    /// Einstellungen nach einer kaputten Datei: Schlafziel und Schalter erst an die Uhr, wenn sie hier wieder gesetzt
    /// sind; ohne Neubeginn wie bisher immer.
    func testSettingsAfterResetAreOmittedUntilSet() throws {
        let url = dir.appendingPathComponent("settings.v1")
        try Data("kein plist".utf8).write(to: url)
        let file = SettingsFile(url: url)
        XCTAssertTrue(file.wasReset)
        XCTAssertNil(PhoneConnectivity.afterReset(480, key: "sleepGoalMinutes", in: file))
        XCTAssertNil(PhoneConnectivity.afterReset(true, key: "backupAutoOnset", in: file))
        file.set(420, forKey: "sleepGoalMinutes")
        XCTAssertEqual(PhoneConnectivity.afterReset(420, key: "sleepGoalMinutes", in: file), 420)

        let fresh = SettingsFile(url: dir.appendingPathComponent("fresh.v1"))
        XCTAssertEqual(PhoneConnectivity.afterReset(480, key: "sleepGoalMinutes", in: fresh), 480)
        XCTAssertEqual(PhoneConnectivity.afterReset(true, key: "backupAutoOnset", in: fresh), true)
    }

    /// B-12: «In 2 Minuten testen» ersetzt einen wartenden Powernap; die Powernap-Karte verschwindet wie auf der Uhr.
    @MainActor
    func testTestReplacesWaitingNap() async {
        let transient = PhoneTransient()
        let now = Date()
        let nap = Transient.nap(base: nil, now: now, calendar: .gregorianLocal, minutes: 20, uuid: UUID().uuidString.lowercased())
        await transient.napFromWatch(nap)
        XCTAssertNotNil(transient.nap)

        let test = Transient.test(base: nil, now: now, calendar: .gregorianLocal, uuid: UUID().uuidString.lowercased())
        transient.begin(test: test)
        XCTAssertNil(transient.nap)
        XCTAssertEqual(transient.test?.plan.alarmId, test.plan.alarmId)

        transient.closeResult()
        await PhoneModel.shared.backup.cancelTransient()
    }

    /// Umgekehrt: Ein neuer Powernap ersetzt einen wartenden Probewecker (wie Uhr und Android); er endet ohne Ergebnis.
    @MainActor
    func testNapReplacesWaitingTest() async {
        let transient = PhoneTransient()
        let now = Date()
        let test = Transient.test(base: nil, now: now, calendar: .gregorianLocal, uuid: UUID().uuidString.lowercased())
        transient.begin(test: test)
        XCTAssertNotNil(transient.run)

        let nap = Transient.nap(base: nil, now: now, calendar: .gregorianLocal, minutes: 20, uuid: UUID().uuidString.lowercased())
        transient.begin(nap: nap, waiting: true)
        XCTAssertNil(transient.test)
        XCTAssertNil(transient.run)
        XCTAssertFalse(transient.showResult)
        XCTAssertEqual(transient.nap?.plan.alarmId, nap.plan.alarmId)
        XCTAssertTrue(transient.waitingForWatch)

        transient.closeResult()
        await transient.cancelNap()
    }
}
