import Foundation

/// Apple Watch, Lernphase (`docs/PHASENERKENNUNG.md`, Abschnitt 10, Apple): Eine App darf auf der Apple Watch nicht die
/// ganze Nacht selbst messen. Stattdessen zeichnet watchOS mit dem Bewegungs-Rekorder (`CMSensorRecorder`) bis 12 Std.
/// lang Bewegung auf, auch wenn die App nicht läuft; die Uhr holt die Werte am Morgen ab (``RecorderEpochs``). Diese
/// Regel entscheidet, wann die Uhr den Rekorder für eine Nacht startet: frühestens ab dem Beginn der Einschlaf-Messung
/// (``ShortNight/autoOnsetStart(target:sleepGoalMinutes:sleepDurationMinutes:)``, 60 Min vor der Bettzeit) und nur, wenn
/// die App dann läuft (geöffnet, etwa über die Erinnerung am Abend, oder Hintergrund-Aktualisierung; Entscheid r1AD
/// 10.11.2026). Ausgewertet wird wie auf der Galaxy Watch ab dann bis zum Wecken.
public enum AppleLearnRecord {
    /// Nach der Zielzeit zeichnet der Rekorder noch so lange auf (Schlummern); ausgewertet wird bis zum Ende des Klingelns.
    public static let afterTarget: TimeInterval = 30 * 60

    /// Was die Uhr jetzt tut.
    public enum Decision: Equatable, Sendable {
        /// Rekorder jetzt für `duration` Sekunden starten; ausgewertet wird ab `from` bis höchstens `until`.
        case start(from: Date, until: Date, duration: TimeInterval)
        /// Noch zu früh (vor dem Beginn der Einschlaf-Messung): ab `until` nochmals versuchen.
        case wait(until: Date)
        /// Diese Nacht nicht: `reason` fürs Diagnose-Protokoll («battery 42%», «too late», «past»).
        case skip(reason: String)
    }

    /// Entscheidet für die Nacht mit Zielzeit `target` und Beginn der Einschlaf-Messung `onsetStart` zum Zeitpunkt
    /// `now` bei Akku `battery` (Prozent, `nil` = unbekannt). Gestartet wird ab `onsetStart` bis ``afterTarget`` nach
    /// der Zielzeit (höchstens 12 Std.). Zu spät, wenn ab dann keine 3 Std. (``Learning/minSpanEpochs``) mehr bis zur
    /// Zielzeit bleiben: Die Nacht könnte nicht zählen. Unter ``Learning/minBattery`` nicht (der Wecker hat Vorrang).
    public static func decide(now: Date, target: Date, onsetStart: Date, battery: Int?) -> Decision {
        if now >= target { return .skip(reason: "past") }
        let end = target.addingTimeInterval(afterTarget)
        if now < onsetStart { return .wait(until: onsetStart) }
        let from = now
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

    /// Zielzeit einer laufenden Aufzeichnung der Lernphase (bisher `current`) nach einem neuen Plan mit Zielzeit
    /// `planTarget` (Wecker verschoben, Entscheid r1AD 13.11.2026): diese, wenn sie nach `now` liegt und höchstens die
    /// längste Schlafdauer (``AlarmSpec/maxSleepMinutes``) entfernt ist, also zur selben Nacht gehört; sonst (Wecker aus,
    /// gelöscht, übersprungen, der nächste erst in einer späteren Nacht) bleibt `current`. Wie `RecordControl.learnUntilFor`
    /// (Galaxy Watch).
    public static func followedTarget(current: Date, planTarget: Date?, now: Date) -> Date {
        guard let planTarget, planTarget > now,
              planTarget.timeIntervalSince(now) <= TimeInterval(AlarmSpec.maxSleepMinutes * 60) else { return current }
        return planTarget
    }
}

/// Apple Watch, automatische Einschlaf-Messung (Schalter «Einschlafen automatisch erkennen», Standard ein, wie Android;
/// freigegeben von r1AD 10.11.2026): Damit die Uhr zu Beginn der Weck-Sitzung die Einschlafzeit nachrechnen kann
/// (``RecorderOnset``), startet sie für jede Nacht eines gespeicherten Weckers den Bewegungs-Rekorder bis zum Beginn des
/// Weckfensters – frühestens ab dem Beginn der Einschlaf-Messung (60 Min vor der Bettzeit, wie Android) und nur, wenn
/// die App dann läuft (Entscheid r1AD 10.11.2026: kein Start mehr bis 12 Std. im Voraus); nicht unter ``minBattery``
/// Akku (wie `AutoOnset.MIN_BATTERY_PERCENT`), nicht mehr ab dem Fensterbeginn und nicht, wenn ab dann keine
/// Einschlaf-Messung mehr möglich ist (15 Min vor der Zielzeit).
public enum AppleAutoOnset {
    /// Unter diesem Akkustand (Prozent) startet die automatische Messung nicht (wie `AutoOnset.MIN_BATTERY_PERCENT`).
    public static let minBattery = 30

