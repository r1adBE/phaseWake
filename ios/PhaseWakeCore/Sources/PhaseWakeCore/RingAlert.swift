import Foundation

/// Weg, auf dem der Weckbildschirm nach vorn kommt (nur Android: Handy und Galaxy Watch). Apple zeigt das
/// Wecken über das System (AlarmKit, Smart-Alarm-Sitzung); Gegenstück zu `RingRoute` (Kotlin) für gleiche Fälle.
public enum RingRoute: String, CaseIterable, Sendable {
    /// Die App hat ein sichtbares Fenster: Sie darf den Weckbildschirm selbst öffnen.
    case direct = "DIRECT"
    /// Begleit-App einer verbundenen Uhr (CompanionDeviceManager): Start aus dem Hintergrund erlaubt, auch bei
    /// Display an und entsperrt. Nur Android-Handy; iOS kennt keine solche Ausnahme.
    case companion = "COMPANION"
    /// Display aus oder gesperrt, Vollbild erlaubt: Das System öffnet den Weckbildschirm über die Weck-Mitteilung.
    case fullScreen = "FULL_SCREEN"
    /// Gerät in Gebrauch oder Vollbild nicht erlaubt: Das System zeigt nur eine Mitteilung oben.
    case headsUp = "HEADS_UP"
}

/// Regeln, damit der Weckbildschirm erscheint; Gegenstück zu `RingAlert` (Kotlin). Android startet das Vollbild
/// nur bei einer neuen Mitteilung (nie bei einer Aktualisierung derselben Kennung) und nur bei dunklem oder
/// gesperrtem Display.
public enum RingAlert {
    /// Abstand der Erinnerungen in Sekunden, solange es klingelt und der Weckbildschirm nicht zu sehen ist.
    public static let realertSeconds = 20

    /// Erwarteter Weg für die Diagnose; `companion`: Zuordnung als Begleit-App einer Uhr; `interactive`: Display an
    /// (Always-on zählt als aus).
    public static func route(appVisible: Bool, companion: Bool, interactive: Bool, locked: Bool, fullScreenAllowed: Bool) -> RingRoute {
        if appVisible { return .direct }
        if companion { return .companion }
        if fullScreenAllowed && (!interactive || locked) { return .fullScreen }
        return .headsUp
    }

    /// Kennung der nächsten Weck-Mitteilung: immer eine andere als `current` (`nil` = noch keine), abwechselnd.
    public static func nextId(current: Int?, first: Int, second: Int) -> Int {
        current == first ? second : first
    }
}
