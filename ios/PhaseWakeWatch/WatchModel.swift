import Observation
import PhaseWakeCore
import SwiftUI
import WidgetKit

/// Zustand der Uhr-App: Wecker, nächster Plan, Verlauf.
@MainActor
@Observable
final class WatchModel {
    static let shared = WatchModel()

    let storage = PhaseWakeStorage.watch
    private(set) var alarms: [AlarmSpec] = []
    private(set) var now = Date()
    /// Schlafziel vom iPhone in Minuten, nur für den Einschlafpunkt auf dem Ring; ohne iPhone der Standard.
    private(set) var sleepGoal = SleepGoal.defaultMinutes
    /// Probewecker oder Powernap, der statt des nächsten Weckers gilt; nie im Verlauf (`Transient`).
    private(set) var transient: TransientPlan?
    private static let transientKey = "transientPlan"
    /// Zeitraum der zuletzt beendeten Nacht(en) für das Nachholen (`AlarmSchedule.missedPlan`), wie auf Wear OS.
    private(set) var catchUp: CatchUpWindow?
    private static let catchUpKey = "catchUpWindow"
    /// «Gute Nacht» (Einschlaf-Modus) für die kommende Nacht; `nil` = keine. Nur über `setGoodNight` ändern.
    private(set) var goodNight: GoodNightState?
    static let goodNightKey = "goodNight"
    /// Beginn der laufenden Aufzeichnung (Lernphase oder «Nacht aufzeichnen»), für die Startseite; `nil` = keine.
    private(set) var recordSince: Date?

    private init() {
        alarms = storage.snapshot().alarms
        sleepGoal = storage.sleepGoal
        transient = SettingsFile.watchSession.string(forKey: Self.transientKey).flatMap { try? Transient.decode($0) }
        catchUp = SettingsFile.watchSession.data(forKey: Self.catchUpKey).flatMap { try? JSONDecoder().decode(CatchUpWindow.self, from: $0) }
        goodNight = SettingsFile.watchSession.data(forKey: Self.goodNightKey).flatMap { try? JSONDecoder().decode(GoodNightState.self, from: $0) }
        recordSince = LearnRecorder.shared.since
        // Zeitzone oder Uhrzeit geändert (Reise): Sitzung und Rückfall hängen an festen Zeitpunkten, also neu planen.
        for name in [Notification.Name.NSSystemTimeZoneDidChange, .NSSystemClockDidChange] {
            _ = NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { _ in
                Task { @MainActor in WatchModel.shared.reschedule() }
            }
        }
    }

    /// Nächster Plan: der frühere von nächstem Wecker und vorübergehendem Plan (Probewecker, Powernap), wie auf
    /// Android (`Transient.choose`); ein Powernap verdrängt nie einen früheren Wecker.
    var plan: WakePlan? { Transient.choose(real: realPlan, transient: transient, now: now) }

    /// Nächster echter Wecker. Eine heute schon erledigte Nacht zählt nicht mehr (früh geweckt, Zielzeit liegt noch vorn).
    /// Wurde ein Wecker fällig, während eine andere Nacht klingelte oder schlummerte, kommt er zuerst (`missedPlan`);
    /// danach eine Nacht, deren Zielzeit da ist, die aber noch nicht geweckt hat (`pendingNight`).
    /// «Gute Nacht» verlegt die Zielzeit dieser Nacht vor (`GoodNightState.apply`).
    var realPlan: WakePlan? {
        let done = storage.doneUntil
        let holidays = storage.holidays.dates
        let plan = AlarmSchedule.missedPlan(alarms, catchUp: catchUp, now: now, doneUntil: done, calendar: .gregorianLocal, holidays: holidays)
            ?? pendingNight
            ?? AlarmSchedule.earliestPlan(alarms, now: now, doneUntil: done, calendar: .gregorianLocal, holidays: holidays)
        return goodNight?.apply(to: plan, spec: alarms.first { $0.id == plan?.alarmId }, now: now, doneUntil: done,
                                ringLimit: SmartAlarm.ringLimit) ?? plan
    }

