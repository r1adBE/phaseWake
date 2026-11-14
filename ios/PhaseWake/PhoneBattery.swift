import Foundation
import PhaseWakeCore
import UIKit

/// Akku je Nacht am iPhone (r1AD 14.11.2026), wie `BatteryNight` und `PhoneBattery` (Android); Rechnung in ``BatteryTrack``.
/// Das iPhone läuft nachts oft nicht; es misst darum bei Ereignissen, die es ohnehin bekommt: Beginn, wenn die Uhr eine Nacht
/// zu messen beginnt (Befehl ``SyncKeys/nightBegin``), Ende beim endgültigen «stop» der Uhr oder, falls das fehlt, wenn ihre
/// Nacht gleich nach dem Ende ankommt. Verspätet zugestellt: kein Beginn bzw. Ende, also kein Wert statt eines falschen.
/// Laden meldet iOS nur, solange die App läuft (`UIDevice.batteryStateDidChangeNotification`); einen Auftrag beim
/// Anschliessen wie auf Android gibt es nicht. Ein unbeobachteter Wechsel ans oder vom Ladegerät (Stand bei Beginn und Ende
/// verschieden) oder ein Anstieg ohne Ladegerät ergibt keinen Wert (``BatteryTrack/used(_:)``).
@MainActor
enum PhoneBattery {
    private static let openKey = "batteryNight.open"
    private static let doneKey = "batteryNight.done"
    private static var observer: NSObjectProtocol?

    /// Akkustand jetzt; unbekannt (z. B. Simulator): `nil`.
    static func read() -> (at: Date, percent: Int, plugged: Bool)? {
        let device = UIDevice.current
        device.isBatteryMonitoringEnabled = true
        guard device.batteryLevel >= 0, device.batteryState != .unknown else { return nil }
        return (Date(), min(100, max(0, Int((device.batteryLevel * 100).rounded()))), device.batteryState != .unplugged)
    }

    /// Anschliessen und Trennen melden, solange die App läuft. Einmal beim Start.
    static func listen() {
        guard observer == nil else { return }
        // Dauerhaft gehalten: Ohne Überwachung meldet iOS kein Laden (kostet praktisch nichts).
        BatteryMonitoring.shared.acquire()
        observer = NotificationCenter.default.addObserver(forName: UIDevice.batteryStateDidChangeNotification, object: nil, queue: .main) { _ in
            Task { @MainActor in charged() }
        }
    }

    /// Die Uhr beginnt eine Nacht (frisch angekommen): ab jetzt messen; läuft schon eine, kommt ein Zwischenstand dazu.
    static func begin() {
        guard let r = read() else { return }
        update { BatteryTrack.begin($0, at: r.at, percent: r.percent, plugged: r.plugged) }
    }

    /// Ladegerät angeschlossen oder getrennt: Ladebeginn bzw. -ende einer laufenden Nacht.
    static func charged() {
        guard let r = read() else { return }
        update { BatteryTrack.charge($0, at: r.at, percent: r.percent, plugged: r.plugged) }
    }

    /// Endgültiges «stop» der Uhr (frisch): Ende der Nacht. Ist ihre Nacht schon gespeichert, kommt der Akku gleich dazu,
    /// sonst wartet er auf sie (``withPhone(_:now:)``).
    static func stopped() {
        guard let track = end() else { return }
        let model = PhoneModel.shared
        if let night = model.nights.last(where: { $0.phoneBattery == nil && BatteryTrack.belongs(track, to: $0) }) {
            model.add(night: night.with(phoneBattery: track))
        } else {
            UserDefaults.standard.set(BatteryTrack.encode(track), forKey: doneKey)
        }
    }

    /// Nacht der Uhr kommt an: Akku des iPhones dazu, wenn er zu ihr gehört – der beim «stop» gemerkte, sonst (nur wenn sie
    /// gleich nach ihrem Ende ankommt, ``BatteryTrack/arrivedFresh(_:now:)``) jetzt beendet.
    static func withPhone(_ night: NightRecord, now: Date = Date()) -> NightRecord {
        if night.phoneBattery != nil { return night }
        if let done = BatteryTrack.decode(UserDefaults.standard.string(forKey: doneKey)), BatteryTrack.belongs(done, to: night) {
            UserDefaults.standard.removeObject(forKey: doneKey)
            return night.with(phoneBattery: done)
        }
        guard BatteryTrack.arrivedFresh(night, now: now), let track = end(), BatteryTrack.belongs(track, to: night) else { return night }
        return night.with(phoneBattery: track)
    }

    /// Ende der laufenden Nacht (ins Protokoll mit Verbrauch); `nil`, wenn keine lief.
    private static func end() -> [BatteryPoint]? {
        let open = BatteryTrack.decode(UserDefaults.standard.string(forKey: openKey)) ?? []
        guard !open.isEmpty else { return nil }
        UserDefaults.standard.removeObject(forKey: openKey)
        guard let r = read(), let track = BatteryTrack.end(open, at: r.at, percent: r.percent, plugged: r.plugged), let last = track.last else {
            DiagStore.shared.log(.battery, "night battery end unknown")
            return nil
        }
        DiagStore.shared.log(.battery, "night battery \(BatteryTrack.describe(last)), used \(BatteryTrack.used(track).map { "\($0)%" } ?? "unknown")")
        return track
    }

    /// Ändert die laufende Nacht und protokolliert einen neuen Punkt.
    private static func update(_ change: ([BatteryPoint]) -> [BatteryPoint]) {
        let before = BatteryTrack.decode(UserDefaults.standard.string(forKey: openKey)) ?? []
        let after = change(before)
        guard after != before, let last = after.last else { return }
        UserDefaults.standard.set(BatteryTrack.encode(after), forKey: openKey)
        DiagStore.shared.log(.battery, "night battery \(BatteryTrack.describe(last))")
    }
}

/// Gemeinsamer Halter der Akku-Überwachung des iPhones (`UIDevice.isBatteryMonitoringEnabled`): an, solange sie jemand
/// hält (``PhoneBattery`` dauerhaft, die Geräuschaufnahme während sie läuft); erst wenn keiner sie mehr hält, aus. So schaltet
/// das Ende einer Aufnahme die Ladeerkennung von «Akku je Nacht» nicht ab.
@MainActor
final class BatteryMonitoring {
    static let shared = BatteryMonitoring { UIDevice.current.isBatteryMonitoringEnabled = $0 }

    private let apply: (Bool) -> Void
    /// Wie viele die Überwachung gerade halten.
    private(set) var holders = 0

    /// `apply` schaltet die Überwachung (im Test ein Ersatz).
    init(apply: @escaping (Bool) -> Void) { self.apply = apply }

    func acquire() {
        holders += 1
        apply(true)
    }

    /// Ein Halter weniger; ohne Halter aus. Mehr Freigaben als Halter bleiben bei 0.
    func release() {
        holders = max(0, holders - 1)
        if holders == 0 { apply(false) }
    }
}