    /// Was die Uhr jetzt tut.
    public enum Decision: Equatable, Sendable {
        /// Rekorder jetzt bis `until` (Beginn des Weckfensters) starten bzw. sicherstellen.
        case start(until: Date)
        /// Noch zu früh: ab `until` nochmals versuchen.
        case wait(until: Date)
        /// Diese Nacht nicht: `reason` fürs Diagnose-Protokoll («battery 24%», «window begun»).
        case skip(reason: String)
    }

    /// Entscheidet für die Nacht mit Zielzeit `target`, Fensterbeginn `windowStart` und Beginn der Einschlaf-Messung
    /// `onsetStart` zum Zeitpunkt `now` bei Akku `battery` (Prozent, `nil` = unbekannt, hindert nicht).
    public static func decide(now: Date, target: Date, windowStart: Date, onsetStart: Date, battery: Int?) -> Decision {
        let until = min(windowStart, target.addingTimeInterval(-RecorderOnset.giveUpLead))
        if now >= until { return .skip(reason: "window begun") }
        if now < onsetStart { return .wait(until: onsetStart) }
        if let battery, battery < minBattery { return .skip(reason: "battery \(battery)%") }
        return .start(until: windowStart)
    }
}

/// Apple Watch: genau ein Aufruf des Bewegungs-Rekorders je Nacht (`CMSensorRecorder.recordAccelerometer`, Entscheid
/// r1AD 10.11.2026). Apple dokumentiert nicht, was ein zweiter Aufruf während einer laufenden Aufzeichnung tut (er
/// könnte sie verkürzen); darum rechnet die Uhr beim ersten Start das nötige Ende aller Bedürfnisse dieser Nacht aus
/// (Lernphase, Einschlaf-Messung, «Gute Nacht», «Nacht aufzeichnen») und verlängert danach nie.
public enum AppleNightRecorder {
    /// Was die Uhr mit dem Rekorder tut.
    public enum Decision: Equatable, Sendable {
        /// Jetzt einmal starten, bis `until` (höchstens 12 Std. ab jetzt).
        case start(until: Date)
        /// Er läuft schon und reicht für den Bedarf: nichts tun.
        case running
        /// Er läuft schon, reicht aber nur bis `until`, weniger als der Bedarf `need`: hinnehmen (kein zweiter Aufruf),
        /// ins Diagnose-Protokoll.
        case short(until: Date, need: Date)
        /// Kein Bedarf in der Zukunft.
        case nothing
    }

    /// Entscheidet zum Zeitpunkt `now` für den Bedarf `need` (bis wann aufgezeichnet werden soll) und die übrigen schon
    /// bekannten Bedürfnisse der Nacht `others`. `runningUntil`: Ende des zuletzt gestarteten Rekorders (`nil` = keiner
    /// bekannt). Beim Start gilt das späteste aller Bedürfnisse, höchstens ``NightRecording/maxDuration`` ab jetzt.
    public static func decide(now: Date, need: Date, others: [Date] = [], runningUntil: Date?) -> Decision {
        if let runningUntil, runningUntil > now {
            return runningUntil >= min(need, now.addingTimeInterval(NightRecording.maxDuration))
                ? .running : .short(until: runningUntil, need: need)
        }
        let end = min(([need] + others).max() ?? need, now.addingTimeInterval(NightRecording.maxDuration))
        return end > now ? .start(until: end) : .nothing
    }
}
