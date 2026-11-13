import Foundation

/// Meldung der Uhr ans iPhone (Lern-Übersicht, Entscheid r1AD 13.11.2026): Die Lernphase zeichnet die Nacht mit Zielzeit
/// `target` nicht auf, aus `reason`; bei ``Reason/battery`` mit dem Akkustand `battery` in Prozent. Die Apple Watch
/// schickt sie als Benutzerdaten (`transferUserInfo`, zugestellt, sobald das iPhone erreichbar ist), die Galaxy Watch über
/// die Warteschlange. Technische Angabe ohne Messwerte, nur am iPhone gespeichert (``LearnLog``). Gleich in Kotlin
/// (`LearnSkip`).
public struct LearnSkip: Equatable, Sendable {
    /// Grund, mit seinem Kürzel im Text (``encode()``). Nie ändern, nur ergänzen.
    public enum Reason: String, Sendable, CaseIterable {
        /// Akku unter ``Learning/minBattery`` (bzw. unter der Schwelle der Einschlaf-Messung, die die Aufzeichnung startet).
        case battery
        /// Keine Sensoren: keine Erlaubnis für Bewegungsdaten bzw. kein Rekorder (Apple Watch), Messdienst verweigert
        /// (Galaxy Watch).
        case sensors
        /// Eine von Hand begonnene Aufzeichnung lief schon (sie zählt dann selbst, sobald sie ankommt).
        case manual
        /// Apple Watch: Die App lief am Abend erst, als keine 3 Std. bis zur Zielzeit mehr blieben.
        case late
    }

    /// Zielzeit der Nacht.
    public let target: Date
    /// Warum die Lernphase diese Nacht nicht aufzeichnet.
    public let reason: Reason
    /// Akkustand in Prozent bei ``Reason/battery``; sonst bzw. unbekannt `nil`.
    public let battery: Int?

    /// Meldung für die Nacht mit Zielzeit `target`.
    public init(target: Date, reason: Reason, battery: Int? = nil) {
        self.target = target
        self.reason = reason
        self.battery = battery
    }

    /// «Zielzeit in ms|Grund|Akku» (Akku leer, wenn unbekannt), siehe ``decode(_:)``.
    public func encode() -> String {
        "\(TimeBounds.millis(target))|\(reason.rawValue)|\(battery.map { String($0) } ?? "")"
    }

    /// Liest ``encode()``; `nil` bei leerem oder unlesbarem Text, unplausibler Zielzeit (``TimeBounds``) oder unbekanntem
    /// Grund (neuere Uhr). Ein Akku ausserhalb 0–100 gilt als unbekannt.
    public static func decode(_ text: String?) -> LearnSkip? {
        guard let parts = text?.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: "|", omittingEmptySubsequences: false),
              parts.count >= 2, let ms = TimeBounds.checked(Int64(parts[0])), ms > 0,
              let reason = Reason(rawValue: String(parts[1])) else { return nil }
        let battery = parts.count > 2 ? Int(parts[2]).flatMap { (0...100).contains($0) ? $0 : nil } : nil
        return LearnSkip(target: Date(timeIntervalSince1970: Double(ms) / 1000), reason: reason, battery: battery)
    }
}

/// Was das iPhone für die Lern-Übersicht festhält (nur lokal, keine Sicherung, Entscheid r1AD 13.11.2026): die Meldungen
/// der Uhr (``LearnSkip``) und die Nächte mit Wecker (Nachtdatum → Zielzeit), je höchstens ``LearnNights/maxNights``.
/// Reine Regeln und das Textformat; gespeichert wird am iPhone. Gleich in Kotlin (`LearnLog`).
public enum LearnLog {
    /// So viele Tage voraus merkt das iPhone die geplanten Nächte mit Wecker (``upcomingTargets(_:now:calendar:holidays:)``).
    public static let planDays = 14

