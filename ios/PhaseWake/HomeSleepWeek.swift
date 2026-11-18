import PhaseWakeCore
import SwiftUI

/// Zeile «Ø 7 Std. 12 Min Schlaf · Schlafziel erreicht: 4 von 7 Nächten» in der Karte «Letzte Nacht» der Startseite:
/// dieselben Werte und Texte wie die Trends im Verlauf (``Trends/asleepThisWeek``, Schlafziel der letzten 7 Nächte),
/// nichts neu gerechnet. Wie Android `sleepWeekText`.
enum HomeSleepWeek {
    /// So viele Tage liest die Startseite aus Apple Health: das Fenster der Kennzahlen (`HealthHistory.forInsights`,
    /// 90 Tage samt einer Nacht davor), damit das Schlafziel wie im Verlauf und auf Android die jüngsten 7 Nachtdaten
    /// zählt, auch wenn in den letzten 7 Tagen Nächte fehlen.
    static let days = NightHistory.insightNights + 1

    /// Liest Apple Health (nur mit Freigabe) und bildet die Zeile; `nil` ohne Freigabe oder unter 3 Nächten mit Dauer.
    @MainActor static func load(_ model: PhoneModel, now: Date = Date()) async -> SleepWeek? {
        guard UserDefaults.standard.bool(forKey: "healthRequested") else { return nil }
        let health = await model.health.sessions(now: now, days: days)
        return of(nights: model.nights, sessions: health.sessions, clearedAt: model.historyClearedAt,
                  goalMinutes: model.sleepGoal, now: now, calendar: .gregorianLocal)
    }

    /// Die Zeile aus `nights` und `sessions` (reine Rechnung): Ø Schlaf wie die Trends im Verlauf, Schlafziel über die
    /// jüngsten 7 Nachtdaten der Nächte aus Apple Health im Fenster der Kennzahlen (wie `goal_week` in den Trends und
    /// auf Android).
    static func of(nights: [NightRecord], sessions: [HealthSession], clearedAt: Date?, goalMinutes: Int, now: Date,
                   calendar: Calendar) -> SleepWeek? {
        let (recentNights, recentSessions) = HealthHistory.forInsights(nights: nights, sessions: sessions, now: now)
        let sleeps = HealthHistory.summaries(recentSessions, clearedAt: clearedAt, calendar: calendar)
        let trends = Trends.compute(nights: recentNights, sleeps: sleeps, now: now, calendar: calendar)
        let goalWeek = SleepGoal.weekStatus(SleepGoal.nightsOf(sleeps, calendar: calendar), goalMinutes: goalMinutes)
        return SleepWeek.of(trends: trends, goalWeek: goalWeek)
    }

    /// Text der Zeile; die Dauer bricht nicht in sich um (wie im Kopf der Startseite).
    static func text(_ week: SleepWeek) -> String {
        let asleep = L("trends_asleep", Format.duration(minutes: week.asleepMinutes, keepTogether: true))
        guard week.nights > 0 else { return asleep }
        return asleep + " · " + LP("goal_week", week.nights, week.reached, week.nights)
    }
}
