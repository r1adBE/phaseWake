import CoreMotion
import HealthKit
import Observation
import PhaseWakeCore
import UserNotifications
import WatchKit

/// Führt eine Weck-Nacht auf der Apple Watch durch – Gegenstück zum `WakeService` auf Wear OS.
///
/// Nutzt die Sitzungsart «Smart Alarm» (`WKExtendedRuntimeSession`): Sie wird vom System gestartet,
/// darf im Hintergrund Bewegung und Puls lesen und dauert höchstens 30 Minuten. Damit die Zielzeit
/// sicher in der Sitzung liegt, beginnt sie höchstens 25 Minuten davor (`sessionStart`); bei einem
/// 30-Minuten-Fenster bleiben die ersten 5 Minuten ungemessen (leere Epochen).
/// Gemessen wird wie auf Android (Regel in docs/PHASENERKENNUNG.md). Bei «Genau» misst die Uhr gleich lang
/// (`AlarmSpec.measureMinutes`), weckt aber nie vor der Zielzeit (Grund `exact`). Die Untergrenze «Kurze Nacht» gilt
/// wie auf Android: Ausserhalb der Sitzung misst die Apple Watch nicht selbst, rechnet die Einschlafzeit aber zu Beginn
/// der Sitzung aus dem Bewegungs-Rekorder nach (`WatchOnset`), wenn er in dieser Nacht lief. Bis die Rechnung fertig
/// ist, wartet ein früher Moment; bringt sie kein Ergebnis, obwohl der Rekorder lief, gilt die Untergrenze bis 10 Min
/// vor der Zielzeit (fail-closed).
///
/// Grenzen von watchOS, die hier abgefangen werden:
/// - Planen geht nur bei aktiver App und höchstens 36 Stunden im Voraus. Sonst wartet der Plan
///   (`waiting`), bis die App das nächste Mal offen ist; das iPhone zeigt das im Nacht-Check.
/// - Lehnt das System die Sitzung ab oder bricht sie ab, weckt eine lokale Mitteilung kurz nach
///   der Zielzeit (Rückfall). Sie bleibt beim Klingeln stehen und wird erst beim Beenden entfernt.
/// - Endet die Sitzung während des Klingelns oder Schlummerns (Ablauf, System), weckt sofort eine
///   Mitteilung, und die Nacht wird trotzdem abgeschlossen; ebenso spätestens 1 h nach der Zielzeit. Endet sie beim
///   Klingeln regulär (Stopp im Weckhinweis des Systems), gilt das als Stopp (`SessionRules.ending`).
/// - Bricht die Sitzung beim Messen vor der Zielzeit ab, weckt der feste Rückfall kurz nach der Zielzeit
///   (`measuringInterrupted`); die Nächte danach haben eigene Rückfälle (`UpcomingFallbacks`).
@MainActor
@Observable
final class SmartAlarm: NSObject, WKExtendedRuntimeSessionDelegate {
    static let shared = SmartAlarm()

    enum Phase: Equatable {
        case idle
        /// Plan steht, Sitzung aber (noch) nicht: App öffnen bzw. näher als 36 h.
        case waiting(WakePlan)
        case scheduled(WakePlan)
        case measuring(WakePlan)
        case ringing(WakeReason, snoozesLeft: Int)
        case snoozed(until: Date, snoozesLeft: Int)

        var isRinging: Bool { if case .ringing = self { true } else { false } }
        var isSnoozed: Bool { if case .snoozed = self { true } else { false } }
        var isMeasuring: Bool { if case .measuring = self { true } else { false } }
    }

