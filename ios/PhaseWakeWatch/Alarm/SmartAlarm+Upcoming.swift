import Foundation
import PhaseWakeCore
import UserNotifications

/// Rückfälle der Nächte nach der nächsten (Audit AP-2): watchOS plant die Smart-Alarm-Sitzung nur bei offener App und
/// weckt die App nicht verlässlich. Läuft die App tagelang nicht, stand bisher nur der Rückfall der nächsten Nacht. Jetzt
/// bekommt jede weitere Nacht bis 14 Tage voraus eine eigene Weck-Mitteilung (wie der Zweitwecker am iPhone), im Rahmen
/// von `NotificationBudget`: innerhalb von 48 h die ganze Kette (`NotificationChain`), danach nur die erste, und nur,
/// solange Platz ist (die übrigen Mitteilungen der Uhr gehen vor). Jedes Neuplanen ersetzt sie.
///
/// Kennungen «phasewake.fallback.night1» … «.night14» (je mit Kette «.1»–«.3»): Sie beginnen mit `SmartAlarm.fallbackId`,
/// gelten also bei offener App als Rückfall (`WatchNotificationRouter`), und weckt eine, klingelt die Uhr für ihre Nacht
/// wie beim Rückfall (`SmartAlarm.fallbackArrived`).
enum UpcomingFallbacks {
    /// Höchstens so viele Nächte nach der nächsten.
    static let maxNights = 14
    /// So weit im Voraus.
    static let horizon: TimeInterval = 14 * 24 * 3600
    /// Gemeinsamer Anfang der Kennungen.
    static let prefix = SmartAlarm.fallbackId + ".night"

    /// Mitteilungen einer Nacht: Kennungen und Zeitpunkte (gleich lang, erste zuerst).
    struct Entry: Equatable {
        let ids: [String]
        let dates: [Date]
    }

    /// Kennung der `index`-ten Nacht (ab 1).
    static func base(_ index: Int) -> String { "\(prefix)\(index)" }

    /// Alle Kennungen samt Ketten (zum Ersetzen).
    static var allIds: [String] { NotificationChain.allIds((1...maxNights).map(base)) }

    /// Ob `id` (auch ein Glied einer Kette) zu diesen Rückfällen gehört.
    static func isUpcoming(_ id: String) -> Bool {
        let base = NotificationChain.baseId(id)
        guard base.hasPrefix(prefix), let index = Int(base.dropFirst(prefix.count)) else { return false }
        return (1...maxNights).contains(index)
    }

    /// Die Nächte nach `first` (dem nächsten echten Wecker) bis `horizon` ab `now`, höchstens `maxNights`, je Wecker
    /// gesucht (`BackupPlan.nights`): eingeschaltet, nicht übersprungen, keine Feiertage, nicht erledigt (`doneUntil`).
    /// Ein einmaliger Wecker nur mit seinem ersten Vorkommen. Hat «Gute Nacht» die Zielzeit von `first` vorverlegt
    /// (`nominalFirst` = eingestellte Weckzeit derselben Nacht), kommt diese Nacht nicht nochmals zur eingestellten Zeit.
    /// Gesucht ab dem späteren von `first.target` und `now` (Vollaudit AP2-2): Liegt `first` schon zurück (ausstehende
    /// Nacht, nachgeholter Wecker), bekäme sonst jede Nacht dazwischen einen Rückfall in der Vergangenheit, der sofort weckt.
    static func nights(after first: WakePlan?, specs: [AlarmSpec], now: Date, doneUntil: [String: Date], nominalFirst: Date?,
                       calendar: Calendar, holidays: Set<String>) -> [WakePlan] {
        guard let first, Transient.kindOf(first.alarmId) == nil else { return [] }
        var suppressed = doneUntil
        let sameNight = GoodNightEstimate.doneUntil(target: first.target, nominal: nominalFirst)
        suppressed[first.alarmId] = max(suppressed[first.alarmId] ?? .distantPast, sameNight)
        var firstTargets: [String: Date] = [:]
        for spec in specs where spec.isOneShot {
            firstTargets[spec.id] = AlarmSchedule.nextTarget(spec, now: now, calendar: calendar, holidays: holidays)
        }
        let candidates = specs.filter { !$0.isOneShot || firstTargets[$0.id] != nil }
        return BackupPlan.nights(candidates, now: max(first.target, now), until: now.addingTimeInterval(horizon), maxNights: maxNights,
                                 suppressed: suppressed, firstTargets: firstTargets, calendar: calendar, holidays: holidays)
    }