    /// Die Nacht, auf die die Uhr wartet (Sitzung geplant oder nicht planbar), wenn ihre Zielzeit schon da ist und sie
    /// weder geweckt hat noch erledigt ist – bis `SmartAlarm.ringLimit` danach (`WatchRules.pendingNight`). Ohne
    /// gemerkten Plan (Ruhezustand, z. B. nach einem Neustart der App) nur bis zum Rückfall (`WatchRules.unwokenNight`).
    /// Sie bleibt der Plan: Sonst ersetzte ein Neuplanen kurz nach der Zielzeit (App im Vordergrund, Wecker vom
    /// iPhone, Zeitzone) ihren Rückfall durch den der nächsten Nacht, und sie weckte gar nicht. Sonst `nil`.
    var pendingNight: WakePlan? {
        let done = storage.doneUntil
        let holidays = storage.holidays.dates
        switch SmartAlarm.shared.phase {
        case .waiting(let plan), .scheduled(let plan):
            // Von «Gute Nacht» vorverlegt: kein Termin des Weckers selbst, darum eigens.
            if let night = goodNight?.pending(plan, now: now, doneUntil: done[plan.alarmId], ringLimit: SmartAlarm.ringLimit) {
                return night
            }
            return WatchRules.pendingNight(plan, specs: alarms, now: now, doneUntil: done, ringLimit: SmartAlarm.ringLimit,
                                           calendar: .gregorianLocal, holidays: holidays)
        case .idle:
            return WatchRules.unwokenNight(specs: alarms, now: now, doneUntil: done, delay: SmartAlarm.fallbackDelay,
                                           calendar: .gregorianLocal, holidays: holidays)
        case .measuring, .ringing, .snoozed:
            return nil
        }
    }

    /// Nachzuholender Wecker (Zielzeit schon vorbei), sonst `nil`: Er klingelt gleich, ohne Messung.
    var missedPlan: WakePlan? {
        AlarmSchedule.missedPlan(alarms, catchUp: catchUp, now: Date(), doneUntil: storage.doneUntil, calendar: .gregorianLocal,
                                 holidays: storage.holidays.dates)
    }

    /// Eine Nacht, die geweckt hat, ist zu Ende (vor dem Ausschalten eines Einmal-Weckers aufrufen): Zeitraum ab ihrem
    /// Fensterbeginn bis jetzt samt den Weckern merken, die darin fällig wurden – sie werden gleich nachgeholt.
    func noteNightEnded(windowStart: Date) {
        let window = AlarmSchedule.catchUpAfter(catchUp, windowStart: windowStart, until: Date(), specs: storage.snapshot().alarms,
                                                calendar: .gregorianLocal, holidays: storage.holidays.dates)
        catchUp = window
        SettingsFile.watchSession.set(try? JSONEncoder().encode(window), forKey: Self.catchUpKey)
    }

    /// Warum ein neuer Probewecker oder Powernap jetzt nicht geht: Fenster läuft, Nacht misst, klingelt oder
    /// schlummert, oder er überdeckte das nächste Fenster (`Transient.conflict`).
    func conflict(_ candidate: TransientPlan) -> TransientConflict? {
        refresh()
        let busy = SmartAlarm.shared.phase.isMeasuring || SmartAlarm.shared.phase.isRinging || SmartAlarm.shared.phase.isSnoozed
        return Transient.conflict(candidate, next: realPlan, nightActive: busy, now: now)
    }

    /// Plan vom iPhone: übernehmen, wenn er nicht mit einem Wecker kollidiert. - Returns: ob übernommen.
    func accept(_ candidate: TransientPlan) -> Bool {
        if let reason = conflict(candidate) {
            DiagStore.shared.log(candidate.kind == .test ? .test : .nap, "refused \(reason.rawValue)")
            return false
        }
        startTransient(candidate)
        return true
    }

    /// Weckart zur Kennung: gespeicherter Wecker oder der vorübergehende Plan.
    func spec(for id: String?) -> AlarmSpec? {
        alarms.first { $0.id == id } ?? (transient?.spec.id == id ? transient?.spec : nil)
    }

    func refresh() {
        now = Date()
        alarms = storage.snapshot().alarms
        // Abgelaufen (2 h nach der Zielzeit): verwerfen.
        if let transient, !Transient.isActive(transient, now: now) { setTransient(nil) }
        // «Gute Nacht» einer vorbeigegangenen oder erledigten Nacht verwerfen.
        if let goodNight, goodNight.expired(now: now, doneUntil: storage.doneUntil[goodNight.alarmId], ringLimit: SmartAlarm.ringLimit) {
            setGoodNight(nil)
        }
    }

    /// «Gute Nacht» merken bzw. verwerfen (`nil`); ausserhalb der Sicherung (`SettingsFile.watchSession`).
    func setGoodNight(_ state: GoodNightState?) {
        goodNight = state
        SettingsFile.watchSession.set(state.flatMap { try? JSONEncoder().encode($0) }, forKey: Self.goodNightKey)
    }

    /// Laufende Aufzeichnung für die Startseite (`LearnRecorder.publishSince`).
    func noteRecording(since: Date?) {
        recordSince = since
    }

