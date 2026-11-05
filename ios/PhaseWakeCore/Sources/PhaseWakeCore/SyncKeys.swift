import Foundation

/// Schlüssel der Verbindung iPhone ↔ Uhr (WatchConnectivity).
public enum SyncKeys {
    /// Wecker im Format «phasewake-alarms 1»; bei Befehlen die Kennung des Weckers.
    public static let alarms = "alarms"
    /// Zeitpunkt der letzten Änderung der Wecker in ms.
    public static let updatedAt = "updatedAt"
    /// Eine Nacht im Format «phasewake-night 1», Uhr → iPhone.
    public static let night = "night"
    /// Name des Befehls (`ring`, `stop`, `snooze`, `clearHistory`).
    public static let command = "command"
    /// Befehl Uhr → iPhone: Die Uhr weckt jetzt, das iPhone weckt mit.
    public static let ring = "ring"
    /// Befehl: Wecken beendet (in beide Richtungen).
    public static let stop = "stop"
    /// Befehl iPhone → Uhr: Schlummern.
    public static let snooze = "snooze"
    /// Befehl iPhone → Uhr: Verlauf auf der Uhr löschen.
    public static let clearHistory = "clearHistory"
    /// Uhr → iPhone bei «ring» und «stop»: Zielzeit der Nacht und Sendezeit (ms seit 1970), bei «ring»
    /// auch die verbleibenden Schlummer-Runden. iPhone → Uhr: nur die Sendezeit. Ältere Versionen schicken nichts davon.
    public static let target = "target"
    /// Sendezeit in ms seit 1970 (siehe `target`).
    public static let sentAt = "sentAt"
    /// Verbleibende Schlummer-Runden bei «ring» (siehe `target`).
    public static let snoozesLeft = "snoozesLeft"
    /// Höchstalter eines «ring» (und eines «stop»/«snooze» vom iPhone) in Millisekunden; ein später zugestelltes wird verworfen.
    public static let maxRingAgeMs: Int64 = 2 * 60_000
    /// Uhr → iPhone im Anwendungskontext: Zielzeit (ms), für die die Uhr scharf ist; 0 = nicht scharf.
    public static let armedTarget = "armedTarget"
    /// Uhr → iPhone im Anwendungskontext: Akkustand der Uhr in Prozent und Zeitpunkt der Messung (ms).
    /// Ältere Uhr-Versionen schicken beides nicht.
    public static let battery = "battery"
    /// Zeitpunkt der Akkumessung in ms seit 1970 (siehe `battery`).
    public static let batteryAt = "batteryAt"
    /// Älter als das gilt der gemeldete Akkustand als unbekannt (wie `WearSync.watchBattery()` auf Android).
    public static let maxBatteryAgeMs: Int64 = 6 * 3600_000

    // MARK: Runde 3 (gleiche Bedeutung wie die Pfade des Data Layer auf Android)

    /// Anwendungskontext iPhone → Uhr: Feiertage im Format von `Holidays.encode`.
    public static let holidays = "holidays"
    /// Anwendungskontext iPhone → Uhr: Schlafziel in Minuten, nur für den Einschlafpunkt auf dem Ring der Uhr.
    /// Fehlt bei älteren iPhone-Versionen; die Uhr zeigt dann `SleepGoal.defaultMinutes`.
    public static let sleepGoal = "sleepGoalMinutes"
    /// Befehl iPhone → Uhr: Probewecker, mit `payload` = `Transient.encode`.
    public static let test = "test"
    /// Befehl Uhr → iPhone: Probewecker empfangen, mit `id` = Kennung des Plans.
    public static let testAck = "testAck"
    /// Befehl in beide Richtungen: Powernap, mit `payload` = `Transient.encode`.
    public static let nap = "nap"
    /// Befehl in beide Richtungen: vorübergehenden Plan (Probewecker, Powernap) verwerfen, mit `id`.
    public static let transientCancel = "transientCancel"
    /// Befehl iPhone → Uhr: Diagnose-Bericht anfordern; die Antwort trägt `payload` = `DiagReports.encode`.
    public static let diagRequest = "diagRequest"
    /// Inhalt eines Befehls (Plan oder Bericht).
    public static let payload = "payload"
    /// Kennung eines vorübergehenden Plans.
    public static let id = "id"
    /// Uhr → iPhone im Anwendungskontext: Felder je Weckerzeile, die die Uhr kennt (`Codec.alarmFields`). Fehlt der
    /// Schlüssel bei einem Kontext, der sonst von der Uhr stammt, ist es eine ältere Uhr (zählt als 0).
    public static let alarmFields = "alarmFields"
    /// Uhr → iPhone im Anwendungskontext: Erlaubnis für Mitteilungen auf der Uhr (`WatchNotifications.rawValue`), für
    /// den Rückfall-Wecker. Fehlt bei älteren Uhr-Versionen und solange die Uhr noch nicht gefragt hat (dann keine Warnung).
    public static let watchNotifications = "watchNotifications"
    /// Uhr → iPhone im Anwendungskontext, zusammen mit `watchNotifications`: Zeitpunkt (ms seit 1970), zu dem die Uhr die
    /// Erlaubnis geprüft hat. Fehlt bei älteren Uhr-Versionen (dann kein Hinweis «veraltet»).
    public static let watchNotificationsAt = "watchNotificationsAt"
}

/// Erlaubnis für Mitteilungen auf der Apple Watch, wie die Uhr sie dem iPhone meldet (`SyncKeys.watchNotifications`).
/// Ohne sie weckt der Rückfall nicht, wenn die Messung der Nacht nicht läuft. Nur Apple.
public enum WatchNotifications: Int64, Sendable {
    /// Nicht erlaubt (abgelehnt oder nur «Ohne Ton zustellen»).
    case blocked = 0
    /// Erlaubt, auch zeitkritisch.
    case allowed = 1
    /// Erlaubt, aber «Zeitkritische Mitteilungen» aus: Ein Fokus wie «Schlafen» hält den Rückfall zurück.
    case notTimeSensitive = 2

    /// Ob das iPhone im Nacht-Check warnt.
    public var isProblem: Bool { self != .allowed }

    /// Älter als das gilt der gemeldete Stand im Nacht-Check als veraltet (3 Tage): Die Uhr hat die Erlaubnis so lange
    /// nicht mehr geprüft, eine Änderung in den Einstellungen der Uhr kann dem iPhone entgangen sein.
    public static let maxAge: TimeInterval = 3 * 24 * 3600

    /// Ob der Stand, den die Uhr zu `checkedAt` geprüft hat, veraltet ist (Hinweis im Nacht-Check). Ohne Zeitpunkt
    /// (ältere Uhr) nie; ein Zeitpunkt in der Zukunft (Uhren nicht gleich gestellt) auch nicht.
    public static func isOutdated(checkedAt: Date?, now: Date) -> Bool {
        guard let checkedAt else { return false }
        return now.timeIntervalSince(checkedAt) > maxAge
    }
}
