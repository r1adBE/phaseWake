import Foundation
import PhaseWakeCore
import UserNotifications
import WatchKit

/// Hinweise der Lernphase auf der Apple Watch (Lern-Übersicht, Entscheid r1AD 13.11.2026; wie `LearnNotice` der Galaxy
/// Watch), damit niemand nach 7 Tagen ohne gezählte Nacht dasteht, ohne den Grund zu kennen:
/// - Warnung am Abend: Eine Hintergrund-Aktualisierung (`scheduleRefresh`) bzw. das Öffnen der App 60 Min vor dem
///   frühesten Start des Rekorders prüft den Akku; liegt er unter ``Learning/minBattery`` und lädt die Uhr nicht, kommt
///   einmal je Nacht «Lade die Uhr …» (``LearnNights/shouldWarn(active:battery:charging:now:recordStart:)``). watchOS
///   bestimmt den genauen Zeitpunkt; ohne Aktualisierung in diesem Fenster bleibt die Warnung aus.
/// - Nacht nicht aufgezeichnet (`logSkip`): Meldung ans iPhone (Benutzerdaten, ``LearnSkip``) und bei Akku, fehlenden
///   Sensoren oder zu spätem Start eine Mitteilung mit dem Grund.
extension LearnRecorder {
    /// Zielzeit der Nacht, für die die Warnung schon kam (einmal je Nacht, auch nach einem Neustart der App).
    private static let warnedKey = "learnWarnedTarget"
    /// Kennung der Mitteilung: eine neue ersetzt die frühere.
    private static let noticeId = "phasewake.learn"

    /// Zeitpunkt der Warnung für den nächsten echten Wecker (``LearnNights/warnAt(_:)`` vor `nightStart`); `nil` ohne.
    static func learnWarnAt() -> Date? {
        nightStart().map(LearnNights.warnAt)
    }

    /// Warnt einmal je Nacht, wenn jetzt das Fenster der Warnung ist und der Akku nicht reicht (siehe oben).
    func checkLearnWarning() {
        guard snapshot.active, let plan = WatchModel.shared.realPlan, let start = Self.nightStart() else { return }
        let target = plan.target.timeIntervalSince1970
        guard SettingsFile.watchSession.double(forKey: Self.warnedKey) != target else { return }
        let device = WKInterfaceDevice.current()
        device.isBatteryMonitoringEnabled = true
        let charging = device.batteryState == .charging || device.batteryState == .full
        let battery = Self.battery()
        guard let level = battery,
              LearnNights.shouldWarn(active: true, battery: battery, charging: charging, now: Date(), recordStart: start) else { return }
        SettingsFile.watchSession.set(target, forKey: Self.warnedKey)
        DiagStore.shared.log(.battery, "learn warn battery \(level)%")
        Self.notify(L("watch_learn_warn", Int64(level), Int64(Learning.minBattery)))
    }

    /// Die Lernphase zeichnet die Nacht nicht auf (`skip`): Meldung ans iPhone und, ausser bei einer laufenden
    /// Aufzeichnung von Hand (sie zählt selbst), eine Mitteilung mit dem Grund.
    func noteLearnSkip(_ skip: LearnSkip) {
        WatchConnectivityBridge.shared.send(learnSkip: skip)
        switch skip.reason {
        case .battery:
            Self.notify(L("watch_learn_skip_battery", Int64(skip.battery ?? 0), Int64(Learning.minBattery)))
        case .sensors:
            Self.notify(L("watch_learn_skip_sensors"))
        case .late:
            Self.notify(L("watch_learn_skip_late"))
        case .manual:
            break
        }
    }

    /// Mitteilung «Lernphase» mit `text`, gleich (ohne Auslöser); ohne Erlaubnis zeigt watchOS nichts.
    private static func notify(_ text: String) {
        let content = UNMutableNotificationContent()
        content.title = L("watch_learn_title")
        content.body = text
        content.sound = .default
        let request = UNNotificationRequest(identifier: noticeId, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request) { error in
            guard let error else { return }
            DiagStore.shared.log(.error, "learn notice not shown \((error as NSError).code)")
        }
    }
}
