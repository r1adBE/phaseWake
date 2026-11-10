import Foundation

/// Apple Watch, Lernphase (`docs/PHASENERKENNUNG.md`, Abschnitt 10, Apple): Eine App darf auf der Apple Watch nicht die
/// ganze Nacht selbst messen. Stattdessen zeichnet watchOS mit dem Bewegungs-Rekorder (`CMSensorRecorder`) bis 12 Std.
/// lang Bewegung auf, auch wenn die App nicht läuft; die Uhr holt die Werte am Morgen ab (``RecorderEpochs``). Diese
/// Regel entscheidet, wann die Uhr den Rekorder für eine Nacht startet. Ausgewertet wird wie auf der Galaxy Watch ab dem
/// Beginn der Einschlaf-Messung (``ShortNight/autoOnsetStart(target:sleepGoalMinutes:sleepDurationMinutes:)``, 60 Min
/// vor der Bettzeit) bis zum Wecken.
public enum AppleLearnRecord {
    /// Nach der Zielzeit zeichnet der Rekorder noch so lange auf (Schlummern); ausgewertet wird bis zum Ende des Klingelns.
    public static let afterTarget: TimeInterval = 30 * 60

    /// Was die Uhr jetzt tut.
    public enum Decision: Equatable, Sendable {
        /// Rekorder jetzt für `duration` Sekunden starten; ausgewertet wird ab `from` bis höchstens `until`.
        case start(from: Date, until: Date, duration: TimeInterval)
        /// Noch zu früh (mehr als 12 Std. vor dem Ende): ab `until` nochmals versuchen.
        case wait(until: Date)
        /// Diese Nacht nicht: `reason` fürs Diagnose-Protokoll («battery 42%», «too late», «past»).
        case skip(reason: String)
    }

    /// Entscheidet für die Nacht mit Zielzeit `target` und Beginn der Einschlaf-Messung `onsetStart` zum Zeitpunkt
    /// `now` bei Akku `battery` (Prozent, `nil` = unbekannt). Gestartet wird, sobald der Rekorder bis
    /// ``afterTarget`` nach der Zielzeit reicht (höchstens 12 Std.), damit ein später Start der App die Nacht nicht
    /// verpasst. Zu spät, wenn ab dann keine 3 Std. (``Learning/minSpanEpochs``) mehr bis zur Zielzeit bleiben: Die
    /// Nacht könnte nicht zählen. Unter ``Learning/minBattery`` nicht (der Wecker hat Vorrang).
    public static func decide(now: Date, target: Date, onsetStart: Date, battery: Int?) -> Decision {
        if now >= target { return .skip(reason: "past") }
        let end = target.addingTimeInterval(afterTarget)
        let earliest = end.addingTimeInterval(-NightRecording.maxDuration)
        if now < earliest { return .wait(until: earliest) }
        let from = max(now, onsetStart)
        let span = TimeInterval(Learning.minSpanEpochs * DetectorConfig.epochSeconds)
        if target.timeIntervalSince(from) < span { return .skip(reason: "too late") }
        if let battery, battery < Learning.minBattery { return .skip(reason: "battery \(battery)%") }
        return .start(from: from, until: end, duration: end.timeIntervalSince(now))
    }

    /// Ende der Auswertung: Ende des Klingelns `wokeAt`, höchstens `until` (Ende des Rekorders); ohne Klingeln die
    /// Zielzeit `target` (wie auf der Galaxy Watch).
    public static func end(wokeAt: Date?, target: Date, until: Date) -> Date {
        wokeAt.map { min($0, until) } ?? target
    }
}
