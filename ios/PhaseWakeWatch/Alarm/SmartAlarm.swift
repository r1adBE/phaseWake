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
            cancelMeasuring()
        }
        guard let plan else {
            invalidateSession()
            removeFallback()
            phase = .idle
            publishArmed()
            return
        }
        scheduleFallback(at: plan.target.addingTimeInterval(Self.fallbackDelay))
        if case .scheduled(let current) = phase, current == plan, session != nil { return }

        let active = WKApplication.shared().applicationState == .active
        let start = Self.sessionStart(plan)
        let tooEarly = start.timeIntervalSinceNow > Self.maxLeadTime
        guard active, !tooEarly else {
            // Eine schon geplante Sitzung für einen anderen Plan ist überholt.
            if case .scheduled(let current) = phase, current != plan { invalidateSession() }
            if case .scheduled = phase, session != nil, !tooEarly { return }
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
        publishArmed()
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
