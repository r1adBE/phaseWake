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
/// Gemessen wird wie auf Android (Regel in docs/PHASENERKENNUNG.md).
///
/// Grenzen von watchOS, die hier abgefangen werden:
/// - Planen geht nur bei aktiver App und höchstens 36 Stunden im Voraus. Sonst wartet der Plan
///   (`waiting`), bis die App das nächste Mal offen ist; das iPhone zeigt das im Nacht-Check.
/// - Lehnt das System die Sitzung ab oder bricht sie ab, weckt eine lokale Mitteilung kurz nach
///   der Zielzeit (Rückfall). Sie bleibt beim Klingeln stehen und wird erst beim Beenden entfernt.
/// - Endet die Sitzung während des Klingelns oder Schlummerns (Ablauf, System), weckt sofort eine
///   Mitteilung, und die Nacht wird trotzdem abgeschlossen; ebenso spätestens 1 h nach der Zielzeit.
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
    nonisolated static let fallbackId = "phasewake.fallback"
    /// Eigene Kennung für das Wecken nach einer abgebrochenen Sitzung: Neu planen ersetzt sie nicht.
    static let interruptedId = "phasewake.fallback.interrupted"
    /// Eigene Kennung für das Wecken eines vorübergehenden Plans, der die Sitzung der Nacht nicht verdrängen durfte.
    static let transientFallbackId = "phasewake.fallback.transient"
    /// Mitteilung «Öffne Phase Wake auf der Uhr», wenn die Sitzung der Nacht nur bei offener App planbar ist.
    static let openAppId = "phasewake.openApp"
    static let stateKey = "smartAlarmNight"

    /// Zustand der Nacht; gesetzt nur von `SmartAlarm` und seinen Erweiterungen (Dateien `SmartAlarm+…`).
    var phase: Phase = .idle

    @ObservationIgnored var session: WKExtendedRuntimeSession?
    /// Plan der laufenden Sitzung (nach Schlummern: der Schlummer-Termin).
    @ObservationIgnored var plan: WakePlan?
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
    @ObservationIgnored var ticker: Timer?
    @ObservationIgnored var deadline: Timer?
    /// Beginn des Klingelns, für die Steigerung der Vibration.
    @ObservationIgnored var hapticStart = Date()

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

    /// Puls lesen (zweite Stimme der Erkennung) und Mitteilungen (Rückfall) – beim ersten Start fragen.
    func requestAccess() async {
        if HKHealthStore.isHealthDataAvailable(), let heartRate = HKQuantityType.quantityType(forIdentifier: .heartRate) {
            try? await health.requestAuthorization(toShare: [], read: [heartRate])
        }
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
    }

    // MARK: Planen

    /// Plant die Sitzung für `plan`. Gelingt das nicht (App nicht aktiv, zu weit voraus), wartet der
    /// Plan, und der Rückfall steht trotzdem.
    func schedule(_ plan: WakePlan?) {
        if phase.isRinging || phase.isSnoozed {
            // Hängengeblieben (z. B. nach einem Neustart der App ohne Sitzung): abschliessen; das plant neu.
            if ringExpired { finishInterrupted() }
            return
        }
        if case .measuring(let running) = phase {
            // Wecker während der Messung geändert, aus- oder abgeschaltet: Messung ohne Aufzeichnung beenden.
            guard running != plan else { return }
            // Ein vorübergehender Plan bricht nie die Messung eines echten Weckers ab (er weckt per Mitteilung).
            if let plan, Transient.kindOf(plan.alarmId) != nil, Transient.kindOf(running.alarmId) == nil {
                scheduleFallback(at: plan.target.addingTimeInterval(Self.fallbackDelay), id: Self.transientFallbackId)
                return
            }
            cancelMeasuring()
        }
        guard let plan else {
            invalidateSession()
            removeFallback()
            phase = .idle
            publishArmed()
            return
        }
        let active = WKApplication.shared().applicationState == .active
        if !active, keepsRealSession(for: plan) { return }
        scheduleFallback(at: plan.target.addingTimeInterval(Self.fallbackDelay))
        if case .scheduled(let current) = phase, current == plan, session != nil { return }

        let start = Self.sessionStart(plan)
        let tooEarly = start.timeIntervalSinceNow > Self.maxLeadTime
        guard active, !tooEarly else {
            // Eine schon geplante Sitzung für einen anderen Plan ist überholt.
            if case .scheduled(let current) = phase, current != plan { invalidateSession() }
            if case .scheduled = phase, session != nil, !tooEarly { return }
            if phase != .waiting(plan) { DiagStore.shared.log(.scheduled, "waiting \(Format.time(plan.target)) active \(active)") }
            phase = .waiting(plan)
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
        DiagStore.shared.log(.scheduled, "session \(Format.time(start)) target \(Format.time(plan.target))")
        publishArmed()
    }

    /// Bei geschlossener App lässt sich keine neue Sitzung planen: Ein vorübergehender Plan verdrängt dann nicht die
    /// schon geplante Sitzung des echten Weckers, sondern weckt über eine eigene Rückfall-Mitteilung. Öffnet man die
    /// App, bekommt er die Sitzung; danach plant `WatchModel.transientEnded` die Nacht neu. - Returns: ob so behandelt.
    private func keepsRealSession(for plan: WakePlan) -> Bool {
        guard Transient.kindOf(plan.alarmId) != nil, case .scheduled(let current) = phase, session != nil,
              Transient.kindOf(current.alarmId) == nil else { return false }
        scheduleFallback(at: plan.target.addingTimeInterval(Self.fallbackDelay), id: Self.transientFallbackId)
        DiagStore.shared.log(.scheduled, "keep session \(Format.time(current.target)), transient by notification")
        return true
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
        DiagStore.shared.log(.scheduled, "ask to open app for \(Format.time(plan.target))")
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
        if let session, session !== extendedSession { session.invalidate() }
        session = extendedSession
    }

    func invalidateSession() {
        session?.delegate = nil
        session?.invalidate()
        session = nil
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

    // MARK: Rückfall

    func scheduleFallback(at date: Date, id: String = SmartAlarm.fallbackId) {
        let content = UNMutableNotificationContent()
        content.title = L("ring_greeting")
        content.body = L("ring_reason_deadline")
        content.sound = .default
        content.interruptionLevel = .timeSensitive
        let interval = max(1, date.timeIntervalSinceNow)
        let request = UNNotificationRequest(
            identifier: id,
            content: content,
            trigger: UNTimeIntervalNotificationTrigger(timeInterval: interval, repeats: false)
        )
        UNUserNotificationCenter.current().add(request)
    }

    func removeFallback(_ ids: [String] = [SmartAlarm.fallbackId]) {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ids)
    }
}