    /// Probewecker oder Powernap übernehmen (vom iPhone oder hier gestartet) und gleich planen.
    func startTransient(_ plan: TransientPlan) {
        // Ersetzt einen anderen vorübergehenden Plan: dessen Rückfall gilt nicht mehr (`schedule` stellt den neuen).
        if let transient, transient.plan.alarmId != plan.plan.alarmId {
            SmartAlarm.shared.removeFallback([SmartAlarm.transientFallbackId])
        }
        setTransient(plan)
        DiagStore.shared.log(plan.kind == .test ? .test : .nap, "received \(Format.logTime(plan.plan.target))")
        reschedule()
    }

    /// Vorübergehenden Plan verwerfen (beendet, abgebrochen oder vom iPhone zurückgenommen).
    func endTransient(_ id: String? = nil) {
        guard let transient, id == nil || transient.plan.alarmId == id else { return }
        setTransient(nil)
        SmartAlarm.shared.removeFallback([SmartAlarm.transientFallbackId])
    }

    /// Nach dem Ende einer Nacht (echter Wecker, Probewecker oder Powernap) neu planen. Bei geschlossener App lässt
    /// watchOS die Sitzung der nächsten Nacht nicht planen: dann eine Mitteilung «Öffne Phase Wake auf der Uhr» (der
    /// Rückfall steht trotzdem).
    func transientEnded() {
        reschedule()
        if case .waiting(let next) = SmartAlarm.shared.phase, Transient.kindOf(next.alarmId) == nil {
            SmartAlarm.shared.notifyOpenApp(for: next)
        }
    }

    /// Powernap von der Uhr aus: gleich planen (die App ist offen, darum klappt die Sitzung) und dem iPhone melden.
    /// - Returns: der Grund, wenn er nicht geht (Hinweis im Dialog); sonst `nil`.
    func startNap(minutes: Int) -> TransientConflict? {
        let next = realPlan
        let nap = Transient.nap(base: alarms.first { $0.id == next?.alarmId }, now: Date(), calendar: .gregorianLocal,
                                minutes: minutes, uuid: UUID().uuidString.lowercased())
        if let reason = conflict(nap) { return reason }
        startTransient(nap)
        WatchConnectivityBridge.shared.send(command: SyncKeys.nap, extra: [SyncKeys.payload: Transient.encode(nap)])
        return nil
    }

    /// Powernap vorzeitig beenden (vor dem Klingeln); das iPhone verwirft seine Sicherung.
    func stopNap() {
        guard let transient, transient.kind == .nap else { return }
        endTransient()
        DiagStore.shared.log(.nap, "cancelled")
        WatchConnectivityBridge.shared.send(command: SyncKeys.transientCancel, extra: [SyncKeys.id: transient.plan.alarmId])
        transientEnded()
    }

    private func setTransient(_ plan: TransientPlan?) {
        transient = plan
        SettingsFile.watchSession.set(plan.map(Transient.encode), forKey: Self.transientKey)
    }

    /// Feiertage vom iPhone übernehmen und neu planen, falls sie sich geändert haben.
    func applyHolidays(_ list: HolidayList) {
        guard storage.holidays != list else { return }
        storage.holidays = list
        reschedule()
    }

    /// Schlafziel vom iPhone übernehmen (nur Anzeige, kein Neuplanen).
    func applySleepGoal(_ minutes: Int) {
        storage.sleepGoal = minutes
        sleepGoal = storage.sleepGoal
    }

    /// Ein/Aus auf der Uhr; Änderung geht ans iPhone und plant neu.
    func setEnabled(_ alarm: AlarmSpec, _ enabled: Bool) {
        let snapshot = storage.mutate { list in list.map { $0.id == alarm.id ? $0.with(enabled: enabled) : $0 } }
        alarms = snapshot.alarms
        WatchConnectivityBridge.shared.publish(snapshot)
        reschedule()
    }

    /// Höchstens so viele Wecker wie am iPhone.
    static let maxAlarms = 8
    var canAddAlarm: Bool { alarms.count < Self.maxAlarms }

    /// Wecker auf der Uhr anlegen oder ändern; geht ans iPhone und plant neu (je Wecker gewinnt die jüngere Änderung).
    /// Ein bestehender Wecker behält alles, was die Uhr nicht bearbeitet (siehe `AlarmSpec.editedOnWatch`).
    /// `skip` ist der Schalter «Nächstes Mal überspringen» (`nil` nicht angefasst, `true` ein, `false` aus); gespeichert
    /// wird `AlarmSchedule.editedSkip` – wie Android. Ein neuer Wecker wird so gespeichert, wie er ist (der Editor bietet
    /// dort keinen Schalter an).
    func save(_ draft: AlarmSpec, skip: Bool?) {
        let now = Date()
        let done = storage.doneUntil[draft.id]
        let holidays = storage.holidays.dates
        let snapshot = storage.mutate { list in
            var list = list
            let old = list.first { $0.id == draft.id }
            var merged = old.map { $0.editedOnWatch(from: draft) } ?? draft
            if let old {
                merged = merged.with(skipUntil: AlarmSchedule.editedSkip(old: old, edited: merged, requested: skip, now: now, doneUntil: done,
                                                                         calendar: .gregorianLocal, holidays: holidays))
            }
            if let i = list.firstIndex(where: { $0.id == draft.id }) {
                list[i] = merged
            } else if list.count < Self.maxAlarms {
                list.append(merged)
            }
            return list
        }
        alarms = snapshot.alarms
        WatchConnectivityBridge.shared.publish(snapshot)
        reschedule()
    }

