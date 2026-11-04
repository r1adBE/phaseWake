import Foundation

/// Entscheide der Apple Watch rund um die Zielzeit, ohne watchOS-Abhängigkeit (testbar). Nur Apple: Wear OS plant
/// über `AlarmManager` und kennt die Grenzen von `WKExtendedRuntimeSession` nicht.
///
/// Hintergrund: Hat die Sitzung einer Nacht nicht gestartet (vom System abgelehnt, App nicht offen), weckt der Rückfall
/// `delay` nach der Zielzeit. Ein Neuplanen genau dazwischen (App im Vordergrund, Wecker vom iPhone, Zeitzone) nahm
/// bisher schon den Plan des nächsten Tages und ersetzte den Rückfall dieser Nacht – sie weckte dann gar nicht.
public enum WatchRules {
    /// Ausstehende Nacht: Die Uhr wartet auf `current` (Sitzung geplant oder nicht planbar), dessen Zielzeit schon da
    /// ist. Sie bleibt der Plan, solange `now` in [Zielzeit, Zielzeit + `ringLimit`) liegt, `current` noch ein gültiger
    /// Termin seines Weckers ist (eingeschaltet, Zeit unverändert, nicht übersprungen, kein Feiertag) und die Nacht
    /// nicht erledigt ist (`doneUntil` vor der Zielzeit) – geprüft wie `AlarmSchedule.inGrace`. Probewecker und
    /// Powernap nie: Sie haben einen eigenen Rückfall und laufen selbst ab. Sonst `nil`.
    /// - Returns: `current` unverändert, damit der Vergleich mit dem Zustand der Uhr gilt.
    public static func pendingNight(_ current: WakePlan?, specs: [AlarmSpec], now: Date, doneUntil: [String: Date],
                                    ringLimit: TimeInterval, calendar: Calendar, holidays: Set<String> = []) -> WakePlan? {
        guard let current, Transient.kindOf(current.alarmId) == nil,
              let spec = specs.first(where: { $0.id == current.alarmId }) else { return nil }
        let valid = AlarmSchedule.inGrace(spec, plannedTarget: current.target, now: now, grace: ringLimit, calendar: calendar,
                                          holidays: holidays, suppressedUntil: doneUntil[spec.id])
        return valid == nil ? nil : current
    }

    /// Ausstehende Nacht ohne gemerkten Plan (Uhr im Ruhezustand, z. B. App-Prozess seit dem Planen beendet): ein
    /// echter Wecker, dessen gültiger Termin in (`now` − `delay`, `now`] liegt und dessen Nacht nicht erledigt ist
    /// (`doneUntil`). Nur so lange, bis sein Rückfall fällig ist – ob danach schon jemand gestoppt hat, weiss die Uhr
    /// ohne Zustand nicht, darum dann kein Nachholen. Mehrere: die früheste Zielzeit, bei Gleichstand die kleinere
    /// Kennung. Sonst `nil`.
    public static func unwokenNight(specs: [AlarmSpec], now: Date, doneUntil: [String: Date], delay: TimeInterval,
                                    calendar: Calendar, holidays: Set<String> = []) -> WakePlan? {
        specs.filter { Transient.kindOf($0.id) == nil }.compactMap { spec -> WakePlan? in
            guard let target = AlarmSchedule.nextTarget(spec, now: now.addingTimeInterval(-delay), calendar: calendar, holidays: holidays),
                  target <= now else { return nil }
            return AlarmSchedule.inGrace(spec, plannedTarget: target, now: now, grace: delay, calendar: calendar, holidays: holidays,
                                         suppressedUntil: doneUntil[spec.id])
        }.min { ($0.target, $0.alarmId) < ($1.target, $1.alarmId) }
    }

    /// Zeitpunkt des Rückfalls für `plan`. Eine ausstehende Nacht (`pending`) behält ihren Rückfall `delay` nach der
    /// Zielzeit; ist der schon vorbei (er hat gewarnt), `nil`: nicht neu stellen, sonst warnte er bei jedem Neuplanen
    /// nochmals. Jeder andere Plan: `delay` nach dem späteren von Zielzeit und `now` (ein nachgeholter Wecker bekommt die
    /// Frist ab jetzt, damit seine Sitzung zuerst weckt).
    public static func fallbackDate(_ plan: WakePlan, pending: Bool, now: Date, delay: TimeInterval) -> Date? {
        guard pending else { return max(plan.target, now).addingTimeInterval(delay) }
        let at = plan.target.addingTimeInterval(delay)
        return at > now ? at : nil
    }

    /// Ob für `plan` jetzt eine Sitzung geplant werden darf. Eine ausstehende Nacht nur, bis ihr Rückfall fällig ist:
    /// Davor weckt die Sitzung gleich (nachgeholt). Danach hat der Rückfall gewarnt – die Uhr wartet nur noch, damit ein
    /// Tipp auf ihn den Weckbildschirm öffnet, statt beim blossen Öffnen der App nochmals zu klingeln.
    public static func startsSession(_ plan: WakePlan, pending: Bool, now: Date, delay: TimeInterval) -> Bool {
        !pending || now < plan.target.addingTimeInterval(delay)
    }

    /// Ob sich die Ortszeit seit dem Planen der Sitzung verschoben hat (Reise). Die Sitzung startet zu einem festen
    /// Zeitpunkt, Wecker und Rückfall gelten in Ortszeit. Verglichen wird der Abstand zu UTC an der Zielzeit `target`
    /// in der Zeitzone beim Planen (`plannedZone`) und in `zone`: Ein Wechsel ohne anderen Abstand (Zürich → Berlin)
    /// zählt nicht, die Sommerzeit derselben Zone auch nicht. Ohne gemerkte Angabe oder bei unbekannter Zone `false`.
    public static func zoneShifted(plannedZone: String?, target: Date?, zone: TimeZone) -> Bool {
        guard let plannedZone, let target, let planned = TimeZone(identifier: plannedZone) else { return false }
        return planned.secondsFromGMT(for: target) != zone.secondsFromGMT(for: target)
    }
}