    /// Höchstens so weit im Voraus nimmt watchOS eine Smart-Alarm-Sitzung an.
    static let maxLeadTime: TimeInterval = 36 * 3600
    /// Der Rückfall weckt kurz nach der Zielzeit, damit er dem regulären Wecken nicht zuvorkommt.
    static let fallbackDelay: TimeInterval = 60
    /// Höchstdauer der Sitzung (30 Min) minus Reserve: So liegt die Zielzeit samt Klingeln in der Sitzung.
    static let sessionLead: TimeInterval = 25 * 60
    /// Spätestens so lange nach der Zielzeit gilt eine Nacht, die noch «klingelt» oder «schlummert», als hängengeblieben.
    static let ringLimit: TimeInterval = 3600
    /// Spielraum, um den das System eine Sitzung früher oder später starten darf.
    static let startTolerance: TimeInterval = 120
    /// Kennung des Rückfalls eines echten Weckers; Probewecker und Powernap nutzen nie diese (`fallbackKey`).
    nonisolated static let fallbackId = "phasewake.fallback"
    /// Eigene Kennung für das Wecken nach einer abgebrochenen Sitzung: Neu planen ersetzt sie nicht.
    static let interruptedId = "phasewake.fallback.interrupted"
    /// Eigene Kennung für den Rückfall eines vorübergehenden Plans (Probewecker, Powernap); `fallbackId` bleibt daneben.
    static let transientFallbackId = "phasewake.fallback.transient"
    /// Mitteilung «Öffne Phase Wake auf der Uhr», wenn die Sitzung der Nacht nur bei offener App planbar ist.
    static let openAppId = "phasewake.openApp"
    /// Früherer Ort des Klingel-Zustands in `UserDefaults`; wird beim Lesen in die Datei übernommen (`SmartAlarm+State`).
    static let stateKey = "smartAlarmNight"
    /// Zeitzone, Zielzeit und Wecker des Plans, für den die Uhr wartet, plant oder misst – gemerkt in der Zone, in der
    /// er berechnet wurde (`notePlanZone`), für die Prüfung nach einer Reise (`replansAfterZoneChange`, `keptNight`).
    /// Liegt in `SettingsFile.watchSession` (nicht in der Sicherung).
    static let sessionZoneKey = "sessionZone"

    /// Zustand der Nacht; gesetzt nur von `SmartAlarm` und seinen Erweiterungen (Dateien `SmartAlarm+…`).
    var phase: Phase = .idle

