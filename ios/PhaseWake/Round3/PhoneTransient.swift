import Observation
import PhaseWakeCore
import SwiftUI
import UserNotifications
import WatchConnectivity
#if canImport(AlarmKit)
import AlarmKit
#endif

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
    /// Das iPhone-Klingeln liess sich nicht beobachten (App im Hintergrund), eine fehlende Erlaubnis ist aber nicht
    /// bekannt: «unbekannt» statt «nicht erfüllt», damit die Diagnose nicht in die Irre führt.
    private(set) var phoneRingUnobserved = false
    /// Zuletzt abgelehnter Start (Art und Grund) für den Hinweis «Gerade läuft dein Wecker.»; `nil` nach einem Start.
    private(set) var refused: (kind: TransientKind, reason: TransientConflict)?
    /// So lange nach dem letzten «klingeln» der Uhr gilt eine Nacht noch als laufend (`SmartAlarm.ringLimit`, 1 h).
    private static let ringLimit: TimeInterval = 3600

    private static let testKey = "transientTest"
    private static let runKey = "transientTestRun"
    private static let napKey = "transientNap"
    private static let ringIdsKey = "transientTestRingIds"
    /// So oft wird geprüft, ob das Ergebnis feststeht.
    private static let pollSeconds = 10
    @ObservationIgnored private var resultTask: Task<Void, Never>?
    @ObservationIgnored private var alertTask: Task<Void, Never>?
    /// Kennungen der iPhone-Alarme dieses Probeweckers (Sicherung, Mitklingeln). «Handy hat mitgeklingelt» zählt
    /// erst, wenn einer davon wirklich klingelt – wie Android, wo jedes Klingeln des Handys zählt.
    @ObservationIgnored private var ringIds: Set<String> = []
    @ObservationIgnored private var concluding = false

    /// Weckart des laufenden Plans zur Kennung, sonst `nil`.
    func spec(for id: String?) -> AlarmSpec? {
        [test, nap].compactMap { $0 }.first { $0.spec.id == id }?.spec
    }

    /// «In 2 Minuten testen»: Weckart wie der nächste Wecker (oder die Vorgaben).
    func startTest() async {
        let model = PhoneModel.shared
        let base = model.nextPlan().flatMap { plan in model.alarms.first { $0.id == plan.alarmId } }
        let plan = Transient.test(base: base, now: Date(), calendar: .current, uuid: UUID().uuidString.lowercased())
        guard allowed(plan) else { return }
        test = plan
        run = TestRun(id: plan.plan.alarmId, target: plan.plan.target, phoneAlert: plan.spec.phone)
        waitingForWatch = true
        showResult = false
        phoneRingUnobserved = false
        ringIds = []
        save()
        PhoneConnectivity.shared.send(command: SyncKeys.test, extra: [SyncKeys.payload: Transient.encode(plan)])
        if let id = await model.backup.scheduleTransient(plan) { addRingId(id) }
        DiagStore.shared.log(.test, "start \(Format.time(plan.plan.target))")
        waitForResult()
    }

    /// Ergebnis geschlossen: der Probewecker ist vorbei.
    func closeResult() {
        showResult = false
        test = nil
        run = nil
        waitingForWatch = false
        phoneRingUnobserved = false
        ringIds = []
        resultTask?.cancel()
        alertTask?.cancel()
        save()
    }

    /// Powernap vom iPhone: geht an die Uhr; sie misst und weckt, sobald Phase Wake dort offen ist.
    func startNap(minutes: Int) async {
        let model = PhoneModel.shared
        let base = model.nextPlan().flatMap { plan in model.alarms.first { $0.id == plan.alarmId } }
        let plan = Transient.nap(base: base, now: Date(), calendar: .current, minutes: minutes, uuid: UUID().uuidString.lowercased())
        guard allowed(plan) else { return }
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

    /// Nicht starten, solange ein Fenster läuft, eine Nacht klingelt bzw. schlummert oder der Plan das nächste Fenster
    /// überdeckte (`Transient.conflict`, wie Android); der Grund steht dann in `refused`.
    private func allowed(_ plan: TransientPlan) -> Bool {
        let model = PhoneModel.shared
        let now = Date()
        let ringing = model.backup.ringActive && (model.lastRing.map { now.timeIntervalSince($0) < Self.ringLimit } ?? false)
        if let reason = Transient.conflict(plan, next: model.nextPlan(now: now), nightActive: ringing, now: now) {
            refused = (plan.kind, reason)
            DiagStore.shared.log(plan.kind == .test ? .test : .nap, "refused \(reason.rawValue)")
            return false
        }
        refused = nil
        return true
    }

    /// Hinweis zum abgelehnten Start dieser Art, sonst `nil`.
    func refusal(_ kind: TransientKind) -> String? {
        guard let refused, refused.kind == kind else { return nil }
        return L(refused.reason == .alarmRunning ? "transient_alarm_running" : "transient_alarm_soon")
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

    /// «klingeln» der Uhr: Sicherung überflüssig; beim Probewecker Schritte merken. `phoneRing` ist der
    /// Mitklingel-Alarm des iPhones; er zählt erst, wenn er klingelt (`phoneAlerted`).
    func watchRang(_ id: String?, phoneRing: UUID?) async {
        guard Transient.kindOf(id) != nil else { return }
        await PhoneModel.shared.backup.cancelTransient()
        waitingForWatch = false
        if let phoneRing, id == run?.id { addRingId(phoneRing) }
        record(.watchRang, id: id)
    }

    /// Ein iPhone-Alarm klingelt bzw. hat geklingelt (AlarmKit «alerting», zugestellte Mitteilung, Stopp am
    /// iPhone). Gehört er zum laufenden Probewecker, ist «Handy hat mitgeklingelt» erfüllt.
    func phoneAlerted(_ ringId: String) {
        guard let run, ringIds.contains(ringId) else { return }
        record(.phoneRang, id: run.id)
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
        ringIds = run == nil ? [] : Set(defaults.stringArray(forKey: Self.ringIdsKey) ?? [])
        nap = defaults.string(forKey: Self.napKey).flatMap { try? Transient.decode($0) }.flatMap { $0.plan.target > now ? $0 : nil }
        save()
        if run != nil { waitForResult() }
    }

    private func record(_ step: TestStep, id: String?) {
        guard let current = run, current.id == id else { return }
        let updated = TestRuns.record(current, step, at: Date())
        guard updated != current else { return }
        run = updated
        save()
        if TestRuns.isFinished(updated, now: Date()) { Task { await self.conclude() } }
    }

    private func addRingId(_ id: UUID) {
        ringIds.insert(id.uuidString)
        UserDefaults.standard.set(Array(ringIds), forKey: Self.ringIdsKey)
    }

    /// Ohne «stop» steht das Ergebnis drei Minuten nach der Zielzeit fest.
    private func waitForResult() {
        resultTask?.cancel()
        watchAlerts()
        resultTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                guard let self, let run = self.run else { return }
                if TestRuns.isFinished(run, now: Date()) {
                    await self.conclude()
                    return
                }
                try? await Task.sleep(for: .seconds(Self.pollSeconds))
            }
        }
    }

    /// Ab iOS 26: Klingelt ein AlarmKit-Alarm des Probeweckers, solange die App läuft, gleich merken.
    private func watchAlerts() {
        alertTask?.cancel()
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) {
            alertTask = Task { @MainActor [weak self] in
                for await alarms in AlarmManager.shared.alarmUpdates {
                    guard let self, self.run != nil else { return }
                    for alarm in alarms where alarm.state == .alerting { self.phoneAlerted(alarm.id.uuidString) }
                }
            }
        }
        #endif
    }

    /// Ergebnis festhalten: zugestellte Mitteilungen nachsehen (vor iOS 26), dann zeigen. Nicht beobachtetes
    /// iPhone-Klingeln gilt nur als «nicht erfüllt», wenn die Erlaubnis dafür fehlt (Nacht-Check).
    private func conclude() async {
        guard !concluding, !showResult, let current = run else { return }
        concluding = true
        defer { concluding = false }
        if current.seen[.phoneRang] == nil {
            let delivered = await UNUserNotificationCenter.current().deliveredNotifications().map(\.request.identifier)
            if let id = delivered.first(where: { ringIds.contains($0) }) { phoneAlerted(id) }
        }
        let missing = await NightCheck.collect()
        guard let run, run.id == current.id else { return }
        phoneRingUnobserved = TestRuns.result(run, .phoneRang) == false && !missing.contains(.alarms) && !missing.contains(.notifications)
        alertTask?.cancel()
        showResult = true
        DiagStore.shared.log(.test, "result \(TestStep.allCases.map { TestRuns.result(run, $0).map { $0 ? "1" : "0" } ?? "-" }.joined())")
    }

    private func save() {
        let defaults = UserDefaults.standard
        defaults.set(test.map(Transient.encode), forKey: Self.testKey)
        defaults.set(run.map(TestRuns.encode), forKey: Self.runKey)
        defaults.set(nap.map(Transient.encode), forKey: Self.napKey)
    }
}