    /// Mitteilungen je Nacht `nights` (nach Zielzeit sortiert), Rückfall `delay` nach der Zielzeit. `otherPending` =
    /// wartende Mitteilungen der Uhr ohne diese (`otherPending(_:)`). Innerhalb von 48 h die ganze Kette, sonst eine;
    /// ohne Platz weniger, die Liste endet bei der ersten Nacht ohne Platz. Eine Nacht, deren Rückfall nicht nach `now`
    /// liegt, fällt weg (Vollaudit AP2-2).
    static func plan(_ nights: [WakePlan], now: Date, otherPending: Int, delay: TimeInterval) -> [Entry] {
        var left = max(0, NotificationBudget.limit - NotificationBudget.margin - otherPending)
        var result: [Entry] = []
        let due = nights.filter { $0.target.addingTimeInterval(delay) > now }
        for (index, night) in due.prefix(maxNights).enumerated() {
            let first = night.target.addingTimeInterval(delay)
            let count = min(NotificationBudget.wanted(first, now: now), left)
            guard count > 0 else { break }
            left -= count
            result.append(Entry(ids: Array(NotificationChain.ids(base(index + 1)).prefix(count)),
                                dates: Array(NotificationChain.dates(first).prefix(count))))
        }
        return result
    }

    /// Wartende Mitteilungen ohne diese Rückfälle, aus den Kennungen `pending`. Die Ketten von Rückfall und Abbruch,
    /// der Rückfall des vorübergehenden Plans, «App öffnen» und die Erinnerung am Abend zählen immer mit: Sie werden
    /// womöglich gerade gestellt und stehen noch nicht in der Liste.
    static func otherPending(_ pending: [String], reserved: [String]) -> Int {
        Set(pending.filter { !isUpcoming($0) } + reserved).count
    }
}

extension SmartAlarm {
    /// Inhalt jeder Weck-Mitteilung (Rückfall): «Guten Morgen», «Zielzeit erreicht», zeitkritisch mit Ton.
    static func fallbackContent() -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = L("ring_greeting")
        content.body = L("ring_reason_deadline")
        content.sound = .default
        content.interruptionLevel = .timeSensitive
        return content
    }

    /// Stellt die Rückfälle der Nächte nach `first` (`UpcomingFallbacks`) neu; ohne `first` entfernt es sie. Zu
    /// Kalenderzeit ohne Zeitzone wie der Rückfall der nächsten Nacht (`LocalTrigger.at`). Ein späterer Aufruf überholt
    /// einen noch laufenden.
    func scheduleUpcomingFallbacks(after first: WakePlan?, model: WatchModel) {
        let now = Date()
        let holidays = model.storage.holidays.dates
        let nominal = first.flatMap { night in
            model.alarms.first { $0.id == night.alarmId }.flatMap {
                AlarmSchedule.nextTarget($0, now: night.target.addingTimeInterval(-0.001), calendar: .gregorianLocal, holidays: holidays)
            }
        }
        let nights = UpcomingFallbacks.nights(after: first, specs: model.alarms, now: now, doneUntil: model.storage.doneUntil,
                                              nominalFirst: nominal, calendar: .gregorianLocal, holidays: holidays)
        upcomingRun += 1
        let run = upcomingRun
        let reserved = NotificationChain.allIds([Self.fallbackId, Self.interruptedId])
            + [Self.transientFallbackId, Self.openAppId, EveningReminder.id]
        Task { @MainActor in
            let center = UNUserNotificationCenter.current()
            let pending = await center.pendingNotificationRequests().map(\.identifier)
            guard run == self.upcomingRun else { return }
            let now = Date()
            let entries = UpcomingFallbacks.plan(nights, now: now, otherPending: UpcomingFallbacks.otherPending(pending, reserved: reserved),
                                                 delay: Self.fallbackDelay)
            center.removePendingNotificationRequests(withIdentifiers: UpcomingFallbacks.allIds)
            let content = Self.fallbackContent()
            for entry in entries {
                for (id, at) in zip(entry.ids, entry.dates) {
                    let request = UNNotificationRequest(identifier: id, content: content, trigger: LocalTrigger.at(at, now: now))
                    center.add(request) { error in
                        guard let error else { return }
                        DiagStore.shared.log(.error, "fallback \(id) not set \((error as NSError).code)")
                    }
                }
            }
            // Diagnose nur bei einer Änderung, nicht bei jedem Neuplanen.
            let firsts = entries.compactMap(\.dates.first)
            guard firsts != self.loggedUpcoming else { return }
            self.loggedUpcoming = firsts
            DiagStore.shared.log(.scheduled, "upcoming fallbacks \(entries.count)/\(nights.count) nights")
        }
    }
}
