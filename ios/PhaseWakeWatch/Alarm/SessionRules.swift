import Foundation
import WatchKit

/// Entscheide rund um die Smart-Alarm-Sitzung ohne Sitzung selbst (testbar): Bedeutung eines Sitzungsendes, Umgang mit
/// einer Sitzung, die nicht mehr gilt, Takt der Vibration und Wecken nach einer abgebrochenen Messung.
///
/// Grundlage (Apple, «Using extended runtime sessions»): Eine mit `start(at:)` geplante Sitzung darf die App nur bei
/// aktiver App mit `invalidate()` beenden; Smart-Alarm-Sitzungen laufen im Hintergrund, bis die Zeit (30 Min) abläuft
/// oder die App sie beendet. Ist die App nicht aktiv, zeigt `notifyUser` den Weckhinweis des Systems. Gründe eines
/// Sitzungsendes (`WKExtendedRuntimeSessionInvalidationReason`): `none` (regulär beendet – die App ruft `invalidate()`
/// nur ohne Delegate auf, ein Ende mit Delegate kommt also vom System, beim Klingeln über «Stopp» im Weckhinweis),
/// `sessionInProgress`, `expired`, `resignedFrontmost`, `suppressedBySystem` und `error`.
enum SessionRules {
    /// Was ein Ende der Sitzung (`didInvalidateWith`) für die Nacht heisst.
    enum Ending: Equatable {
        /// Beim Klingeln regulär beendet (Weckhinweis des Systems gestoppt): wie Stopp auf der Uhr.
        case dismissed
        /// Abgelaufen, vom System beendet oder Fehler: weiterwecken bzw. Rückfall wie bisher.
        case interrupted
    }

    /// Bedeutung des Endes mit Grund `reason`. Nur beim Klingeln (`ringing`) gilt ein reguläres Ende als Stopp; in jedem
    /// anderen Zustand und bei jedem anderen Grund (auch einem künftigen) bleibt es ein Abbruch (fail-safe: lieber
    /// nochmals wecken als gar nicht).
    static func ending(_ reason: WKExtendedRuntimeSessionInvalidationReason, ringing: Bool) -> Ending {
        guard ringing, reason == .none else { return .interrupted }
        return .dismissed
    }

    /// Name des Grundes fürs Diagnose-Protokoll, mit Rohwert (unbekannte Gründe künftiger watchOS-Versionen bleiben so
    /// lesbar).
    static func reasonName(_ reason: WKExtendedRuntimeSessionInvalidationReason) -> String {
        let name = switch reason {
        case .none: "none"
        case .sessionInProgress: "sessionInProgress"
        case .expired: "expired"
        case .resignedFrontmost: "resignedFrontmost"
        case .error: "error"
        default: "other"
        }
        return "\(name)/\(reason.rawValue)"
    }

    /// Wie mit einer Sitzung verfahren wird, die nicht mehr gilt (neu geplant, gestoppt, verwaist).
    enum Retire: Equatable {
        /// Jetzt beenden (`invalidate()` ist erlaubt).
        case now
        /// Läuft schon, App aber nicht aktiv: jetzt versuchen und gemerkt behalten, bis das Ende gemeldet ist; beim
        /// nächsten Aktivwerden nochmals. Vibration endet über den Wiederhol-Takt (`hapticStep`).
        case tryNowAndKeep
        /// Geplant (`start(at:)`), App nicht aktiv: `invalidate()` ist nicht erlaubt. Gemerkt behalten und beenden,
        /// sobald die App aktiv ist; startet sie vorher, wird sie gleich ohne Vibration beendet.
        case whenActive
        /// Schon beendet: nichts zu tun.
        case drop
    }

    static func retire(_ state: WKExtendedRuntimeSessionState, active: Bool) -> Retire {
        switch state {
        case .invalid: return .drop
        case .notStarted: return .now
        case .scheduled: return active ? .now : .whenActive
        case .running: return active ? .now : .tryNowAndKeep
        @unknown default: return active ? .now : .tryNowAndKeep
        }
    }

    /// Vibration beim Klingeln nach `elapsed` Sekunden: Steigerung wie auf Android (sanft, deutlich, anhaltend – je eine
    /// Minute) und Abstand bis zur nächsten. Klingelt diese Sitzung nicht mehr (`ringing` falsch: gestoppt,
    /// geschlummert, Sitzung ersetzt), Abstand 0: Das beendet die Wiederholung, auch wenn `invalidate()` im Hintergrund
    /// nicht greift.
    static func hapticStep(elapsed: TimeInterval, ringing: Bool) -> (type: WKHapticType, interval: TimeInterval) {
        guard ringing else { return (.stop, 0) }
        switch elapsed {
        case ..<60: return (.notification, 3)
        case ..<120: return (.retry, 2)
        default: return (.failure, 1.5)
        }
    }

    /// Wann nach einem Abbruch mitten in der Messung sofort geweckt wird: vor der Zielzeit nie (es weckt der feste
    /// Rückfall kurz nach der Zielzeit, der schon steht), ab der Zielzeit jetzt. - Returns: Zeitpunkt oder `nil`.
    static func interruptedWake(now: Date, target: Date) -> Date? {
        now >= target ? now : nil
    }
}
