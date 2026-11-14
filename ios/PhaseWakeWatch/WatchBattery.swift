import Foundation
import PhaseWakeCore
import WatchKit

/// Akku je Nacht auf der Apple Watch (r1AD 14.11.2026), wie `BatteryNight` auf der Galaxy Watch; Rechnung in
/// ``BatteryTrack``. Die laufende Nacht steht in `UserDefaults` und übersteht so ein Ende der App.
/// watchOS meldet das Laden nicht: Solange gemessen wird, prüft der Takt der Messung den Ladestand (``poll()``). Läuft die
/// App nicht (Einschlaf-Messung und Aufzeichnung laufen über den Bewegungsrekorder des Systems), sieht sie ein Laden erst
/// beim nächsten Messpunkt; ein solcher Wechsel oder ein Anstieg ohne Ladegerät ergibt keinen Wert statt eines falschen.
@MainActor
enum WatchBattery {
    private static let openKey = "batteryNight.open"

    /// Akkustand jetzt; unbekannt (z. B. Simulator): `nil`.
    static func read() -> (at: Date, percent: Int, plugged: Bool)? {
        let device = WKInterfaceDevice.current()
        device.isBatteryMonitoringEnabled = true
        guard device.batteryLevel >= 0, device.batteryState != .unknown else { return nil }
        return (Date(), min(100, max(0, Int((device.batteryLevel * 100).rounded()))), device.batteryState != .unplugged)
    }

    /// Die Uhr beginnt zu messen («Gute Nacht», Einschlaf-Messung, Aufzeichnung oder Weckfenster): Akkustand festhalten.
    /// Beginnt damit eine neue Nacht, erfährt es das iPhone (``SyncKeys/nightBegin``, nur sofort zugestellt sinnvoll) und
    /// misst ab dann seinen eigenen Akku. Probewecker und Powernap zählen nicht.
    static func begin(alarmId: String?, target: Date?) {
        if let alarmId, Transient.kindOf(alarmId) != nil { return }
        guard let r = read() else { return }
        let before = openTrack
        update { BatteryTrack.begin($0, at: r.at, percent: r.percent, plugged: r.plugged) }
        if !BatteryTrack.isOpen(before, now: r.at) {
            WatchConnectivityBridge.shared.send(command: SyncKeys.nightBegin, alarmId: alarmId ?? "", target: target)
        }
    }

    /// Takt der Messung: Ladegerät angeschlossen oder getrennt → Ladebeginn bzw. -ende.
    static func poll() {
        guard let r = read() else { return }
        update { BatteryTrack.charge($0, at: r.at, percent: r.percent, plugged: r.plugged) }
    }

    /// Ende der Nacht: die fertigen Punkte (ins Protokoll mit Verbrauch); `nil`, wenn keine lief.
    static func end() -> [BatteryPoint]? {
        let current = openTrack
        guard !current.isEmpty else { return nil }
        UserDefaults.standard.removeObject(forKey: openKey)
        guard let r = read(), let track = BatteryTrack.end(current, at: r.at, percent: r.percent, plugged: r.plugged), let last = track.last else {
            DiagStore.shared.log(.battery, "night battery end unknown")
            return nil
        }
        DiagStore.shared.log(.battery, "night battery \(BatteryTrack.describe(last)), used \(BatteryTrack.used(track).map { "\($0)%" } ?? "unknown")")
        return track
    }

    /// Laufende Nacht verwerfen (`why` ins Protokoll), z. B. Aufzeichnung ohne Weck-Nacht beendet.
    static func drop(_ why: String) {
        guard !openTrack.isEmpty else { return }
        UserDefaults.standard.removeObject(forKey: openKey)
        DiagStore.shared.log(.battery, "night battery dropped: \(why)")
    }

    private static var openTrack: [BatteryPoint] { BatteryTrack.decode(UserDefaults.standard.string(forKey: openKey)) ?? [] }

    /// Ändert die laufende Nacht und protokolliert einen neuen Punkt.
    private static func update(_ change: ([BatteryPoint]) -> [BatteryPoint]) {
        let before = openTrack
        let after = change(before)
        guard after != before, let last = after.last else { return }
        UserDefaults.standard.set(BatteryTrack.encode(after), forKey: openKey)
        DiagStore.shared.log(.battery, "night battery \(BatteryTrack.describe(last))")
    }
}