    @ObservationIgnored var session: WKExtendedRuntimeSession?
    /// Ersetzte Sitzungen, die die App bei geschlossener App nicht beenden durfte (`start(at:)`): beim nächsten
    /// Aktivwerden beenden, beim Start gleich (`SessionRules.retire`).
    @ObservationIgnored var staleSessions: [WKExtendedRuntimeSession] = []
    /// Plan der laufenden Sitzung (nach Schlummern: der Schlummer-Termin).
    @ObservationIgnored var plan: WakePlan?
    /// Start der geplanten Sitzung, die bei geschlossener App eine geänderte Nacht übernommen hat (`keepsSession`,
    /// Tiefenaudit AP3-3); `nil`, wenn die Sitzung für ihren eigenen Plan steht. Beim nächsten Öffnen plant die App neu.
    @ObservationIgnored var keptStart: Date?
    /// Ursprünglicher Plan der Nacht; wird im Verlauf gespeichert.
    @ObservationIgnored var nightPlan: WakePlan?
    @ObservationIgnored var spec: AlarmSpec?
    @ObservationIgnored var detector = WakeDetector()
    @ObservationIgnored var assembler: EpochAssembler?
    @ObservationIgnored var epochs: [Epoch] = []
    @ObservationIgnored var firstRing: Date?
    @ObservationIgnored var reason: WakeReason = .deadline
    @ObservationIgnored var snoozes = 0
    /// Epochen ab Fensterbeginn, die vor dem Start der Sitzung lagen (leer, nicht für die Erkennung).
    @ObservationIgnored var unmeasured = 0
    /// Einschlafzeit der Nacht aus dem Rekorder (`WatchOnset`); `nil` = unbekannt (Untergrenze kurzer Nächte dann nur,
    /// wenn `onsetMeasured`).
    @ObservationIgnored var asleepAt: Date?
    /// Der Rekorder lief in dieser Nacht, die Rechnung brachte aber keine Einschlafzeit (fail-closed, `ShortNight`).
    @ObservationIgnored var onsetMeasured = false
    /// Die Einschlafzeit wird noch gerechnet: Frühe Momente warten so lange (`heldWake`).
    @ObservationIgnored var onsetPending = false
    /// Früher Moment, der kam, während die Einschlafzeit noch gerechnet wurde; entschieden, sobald sie da ist.
    @ObservationIgnored var heldWake: WakeReason?
    /// Zählt die Rechnungen der Einschlafzeit; ein überholtes oder zu spätes Ergebnis gilt nicht.
    @ObservationIgnored var onsetRun = 0
    /// Schlafziel der Nacht in Minuten (Schlafdauer des Weckers, sonst das Schlafziel vom iPhone).
    @ObservationIgnored var goalMinutes = SleepGoal.defaultMinutes
    /// Ein früher Moment wurde übergangen (kurze Nacht): Zur Zielzeit weckt die Uhr mit `shortNight`.
    @ObservationIgnored var earlyBlocked = false
    @ObservationIgnored var ticker: Timer?
    @ObservationIgnored var deadline: Timer?
    /// Beginn des Klingelns, für die Steigerung der Vibration.
    @ObservationIgnored var hapticStart = Date()
    /// Vibration bei offener App, wenn ohne laufende Sitzung geklingelt wird (Rückfall, `fallbackArrived`).
    @ObservationIgnored var fallbackHaptic: Timer?
    /// Zielzeit, für die `keepRealFallback` zuletzt protokolliert hat.
    @ObservationIgnored private var keptFallback: Date?
    /// Zielzeit der Nacht, die zuletzt über einen Zeitzonenwechsel behalten wurde (`holdFallback`, Diagnose einmal).
    @ObservationIgnored var keptZoneNight: Date?
    /// Zählt die Aufrufe von `scheduleUpcomingFallbacks`; ein überholter stellt nichts.
    @ObservationIgnored var upcomingRun = 0
    /// Zuletzt protokollierte Rückfälle der nächsten Nächte (erste Zeit je Nacht).
    @ObservationIgnored var loggedUpcoming: [Date] = []

    @ObservationIgnored let motion = CMMotionManager()
    /// Alle Zugriffe auf den `EpochAssembler` laufen über diese eine serielle Warteschlange.
    nonisolated let assemblerQueue = DispatchQueue(label: "phasewake.epochs")
    @ObservationIgnored let motionQueue = OperationQueue()
    @ObservationIgnored let health = HKHealthStore()
    @ObservationIgnored var heartRateQuery: HKAnchoredObjectQuery?

    override private init() {
        super.init()
        motionQueue.maxConcurrentOperationCount = 1
        motionQueue.underlyingQueue = assemblerQueue
        restore()
    }

    // MARK: Berechtigungen

    /// Puls lesen (zweite Stimme der Erkennung) und Mitteilungen (Rückfall) – beim ersten Start fragen. Scheitert eine
    /// Anfrage, steht es im Protokoll; danach geht der Stand der Mitteilungen ans iPhone (Nacht-Check).
    func requestAccess() async {
        if HKHealthStore.isHealthDataAvailable(), let heartRate = HKQuantityType.quantityType(forIdentifier: .heartRate) {
            do {
                try await health.requestAuthorization(toShare: [], read: [heartRate])
            } catch {
                NotificationLog.permissionFailed("heart rate", error)
            }
        }
        await NotificationLog.requestPermission("watch")
        await WatchConnectivityBridge.shared.publishNotificationState()
    }

    // MARK: Planen

