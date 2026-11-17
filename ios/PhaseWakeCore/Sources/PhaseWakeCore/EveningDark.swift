import Foundation

/// Einstellung «Abends dunkel ab», identisch zu Kotlin (`EveningDark.kt`): Steht das Design
/// auf «System» oder «Hell», zeigt die App ab der gewählten Uhrzeit (Standard 20:00) bis zum Morgen (``untilMinutes``,
/// 06:00) das dunkle Design; «Dunkel» bleibt dunkel. Gerechnet wird mit der Ortszeit (Wanduhr) in Minuten des Tages,
/// über Mitternacht hinweg. Gemeinsame Fälle in shared/testdata/evening-dark.vectors.
public enum EveningDark {
    /// Standard: an.
    public static let defaultEnabled = true
    /// Standard-Beginn 20:00 (Minuten des Tages): im Sommer nicht schon bei hellem Tag dunkel. Ein gespeicherter Beginn
    /// bleibt, wie er ist.
    public static let defaultFromMinutes = 20 * 60
    /// Ende am Morgen: fest 06:00. Nicht die Zielzeit des nächsten Weckers – die fehlt an freien Tagen oder liegt Tage
    /// entfernt, und das Ende spränge mit jedem Wecker; der Weckbildschirm ist ohnehin immer dunkel.
    public static let untilMinutes = 6 * 60

    private static let dayMinutes = 24 * 60
    /// Längste Suche nach dem nächsten Wechsel: ein Tag und zwei Stunden (mehr als jeder Sommerzeit-Wechsel
    /// verschiebt).
    private static let searchMinutes = dayMinutes + 120

    /// Gespeicherter Beginn zur Verwendung: ausserhalb 00:00–23:59 (oder fehlend) der Standard.
    public static func checkedFrom(_ raw: Int?) -> Int {
        guard let raw, (0..<dayMinutes).contains(raw) else { return defaultFromMinutes }
        return raw
    }

    /// Liegt die Minute des Tages `minuteOfDay` im dunklen Abschnitt von `from` (einschliesslich) bis `until`
    /// (ausschliesslich), auch über Mitternacht? Gleiche Grenzen: nie.
    public static func covers(_ minuteOfDay: Int, from: Int, until: Int = untilMinutes) -> Bool {
        if from == until { return false }
        if from < until { return minuteOfDay >= from && minuteOfDay < until }
        return minuteOfDay >= from || minuteOfDay < until
    }

    /// Ob um `now` (Ortszeit des Kalenders) das dunkle Design gilt; aus: nie.
    public static func isDark(now: Date, calendar: Calendar, enabled: Bool, fromMinutes: Int) -> Bool {
        enabled && covers(minuteOfDay(now, calendar), from: checkedFrom(fromMinutes))
    }

    /// Nächster Wechsel nach `now`: die erste volle Minute, ab der ``isDark(now:calendar:enabled:fromMinutes:)`` anders
    /// ist als jetzt; `nil`, wenn sich nie etwas ändert (aus, oder Beginn = 06:00). Minutenweise gesucht (höchstens gut
    /// einen Tag, nur einmal je Wechsel): So stimmt er auch über Sommerzeit-Wechsel hinweg, in der Lücke wie in der
    /// doppelten Stunde, ohne Sonderfälle.
    public static func nextChange(now: Date, calendar: Calendar, enabled: Bool, fromMinutes: Int) -> Date? {
        let from = checkedFrom(fromMinutes)
        guard enabled, from != untilMinutes else { return nil }
        let dark = covers(minuteOfDay(now, calendar), from: from)
        var t = Date(timeIntervalSince1970: (now.timeIntervalSince1970 / 60).rounded(.down) * 60 + 60)
        for _ in 0..<searchMinutes {
            if covers(minuteOfDay(t, calendar), from: from) != dark { return t }
            t = t.addingTimeInterval(60)
        }
        return nil
    }

    private static func minuteOfDay(_ date: Date, _ calendar: Calendar) -> Int {
        let c = calendar.dateComponents([.hour, .minute], from: date)
        return (c.hour ?? 0) * 60 + (c.minute ?? 0)
    }
}