    /// Meldungen nach dem Eintreffen von `skip`: dieselbe Nacht (Nachtdatum der Zielzeit) ersetzt die frühere, höchstens
    /// ``LearnNights/maxNights`` Nächte, die jüngsten. Älteste zuerst.
    public static func add(_ skip: LearnSkip, to skips: [LearnSkip], calendar: Calendar) -> [LearnSkip] {
        let date = SleepNotes.nightDate(skip.target, calendar: calendar)
        let list = skips.filter { SleepNotes.nightDate($0.target, calendar: calendar) != date } + [skip]
        return Array(list.sorted { $0.target < $1.target }.suffix(LearnNights.maxNights))
    }

    /// Meldungen als Text, eine je Zeile (``LearnSkip/encode()``).
    public static func encodeSkips(_ skips: [LearnSkip]) -> String {
        skips.map { $0.encode() }.joined(separator: "\n")
    }

    /// Liest ``encodeSkips(_:)``; unlesbare Zeilen fallen weg. Älteste zuerst.
    public static func decodeSkips(_ text: String?) -> [LearnSkip] {
        (text ?? "").components(separatedBy: "\n").compactMap(LearnSkip.decode).sorted { $0.target < $1.target }
    }

    /// Zielzeiten der Wecker `alarms` (nur eingeschaltete) ab `now` bis ``planDays`` Tage voraus, mit den Feiertagen
    /// `holidays` (``AlarmSchedule/nextTarget(_:now:calendar:holidays:)``); ein einmaliger Wecker nur mit seinem nächsten
    /// Termin.
    public static func upcomingTargets(_ alarms: [AlarmSpec], now: Date, calendar: Calendar, holidays: Set<String>) -> [Date] {
        let until = now.addingTimeInterval(TimeInterval(planDays * 86_400))
        var result: [Date] = []
        for spec in alarms {
            var from = now
            while let target = AlarmSchedule.nextTarget(spec, now: from, calendar: calendar, holidays: holidays), target <= until {
                result.append(target)
                if spec.isOneShot { break }
                from = target
            }
        }
        return result.sorted()
    }

    /// Nächte mit Wecker nach einem Neuplanen um `now`: Vergangene Einträge (Zielzeit nicht nach `now`) bleiben als
    /// Verlauf; die künftigen ersetzt der neue Plan `planned` (ein ausgeschalteter oder übersprungener Wecker fällt so
    /// heraus). Je Nachtdatum die früheste Zielzeit; höchstens die jüngsten ``LearnNights/maxNights`` vergangenen Nächte.
    public static func mergeAlarmNights(_ old: [String: Date], planned: [Date], now: Date, calendar: Calendar) -> [String: Date] {
        let past = old.filter { $0.value <= now }.sorted { $0.key < $1.key }.suffix(LearnNights.maxNights)
        var result: [String: Date] = [:]
        for (date, target) in past { result[date] = target }
        for target in planned where target > now {
            let date = SleepNotes.nightDate(target, calendar: calendar)
            if let kept = result[date], !(kept > now && target < kept) { continue }
            result[date] = target
        }
        return result
    }

    /// Nächte mit Wecker als Text, je Zeile «yyyy-MM-dd=ms».
    public static func encodeAlarmNights(_ nights: [String: Date]) -> String {
        nights.sorted { $0.key < $1.key }.map { "\($0.key)=\(TimeBounds.millis($0.value))" }.joined(separator: "\n")
    }

    /// Liest ``encodeAlarmNights(_:)``; unlesbare Zeilen fallen weg.
    public static func decodeAlarmNights(_ text: String?) -> [String: Date] {
        var result: [String: Date] = [:]
        for line in (text ?? "").components(separatedBy: "\n") {
            let parts = line.trimmingCharacters(in: .whitespaces).split(separator: "=", omittingEmptySubsequences: false).map(String.init)
            guard parts.count == 2, NightDate.isValid(parts[0]), let ms = TimeBounds.checked(Int64(parts[1])) else { continue }
            result[parts[0]] = Date(timeIntervalSince1970: Double(ms) / 1000)
        }
        return result
    }
}
