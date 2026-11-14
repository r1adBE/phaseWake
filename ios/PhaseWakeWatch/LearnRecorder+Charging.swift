import Foundation
import PhaseWakeCore
import WatchKit

/// Laden der Apple Watch während der Einschlaf-Messung (Prüfrunde 2 F-1, HG-3): Die Galaxy Watch meldet Netzteil an/ab
/// sofort; eine App auf der Apple Watch läuft nachts nur zeitweise. Darum tastet die Uhr den Ladezustand
/// (`WKInterfaceDevice.batteryState`) bei jedem Durchgang ab (Öffnen, Hintergrund-Aktualisierung, Beginn der Weck-Sitzung)
/// und stellt die Hintergrund-Aktualisierung, solange die Messung vor dem Weckfenster läuft, auf höchstens
/// ``chargeCheck``. Aus den Stichproben ergeben sich Zeiten am Ladegerät (``chargeSpans(until:)``); ihre Epochen gelten
/// als leer und verlängern die Zeitgrenze (``RecorderOnset/measure(motions:since:threshold:personalFloor:latest:extendCap:heartRates:heartLead:away:)``).
/// Zwischen zwei Stichproben weiss die Uhr nichts: Eine Zeit am Ladegerät reicht bis zur nächsten Stichprobe, die nicht
/// lädt, ohne solche höchstens ``chargeCheck`` über die letzte hinaus. Was dazwischen fehlt, deckt die Puls-Regel ab
/// (am Ladegerät misst die Uhr keinen Puls).
extension LearnRecorder {
    /// Höchster Abstand der Stichproben, solange die Messung läuft (watchOS kann ihn vergrössern).
    nonisolated static let chargeCheck: TimeInterval = 15 * 60
    private static let chargeSamplesKey = "chargeSamples"

    /// Stichproben seit dem Start des Rekorders: je [Zeitpunkt (s seit 1970), 1 = lädt / 0 = nicht].
    private var chargeSamples: [[Double]] {
        get { (SettingsFile.watchSession.object(forKey: Self.chargeSamplesKey) as? [[Double]]) ?? [] }
        set { SettingsFile.watchSession.set(newValue, forKey: Self.chargeSamplesKey) }
    }

    /// Neuer Rekorder ab `from`: frühere Stichproben verwerfen, die erste gleich nehmen.
    func resetChargeSamples(from: Date) {
        chargeSamples = []
        noteCharging(now: from)
    }

    /// Ob die Uhr gerade lädt (oder voll am Ladegerät hängt).
    static func charging() -> Bool {
        let device = WKInterfaceDevice.current()
        device.isBatteryMonitoringEnabled = true
        return device.batteryState == .charging || device.batteryState == .full
    }

    /// Stichprobe jetzt, solange der Rekorder läuft (höchstens 200 je Nacht).
    func noteCharging(now: Date = Date()) {
        guard recorderRunning else { return }
        var samples = chargeSamples
        samples.append([now.timeIntervalSince1970, Self.charging() ? 1 : 0])
        chargeSamples = Array(samples.suffix(200))
    }

    /// Zeiten am Ladegerät bis `until` aus den Stichproben (siehe oben).
    func chargeSpans(until: Date) -> [(from: Date, until: Date)] {
        Self.chargeSpans(chargeSamples.compactMap { $0.count == 2 ? (at: Date(timeIntervalSince1970: $0[0]), charging: $0[1] != 0) : nil },
                         until: until, gap: Self.chargeCheck)
    }

    /// Zeiten am Ladegerät aus `samples` (zeitlich geordnet oder nicht): ab einer ladenden Stichprobe bis zur nächsten,
    /// die nicht lädt; ohne solche bis höchstens `gap` nach der letzten ladenden, nie über `until`.
    nonisolated static func chargeSpans(_ samples: [(at: Date, charging: Bool)], until: Date, gap: TimeInterval) -> [(from: Date, until: Date)] {
        var spans: [(from: Date, until: Date)] = []
        var open: Date?
        var last: Date?
        for sample in samples.sorted(by: { $0.at < $1.at }) {
            if sample.charging {
                if open == nil { open = sample.at }
                last = sample.at
            } else if let from = open {
                spans.append((from: from, until: min(sample.at, until)))
                open = nil
            }
        }
        if let from = open, let last { spans.append((from: from, until: min(last.addingTimeInterval(gap), until))) }
        return spans.filter { $0.until > $0.from }
    }

    /// Hintergrund-Aktualisierung für die nächste Stichprobe: in ``chargeCheck``, solange der Rekorder für die
    /// Einschlaf-Messung läuft und das Weckfenster der Nacht nicht begonnen hat; sonst `nil`.
    func chargeRefresh(now: Date) -> Date? {
        guard autoOnset, recorderRunning, let plan = WatchModel.shared.realPlan, now < plan.windowStart else { return nil }
        return min(now.addingTimeInterval(Self.chargeCheck), plan.windowStart)
    }
}