    /// Plant die Sitzung für `plan`. Gelingt das nicht (App nicht aktiv, zu weit voraus), wartet der
    /// Plan, und der Rückfall steht trotzdem. Reise im schon begonnenen Weckfenster der geplanten oder wartenden Nacht
    /// (`heldNight`): Sie bleibt statt `plan`, zur geplanten absoluten Zeit samt festem Rückfall.
    func schedule(_ plan: WakePlan?) {
        if keepsNight(for: plan) { return }
        let held = heldNight(for: plan)
        guard let plan = held ?? plan else {
            invalidateSession()
            removeFallback([Self.fallbackId, Self.transientFallbackId])
            phase = .idle
            publishArmed()
            return
        }
        // Rückfall je unter eigener Kennung: Ein Probewecker oder Powernap ersetzt nie den des echten Weckers, der
        // daneben für dessen nächste Nacht stehen bleibt – auch wenn der vorübergehende Plan nie sauber endet.
        // Ein nachgeholter Wecker (Zielzeit vorbei) bekommt die Frist ab jetzt, damit seine Sitzung zuerst weckt; eine
        // ausstehende Nacht (`WatchModel.pendingNight`) behält ihren Rückfall (`WatchRules.fallbackDate`).
        let now = Date()
        // Eine gehaltene Nacht zählt wie eine ausstehende: Rückfall nur bis zu seiner Zeit, danach keine neue Sitzung.
        let pending = held != nil || plan == WatchModel.shared.pendingNight
        if held != nil {
            holdFallback(plan)
        } else if let at = WatchRules.fallbackDate(plan, pending: pending, now: now, delay: Self.fallbackDelay) {
            scheduleFallback(at: at, id: Self.fallbackKey(for: plan.alarmId))
        }
        if Transient.kindOf(plan.alarmId) != nil { keepRealFallback() }
        let active = WKApplication.shared().applicationState == .active
        if !active, keepsRealSession(for: plan) { return }
        // Eine übernommene Sitzung (`keptStart`) bei offener App durch eine zum Plan passende ersetzen.
        if case .scheduled(let current) = phase, current == plan, session != nil, !active || keptStart == nil { return }
        // Nach einem Neustart der App: die noch geplante Sitzung behalten, nie eine zweite (Vollaudit AP2-1).
        if keepsRestoredSession(for: plan) { return }

        let start = Self.sessionStart(plan)
        let tooEarly = start.timeIntervalSinceNow > Self.maxLeadTime
        // Eine ausstehende Nacht startet ihre Sitzung nur, bis ihr Rückfall fällig ist; danach wartet sie auf ihn.
        let startsNow = WatchRules.startsSession(plan, pending: pending, now: now, delay: Self.fallbackDelay)
        guard active, !tooEarly, startsNow else {
            // Eine schon geplante Sitzung für einen anderen Plan: übernimmt sie die geänderte Nacht, bleibt sie
            // (Tiefenaudit AP3-3); sonst ist sie überholt, und die App bittet ums Öffnen, damit die Nacht eine Sitzung
            // bekommt (nicht nur den Rückfall).
            if case .scheduled(let current) = phase, current != plan {
                if !tooEarly, startsNow, keepsSession(from: current, for: plan) { return }
                invalidateSession()
                // Startet doch noch eine Sitzung, die die App nicht mehr kennt (vom System über einen Neustart der App
                // behalten, AP3-4), prüft `startNight` sie gegen den dann aktuellen Plan statt gegen den alten.
                self.plan = nil
                if !tooEarly, startsNow { notifyOpenApp(for: plan) }
            }
            if case .scheduled = phase, session != nil, !tooEarly { return }
            if phase != .waiting(plan) {
                DiagStore.shared.log(.scheduled, "waiting \(Format.logTime(plan.target)) active \(active)\(pending ? " pending" : "")")
            }
            phase = .waiting(plan)
            notePlanZone(plan)
            publishArmed()
            return
        }
        invalidateSession()
        let newSession = WKExtendedRuntimeSession()
        newSession.delegate = self
        if start <= Date() {
            newSession.start()
        } else {
            newSession.start(at: start)
        }
        session = newSession
        self.plan = plan
        phase = .scheduled(plan)
        notePlanZone(plan)
        if start > now { noteScheduled(plan, start: start) }
        DiagStore.shared.log(.scheduled, "session \(Format.logTime(start)) target \(Format.logTime(plan.target))")
        publishArmed()
    }

