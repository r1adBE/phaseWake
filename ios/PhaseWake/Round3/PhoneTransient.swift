import Observation
import PhaseWakeCore
import SwiftUI
import WatchConnectivity

/// Probewecker und Powernap am iPhone: schickt den Plan an die Uhr (die Apple Watch plant die Sitzung nur
/// bei offener App), stellt eine Sicherung kurz nach der Zielzeit und wertet den Probewecker aus. Kein
/// Eintrag im Verlauf, keine Änderung an den Weckern (`Transient`).
@MainActor
@Observable
final class PhoneTransient {
    /// Laufender Probewecker und was das iPhone davon gesehen hat.
    private(set) var test: TransientPlan?
    private(set) var run: TestRun?
    /// Laufender Powernap (hier oder auf der Uhr gestartet).
    private(set) var nap: TransientPlan?
    /// Die Uhr hat den Plan noch nicht bestätigt bzw. war nicht erreichbar: «Öffne Phase Wake auf der Uhr».
    private(set) var waitingForWatch = false
    /// Ergebnis des Probeweckers zeigen.
    var showResult = false

    private static let testKey = "transientTest"
    private static let runKey = "transientTestRun"
    private static let napKey = "transientNap"
    /// So oft wird geprüft, ob das Ergebnis feststeht.
    private static let pollSeconds = 10
    @ObservationIgnored private var resultTask: Task<Void, Never>?

    /// Weckart des laufenden Plans zur Kennung, sonst `nil`.
    func spec(for id: String?) -> AlarmSpec? {
        [test, nap].compactMap { $0 }.first { $0.spec.id == id }?.spec
    }

    /// «In 2 Minuten testen»: Weckart wie der nächste Wecker (oder die Vorgaben).
    func startTest() async {
        let model = PhoneModel.shared
        let base = model.nextPlan().flatMap { plan in model.alarms.first { $0.id == plan.alarmId } }
        let plan = Transient.test(base: base, now: Date(), calendar: .current, uuid: UUID().uuidString.lowercased())
        test = plan
        run = TestRun(id: plan.plan.alarmId, target: plan.plan.target, phoneAlert: plan.spec.phone)
        waitingForWatch = true
        showResult = false
        save()
        PhoneConnectivity.shared.send(command: SyncKeys.test, extra: [SyncKeys.payload: Transient.encode(plan)])
        await model.backup.scheduleTransient(plan)
        DiagStore.shared.log(.test, "start \(Format.time(plan.plan.target))")
        waitForResult()
    }

    /// Ergebnis geschlossen: der Probewecker ist vorbei.
    func closeResult() {
        showResult = false
        test = nil
        run = nil
        waitingForWatch = false
        resultTask?.cancel()
        save()
    }

    /// Powernap vom iPhone: geht an die Uhr; sie misst und weckt, sobald Phase Wake dort offen ist.
    func startNap(minutes: Int) async {
        let model = PhoneModel.shared
        let base = model.nextPlan().flatMap { plan in model.alarms.first { $0.id == plan.alarmId } }
        let plan = Transient.nap(base: base, now: Date(), calendar: .current, minutes: minutes, uuid: UUID().uuidString.lowercased())
        nap = plan
        waitingForWatch = !(WCSession.isSupported() && WCSession.default.isReachable)
        save()
        PhoneConnectivity.shared.send(command: SyncKeys.nap, extra: [SyncKeys.payload: Transient.encode(plan)])
        await model.backup.scheduleTransient(plan)
        DiagStore.shared.log(.nap, "start \(minutes) min")
    }

    /// Powernap hier beenden: Uhr und Sicherung wissen Bescheid.
    func cancelNap() async {
        guard let nap else { return }
        PhoneConnectivity.shared.send(command: SyncKeys.transientCancel, extra: [SyncKeys.id: nap.plan.alarmId])
        self.nap = nil
        waitingForWatch = false
        save()
        await PhoneModel.shared.backup.cancelTransient()
        DiagStore.shared.log(.nap, "cancelled")
    }

    // MARK: Meldungen der Uhr

    /// Die Uhr hat den Probewecker empfangen.
    func received(_ id: String?) {
        guard id != nil, id == run?.id else { return }
        waitingForWatch = false
        record(.received, id: id)
    }

    /// Powernap auf der Uhr gestartet: Sicherung stellen.
    func napFromWatch(_ plan: TransientPlan) async {
        nap = plan
        waitingForWatch = false
        save()
        await PhoneModel.shared.backup.scheduleTransient(plan)
        DiagStore.shared.log(.nap, "from watch \(Format.time(plan.plan.target))")
    }

    /// Powernap auf der Uhr beendet.
    func cancelledOnWatch(_ id: String?) async {
        guard let nap, nap.plan.alarmId == id else { return }
        self.nap = nil
        save()
        await PhoneModel.shared.backup.cancelTransient()
    }

    /// «klingeln» der Uhr: Sicherung überflüssig; beim Probewecker Schritte merken.
    func watchRang(_ id: String?, phoneRang: Bool) async {
        guard Transient.kindOf(id) != nil else { return }
        await PhoneModel.shared.backup.cancelTransient()
        waitingForWatch = false
        record(.watchRang, id: id)
        if phoneRang { record(.phoneRang, id: id) }
    }

    /// «stop» der Uhr: Probewecker fertig, Powernap vorbei.
    func stopArrived(_ id: String?) async {
        guard Transient.kindOf(id) != nil else { return }
        await PhoneModel.shared.backup.cancelTransient()
        if nap?.plan.alarmId == id { nap = nil }
        record(.stopArrived, id: id)
        save()
    }

    // MARK: Speicher

    /// Nach einem Neustart der App: laufende Pläne zurückholen, abgelaufene verwerfen.
    func restore() async {
        let defaults = UserDefaults.standard
        let now = Date()
        test = defaults.string(forKey: Self.testKey).flatMap { try? Transient.decode($0) }.flatMap { Transient.isActive($0, now: now) ? $0 : nil }
        run = test == nil ? nil : TestRuns.decode(defaults.string(forKey: Self.runKey))
        nap = defaults.string(forKey: Self.napKey).flatMap { try? Transient.decode($0) }.flatMap { $0.plan.target > now ? $0 : nil }
        save()
        if run != nil { waitForResult() }
    }

    private func record(_ step: TestStep, id: String?) {
        guard let current = run, current.id == id else { return }
        let updated = TestRuns.record(current, step, at: Date())
        run = updated
        save()
        if TestRuns.isFinished(updated, now: Date()) { showResult = true }
    }

    /// Ohne «stop» steht das Ergebnis drei Minuten nach der Zielzeit fest.
    private func waitForResult() {
        resultTask?.cancel()
        resultTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                guard let self, let run = self.run else { return }
                if TestRuns.isFinished(run, now: Date()) {
                    self.showResult = true
                    DiagStore.shared.log(.test, "result \(TestStep.allCases.map { TestRuns.result(run, $0).map { $0 ? "1" : "0" } ?? "-" }.joined())")
                    return
                }
                try? await Task.sleep(for: .seconds(Self.pollSeconds))
            }
        }
    }

    private func save() {
        let defaults = UserDefaults.standard
        defaults.set(test.map(Transient.encode), forKey: Self.testKey)
        defaults.set(run.map(TestRuns.encode), forKey: Self.runKey)
        defaults.set(nap.map(Transient.encode), forKey: Self.napKey)
    }
}