    /// Wecker auf der Uhr löschen; geht ans iPhone und plant neu.
    func remove(_ id: String) {
        let snapshot = storage.mutate { list in list.filter { $0.id != id } }
        alarms = snapshot.alarms
        WatchConnectivityBridge.shared.publish(snapshot)
        reschedule()
    }

    /// Plan, nach dem die Uhr weckt, für die Komplikation (`EffectivePlan`, Audit AP-9): mit «Gute Nacht», Probewecker
    /// und Powernap wie die Sitzung. Nur bei einer Änderung geschrieben.
    private func publishEffectivePlan() {
        let current = plan
        guard EffectivePlan.decode(SettingsFile.watchAlarms.object(forKey: EffectivePlan.key)) != current else { return }
        SettingsFile.watchAlarms.set(EffectivePlan.encode(current), forKey: EffectivePlan.key)
    }

    /// Schlüssel des zuletzt angewandten «Verlauf löschen» (Sendezeit, s) in `SettingsFile.watchSession`.
    private static let historyClearedKey = "historyClearedAt"

    /// «Verlauf löschen» vom iPhone mit Sendezeit `sentAt` (Audit AP-6, wie Android): nur Nächte und Aufzeichnungen, die
    /// bis dahin endeten – auch die noch nicht gesendeten. Ohne Sendezeit (ältere iPhone-App) oder schon angewandt
    /// (doppelt zugestellt) nichts.
    func clearHistory(sentAt: Date?) {
        let stored = SettingsFile.watchSession.double(forKey: Self.historyClearedKey)
        let applied = stored > 0 ? Date(timeIntervalSince1970: stored) : nil
        guard let until = HistoryClear.clearUntil(sentAt: sentAt, applied: applied) else {
            DiagStore.shared.log(.suppressed, "clear history ignored (\(sentAt == nil ? "no time" : "old"))")
            return
        }
        SettingsFile.watchSession.set(until.timeIntervalSince1970, forKey: Self.historyClearedKey)
        let nights = storage.nights()
        let kept = nights.filter { HistoryClear.keeps(end: $0.wokeAt, clearedAt: until) }
        if kept.isEmpty {
            storage.clearNights()
        } else if kept.count != nights.count {
            storage.replaceNights(kept)
        }
        WatchConnectivityBridge.shared.dropQueued(clearedAt: until)
        LearnRecorder.shared.clearHistory(until: until)
        DiagStore.shared.log(.sync, "history cleared \(nights.count - kept.count)/\(nights.count)")
    }

    /// Vom iPhone empfangene Wecker je Wecker übernehmen (Audit A-4, ``AlarmStamps``).
    func applyRemote(_ snapshot: AlarmSnapshot) {
        guard storage.applyRemote(snapshot) else { return }
        alarms = storage.snapshot().alarms
        DiagStore.shared.log(.alarmsReceived, "\(alarms.count) alarms")
        reschedule()
    }

    /// Plant die Smart-Alarm-Sitzung für das nächste Fenster (geht nur, solange die App aktiv ist), die Rückfälle der
    /// Nächte danach (`SmartAlarm.scheduleUpcomingFallbacks`) und, mit `recorder`, den Rekorder der Lernphase (die
    /// Hintergrund-Aktualisierung startet ihn selbst, `LearnRecorder.refreshTask`).
    func reschedule(recorder: Bool = true) {
        refresh()
        SmartAlarm.shared.schedule(plan)
        SmartAlarm.shared.scheduleUpcomingFallbacks(after: realPlan, model: self)
        publishEffectivePlan()
        let armed: Bool
        switch SmartAlarm.shared.phase {
        case .idle, .waiting: armed = false
        case .scheduled, .measuring, .ringing, .snoozed: armed = true
        }
        WatchDiagStatus.shared.update(plan: plan, armed: armed)
        // Erinnerung am Abend: App öffnen, damit die Uhr scharf stellt und aufzeichnet (`EveningReminder`).
        EveningReminder.update(model: self)
        // Komplikation auf dem Zifferblatt sofort aktualisieren.
        WidgetCenter.shared.reloadAllTimelines()
        // Lernphase: Rekorder für die nächste Nacht starten bzw. die letzte auswerten.
        if recorder { LearnRecorder.shared.plan() }
    }
}