    /// Klingelt, schlummert oder misst gerade eine Nacht: nur Rückfälle nachführen und die Nacht laufen lassen.
    /// Ausnahme: Eine Messung endet ohne Aufzeichnung, wenn ihr Wecker geändert, aus- oder abgeschaltet wurde (dann
    /// plant `schedule` weiter). Nach einer Reise misst eine begonnene Nacht weiter (`keptNight`), ihr Rückfall steht
    /// fest (`holdFallback`). - Returns: ob `schedule` hier endet.
    private func keepsNight(for plan: WakePlan?) -> Bool {
        if phase.isRinging || phase.isSnoozed {
            // Hängengeblieben (z. B. nach einem Neustart der App ohne Sitzung): abschliessen; das plant neu.
            if ringExpired {
                finishInterrupted()
                return true
            }
            // Klingelt ein vorübergehender Plan, bleibt der Rückfall des echten Weckers aktuell.
            if Transient.kindOf(nightPlan?.alarmId) != nil { keepRealFallback() }
            return true
        }
        guard case .measuring(let running) = phase else { return false }
        let runningTransient = Transient.kindOf(running.alarmId) != nil
        if runningTransient { keepRealFallback() }
        guard running != plan else { return true }
        // Ein vorübergehender Plan bricht nie die Messung eines echten Weckers ab (er weckt per Mitteilung).
        if let plan, Transient.kindOf(plan.alarmId) != nil, !runningTransient {
            scheduleFallback(at: plan.target.addingTimeInterval(Self.fallbackDelay), id: Self.transientFallbackId)
            return true
        }
        if keptNight(running) != nil {
            holdFallback(running)
            return true
        }
        cancelMeasuring()
        return false
    }

    /// Wecker geändert, während die App nicht offen ist (Tiefenaudit AP3-3): Eine neue Sitzung lässt sich dann nicht
    /// planen, die schon geplante für `current` auch nicht beenden. Kann sie `plan` übernehmen
    /// (``NightRules/sessionServes(start:plan:sessionStart:tolerance:delay:)``, wie `startNight` beim Start prüft), bleibt
    /// sie und misst `plan` ab ihrem Start; beim nächsten Öffnen plant die App die passende. - Returns: ob so behandelt.
    private func keepsSession(from current: WakePlan, for plan: WakePlan) -> Bool {
        guard let session, session.state == .scheduled else { return false }
        let start = keptStart ?? Self.sessionStart(current)
        guard NightRules.sessionServes(start: start, plan: plan, sessionStart: Self.sessionStart(plan),
                                       tolerance: Self.startTolerance, delay: Self.fallbackDelay) else { return false }
        DiagStore.shared.log(.scheduled, "session \(Format.logTime(start)) kept for changed target \(Format.logTime(plan.target))")
        keptStart = start
        self.plan = plan
        phase = .scheduled(plan)
        notePlanZone(plan)
        publishArmed()
        return true
    }

    /// Bei geschlossener App lässt sich keine neue Sitzung planen: Ein vorübergehender Plan verdrängt dann nicht die
    /// schon geplante Sitzung des echten Weckers, sondern weckt über seine eigene Rückfall-Mitteilung (in `schedule`
    /// gestellt). Öffnet man die App, bekommt er die Sitzung; danach plant `WatchModel.transientEnded` die Nacht neu.
    /// - Returns: ob so behandelt.
    private func keepsRealSession(for plan: WakePlan) -> Bool {
        guard Transient.kindOf(plan.alarmId) != nil, case .scheduled(let current) = phase, session != nil,
              Transient.kindOf(current.alarmId) == nil else { return false }
        DiagStore.shared.log(.scheduled, "keep session \(Format.logTime(current.target)), transient by notification")
        return true
    }

    /// Kennung des Rückfalls für die Nacht `alarmId`: `transientFallbackId` für Probewecker und Powernap, sonst
    /// `fallbackId`. So entfernt oder ersetzt ein vorübergehender Plan nie den Rückfall des echten Weckers.
    static func fallbackKey(for alarmId: String?) -> String {
        Transient.kindOf(alarmId) != nil ? transientFallbackId : fallbackId
    }

