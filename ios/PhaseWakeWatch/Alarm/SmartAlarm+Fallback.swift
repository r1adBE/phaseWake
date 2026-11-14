import Foundation
import PhaseWakeCore
import UserNotifications

extension SmartAlarm {
    // MARK: Rückfall

    /// Weck-Mitteilung zu `date` unter der Kennung `id` (ersetzt eine gleichnamige). Zu Kalenderzeit ohne Zeitzone
    /// (`LocalTrigger`): Nach einem Zeitzonenwechsel weckt sie zur selben Ortszeit, auch wenn die App nicht läuft.
    /// `fixed`: zum festen Zeitpunkt (`LocalTrigger.fixed`), für eine über eine Reise behaltene Nacht.
    /// Rückfall des echten Weckers (`fallbackId`) und Wecken nach abgebrochener Sitzung (`interruptedId`): eine Kette
    /// aus 4 Mitteilungen im Abstand von 30 s (`NotificationChain`), damit eine verschlafene oder weggewischte nicht die
    /// einzige bleibt. Probewecker und Powernap wecken mit einer. Der Stand der Erlaubnis für Mitteilungen geht dabei
    /// ans iPhone (Nacht-Check), höchstens bei einer Änderung oder alle 6 h.
    func scheduleFallback(at date: Date, id: String = SmartAlarm.fallbackId, fixed: Bool = false) {
        Task { await WatchConnectivityBridge.shared.publishNotificationStateIfNeeded() }
        let content = Self.fallbackContent()
        let chained = id == Self.fallbackId || id == Self.interruptedId
        let ids = chained ? NotificationChain.ids(id) : [id]
        let dates = chained ? NotificationChain.dates(date) : [date]
        let now = Date()
        for (member, at) in zip(ids, dates) {
            let trigger = fixed ? LocalTrigger.fixed(at, now: now) : LocalTrigger.at(at, now: now)
            let request = UNNotificationRequest(identifier: member, content: content, trigger: trigger)
            // Scheitert das Stellen (z. B. Mitteilungen aus), fehlt der Rückfall: im Protokoll sichtbar machen.
            UNUserNotificationCenter.current().add(request) { error in
                guard let error else { return }
                DiagStore.shared.log(.error, "fallback \(member) not set \((error as NSError).code)")
            }
        }
    }

    /// Entfernt die wartenden Rückfälle `ids`, je samt ihrer Kette (`NotificationChain`).
    func removeFallback(_ ids: [String] = [SmartAlarm.fallbackId]) {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: NotificationChain.allIds(ids))
    }

    /// Klingelt die Sitzung selbst, braucht es die noch folgenden Mitteilungen der Kette von `id` nicht mehr. Die
    /// erste bleibt wie bisher stehen, bis beendet wird: Endet die Sitzung, weckt sie trotzdem.
    func removeFollowingFallback(_ id: String) {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: NotificationChain.followingIds(id))
    }

    /// Nach dem Stopp: auch schon zugestellte Rückfälle `ids` samt Kette aus der Mitteilungszentrale nehmen.
    func removeDeliveredFallback(_ ids: [String]) {
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: NotificationChain.allIds(ids))
    }
}