    /// Rückfall des nächsten echten Weckers (`WatchModel.realPlan`) unter `fallbackId` stellen, solange ein
    /// vorübergehender Plan geplant ist, misst oder klingelt; ohne echten Wecker entfernen.
    private func keepRealFallback() {
        guard let real = WatchModel.shared.realPlan else {
            removeFallback([Self.fallbackId])
            return
        }
        scheduleFallback(at: max(real.target, Date()).addingTimeInterval(Self.fallbackDelay))
        // Diagnose: einmal je Zielzeit, nicht bei jedem Neuplanen.
        guard keptFallback != real.target else { return }
        keptFallback = real.target
        DiagStore.shared.log(.scheduled, "real fallback kept \(Format.logTime(real.target))")
    }

    /// Bittet, die App zu öffnen, damit die Sitzung für `plan` geplant wird – nur, wenn sie schon planbar wäre (36 h).
    func notifyOpenApp(for plan: WakePlan) {
        guard Self.sessionStart(plan).timeIntervalSinceNow <= Self.maxLeadTime else { return }
        let content = UNMutableNotificationContent()
        content.title = L("watch_open_app")
        content.sound = .default
        let request = UNNotificationRequest(identifier: Self.openAppId, content: content,
                                            trigger: UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false))
        UNUserNotificationCenter.current().add(request)
        DiagStore.shared.log(.scheduled, "ask to open app for \(Format.logTime(plan.target))")
    }

    /// Start der Sitzung: am Fensterbeginn, aber höchstens `sessionLead` vor der Zielzeit, weil die
    /// Sitzung nach 30 Minuten endet und das Wecken zur Zielzeit sonst auf ihr Ende fiele.
    static func sessionStart(_ plan: WakePlan) -> Date {
        max(plan.windowStart, plan.target.addingTimeInterval(-sessionLead))
    }

    /// Klingeln oder Schlummern dauert schon über die Zeitgrenze hinaus.
    var ringExpired: Bool {
        guard let target = nightPlan?.target else { return true }
        return Date() > target.addingTimeInterval(Self.ringLimit)
    }

    /// Vom System gestartete Sitzung übernehmen. Muss synchron in `handle(_:)` geschehen,
    /// sonst beendet das System die Sitzung.
    func adopt(_ extendedSession: WKExtendedRuntimeSession) {
        extendedSession.delegate = self
        // Eine gemerkte, nicht mehr gültige Sitzung wird beim Start beendet (`extendedRuntimeSessionDidStart`).
        if isStale(extendedSession) { return }
        if let session, session !== extendedSession {
            // Die eigene, geplante oder laufende Sitzung nie verwerfen (Tiefenaudit AP3-4): Die ankommende ist dann eine
            // verwaiste aus der Zeit vor einem Neustart der App und endet bei ihrem Start, ohne zu vibrieren.
            if SessionRules.keepsOwn(session.state) {
                DiagStore.shared.log(.error, "other session started, own kept")
                keepStale(extendedSession)
                return
            }
            retire(session)
        }
        session = extendedSession
    }

    /// Beendet die Sitzung dieser Nacht. Darf die App sie gerade nicht beenden (`SessionRules.retire`), bleibt sie
    /// gemerkt (`staleSessions`) und endet beim nächsten Aktivwerden oder gleich bei ihrem Start (Audit AP-3).
    func invalidateSession() {
        forgetScheduled()
        keptStart = nil
        guard let old = session else { return }
        session = nil
        retire(old)
    }

    /// Meldet dem iPhone, für welche Zielzeit die Uhr scharf ist (Nacht-Check).
    func publishArmed() {
        var armed: Date?
        switch phase {
        case .scheduled(let plan), .measuring(let plan): armed = plan.target
        case .ringing, .snoozed: armed = nightPlan?.target
        case .idle, .waiting: armed = nil
        }
        WatchConnectivityBridge.shared.publish(armedTarget: armed)
    }
}
